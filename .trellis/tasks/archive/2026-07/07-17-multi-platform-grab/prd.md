# 自动抢单增加饿了么/京东平台并支持按平台选择

## Goal

解除当前自动抢单被硬编码限制为"仅美团"的现状，让自动抢单链路支持饿了么(2)、京东(3)平台，并允许在每条监控配置上勾选"启用抢单的平台"，只对勾选平台的命中活动建抢单任务。

## Background（现状）

- 平台枚举 `StorePlatformEnum` 已定义：UNKNOWN=0、MEITUAN=1、ELEME=2、JD=3；DB `grab_config.store_platform` 字段已存在（默认 1）。
- 但抢单链路被硬编码成只能美团：
  - `XiaochanHttp.grabPromotionQuota`（L224）请求体 `store_platform` 写死 1；
  - `AutoGrabServiceImpl`（L70）硬门禁 `store.getType() != PLATFORM_MEITUAN` → 非美团活动命中只通知、不建抢单任务；
  - 前端 `GrabConfigView` 不收集 `storePlatform`，落库默认 1。
- 不存在"按平台选择性抢单"的配置项（`grab_config`/`monitor_config` 均无平台过滤字段）。
- 前端唯一与平台相关的 UI 是 `GrabConfigView.vue` 活动选择对话框里的临时筛选复选框，不入库。

## Requirements

### 功能需求

- R1 自动抢单链路对饿了么(2)、京东(3)平台可用：上游 `grabPromotionQuota` 请求体 `store_platform` 改为按活动实际平台传入，不再写死 1。
- R2 监控命中建抢单任务的门禁按"监控配置勾选的启用平台集合"判断，而非硬编码美团。命中平台 ∈ 启用集合 → 建抢单任务；否则只通知不抢。
- R3 每条监控配置新增"启用抢单平台"多选项（默认含美团，保持向后兼容）。
- R4 `grab_config` 落库的 `store_platform` 取自命中活动的真实平台，而非默认 1。
- R5 手动抢单（`POST /api/grab/config/{id}/execute`）对饿了么/京东活动同样可用（请求体随配置平台走）。
- R6 前端监控配置页提供平台多选控件，保存生效。

### 约束

- C1 向后兼容：未改动的存量监控配置默认按"仅美团"行为，不改变其既有抢单结果。
- C2 登录态仍走现有 `login_state` 单池 + `grabLoginStateId`；若饿了么/京东对登录态/header 有平台差异，按 HAR 验证结论处理（见 design 前置假设 A1）。
- C3 上游接口对 store_platform=2/3 的可用性与响应结构需经真实抓包验证（HAR），是本需求落地的硬前提。

## Acceptance Criteria

- [ ] AC1 用饿了么活动触发监控命中，且该监控勾选了饿了么平台 → 自动建抢单任务，`grab_config.store_platform=2`，上游请求体 `store_platform=2`。
- [ ] AC2 用京东活动触发监控命中，且该监控勾选了京东平台 → 自动建抢单任务，`store_platform=3`。
- [ ] AC3 监控未勾选某平台时，该平台命中活动只通知、不建抢单任务（与现状非美团行为一致）。
- [ ] AC4 存量监控配置（未设置启用平台集合）行为与改造前一致：仅美团活动建抢单任务。
- [ ] AC5 手动对饿了么/京东的 grab_config 执行 `POST /api/grab/config/{id}/execute` 能正常发起上游抢单请求。
- [ ] AC6 前端监控配置页能勾选启用抢单平台并保存，刷新后保持。
- [ ] AC7 HAR 验证结论记录在 design.md，且据其确认饿了么/京东上游抢单可用（或明确不可用并降级方案）。

## Open Questions / 前置验证（已由 HAR 抓包验证，2026-07-17）

### V1 上游抢单接口按平台分两套（关键发现）

HAR（ProxyPin 2026-07-17 抓取，导入 mitmproxy 库 489 条小蚕流量）确认：

