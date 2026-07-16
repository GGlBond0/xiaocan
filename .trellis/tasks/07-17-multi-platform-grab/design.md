# 技术设计：自动抢单增加饿了么/京东平台并支持按平台选择

## 前置假设（HAR 已验证，见 prd.md V1-V3）

- 饿了么/京东抢单走 `SilkwormMobileCommunityService.OrderExchange`，与美团 `SilkwormService.GrabPromotionQuota` 是两套独立接口契约。
- 平台映射：`tp_promotion.store_platform`/`bwc_platform` 1=美团/2=饿了么/3=京东，与 `StorePlatformEnum` 一致。

## 范围与边界

本任务只做"自动抢单链路打通饿了么/京东 + 监控层按平台选择"。手动抢单（`GrabConfigView` 选活动建 config）的饿了么/京东活动选择不在本期范围（前端目前的活动选择对话框已能按平台筛选展示，但落 config 时 store_platform 仍需后端正确写入——见改动点 D5，最小支持手动触发）。

## 数据模型

### M1 `monitor_config` 新增启用抢单平台集合

- 新增列 `grab_platforms VARCHAR(64) DEFAULT '1'`（逗号分隔的平台 int 集合，如 `"1,2"`）。
- 默认 `'1'`：存量监控配置行为不变（仅美团），满足 AC4。
- 不用单独关联表：集合小（最多 3 值）、查询只做"包含"判断，逗号串足够；MyBatis-Plus 实体加 `String grabPlatforms` 字段。
- 读取侧封装辅助方法：`Set<Integer> platformSet = parsePlatforms(config.getGrabPlatforms())`，空/null → `{1}` 兜底。

### M2 `grab_config.store_platform` 落真实平台

- 字段已存在（DDL 默认 1）。改动只在写入处：`AutoGrabServiceImpl` 建占位任务时 `storePlatform = store.getType()`（命中活动的真实平台），不再恒为 1。
- 手动建 config 路径（`GrabServiceImpl.addUpdateConfig`）保持现状默认 1（手动链路本期不强改，见 D5 备注）。

## 后端改动点

### D1 `XiaochanHttp` 新增 `orderExchange` 方法 + `getClientUserInfo`

新增对 `SilkwormCommunity / SilkwormMobileCommunityService.OrderExchange` 的调用，复用 `postWithResAuth`（header/加密/代理机制与 GrabPromotionQuota 一致，HAR 确认 header 字段名相同：X-Ashe/X-Nami/X-Garen/X-Sivir/X-Vayne/x-Teemo/X-Session-Id）。

```
JSONObject orderExchange(GrabAuth auth, OrderExchangeReq req)
JSONObject getClientUserInfo(GrabAuth auth)   // 取 user_info.vip_level_info.new_level/is_plus
```

- 新增 `OrderExchangeReq` 模型承载 OrderExchange 请求体字段（见 D2）。
- 响应判据：`status.code == 0` 视为成功。
- OrderExchange 所需的 `new_level/is_plus` 由 `getClientUserInfo` 取，`ingot` 及活动属性字段按 D2 补抓结论取。

### D2 OrderExchange 请求体字段来源（实现期硬卡点 ⚠）

字段分三类：

| 字段 | 来源 | 状态 |
|---|---|---|
| silk_id | auth.silkId | ✓ |
| store_id | promotion_detail.store.store_id | ✓ |
| promotion_id | config.promotionId | ✓ |
| promotion_type | promotion_detail.promotion_type | ✓ |
| city_code | promotion_detail.store.city_code | ✓ |
| promotion_silk_amount | promotion_detail.tp_promotion.tp_user_rebate | ✓ |
| store_category_sub_type | promotion_detail.store.store_category_sub_type | ✓ |
| store_platform_order_money | promotion_detail.tp_promotion.tp_order_money | ✓ |
| bwc_platform | promotion_detail.tp_promotion.store_platform | ✓ |
| new_level / is_plus | `SilkwormService.GetClientUserInfo` → `user_info.vip_level_info` | ✓（HAR flow 2b8c1da7）需新增该接口调用 |
| store_type / store_category_type / is_super_brand / bwc_type / promotion_event_type | 活动属性接口（feed `FusionService.GetFeedPromotions` 或活动详情2/规则接口） | ⚠ 部分待补抓精确定位 |
| ingot | 用户元宝余额接口 | ⚠ 待补抓 |

**D2 已从"完全未知"降为"大部分已知"**。关键影响：实现 OrderExchange 必须在抢单前额外调用 `GetClientUserInfo` 取 `new_level/is_plus`，并补抓确认 `ingot`/活动属性字段的来源接口。这使本任务实现量明显增加（新增 ≥2 个上游接口调用 + 请求体组装逻辑），但不再是阻塞性风险。

