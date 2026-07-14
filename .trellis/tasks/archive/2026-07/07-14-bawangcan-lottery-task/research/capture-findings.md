# 抓包调研：小蚕霸王餐抽奖任务接口

## 来源
- 抓包环境：mitmproxy 8080 + mitmweb 8081，系统代理切 8080，电脑微信打开小蚕惠生活小程序走完整抽奖流程。
- 抓包时间：2026-07-14，共 129 个 `POST gw.xiaocantech.com/rpc` 请求。
- 还原脚本/代理还原：`C:\Users\123\mitm_restore.ps1`（抓完恢复 10808）。

## 通用机制（RPC over HTTP）
- 端点：`POST https://gw.xiaocantech.com/rpc`（单一网关，靠 header 区分接口）
- 接口名 = `serverName` + `.` + `methodName`（放在请求 header，不在 body）
- body：纯明文 JSON，无加密
- 响应：`{"status":{"code":0}, ...}`，code!=0 即失败

## 关键 header
| header | 含义 | 抓包值(mini) |
|---|---|---|
| serverName | 服务名 | Silkworm / SilkwormLottery / ActivityTask ... |
| methodName | 方法名 | SilkwormLotteryMobile.AddLotteryTimes ... |
| appid | 应用 | 20 |
| X-Ashe | 签名(md5) | 见下算法 |
| X-Nami | 16位hex | UUID去横线插入silk_id |
| X-Garen | 时间戳(毫秒) | System.currentTimeMillis() |
| X-Session-Id | 会话 | f7b87152-...（登录态） |
| X-Platform | 平台 | mini（小程序）/ Android（App抢单） |
| x-City / X-CityCode | 城市码 | 0 或 440111 |
| x-Teemo | silk_id（带登录态时） | 222559356 |
| X-Vayne | user_id | 5263106 |

## 签名算法（已破，见 XiaochanHttp.java:36）
```java
getAshe(timeMillis, serverName, methodName, nami):
  x = MD5( (serverName + "." + methodName).toLowerCase() )
  return MD5( x + timeMillis + nami )   // 32位hex

getNami(): UUID去横线 -> 前4位 + silk_id + 剩余位 = 16位hex
```
本项目 `XiaochanHttp.getAshe/getNami/getHeaders/getGrabHeaders` 已完整实现，直接复用。

## 霸王餐抽奖相关接口清单
| serverName.methodName | 请求体 | 响应要点 | 用途 |
|---|---|---|---|
| SilkwormLottery.SilkwormLotteryMobile.LotteryInfo | {silk_id,app_id} | lottery_info{is_view_xxx...}, lottery_times{各任务+1次} | 查抽奖机会来源/哪些浏览没做 |
| **SilkwormLottery.SilkwormLotteryMobile.AddLotteryTimes** | {silk_id,type,app_id} | {status:{code:0}} | **type=浏览任务类型，调一次+1抽奖机会 ← 刷任务核心** |
| SilkwormLottery.SilkwormLotteryMobile.GetLotteryProgress | {silk_id,app_id} | lottery_progress{lottery_count} | 抽奖进度 |
| SilkwormLottery.SilkwormLotteryMobile.GetRewardUsers | {silk_id,app_id} | 中奖名单 | 参考 |
| SilkwormLottery.SilkwormLotteryMobile.IsShowStepLottery | {silk_id,app_id} | 阶梯抽奖开关 | 参考 |
| ActivityTask.ActivityTaskMobileService.GetDailyTask | {silk_id,city_code,user_id,app_id} | data.list[]{id,title,action_type,completed...} | 每日任务列表 |
| ActivityTask.ActivityTaskMobileService.UserTaskV2 | {silk_id,app_id} | data{point,yb_point,unreceived_points...} | 任务/积分状态 |
| ActivityTask.ActivityTaskMobileService.YbLotteryInfo | {silk_id,app_id} | lottery_info{lottery_times,daily_limit:30,activity_id:173,box_id:186,cost:200} | **霸王餐抽奖页：cost=200积分抽一次** |
| ActivityTask.ActivityTaskMobileService.WaitClaimedPoints | {silk_id,app_id} | 待领积分 | 参考 |
| ActivityTask.ActivityTaskMobileService.GetPointTaskAwardProgress | {silk_id,app_id} | 积分任务进度 | 参考 |
| ActivityTask.ActivityTaskMobileService.YbLotteryInfo | 同上 | 霸王餐抽奖 | 抽奖入口 |

