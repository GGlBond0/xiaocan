# ProxyHolder 多池轮换规避携趣高峰段IP劣化

## Goal

规避携趣代理 **高峰段（14-20点）代理 IP 大面积不可用** 导致的线上上游请求 503 / `状态码错误:-1`。
根因（2026-08-24 探活+日志铁证，非限流）：14-20 点是携趣共享代理池全网使用高峰，分配给本 uid(183587) 的 IP 多为其它客户用完的尾单/失效连接 → 高峰段提取 IP 存活率骤降（8-23 高峰段 94% 换代理重试）。改造后让代理提取在多个隧道池间**轮换**，摊薄到更多池捞可用 IP，提升高峰段命中率。

## Background / Confirmed Facts

- 携趣 6 个隧道池(51/82/57/61/62/76)，**切换方式=只改 `act=getturn{N}`，`group` 固定=51**（用户纠正）。各池均能 `code:0` 提取可用 IP。
- 凌晨实测 6 池 IP 全部 `baidu 200`（可用）；高频连测会触发限流（间隔2s则全部可用）——**限流非线上主因，高峰段 IP 劣化才是**。
- **决定性证据**：4 天 error.log 错误小时分布几乎严格集中在 14-20 点（与任务 cron `0 */10 14-20` 完全重合）；8-23 高峰段 191 次提取、179 次(94%)换代理重试，失败 **177 次统一 `Unable to tunnel through proxy...503`**。
- proxy_config(id=1) 当前：enabled=1, ttl=28, retry=3, api_url=`act=getturn51&...&group=51&time=6`；env 兜底 bilinip 已 ErrBan。
- ProxyHolder：`loadCfg()` 读 DB(5s快照)，`getProxy(key, force)` 按账号key+ttl缓存，过期才 `fetchProxyList`。
- 线上 04:37 凌晨任务不跑时无错误（自愈假象）；**高峰段会再复发**。
- 上游 rpc 对裸 curl 403 是需 X-Ashe 签名的预期行为。
- 方案选型结论（用户已确认）：**A 先实现多池轮换并部署，等今天 14-20 点高峰段实测验证收益**；**配置承载=独立 `pool_list` 字段（非复用 api_url 分隔符）**。

## Requirements

- R1: `proxy_config` 新增独立字段 `pool_list`（VARCHAR，存隧道池组号列表如 `51,82,57,61,62,76`），承载多池配置。
- R2: apiUrl 保持单池模板（`act=getturn{SINGLE_POOL}&...&group=51`）；`pool_list` 为空时行为完全兼容现状（单池）。
- R3: 多池轮换只在 `pool_list` 非空时启用；ProxyHolder 提取代理时按池间轮换（Round-Robin），分摊到不同 `act=getturn{N}`。
- R4: 轮换不破坏现有「同 key 在 ttl 内复用同一 IP」缓存语义；同 key 单次缓存内的 IP 固定，换 IP 时机仍由 ttl 驱动。
- R5: 后端接口 `GET/PUT /api/proxy/config` 暴露 poolList 字段（DTO/VO/Entity/Service）；前端设置页新增池列表输入。
- R6: 不改 XiaochanHttp 调用方；ProxyHolder 方法签名对外不变。
- R7: loadCfg 兜底与 ensureRow 惰性初始化需包含 pool_list 的默认值处理（表旧行无此列时兼容）。

## Acceptance Criteria

- [ ] AC1: proxy_config 表加 `pool_list` 列；DDL 脚本与 ALTER 语句可用。
- [ ] AC2: `GET/PUT /api/proxy/config` 能读写 poolList 字段；保存后 `ProxyHolder.invalidate()` 即时生效（沿用现有机制）。
- [ ] AC3: `pool_list=51,82,57,61,62,76` 配置下，ProxyHolder 提取代理在 6 池间轮换（日志可见 `act=getturn{N}` / slot 变化跨池，IP 分布跨多个池）。
- [ ] AC4: `pool_list` 为空（默认）时，行为完全等同改造前单池，无回归。
- [ ] AC5: 单测/验证：mock 多池 URL，轮换计数器正确 Round-Robin 分配；空/非法 pool_list 优雅回退。
- [ ] AC6: 部署生产后，14-20 点高峰段 error.log `状态码错误:-1` 频率较 8-23 同段显著下降（验证点）。
- [ ] AC7: 前端设置页能输入并保存 poolList，回显正常。

## Out of Scope

- 更换代理服务商 / bilinip 兜底源修复。
- 降低业务任务 cron 频率（若高峰段轮换后仍失败，另开任务评估）。
- 携趣 side 的账户/权益处理（续费等）。
- 高峰段多池存活率的**事前**证明（当前无法在凌晨验证，需今天 14-20 点实测——见 AC6）。

## Open Questions

- (已解决) 承载格式 → 独立 pool_list 字段。
- (已解决) 是否先实现 vs 先验证 → A 先实现部署后高峰段验证。
- 部署后高峰段验证若收益不明显，是否回退/换方案 → 依 AC6 数据再定（记录为风险）。