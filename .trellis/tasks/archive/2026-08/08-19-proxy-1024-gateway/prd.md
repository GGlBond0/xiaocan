# 1024proxy HTTP 网关按账号粘性

## Goal

把生产上游代理从已封禁的 bilinip 提取接口，换成 1024proxy **HTTP 网关 + 账号密码 + 每账号独立 sid 短粘性**，使抢单/监控/霸王餐在生产机可出网，且同一账号短时间内出口 IP 稳定。

## Background

- 现网 `proxy_config.api_url` 为 bilinip `GetIPL.aspx`，2026-08-18 起持续 `ErrBan`，拉不到 IP。
- 2026-08-19 实测 1024 网关（生产 `121.91.175.192`）：
  - `curl -x hk.1024proxy.io:3000 -U "{user}-region-HK-sid-{sid}-t-5:{pass}"` HTTP **通**
  - `--socks5` 也通；本机到 `us.1024proxy.io:3000` 的 HTTP 会超时，**以生产为准**
  - 同一 sid 复用同一出口；换 sid 换出口
  - `us` 入口 + `region-HK` 可能出菲律宾 IP；**HK 入口更稳**
  - 提取链接 `white.1024proxy.com` 返回 `ip:port` / `[{host,port}]`，与现有 `{code:0,data:[{IP,Port}]}` 解析不兼容，且用代理要 Basic 认证
- 现有代码：`ProxyHolder` 只缓存 `IP:Port`，`setHttpProxy(ip,port)` **无代理认证**。网关模式不能靠改设置页 URL 直接生效。
- 用户 2026-08-19 确认：HTTP 代理 + 每账号一个 sid + 入口 `hk.1024proxy.io:3000`。

## Requirements

### R1 网关模式
- 当 `proxy_config.api_url` 为 `http://user:pass@host:port`（可带 `?region=HK&time=5`）时，走 1024 网关，**不再 GET 提取 API**。
- 默认入口 host/port：`hk.1024proxy.io:3000`（以 URL 为准）。
- 默认 `region=HK`，`time=5`（分钟）。
- 凭据只存在 DB/`api_url`，**不得写入源码或提交 git**。日志不得打印密码。

### R2 按账号 sid 短粘性
- 缓存 key 仍为 `silk_id` 字符串，匿名为 `shared`。
- 同一 key 在粘性期内复用同一 `sid`，代理用户名为 `{user}-region-{region}-sid-{sid}-t-{time}`。
- `invalidate(accountKey)` 丢掉该 key 的 sid，下次取新 sid（失败换出口）。
- `invalidate()` 全清（配置保存后仍如此）。
- 网关模式本地 sid 缓存时长按 URL `time` 分钟（可略短于供应商粘性），不沿用 bilinip 的 28s 提取 TTL。

### R3 调用方
- `XiaochanHttp` / `LotteryHttp` 的 `executeWithProxy` 对网关模式设置 HTTP 代理 **并带 Proxy Basic 认证**。
- 未启用代理仍直连。
- 提取模式（`api_url` 无 userinfo）保持现有 bilinip JSON 解析，便于回滚。
- WAF 403 行为不变：Lottery 判定 WAF 不换 sid；非 WAF 403 / 网络异常只换当前账号 sid。

### R4 配置与生效
- 不新增表字段、不改前端表单结构：设置页现有「API 地址」粘贴网关 URL 即可。
- `PUT /api/proxy/config` 后 `ProxyHolder.invalidate()`，下次请求用新 URL。
- 生产在部署 JAR 后更新 `proxy_config.api_url`（及必要时 ttl），无需为凭据改 EnvironmentFile（表已有行）。

## Constraints

- 禁止生产机 `mvn`；本机 JDK17 打包后上传 JAR。
- 大 JAR 不用 scp 整包（易卡死 sshd），用 rsync 或分片。
- hutool 5.5.7 + `HttpURLConnection`：HTTPS 经带认证 HTTP 代理需打开 Basic tunneling（`jdk.http.auth.tunneling.disabledSchemes`）。
- 并发多账号不同 sid：不可用进程级单一 `Authenticator` 凭据；按请求线程隔离。
- 不引入 SOCKS5（现有 `setHttpProxy` 是 HTTP；生产 HTTP 已通）。

## Out of Scope

- 1024 提取 API（`white.1024proxy.com`）解析适配。
- SOCKS5 协议。
- 新设置页字段 / 新表结构。
- `region-CN`（实测失败）。
- 长效静态 ISP、按天独享。
- 前端改动（除非 URL 展示需要，本任务不做）。

## Acceptance Criteria

- [ ] AC1 生产机经网关 HTTP 代理可访问 `https://ipinfo.io/json`，出口为香港或至少非空 IP。
- [ ] AC2 两个不同 `silk_id` 在粘性期内使用不同 sid（日志可见 sid/key）；同一 `silk_id` 复用同一 sid。
- [ ] AC3 账号 A 失败 `invalidate(A)` 后 A 换新 sid，账号 B 的 sid 不变。
- [ ] AC4 `PUT /api/proxy/config` 保存网关 URL 后全部 sid 缓存清空，无需重启。
- [ ] AC5 提取模式 URL（无 userinfo 的 bilinip JSON）仍能解析；网关 URL 不再打提取 API。
- [ ] AC6 日志有「获取代理 key=… host:port sid=…」，**无密码**。
- [ ] AC7 本地 `mvn -DskipTests package` 通过；部署后监控/抢单请求不再因 bilinip `ErrBan` 报「代理不可用」。
- [ ] AC8 Lottery WAF 403 仍不换代理重试。

## Notes

- 网关 URL 形态（密码不入库文档）：`http://<user>:<pass>@hk.1024proxy.io:3000?region=HK&time=5`
- 回滚：把 `api_url` 改回 bilinip 或 `enabled=false`，再保存配置。