**降级方案**：若补抓后某字段仍无法定位，允许在 `OrderExchangeReq` 里给经样本验证的默认值（代码注释标注依据），或先只发布可完整组装的平台。

### D3 活动详情解析扩展

现有 `XiaochanHttp.getStorePromotionDetail` 返回 `StoreInfo`，但 `StoreInfo` 未解析 `tp_promotion`/`store.store_category_sub_type` 等 OrderExchange 所需字段，也未保留原始 JSON。

- 扩展 `StoreInfo`（或新增 `PromotionDetail` 模型）保留 `tpPromotion{storePlatform,tpOrderMoney,tpUserRebate,tpLeftNumber,tpStatus}`、`storeType`、`storeCategorySubType`、`storeCategoryType`、`isSuperBrand`、`bwcType`、`promotionType`、`cityCode` 等。
- 或：新增 `JSONObject getPromotionDetailRaw(promotionId)` 保留原始 JSON，由 `doGrab` 自行取字段。优先选结构化模型，避免散落 JSONObject。

### D4 `GrabServiceImpl.doGrab` 按平台分叉

在重试循环内，按 `config.getStorePlatform()` 分派：

- `storePlatform == 1`（美团）：现有 `grabPromotionQuota` 路径不变，成功判据 `code==0 && promotion_order_id!=null`。
- `storePlatform == 2 或 3`（饿了么/京东）：调 `orderExchange`，成功判据 `status.code==0`。
- `UNKNOWN/其它`：按美团兜底或直接失败（实现期定，倾向失败并记历史）。

`promoSnapshot` 查询改为平台无关（`getStorePromotionDetail` 对多平台活动也返回 detail），OrderExchange 所需字段从扩展后的 snapshot 取。

`saveHistory`/`push` 复用现有逻辑，`promotion_order_id` 对 OrderExchange 可能为空（响应无此字段），历史记录 orderId 字段对饿了么/京东允许 null。

### D5 `AutoGrabServiceImpl` 门禁改按启用平台集合

- L70 硬门禁 `store.getType() != PLATFORM_MEITUAN` 改为：`!platformSet.contains(store.getType())` → 只通知不建任务。
- `platformSet` 来自该监控配置的 `grabPlatforms`（M1）。
- 建占位任务时 `storePlatform = store.getType()`（M2）。

### D6 手动抢单最小支持

手动 `POST /api/grab/config/{id}/execute` 复用 `doGrab`，因 D4 已按平台分叉，只要 `grab_config.store_platform` 落了真实值就能正确发 OrderExchange。手动建 config 的链路（前端 `GrabConfigView.pickStore`）暂不强制收集 storePlatform，保持默认 1（AC5 验收用手动改库或后续补前端）。

## 前端改动点

### F1 监控配置页平台多选

`MonitorConfigView.vue`（`/monitor`）新增"启用抢单平台"多选控件（美团/饿了么/京东），仅在 `autoGrab` 开关打开时显示。

- 选项：`[{label:'美团',value:1},{label:'饿了么',value:2},{label:'京东',value:3}]`
- 默认勾选美团（向后兼容）。
- 保存为 `grabPlatforms` 字段（逗号串），随监控配置一起 `POST /api/notify/*`。
- 加载时反显。

### F2 监控配置 DTO/VO

`monitorConfigDTO` 与对应 VO 新增 `grabPlatforms: String`。

## 兼容性 / 回滚

- M1 新列 `DEFAULT '1'` + 读取侧 null/空兜底 `{1}`：存量配置零迁移。
- D4 美团分支完全不变，D5 门禁对"集合含美团"等价原逻辑。
- 回滚：M1 列保留不删即可；D1/D3/D4/D5 代码改动集中在 `XiaochanHttp`/`GrabServiceImpl`/`AutoGrabServiceImpl`/`StoreInfo`，git revert 即可恢复纯美团行为。
- D2 若补抓后证实无法获得某字段 → 不发布该平台，仅发布已确认链路（如先只发饿了么或只发京东），剩下一平台留待后续。

## 测试 / 验证

- 无单测框架改动；以 HAR replay + 服务器实测为准。
- AC1-AC3 用真实饿了么/京东活动触发监控命中验证（需登录态有效）。
- D2 补抓是 `task.py start` 前的前置：补抓结论回填 prd.md V2 后再实现。

## 风险登记

- R-D2（高）：OrderExchange 8 个字段来源未定位，是落地硬卡点。
- R-风控：OrderExchange 对饿了么/京东是否也需饭票校验、是否有 WAF 风控，未在 HAR 中观察到失败样本，实现期需小流量验证。
- R-名额语义：`tp_promotion.tp_left_number` 是否随 OrderExchange 成功递减、是否会因多次抢触发上游风控，待实测。