## LotteryInfo 响应（抽奖机会来源，关键）
```json
{"lottery_info":{
  "is_view_bwc_page":false, "is_view_welfare_page":false, "is_view_tp_ad":true,
  "is_view_douyin_mall":true, "is_lucky":false, "if_shared":false, ...
},
"lottery_times":{
  "sign_in":1,"silk_order":1,"blindbox_order":1,"meituan_redpack":1,"eleme_redpack":1,
  "view_bwc_page":1,"view_welfare_page":1,"view_tp_ad":1,"view_douyin_mall":1
}}
```
- `is_view_xxx:false` = 该浏览任务未完成，完成后 +1 抽奖机会
- 对应 `AddLotteryTimes` 的 `type` 取值（type:10 已实测一种，其余需枚举）

## 两个不同的抽奖活动（重要，勿混淆）

### 活动 A：SilkwormLottery — 浏览任务得抽奖机会（本次目标）
- `LotteryInfo.lottery_times`：每个浏览任务(view_bwc_page/view_welfare_page/view_tp_ad/view_douyin_mall 等) +1 次机会
- `AddLotteryTimes(type)`：完成浏览任务 +1 机会（type=10 实测一种）
- `GetLotteryProgress`：lottery_count(当前次数), first/second_step_count(阶梯)
- `GetRewardUsers`：中奖名单，奖品=随机券/2元小红包/0.1度电 → **红包/券，非积分**
- `GetRedPackRainEvent`：红包雨(event_id/reward)
- **无 cost 字段，纯次数制** ← 浏览任务=抽奖机会

### 活动 B：ActivityTask.YbLotteryInfo — 积分宝箱（与本次无关）
- `lottery_times:1, daily_limit:30, activity_id:173, box_id:186, cost:200`
- **cost:200 = 用200积分换抽奖**，是另一套活动，本次不做
- 之前把 B 的 cost:200 错安到 A，已纠正

## 执行抽奖/开红包接口（已抓到）
- `SilkwormLotteryMobile.Lottery`：REQ `{silk_id,prize_type:1,app_id}`，RESP `{status, lucky_times, is_lucky, verify_method:1}`
- 抽奖返回 `code:200001 msg:"活动太火爆了，请完成验证确认参与资格..."` + `verify_method:1`
- 抽奖前置验证：`Brs.RiskCheckService.Verify`，REQ `{silk_id, service_name:"Brs", ticket, rand_str, check_sum, platform:1, app_id}`
  - ticket/rand_str/check_sum = 腾讯防水墙(TCaptcha)滑块/点选验证票据
  - 抓到的 ticket 失效：`{code:1,msg:"invalid captcha",pass:false}`
- **结论：执行抽奖被腾讯防水墙硬卡，纯接口无法绕过（票据时效性+行为验证）。本次不做自动抽奖。**

## 最终范围（已与用户确认）
- ✅ 自动完成浏览任务(`AddLotteryTimes` 各 type)攒抽奖机会
- ❌ 不做自动抽奖（需手动在小程序过滑块后抽奖）
- 即：后端只负责"刷机会"，用户手动把机会用掉

## 登录态候选（待实测）
1. **小程序(mini)登录态**：抓包所见，X-Platform=mini，X-Session-Id=f7b87152...，x-Teemo/X-Vayne 为 silk_id/user_id。来源接口 `WechatOpenapiService.MiniLogin`（抓包见过，reqCL=55）。
2. **Android(App)登录态**：项目现有 `GrabAuth`（抢单用），X-Platform=Android，含 X-Sivir/X-Teemo/X-Session-Id/Nami。
- 两者是否通用、哪种能刷浏览任务/抽奖，需实测：先用抓到的 mini 登录态重放 AddLotteryTimes，再用 Android 登录态重放，看哪个 code=0。
- mini 登录态从哪入库：可能需前端抓包/扫码获取 MiniLogin 结果存库，类似 GrabAuth 的录入方式。

## 风险
- 违反小蚕 TOS，有封号风险（账号本人所有，风险自担）。
- 浏览任务有停留时长/设备指纹风控，纯接口重放可能被判无效或风控。
- daily_limit:30 抽奖日上限，cost:200 积分/次，积分不够抽不了。
- 登录态可能过期，需支持刷新机制。
