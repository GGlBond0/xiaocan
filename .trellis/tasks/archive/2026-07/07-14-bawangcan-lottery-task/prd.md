# PRD：小蚕霸王餐抽奖任务自动完成

## 背景
小蚕惠生活小程序有霸王餐抽奖页（`SilkwormLottery` 活动）：完成"浏览霸王餐页/浏览福利页/浏览广告/浏览抖音商城"等浏览任务可获得抽奖机会（每个浏览任务 +1 次，`AddLotteryTimes`）；抽奖是**次数制**，机会用于开红包/券（奖品=随机券、2元小红包、0.1度电等），**不消耗积分**。当前这些浏览任务需用户手动逐个点击进入页面浏览，繁琐。

> 注：小蚕另有一套 `ActivityTask.YbLotteryInfo` 积分宝箱活动（cost=200 积分/次），与本次霸王餐浏览任务抽奖**不是同一个**，本次不做。

本项目（xiaocan-main）已逆向小蚕 RPC 网关并破解 X-Ashe 签名算法（`XiaochanHttp.java`），本任务复用该能力，自动完成浏览任务攒抽奖机会。

## 目标
通过后端调用小蚕 RPC 接口，自动：
1. 完成霸王餐抽奖页各"浏览类"任务（`AddLotteryTimes`），增加抽奖机会；
2. 前端设置页提供"一键刷霸王餐浏览任务"按钮手动触发，并展示"完成哪些任务、机会数变化"。

> 自动抽奖不在本次范围：执行抽奖 `Lottery` 前置 `Brs.RiskCheckService.Verify` 走腾讯防水墙(TCaptcha)验证（ticket/rand_str/check_sum 有时效性），纯接口无法绕过。**本次只刷机会，抽奖由用户手动在小程序过滑块后完成。**

## 范围
- ✅ 浏览任务自动完成（`AddLotteryTimes` 各 type）+ 前端手动触发按钮 + 结果展示。
- ❌ 不做：自动执行抽奖（腾讯防水墙验证硬卡点）、定时自动跑、下单类任务、分享到真实群、领取抽奖奖励后续处理。
- 登录态：已实测 mini 登录态 + 复刻签名调 `LotteryInfo` 返回 code=0 ✅，经梯子(非抓包IP)通过 → 无强 IP 绑定风控。倾向 mini，需补"小程序登录态录入"机制。

## 接口（已抓包确认，详见 research/capture-findings.md）
| 用途 | serverName.methodName | body |
|---|---|---|
| 查任务来源 | SilkwormLotteryMobile.LotteryInfo | {silk_id,app_id} |
| 刷浏览任务(+1机会) | SilkwormLotteryMobile.AddLotteryTimes | {silk_id,type,app_id} |
| 抽奖进度/次数 | SilkwormLotteryMobile.GetLotteryProgress | {silk_id,app_id} |
| 任务/积分状态 | ActivityTaskMobileService.UserTaskV2 | {silk_id,app_id} |
| 执行抽奖(本次不做) | SilkwormLotteryMobile.Lottery | {silk_id,prize_type,app_id} |
| 风控验证(本次不做) | Brs.RiskCheckService.Verify | {silk_id,ticket,rand_str,check_sum,...} |

> 注：`ActivityTaskMobileService.YbLotteryInfo`(cost:200)是另一套积分宝箱活动，本次不接入。

## 复用
- 签名：`XiaochanHttp.getAshe/getNami/getHeaders/getGrabHeaders`
- 代理重试：`ProxyHolder` + `postWithRes/postWithResAuth`
- 登录态：`GrabAuth`（Android）或新增 mini 登录态存储

## 待确认项（实现阶段实测）
1. **AddLotteryTimes 的 type 完整取值**：仅实测 type=10 一种。需从 LotteryInfo 的 is_view_xxx 映射出全部 type（view_bwc_page/view_welfare_page/view_tp_ad/view_douyin_mall 等）。实现时补抓/逐个点浏览任务记录 type。
2. **mini 登录态录入机制**：mini 登录态来源接口 `WechatOpenapiService.MiniLogin`（抓包见过，reqCL=55）。需确认如何让用户录入（前端填 silk_id/sessionId 或扫码 MiniLogin 结果入库），类似现有 GrabAuth 录入方式。
3. **AddLotteryTimes 真实刷成功验证**：实测调 AddLotteryTimes(各 type) 是否真的让 GetLotteryProgress.lottery_count +1（已实测签名/登录态通，但"加机会"效果待跑通确认）。

## 验收标准
- [ ] 后端新增服务能调通 LotteryInfo / AddLotteryTimes / GetLotteryProgress / UserTaskV2，签名通过（code=0）。
- [ ] 能枚举并完成全部浏览类任务 type，抽奖机会数增加（GetLotteryProgress.lottery_count 上升）。
- [ ] 前端设置页有"刷霸王餐浏览任务"按钮，点击后展示：完成哪些任务、前后机会数变化。
- [ ] mini 登录态录入机制落地，登录态过期可刷新。
- [ ] 不影响现有抢单/监控功能。

## 风险
- 违反小蚕 TOS，封号风险（账号本人，风险自担）。
- 浏览任务风控（停留时长/设备指纹），接口重放可能被判无效（需实测 lottery_count 是否真涨）。
- 机会用完/当日上限时加机会失败需明确提示。
- 登录态过期需可刷新。
