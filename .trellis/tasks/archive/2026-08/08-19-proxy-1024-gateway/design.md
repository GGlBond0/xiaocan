# Design: 1024proxy HTTP 网关按账号 sid

## Overview

在 `ProxyHolder` 识别 `api_url` 是否带 userinfo：有则走网关模式（固定 host:port + 按账号拼 sid 用户名）；无则走现有提取 JSON。`XiaochanHttp` / `LotteryHttp` 在 `setHttpProxy` 之后按需挂代理认证。

## Key Decisions

| 决策 | 选择 | 理由 |
|------|------|------|
| 协议 | HTTP 代理（`-x`） | 生产已通；与现有 `setHttpProxy` 同类；不引入 SOCKS5 |
| 入口 | URL host/port，默认 `hk.1024proxy.io:3000` | 实测 HK 入口比 US 更贴 `region-HK` |
| 凭据存放 | 现有 `proxy_config.api_url` | 零 DDL、零前端；与 bilinip vkey 同级（已在 DB） |
| 粘性 | 用户名 `-sid-{sid}-t-{time}` | 官方网关契约；提取 IP 做不到按账号钉 |
| 认证隔离 | ThreadLocal Authenticator | JDK 对 HTTPS CONNECT 走 Authenticator；多账号不能共用一份默认凭据 |
| 提取模式 | 保留 | 回滚改 URL 即可 |

## Gateway URL contract

```
http://{user}:{password}@{host}:{port}?region=HK&time=5
```

- `user`/`password`：1024 子账号（不含 `-region-` 后缀）
- `region` 缺省 `HK`；`time` 缺省 `5`（分钟）
- 非法 URL / 缺 host 或 userinfo 不完整 → 网关模式失败，`getProxy` 返回 null 并打 error（不打印密码）

## Data flow

```
executeWithProxy(key)
  → ProxyHolder.getProxy(key, force)
       网关模式:
         读 cfg.apiUrl → GatewayCfg(host,port,user,pass,region,timeMin)
         命中 sid 缓存且未过期且 !force → 复用 sid
         否则 SecureRandom 生成 8 位 [A-Za-z0-9] sid 写入 cacheByKey
         返回 ProxySpec(host, port, "{user}-region-{region}-sid-{sid}-t-{timeMin}", pass)
       提取模式: 现逻辑，ProxySpec(ip, port, null, null)
  → req.setHttpProxy(host, port)
  → 若 hasAuth: ThreadLocal 写入用户名密码，静态 Authenticator 在 PROXY 请求时取出
  → req.execute()
  → finally 清 ThreadLocal
  → 失败非 WAF: invalidate(key) 删 sid，重试 force=true
```

## Components

### `ProxySpec`（http 包，不可变）
- `host`, `port`, `username`, `password`
- `hasAuth()`：username/password 非空
- 日志用 `host:port` + sid 片段，不 toString 密码

### `ProxyAuth`（http 包）
- 静态初始化：`Authenticator.setDefault` 一次；`jdk.http.auth.tunneling.disabledSchemes` / `proxying.disabledSchemes` 置空（JDK17 默认禁 Basic CONNECT）
- `set(user, pass)` / `clear()` 基于 ThreadLocal
- `getPasswordAuthentication` 仅 `RequestorType.PROXY` 返回 ThreadLocal 值

### `ProxyHolder`
- `CacheEntry` 网关模式存 `sid`；提取模式仍存 `String[]{ip,port}`（可用统一结构：`sid` 或 `ipPort` 二选一，或始终存 `ProxySpec` 快照）
- **推荐**：缓存 `sid`（网关）或 `String[] endpoint`（提取）。`getProxy` 组装最新 `ProxySpec`（密码始终来自当前 cfg，配置更新后旧 sid 可在全量 invalidate 后作废）
- 网关 sid TTL = `timeMin * 1000L * 60`（不读 cfg.ttl，避免 28s 把粘性打穿）
- `getProxy(boolean)` 仍转 `SHARED_KEY`
- 解析 URL 失败、缺凭据：log.error 不含密码，返回 null
- 日志：`获取代理 key={} {}:{} sid={}`（提取模式无 sid 则打 IP）

### `XiaochanHttp` / `LotteryHttp`
- `getProxy` 改为使用 `ProxySpec`（或仍 `String[]` 但 length=4 不推荐）
- `attach`：`setHttpProxy` + `ProxyAuth.set`；`finally ProxyAuth.clear()`
- 重试/WAF 分支不改语义
- lambda 目前忽略 proxy 参数，可继续 `reqFn.apply(...)` 后 attach

## Compatibility

- `enabled/retry/requestTimeout` 不变
- 设置页只改 `apiUrl` 文本
- 提取 URL 行为不变
- 配置保存仍 `invalidate()` 全清

## Risks

| 风险 | 缓解 |
|------|------|
| JDK 禁 Basic tunneling 导致 CONNECT 407/403 | 启动时清空 `jdk.http.auth.tunneling.disabledSchemes` |
| 全局 Authenticator 与其它库冲突 | 本服务无其它 Authenticator；仅 PROXY 类型返回凭据 |
| `region-HK` 仍可能分到非 HK | 入口固定 HK；不行再换 region（本任务不自动切） |
| 密码出现在 `proxy_config` / 设置页 | 与现 bilinip vkey 相同暴露面；日志禁止密码 |
| hutool 连接复用带错代理认证 | 每次 execute 设 ThreadLocal 且 finally 清；同步阻塞调用 |

## Rollout / Rollback

- 本机 `mvn -DskipTests package` → 备份生产 JAR → rsync 新 JAR → `systemctl restart xiaocan`
- 重启后 SQL 或设置页写入网关 `api_url`（保存会 invalidate；若只 SQL 则再调一次保存或重启以清缓存）
- 回滚：旧 JAR + 旧 bilinip URL，或仅改 URL 回提取模式（网关代码保留无害）
