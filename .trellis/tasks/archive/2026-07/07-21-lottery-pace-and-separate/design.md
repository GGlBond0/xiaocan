# Design — lottery 一键批量编排

## 新增/改动文件

| 文件 | 改动 |
|---|---|
| `LotteryHttp.java` | 无需改（已有 lottery/receiveExtraLottery/onAdViewed/addLotteryTimes + WAF 识别） |
| `LotteryService.java` | 加 `runAll(List<Integer> authIds)` 返回 taskId；加 `getBatchResult(taskId)` |
| `LotteryServiceImpl.java` | runTask 内部加 40s 间隔；draw 恢复循环+10s 间隔；实现 runAll 编排；内存存结果 |
| `LotteryController.java` | run/draw 恢复调用 service；新增 `POST /run-all`、`GET /run-all/result` |
| `LotteryBatchResultVO.java`（新） | 批量结果 VO |
| `LotteryTaskResultVO.java`/`LotteryDrawResultVO.java` | 复用，不改 |

## 编排核心（LotteryServiceImpl.runAll）

```java
private static final Map<String, LotteryBatchResultVO> BATCH_RESULTS = new ConcurrentHashMap<>();
private static final long TASK_INTERVAL_MS = 40_000;
private static final long ACCOUNT_INTERVAL_MS = 10_000;
private static final long STAGE_INTERVAL_MS = 60_000;
private static final long DRAW_INTERVAL_MS = 10_000;

public String runAll(List<Integer> authIds) {
    // 鉴权所有 authId 归属（resolveAuth 逐个校验，失败抛异常，不进后台）
    String taskId = UUID.randomUUID().toString();
    LotteryBatchResultVO result = new LotteryBatchResultVO();
    result.setTaskId(taskId);
    result.setStatus("RUNNING");
    BATCH_RESULTS.put(taskId, result);
    new Thread(() -> runAllInternal(authIds, taskId), "lottery-batch-"+taskId).start();
    return taskId;
}

private void runAllInternal(List<Integer> authIds, String taskId) {
    LotteryBatchResultVO result = BATCH_RESULTS.get(taskId);
    try {
        // 阶段1 做任务
        for (int i=0;i<authIds.size();i++){
            result.setCurrentStage("阶段1做任务 账号"+authIds.get(i));
            LotteryTaskResultVO t = runTask(authIds.get(i));   // runTask 内部已带 40s 间隔
            result.addTaskResult(authIds.get(i), t);
            if (i < authIds.size()-1) sleepBetween(ACCOUNT_INTERVAL_MS);
        }
        sleepBetween(STAGE_INTERVAL_MS);  // 60s
        // 阶段2 开红包
        for (int i=0;i<authIds.size();i++){
            result.setCurrentStage("阶段2开红包 账号"+authIds.get(i));
            LotteryDrawResultVO d = draw(authIds.get(i));      // draw 内部已带 10s 间隔
            result.addDrawResult(authIds.get(i), d);
            if (i < authIds.size()-1) sleepBetween(ACCOUNT_INTERVAL_MS);
        }
        // 阶段3 领累计奖励
        for (int i=0;i<authIds.size();i++){
            result.setCurrentStage("阶段3领奖 账号"+authIds.get(i));
            claimStepPrizes(authIds.get(i), result);           // step1→10s→step2
            if (i < authIds.size()-1) sleepBetween(ACCOUNT_INTERVAL_MS);
        }
        result.setStatus("DONE");
    } catch (Exception e) {
        result.setStatus("FAIL");
        result.setError(e.getMessage());
        log.error("run-all 失败 taskId={}", taskId, e);
    }
}
```

## 阶段3 领奖（claimStepPrizes）

```java
private void claimStepPrizes(Integer authId, LotteryBatchResultVO result) {
    AuthBundle b = resolveAuth(authId);
    JSONObject progress = lotteryHttp.getLotteryProgress(b.auth);
    JSONObject lp = progress.getJSONObject("lottery_progress");
    for (int step=1; step<=2; step++) {
        // 复用 addStepPrizeTask 逻辑：达阈值且未领才调
        boolean called = tryReceiveStep(b.auth, lp, step, result, authId);
        if (called && step==1) sleepBetween(ACCOUNT_INTERVAL_MS);  // step1↔step2 间 10s
    }
}
```
（从现有 addStepPrizeTask 抽取"达阈值+未领则调 receiveExtraLottery"的核心，返回是否真调了。SKIPPED 不 sleep。）

## runTask 内部 40s 间隔

runTask 现有 3 处任务循环（FLAG_TO_TYPE/addAdViewTask/addStepPrizeTask）。但注意：**runTask 内部本来含阶段3领阶梯奖**（line 147-156），现在阶段3 移到 runAll 阶段3 单独做，runTask 内部的领阶梯奖逻辑**移除**（避免重复领）。runTask 只做阶段1的任务（addLotteryTimes + onAdViewed）。

每个 addLotteryTimes/onAdViewed 实际调用后 sleep 40s（非该循环最后一个）。SKIPPED 不 sleep。

## draw 内部 10s 间隔

恢复 `n = min(lottery_count, DRAW_HARD_CAP)`，循环内 `if (i < n-1) sleepBetween(DRAW_INTERVAL_MS)`。

## controller

```java
@PostMapping("/run")
public BaseResult<LotteryTaskResultVO> runTask(@RequestParam Integer authId) { return BaseResult.ok(lotteryService.runTask(authId)); }

@PostMapping("/draw")
public BaseResult<LotteryDrawResultVO> draw(@RequestParam Integer authId) { return BaseResult.ok(lotteryService.draw(authId)); }

@PostMapping("/run-all")
public BaseResult<String> runAll(@RequestBody List<Integer> authIds) { return BaseResult.ok(lotteryService.runAll(authIds)); }

@GetMapping("/run-all/result")
public BaseResult<LotteryBatchResultVO> getBatchResult(@RequestParam String taskId) { return BaseResult.ok(lotteryService.getBatchResult(taskId)); }
```

## LotteryBatchResultVO

```java
@Data
public class LotteryBatchResultVO {
    private String taskId;
    private String status;   // RUNNING / DONE / FAIL
    private String currentStage;
    private String error;
    private Map<Integer,LotteryTaskResultVO> taskResults = new LinkedHashMap<>();
    private Map<Integer,LotteryDrawResultVO> drawResults = new LinkedHashMap<>();
    private Map<Integer,List<StepPrizeItem>> stepPrizeResults = new LinkedHashMap<>();
    // StepPrizeItem: step, ok, msg
}
```

## WAF 处理

runAll 中某账号 runTask/draw 内部遇 WAF 403（isWafBlock 返回 code:403 结构化失败），该账号任务/红包记失败，不抛异常，继续下一账号。runTask/draw 内部已有 try-catch 容错，不会中断 runAll。

## 兼容性/回滚

- 新增端点 + 新增 VO，run/draw 恢复，runTask 移除内部领阶梯奖（行为变化，但领奖移到阶段3，整体行为一致）。
- 回滚：删 run-all 端点 + BatchResultVO + runAll 方法，run/draw 恢复停用或保留。

## 风险

- 后台线程无监控，进程重启丢。可接受（用户一次跑完看结果）。
- runAll 鉴权：逐个 resolveAuth 校验，任一失败立即抛异常不进后台（避免跑一半才发现无权）。
- 并发：同一 taskId 唯一；用户可多次点按钮生成多个 taskId 并行跑（可能加重风控）——可选加"单 taskId 同时只允许一个"限制，本任务先不做（前端按钮点完置灰即可）。
