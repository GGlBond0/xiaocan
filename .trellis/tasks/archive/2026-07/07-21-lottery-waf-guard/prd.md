# lottery gwh WAF 风控识别与停用

## Goal

修复后端抽奖模块（LotteryHttp）两个 WAF 风控相关问题，并立即停止一切 gwh 请求，避免加重 183 账号封禁、连累 153/15270957596。

## 事件背景（2026-07-21 实测）

- 用户 183 账号 App 端 gwh 请求被腾讯云 WAF 全面拦截（抓包 `har/ProxyPin7-21_00_59_49.har`：50 次 gwh 请求返回 403 WAF拦截 HTML）。
- 服务器日志 `error.log` 早已记录 `lotteryInfo/getLotteryProgress 经代理...返回 403，换代理重试`，但此前未被发现（违反 [[ssh-first-behavior]]"先读远端日志"）。
- 根因 1（请求放大）：`LotteryHttp.executeWithProxy` line 252-254 把 403 当"代理坏"换代理重试 3 次，每个失败请求打 4 次 gwh。WAF 按"账号+端点"封，换代理无效，只放大请求量、加重封禁。
- 根因 2（风控不识别）：`postAuth` 只判 `response.isOk()`（HTTP 200），403 WAF 拦截走"状态码错误"分支抛异常，上层 try-catch 吞成 `lottery_count=0` 等废值，掩盖了"被封"事实，导致反复误判为"次数为 0""活动迁移""App 死了"。
- 实测结果：draw 因 `lottery_count=0` 循环 0 次，**未发出任何 Lottery 抽奖请求、未消耗抽奖次数**（183 真实剩 8 次，在小程序端）。封禁由 runTask 的 addLotteryTimes/onAdViewed 大量请求 + 403 重试放大触发，非 draw 抽奖触发。
- 小程序端 gw（+app_id:20）未被封，是可用路径，但属另一个改造任务，不在本任务范围。

## Requirements

### R1 识别 WAF 403，停止重试
- `LotteryHttp` 对 403 响应：检查 body 是否为 WAF 拦截页（含 `WAF拦截` 或 `block-pages`/`403` HTML 特征）。若判定为 WAF 拦截：
  - **不再换代理重试**，直接停止本次请求。
  - 返回结构化失败 `{"status":{"code":403,"msg":"账号已被WAF风控，请暂停操作"}}`（与 401 业务拒绝同款结构，不抛异常中断整轮）。
  - 记 ERROR 日志，文案明确含"WAF风控"，便于后续一眼定位（之前日志只写"返回 403"被淹没）。
- 非 WAF 的真 403（若可区分）保留原换代理逻辑；无法区分时按 WAF 处理（保守，避免再放大）。

### R2 暂停 gwh 调用入口
- `/api/lottery/run` 与 `/api/lottery/draw` 临时停用：直接返回明确提示"抽奖服务暂停（账号 WAF 风控规避中）"，不再发任何 gwh 请求。
- 用开关或硬编码停用均可，但**必须保证不发出 gwh 请求**。
- 撤掉临时探查端点 `/api/lottery/progress` 及 `probeProgress`（调试用，不该留生产）。

### R3 代码清理
- 删除 `LotteryService.probeProgress` / `LotteryController.progress` / `LotteryServiceImpl.probeProgress` 实现。
- `LotteryServiceImpl.draw` 里抽后 `getLotteryProgress` 失败吞异常的 `afterCount=null` 现象：保留容错，但 R1 后 403 会返回结构化失败而非异常，draw 收到 code=403 时应停止并设 error（不再循环）。

## Constraints

- 不改 runTask/draw 的正常业务逻辑，只在风控路径上停。
- 本地构建 + 分片 scp 部署（[[scp-large-jar-hangs-server]]、[[prod-build-avoid-server]]）。
- 修复后**不再主动调 run/draw 验证**（会发 gwh 请求），只验证接口返回"已停用"提示 + 日志不再出现新的 gwh 403。

## Acceptance Criteria

- [ ] AC1 部署后 `POST /api/lottery/run` 与 `/draw` 返回"已停用"提示，**不发出任何 gwh 请求**（日志无 LotteryHttp 调用）。
- [ ] AC2 `/api/lottery/progress` 端点已移除（404）。
- [ ] AC3 `LotteryHttp` 遇 WAF 403 不再换代理重试，返回结构化 `code:403` 失败 + ERROR 日志含"WAF风控"。
- [ ] AC4 本地 mvn 构建通过，部署无 NPE。
- [ ] AC5 服务器日志确认修复部署后无新增 gwh 请求/403。

## Notes

- 中低复杂度，PRD-only。具体停用方式（开关 vs 硬编码）实现时定，倾向硬编码停用 + 注释（简单、显式、好回滚）。
- 小程序端改造（gw + app_id:20）是后续独立任务，不在本任务。
- 记忆 [[lottery-app-auth-table]] 涉及 gwh 契约，本任务后需更新注明 WAF 风控现状。
