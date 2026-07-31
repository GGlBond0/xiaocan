# 代理按账号分配（降风控）

## Goal

将进程内代理缓存从「全局单 IP」改为「按账号（silk_id）分 IP」：同一账号在 TTL 内复用同一代理，不同账号尽量使用不同出口 IP，降低多账号串行刷任务/抢单时被同一 IP 关联风控的概率。

范围：**全局**——霸王餐（`LotteryHttp`）与抢单/监控（`XiaochanHttp`）均按账号分配。

## Background

- 当前 `ProxyHolder` 仅缓存一个 `cachedProxy`，TTL 默认 28s；多账号串行刷任务时几乎共用同一 IP。
- 上游代理池已切 bilinip（`num=200` 可一次多取，但代码仍只取 `data[0]`）；本任务以「按账号缓存 + 失败换该账号代理」为主，批量预取池可二期。
- 历史结论：WAF 多为「账号+端点」封禁，换代理对已封账号无效；本改动目标是**降低未封前的 IP 关联**，不是解封手段。

## Requirements

### R1 按账号键缓存代理
- 缓存 key 为账号标识字符串：`silk_id` 的十进制字符串；无登录态/匿名请求（`silk_id=0` 或无 auth）使用固定 key `"shared"`。
- 同一 key 在 TTL 内返回同一 `IP:Port`；不同 key 独立缓存、独立过期。
- TTL / enabled / retry / requestTimeout / apiUrl 仍读全局 `proxy_config`，不新增配置表字段（MVP）。

### R2 失败只换本账号代理
- 网络异常、非 WAF 的 403 重试时：只失效**当前账号 key** 的缓存并强制重取，不得清空其它账号缓存。
- 配置保存（`updateConfig` → `invalidate()`）仍清空**全部**账号缓存 + 配置快照。

### R3 调用方传账号键
- `LotteryHttp.executeWithProxy`：从 `LotteryAuth.silkId` 传 key。
- `XiaochanHttp`：
  - 带 `GrabAuth` 的请求：用 `auth.silkId`；
  - 无 auth 的列表/搜索等（silk_id=0）：用 `"shared"`。
- 对外保持代理启用开关与重试次数语义不变。

### R4 兼容与日志
- 保留 `getProxy(boolean force)` 作为 `getProxy("shared", force)` 兼容入口（或等价），避免遗漏调用方。
- 日志在取代理/换代理时打印 key + IP:Port，便于核对「多账号是否不同 IP」。

### R5 不在本任务
- 不改前端多账号串行逻辑。
- 不实现按地区强制；不改 bilinip URL（已由运维配置）。
- 不实现批量预取本地 IP 池（`num>1` 全量入库）——可 follow-up。
- 不改开红包/领奖间隔；刷任务无间隔若本地已改，可同批部署但非本 PRD 验收点。

## Constraints

- 生产小机内存紧：缓存 Map 仅按「活跃 silk_id 数量」增长，条目为 IP 字符串，可接受；无需持久化。
- 禁止在生产机 `mvn`；本地构建 + 上传 JAR。
- 直接 SQL 改 `api_url` 不会调 `invalidate()`；本任务代码路径仍依赖 `updateConfig` 或重启清缓存（与现网一致）。

## Acceptance Criteria

- [ ] AC1 两个不同 `silk_id` 在 TTL 内分别 `getProxy`，可得到不同 IP（代理池有货时）；同 `silk_id` 复用同一 IP。
- [ ] AC2 账号 A 失败触发换代理后，账号 B 的缓存 IP 不变。
- [ ] AC3 `PUT /api/proxy/config` 保存后全部账号缓存清空，下次请求重新拉取。
- [ ] AC4 霸王餐 `run/draw/claim-step` 与抢单相关 `XiaochanHttp` 请求均按账号/shared 走代理，无编译错误。
- [ ] AC5 匿名/监控列表请求走 `shared`，不 NPE。
- [ ] AC6 本地 `mvn -o -DskipTests package`（或 compile）通过；部署后日志可见「获取代理 key=… IP:Port」。
- [ ] AC7 WAF 403 行为不变：判定 WAF 不重试换代理；非 WAF 403 仅换当前账号代理。

## Notes

- 用户确认范围 **A 全局**。
- 代理协议继续 HTTP（`setHttpProxy`）。
