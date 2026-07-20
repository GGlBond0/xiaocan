# lottery 一键批量编排（做任务→开红包→领奖）

## Goal

后端新增**异步批量编排**端点，前端一个按钮触发，后端后台线程按固定间隔依次完成所有账号的"做任务→开红包→领累计奖励"全流程，跑完存结果供前端查询。降低 WAF 风控触发概率。

保留现有单账号 `run`/`draw` 接口不变。

## Background

- 2026-07-21 实测：draw 开红包本身安全（153 后端抽 2 次成功、App 正常）。风控触发主因是"短时间高频请求"（183 被 App 进页面 5 秒 50 并发触发 WAF 封 gwh）。
- 加间隔降低单位时间请求数，是缓解风控的核心手段。
- 全流程耗时长（多账号 15~20 分钟），不能同步占 HTTP，用**异步**（立即返回 taskId，后台线程跑，前端查最终结果）。

## 编排流程（后端后台线程）

```
POST /api/lottery/run-all  传 authId 列表
  → 立即返回 taskId（HTTP 几毫秒结束）
  → 后台线程依次执行：

阶段1 做任务：
  for 每个账号 A, B, ...:
    账号内每个任务间 40s 间隔（runTask 内部 AddLotteryTimes/OnAdViewed）
    该账号任务完成
    若不是最后一个账号 → sleep 10s
  所有账号做任务结束

sleep 60s

阶段2 开红包：
  for 每个账号 A, B, ...:
    账号内每次抽奖间 10s 间隔（draw 循环）
    该账号抽完
    若不是最后一个账号 → sleep 10s
  所有账号开红包结束

阶段3 领累计奖励：
  for 每个账号 A, B, ...:
    调 ReceiveExtraLottery step=1（达阈值且未领才调），step=2 同理
    step1 与 step2 间 10s
    若不是最后一个账号 → sleep 10s
  所有账号领奖结束

存最终结果（内存 Map<taskId, 全流程结果>）
```

## 间隔规则汇总

| 位置 | 间隔 |
|---|---|
| 阶段1 账号内任务间 | 40s |
| 阶段1 账号间 | 10s |
| 阶段1→阶段2 | 60s |
| 阶段2 账号内抽奖间 | 10s |
| 阶段2 账号间 | 10s |
| 阶段2→阶段3 | （可不加，阶段3账号间已有10s） |
| 阶段3 step1↔step2 | 10s |
| 阶段3 账号间 | 10s |

间隔只在"实际发了上游请求"后触发，SKIPPED（已完成/未达阈值）不 sleep。

## Requirements

### R1 后端批量端点
- 新增 `POST /api/lottery/run-all`，body 传 `authIds` 列表（List<Integer>）。
- 鉴权：校验所有 authId 归属当前 user（复用 resolveAuth 的归属逻辑）。
- 生成 taskId（UUID），立即返回 `{"taskId": "..."}`，HTTP 马上结束。
- 起后台线程跑全流程（@Async 或手动 new Thread，本任务用手动线程避免引入新配置）。

### R2 编排服务
- 新增 `LotteryBatchService`（或放 LotteryServiceImpl），方法 `runAll(List<Integer> authIds, String taskId)`：
  - 阶段1：循环账号调现有 `runTask` 逻辑（但 runTask 已含间隔？需重构——runTask 内部加 40s 间隔，账号间在编排层加 10s）。
  - 60s。
  - 阶段2：循环账号调 `draw` 逻辑（draw 内部 10s 间隔，账号间编排层 10s）。
  - 阶段3：循环账号调 `receiveExtraLottery` step1/step2（step 间 10s，账号间 10s）。
  - 收集每账号结果，存内存。

### R3 间隔实现
- runTask 内部：每个发请求任务间 40s（复用 07-21-lottery-pace-and-separate 之前的设计）。
- draw 内部：每次抽奖间 10s。
- 编排层：账号间 10s、阶段间 60s。
- sleep 工具方法 `sleepBetween(ms)`，含日志。

### R4 结果存储与查询
- 内存 `ConcurrentHashMap<String, BatchResult>`，key=taskId。
- `BatchResult`：状态（RUNNING/DONE/FAIL）、每账号的任务明细/红包明细/领奖明细、开始/结束时间、错误。
- 新增 `GET /api/lottery/run-all/result?taskId=`：跑完返回结果，没跑完返回 `{"status":"RUNNING"}`。
- 进程重启丢内存结果（可接受，用户一次跑完看一眼）。

### R5 保留单账号接口
- 现有 `POST /api/lottery/run` 和 `/draw` 保留（去掉上一任务的停用提示，恢复可用），前端可单独调，也可一键 run-all。
- runTask/draw 内部加间隔后，单账号接口也带间隔（40s/10s）。

### R6 WAF 识别保留
- 保留 `LotteryHttp.isWafBlock` + 403 不重试逻辑。批量流程中某账号被 WAF 封，该账号记失败，不中断其它账号。

## Constraints

- 后台线程手动 new Thread（或线程池），不引入 @Async 新配置。
- 本地构建 + 分片 scp 部署。
- 间隔值做成常量便于调整。
- DRAW_HARD_CAP=50 保留。

## Acceptance Criteria

- [ ] AC1 `POST /api/lottery/run-all` 传 authIds 立即返回 taskId，不阻塞。
- [ ] AC2 后台线程按顺序：阶段1(账号内40s+账号间10s)→60s→阶段2(账号内10s+账号间10s)→阶段3(step间10s+账号间10s)。
- [ ] AC3 `GET /run-all/result?taskId=` 跑完返回完整结果，没跑完返回 RUNNING。
- [ ] AC4 间隔只在实际发请求后触发，SKIPPED 不 sleep。
- [ ] AC5 现有 /run /draw 恢复可用，内部带间隔。
- [ ] AC6 某账号被 WAF 封不影响其它账号（记失败继续）。
- [ ] AC7 本地 mvn 构建通过，部署无 NPE。
- [ ] AC8 验证：单账号 run-all 跑通（用 153 低风险，注意会真抽红包/做任务，需你确认时机）。

## Notes

- 较复杂，写 design.md + implement.md。
- 修正 memory `lottery-batch-waf-risk`：开红包低频可用，风控主因高频并发，加间隔缓解。
- 前端"一个按钮"调 /run-all，前端仓库另改（你做）。
