# AddLotteryTimes type → 浏览任务 映射

## 实测来源
- 抓包补抓：电脑微信小蚕惠生活小程序抽奖页，逐个点击浏览/领券/分享任务，记录每个 `AddLotteryTimes` 的 `type`，并对照前后 `LotteryInfo.lottery_info` 的 is_view/is_get/if_shared 标志翻转反推。
- 全部 type 返回 `{"status":{"code":0}}` → 接口接受，无风控拦截。

## 已确认映射（6 个）

| type | 触发的标志位 | 任务描述 |
|---|---|---|
| 2 | if_shared | 分享（分享给好友） |
| 8 | is_get_meituan_redpack | 领美团红包 |
| 9 | is_get_eleme_redpack | 领饿了么红包 |
| 10 | is_view_welfare_page | 浏览福利页 |
| 11 | is_view_bwc_page | 浏览霸王餐页 |
| (待补) | is_view_tp_ad | 浏览广告 |

## LotteryInfo.lottery_info 完整标志位
```
is_add_times, is_lucky, if_shared, order_num,
is_get_meituan_redpack, is_get_eleme_redpack,
is_view_welfare_page, is_view_bwc_page, is_view_tp_ad, is_view_douyin_mall
```
- `day_num` = 当日累计已获机会数；每次成功的 AddLotteryTimes 都让 day_num+1（实测 2→3→4→5）

## 风控有效性验证（关键）
- `AddLotteryTimes` 全部 code=0
- `day_num` 随每次调用 +1 → **纯接口重放真实生效，浏览任务判有效** ✅
- `lottery_count` 未变是因为机会已用（之前抽过），非无效
- 结论：自动刷浏览任务可行，不触发腾讯防水墙（验证只在执行抽奖 Lottery 时）

## 待补
- `is_view_tp_ad`(浏览广告)、`is_view_douyin_mall`(浏览抖音商城) 对应 type 未抓到（这两个任务当时已完成无法再点）
- sign_in(签到)、silk_order(下单)、blindbox_order(盲盒) 非"浏览类"，不在本次自动化范围
- 实现：用已知 5 个 type 先行；is_view_tp_ad/is_view_douyin_mall 若 LotteryInfo 显示未完成，前端列为"待补 type"，后续补抓后加常量

## 实现映射建议（LotteryHttp 静态常量）
```java
// type -> 任务描述
2  -> "分享"
8  -> "领美团红包"
9  -> "领饿了么红包"
10 -> "浏览福利页"
11 -> "浏览霸王餐页"
```
刷任务时：LotteryInfo 取所有 `is_view_*/is_get_*/if_shared == false` 的项，按上表映射出 type，逐个调 AddLotteryTimes。