- **美团**：抢单走 `servername=Silkworm / methodname=SilkwormService.GrabPromotionQuota`，请求体 `{latitude,longitude,city_code,store_platform:1,if_advance_order:false,promotion_id,silk_id}`，响应含 `status.code/promotion_order_id/timeout`。活动详情用顶层 `meituan_status/meituan_order_money/meituan_left_number/meituan_user_rebate`，**无 `tp_promotion`**。
- **饿了么/京东**：抢单走 `servername=SilkwormCommunity / methodname=SilkwormMobileCommunityService.OrderExchange`，请求体字段**完全不同**：`{silk_id,new_level,is_plus,ingot,store_id,store_type,promotion_id,promotion_type,city_code,is_super_brand,promotion_silk_amount,store_category_sub_type,store_category_type,bwc_type,store_platform_order_money,bwc_platform,promotion_event_type}`，响应 `{"status":{"code":0},"data":{"red_pack":null,"extra":null}}`（code:0 成功）。
  - **京东**活动详情用 `tp_promotion{store_platform:3,tp_status,tp_order_money,tp_left_number,tp_user_rebate}`。
  - **饿了么**活动详情走**独立分支** `eleme_status/eleme_order_money/eleme_user_rebate/eleme_left_number`，**没有 `tp_promotion` 字段**。抢单请求里 `bwc_platform=2`。→ `parsePromotion` 饿了么分支必须单独填 OrderExchange 字段（tpStorePlatform=2，金额取 eleme_*）。
  - **`bwc_platform`/平台** 与现有 `StorePlatformEnum` 一致（1美团/2饿了么/3京东）。**`store_type` ≠ bwc_platform**：HAR 实测 `store_type == store_category_sub_type`（京东 sub_type=2→store_type=2，bwc_platform=3；饿了么 sub_type=12→store_type=12，bwc_platform=2），`store_category_type` 也 == sub_type。
- **结论**：饿了么/京东抢单**无法通过改 `GrabPromotionQuota` 的 `store_platform` 实现**，必须新增 `OrderExchange` 调用路径，且饿了么/京东活动详情结构不同需分别处理。

### V2 OrderExchange 请求体字段来源（部分待补）

已确认可从活动详情 response 拼出的字段：`store_id`(store.store_id)、`promotion_id`、`promotion_type`(promotion_detail.promotion_type)、`city_code`(store.city_code)、`promotion_silk_amount`(tp_promotion.tp_user_rebate)、`store_category_sub_type`(store.store_category_sub_type)、`store_platform_order_money`(tp_promotion.tp_order_money)、`bwc_platform`(tp_promotion.store_platform)。

来源已在 HAR 中部分定位、需补抓确认的字段：
- `new_level`/`is_plus` ← `SilkwormService.GetClientUserInfo` → `user_info.vip_level_info.new_level/is_plus`（已确认，flow 2b8c1da7）。
- `ingot` ← 未定位（user_info 顶层未见；可能在另一个接口或 user_info 某字段），需补抓。
- `store_type`/`store_category_type`/`is_super_brand`/`bwc_type` ← 活动属性，feed 列表 `FusionService.GetFeedPromotions`(flow 5b0383d5) 的 feed_items 含 `store_platform/order_money/user_rebate/left_number` 及 `store.category_sub_type`，但 `bwc_type`/`is_super_brand`/`store_category_type` 仍需在"活动详情2/活动规则"接口里精确定位。
- `promotion_event_type` ← 抢单样本里为 null，可能可固定 null，需确认。

实现期需补抓一次（含 `GetClientUserInfo` + 活动规则/详情接口）确认 `ingot`/`store_type`/`store_category_type`/`is_super_brand`/`bwc_type`/`promotion_event_type` 的精确来源。**D2 已从"完全未知"降为"大部分已知、少量待补"，不再阻塞设计落地，但增加了实现复杂度（需新增 GetClientUserInfo 调用 + 活动详情扩展解析）。**

### V3 平台映射

`tp_promotion.store_platform`/`bwc_platform`：1=美团、2=饿了么、3=京东，与现有 `StorePlatformEnum` 一致。多平台活动 store 同时有 meituan_id/eleme_id/jd_id，但每个活动只在一个平台放名额（`tp_promotion` 单值）。

## Notes

- 此任务为复杂任务，需 `design.md` + `implement.md`。
- 上游验证用 HAR 由用户在手机端 ProxyPin 抓取后提供。
