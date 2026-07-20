# Implement — lottery 一键批量编排

## 执行清单

- [ ] I1 新增 `LotteryBatchResultVO`：taskId/status/currentStage/error/taskResults/drawResults/stepPrizeResults + 内部 StepPrizeItem(step,ok,msg)。`@Data`。
- [ ] I2 `LotteryServiceImpl`：加常量 TASK_INTERVAL_MS=40000/ACCOUNT_INTERVAL_MS=10000/STAGE_INTERVAL_MS=60000/DRAW_INTERVAL_MS=10000 + `sleepBetween(ms)` 工具 + `BATCH_RESULTS` ConcurrentHashMap。
- [ ] I3 runTask 重构：
  - 保留 addLotteryTimes 5 项 + onAdViewed 2 项，每处实际调用后 sleep 40s（非最后一个）。
  - **移除 runTask 内部领阶梯奖逻辑**（line 147-156 的 addStepPrizeTask），领奖移到 runAll 阶段3。
  - 保留抽前/抽后快照、lotteryInfo。
- [ ] I4 draw 恢复：去掉 n=1 单次测试，恢复 `n=min(before,DRAW_HARD_CAP)`；循环内 `if(i<n-1) sleepBetween(DRAW_INTERVAL_MS)`。
- [ ] I5 抽取 `claimStepPrizes(authId, result)`：getLotteryProgress → 阶梯1达阈值未领调 receiveExtraLottery step=1 → sleep 10s → step=2。返回是否调用记 StepPrizeItem。
- [ ] I6 `runAll(List authIds)`：鉴权所有 authId（逐个 resolveAuth，失败抛异常）→ 生成 taskId → 存 RUNNING 结果 → new Thread 起 runAllInternal → 返回 taskId。
- [ ] I7 `runAllInternal(authIds, taskId)`：阶段1循环 runTask + 账号间 10s → sleep 60s → 阶段2循环 draw + 账号间 10s → 阶段3循环 claimStepPrizes + 账号间 10s → status=DONE（catch 设 FAIL）。
- [ ] I8 `getBatchResult(taskId)`：从 BATCH_RESULTS 取返回（无则 null/提示）。
- [ ] I9 `LotteryService` 接口加 runAll/getBatchResult。
- [ ] I10 `LotteryController`：run/draw 恢复调用 service；新增 `POST /run-all`（@RequestBody List<Integer>）返回 taskId；新增 `GET /run-all/result?taskId=`。
- [ ] I11 本地 mvn package。
- [ ] I12 部署：分片 scp + 替换 + restart。
- [ ] I13 验证（用 153 跑真流程，你确认时机）：
  - 调 `POST /run-all` body `[1]`（只 153 一个账号），确认立即返回 taskId。
  - 调 `GET /run-all/result?taskId=` 确认返回 RUNNING。
  - 等流程跑完（6~8 分钟），再查 result 确认 status=DONE + 任务/红包/领奖明细齐全。
  - 你手机 153 App 端核对：任务做了、红包抽了几个、次数减少、页面正常无 WAF。
- [ ] I14 修正 memory `lottery-batch-waf-risk`：开红包低频可用（153 实测），风控主因高频并发，加间隔缓解，批量编排已落地。

## Review Gate

- I3：runTask 移除领阶梯奖（避免与阶段3重复），间隔只在实调用后。
- I4：draw 恢复循环 + 10s 间隔，DRAW_HARD_CAP 保留。
- I6：鉴权先于后台线程，任一无权立即抛。
- I7：阶段间 60s、账号间 10s、WAF 失败不中断。
- I11 构建必须通过。
- I13 真流程跑通才算验收（你手机核对）。

## Rollback

- I12 失败 → 不部署修代码。
- I13 NPE/异常 → 回滚上一 jar restart，查日志定位。
- 153 若因此被封（低概率，已加间隔）→ 回滚 + 等 WAF 解封。
