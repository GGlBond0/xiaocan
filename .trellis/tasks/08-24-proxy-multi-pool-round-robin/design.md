# Design: ProxyHolder 多池轮换（规避携趣高峰段 IP 劣化）

## 1. 目标与边界

让代理提取在多个携趣隧道池间 Round-Robin 轮换，摊薄高峰段单池 IP 劣化影响。
**边界**：只改 ProxyHolder 提取层 + proxy_config 配置承载；不改 XiaochanHttp/XiaoChanServiceImpl 调用方；不改代理协议解析逻辑。

## 2. 现状关键代码路径（已审读）

```
getProxy(key, force)                       ProxyHolder.java:117
  └─ getExtractProxy(key, force, cfg, apiUrl)  :185
       ├─ 命中缓存(readCache, ttl内) → 返回复用  :187
       └─ 未命中 → fetchProxyList(apiUrl)        :194
                     └─ HttpUtil.createGet(url)  :300  ← 池组号在 url 的 act=getturn{N}
```
- 携趣 `act=getturn{N}` 里的 `{N}` 是隧道池组号，`group=51` 固定。
- 多池含义 = 同一 uid/vkey/group 下，act 组号集合 {51,82,57,61,62,76}。

## 3. 配置承载（proxy_config 表 + DTO/VO/Entity/Service）

新增字段 `pool_list`：

| 层 | 改动 |
|---|---|
| **DDL** | `ALTER TABLE proxy_config ADD COLUMN pool_list VARCHAR(200) NULL DEFAULT NULL COMMENT '多隧道池组号列表,逗号分隔如 51,82,57'` |
| **Entity** `ProxyConfigEntity` | 加 `poolList` (String) |
| **DTO** `ProxyConfigDTO` | 加 `poolList` (String) |
| **VO** `ProxyConfigVO` | 加 `poolList` (String) |
| **Service** `ProxyConfigServiceImpl` | updateConfig 落库 poolList（可空，为空=单池兼容）；ensureRow 惰性初始化含上半（旧行无此列→null 安全） |
| **前端设置页** SettingsView.vue | 加"隧道池列表"输入框 + 保存/回显 |

**兼容**：pool_list 为 null/空 → 不轮换，行为等同改造前单池（apiUrl 原样使用）。

## 4. ProxyHolder 多池轮换逻辑

**新增**：池列表解析 + 池游标 + url 池组号替换。

```
// 池组号列表（从 cfg.getPoolList() 解析，逗号分隔）
parsePools(cfg) -> List<Integer>  // 去空、非法(1-999)项
nextPool(pools) -> Integer       // Round-Robin：复用 ROUND_ROBIN AtomicInteger 取模 pools.size()
resolveActUrl(apiUrl, poolId)    // 把 url 里 "act=getturn{oldId}" 替换为 "act=getturn{poolId}"
```

**改动点**（ProxyHolder.java）：
- `getExtractProxy(...)`：在 `fetchProxyList(apiUrlOf(c))` 前，若 `parsePools(c)` 非空 → `apiUrl = resolveActUrl(apiUrl, nextPool(pools))`；为空则用原 apiUrl。
- `ROUND_ROBIN` 已有（:68 按账号分配端点槽位）。**池轮换可独立游标** `POOL_ROUND_ROBIN`（AtomicInteger），避免与端点槽位游标语义混淆。
- **替换实现**：`url.replaceAll("act=getturn\\d+", "act=getturn" + poolId)`（对携趣模板安全；其余参数/group 不动）。

**缓存语义不变**：轮换只在 `fetchProxyList`（缓存未命中/ttl过期/force）时发生 → 同 key 在 ttl 内仍复用同一 IP，换 IP 仍由 ttl 驱动。**每 ttl 周期换一个池**，而非每请求换池。

**日志**：`fetchProxyList` 成功路径已打 `获取代理 key=... slot=...`；轮换时补打 `切换隧道池 act=getturn{poolId} (prop n/m)` 便于高峰段排查验证。

## 5. 关键 trade-off

| 取舍 | 说明 |
|---|---|
| Round-Robin 池轮换 vs 高峰段实时选优池 | RR 简单无状态；不依赖"此刻哪个池好"的动态判断。高峰段是否真有池存活差异 —— 待 AC6 实测，RR 是合理首版 |
| 每 ttl 周期换池 vs 每请求换池 | 前者保持缓存复用、减少 API 调用（不大幅提高提取频率）；后者能更快逃出坏池但放大提取频次，可能反触限流。选前者 |
| 独立 poolList 字段 vs api_url 分隔符 | 已定独立字段，语义清晰、不污染单池模板、兼容空池 |

## 6. 兼容与回滚

- Pool list 为空 → 零行为变化，可安全灰度。
- **回滚**：改动集中在 ProxyHolder + 新列 + DTO/VO（后端）+ 前端设置页。回滚=去掉 poolList 配置（置空）即回到单池；或回退 jar。新列对旧代码读取无害（MyBatis-Plus 映射多一个空字段）。
- 旧库行 `pool_list=NULL` 无需迁移。

## 7. 验证策略

- 本机单测：`resolveActUrl` 替换正确；`parsePools` 空/非法项处理；RR 游标分布。
- 本机集成（可选）：设 poolList，观察日志跨池切换。
- **生产验证（关键 AC6）**：部署后等今天 14-20 点高峰段，对比 8-23 同段 error.log `状态码错误:-1` 频率。若改善不明显，评估降频/换源（风险已记录 PRD）。