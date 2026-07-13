# 为 XiaochanHttp 加动态代理绕过小蚕 WAF 封禁

## Goal

让小蚕后端调小蚕官方网关 `https://gw.xiaocantech.com/rpc` 时走动态代理出口，
绕过服务器公网 IP 被腾讯云 WAF 封禁(403) 的问题，使"搜索地址/活动列表"等依赖官方接口的功能恢复。

## Background / 研究结论（2026-07-14 已查实）

- 部署本身完全正常：`http://121.91.175.192:8088/` 首页 200、`/api/location/cityCode` 返回真实数据、`/api/user/getUserInfo` 正常。
- 服务器直连 `curl https://gw.xiaocantech.com/rpc` 返回 **403**（百度 200）→ 服务器公网 IP `121.91.175.192` 被小蚕腾讯云 WAF 封禁。
- 后端 `XiaochanHttp`（`xiaochan-main/src/main/java/io/github/xiaocan/http/XiaochanHttp.java`）用 hutool `HttpUtil.createPost()` 直连小蚕网关。
- 403 代码路径：`searchAddress()` / `postWithRes()` 收到 `response.getStatus()!=200` → 抛 `BusinessException("状态码错误:"+status)`。
- README 明确：小蚕有 IP 检测/WAF 封禁机制，"用代理可能避免这个问题"。

## User Decisions

- 代理形式：**动态代理池 API** —— 先调代理 API 取临时代理地址，再用该代理调小蚕网关。
- 代理 API 调用细节（URL/认证/返回格式/协议类型 HTTP or SOCKS5/有效期/取频率）**待用户提供**。

## Requirements

- R1 在 `XiaochanHttp` 中增加"先取代理、再用代理发请求"的能力，所有调 `gw.xiaocantech.com` 的方法统一走代理（searchAddress / postWithRes 等）。
- R2 代理配置（代理 API 的 URL、认证 key、协议、有效期等）走**环境变量**注入（与现有 MySQL/Redis 配置方式一致），不硬编码、不入仓库。
- R3 代理失败（取不到/代理不可用）时：换下一个代理重试 N 次；全失败则降级（可选：直连并明确报错，或抛业务异常提示"代理不可用"）。
- R4 代理地址缓存其有效期，未过期复用，避免每次请求都取代理（减少代理 API 调用频率）。
- R5 改动经 fork workflow 重新构建 jar、上传、重启 systemd 部署生效。
- R6 不影响现有非小蚕网关请求（本项目所有外部请求都是小蚕网关，故等价全走代理；但仍保证代理仅作用于调 `gw.xiaocantech.com` 的请求）。

## Acceptance Criteria

- [ ] 部署后从服务器经后端代码路径调小蚕网关返回 200（不再 403）
- [ ] 浏览器 `http://121.91.175.192:8088/` 搜索地址功能返回真实地址列表（非"状态码错误:403"）
- [ ] 活动列表 `/api/xiaochan/query` 能正常返回门店数据
- [ ] 代理 API 配置经环境变量注入，jar 与仓库内无明文代理密钥
- [ ] 代理失败时日志清晰、不会卡死或无限重试
- [ ] 他人项目/站点不受影响

## Open Questions（阻塞，待用户提供）

- Q1 代理 API 的调用 URL、认证方式（key 在 url 还是 header）、返回格式（JSON 字段名 or 纯文本 ip:port）
- Q2 代理协议：HTTP 还是 SOCKS5
- Q3 单个代理地址有效期、是否需失败轮换、取代理频率限制

## Notes

- 本任务为后端代码改造 + 重新构建部署，属复杂任务，需补 design.md + implement.md。
- 当前部署任务 `07-14-deploy-xiaocan-server` 可先行归档（部署本身完成），代理为独立后续任务。
