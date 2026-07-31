# 霸王餐刷任务接入每日签到

## 背景

抽奖页「每日签到」可 +1 抽奖机会。现有一键刷任务只覆盖浏览/红包/广告类，漏掉签到。

### 接口结论（2026-07-29 实测）

- **写接口**：`SilkwormLotteryMobile.AddLotteryTimes`，**`type=1`**（端点 `gwh`）
  - 未签：`status.code=0`，`day_num` +1
  - 已签：`status.code=40002`，`msg=签到限一次`
- **无** `lottery_info.is_sign*` 标志位；完成态靠 type=1 返回码判断
- 与 VIP `VipRightsService.SignInLottery`（VIP 级别门槛）/ `UserSignInDays` **不是同一条链路**
- 与 `ActivityTaskMobileService.SignIn`（积分签到 +200 元宝）无关，本次不接

实测：authId=4（152）type=1 成功后 `day_num` 7→8；三账号再调均 `40002`。

## 目标

`/api/lottery/run` 自动处理每日签到：未签则 `AddLotteryTimes(1)`；已签记 SKIPPED。

## 范围

### In
- `LotteryServiceImpl.runTask` 增加 type=1「每日签到」明细
- 40002 → SKIPPED（已完成）；code=0 → OK；其它/异常 → FAIL，不中断其它任务

### Out
- VIP 连续签到 / `SignInLottery`
- 积分签到 `ActivityTask.SignIn`
- 独立签到 API 按钮（并进 run 即可）

## 验收

1. 未签账号 run 后 day_num +1，明细「每日签到」OK — **接口已实测**（authId=4 type=1 → day_num 7→8）；代码路径编译通过
2. 已签账号 run 明细 SKIPPED（40002→已完成），不报整单 error — **三账号再调均 40002**
3. 现有 7 项浏览/广告任务行为不变
4. 签到失败不阻断后续任务
5. 部署后前端点「刷任务」可见「每日签到」条目 — **已部署** 2026-07-29 03:21（bak=`xiaocan.jar.bak.20260729-032044`，service active，api 200）。今日三号均已签过，再刷预期 SKIPPED

## 约束

- 复用现有 `addLotteryTimes`，不新开 VIP 服务
- 因无 flag，**每次 run 都尝试 type=1**，用返回码区分 OK/SKIPPED
