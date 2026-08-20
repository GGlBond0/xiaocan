# Upstream Modules Merge（强耦合网合入专项设计）

> 把上游 lyrric/xiaochan 的强耦合功能网（歪麦/收藏/库存历史/门店搜索/消息批量）在**本地二开底座上**以最小侵入方式移植合入。
> 关联记忆：`upstream-fork-merge-analysis`。第一批低风险增量已完成（2026-08-21，commit ef4bafb）。

---

## Overview（目标与边界）

本地 `main` 与上游 `upstream/main` git 历史不相交，无法 merge。上游五模块互相关联且锁死在上游重构底座（StoreInfo 的 `uniqId/storeTypeEnum/rebateRatio/...`、`UserEntity.waimaiToken`、静态化 XiaochanHttp、`promotionId Integer→String`、`store_pushed_history` 字段重构）。

本专项采用**最小侵入的"独立能力叠加"**策略：
- **本地主权区一律不动**：抢单链路(GrabService/AutoGrab/OrderExchange)、去重(storeId+promotionId)、3km 过滤、登录态池(`login_state`/LoginStateEntity)、代理(ProxyHolder/executeWithProxy)、监控任务链(BaseTask/StoreTask/MinimumPayService)、`store_pushed_history` 现有表结构。
- **新增五模块作为独立可用能力**：新增 Controller + Service，内部自取数据，**不接入本地监控任务链**，**不改本地现有数据流**。
- 只做**加法**：补实体字段、补缺失 bean/方法、新增新类，使五模块编译运行。

## 用户决策（已确认）
1. 五件全做：歪麦 + 收藏 + 库存历史 + 门店搜索 + 消息批量。
2. StoreInfo **加法扩展**：新增 8 字段（`uniqId/storeTypeEnum/distanceStr/rebateRatio/rebateMax/rebateConditionStr/favoriteId/exists`），保留本地 storeId/promotionId(Integer)/distance/rebateCondition/openHours/ifNew。
3. 歪麦加 `waimaiToken`（UserEntity 新字段 + `waimai_token` 列）。
4. 独立能力，不改本地任务链 / 数据流 / 现有表结构。

## 关键事实（已第一手核实）
- 本地 `XiaochanHttp` 是**实例方法 + new 实例**（GrabServiceImpl:55、XiaoChanServiceImpl:27）。本地 getList/searchList 签名与上游一致；本地缺 `searchMeituanList/getMeituanList`。
- 本地 `UserEntity.xc*` 登录态字段**全为死字段**（零调用）；真登录态在 `login_state` 表。上游删 xc* 对本地零损失。
- 本地缺 bean：`SystemConfig`、`MessageBatchRecordService`、`TransactionTemplate`(依赖 Spring Boot 默认 DataSourceTransactionManager，必要时 MybatisPlusConfig 显式补)。
- 本地 `StorePlatformEnum` 在 `constant` 包，可用。
- `PageConvertUtil.convertList` 上批漏合，本专项先补（纯净无冲突）。

---

## 实现分层（L0 地基 → L4 控制器）

### L0 地基（纯新增/补字段，无本地冲突）
| 项 | 说明 |
|---|---|
| `PageConvertUtil.convertList` | 补上游方法（本批先补） |
| `SystemConfig` | `config/` 新类，读 `system.web-url`（yaml 已配） |
| `StoreInfo` 加法 8 字段 | 见 Overview，setter/getter；`distanceStr` 与 distance 互转 setter 一并带 |
| `UserEntity` + `waimaiToken` | →列 `waimai_token`，保留本地 xc*/notifyDedupMinutes 等 |
| `TransactionTemplate` bean | 本地 MybatisPlusConfig 补，或依赖 Spring Boot 默认 |
| `StoreTypeEnum`/`NotifyFrequencyEnums`/`StoreConstant` | 上批已合 ✅ |

### L1 基础设施服务（新增）
- `MessageBatchRecordService(+Impl/Mapper/Entity)` — 消息批次
- `StoreInventoryHistoryService(+Impl/Mapper/Entity/VO)` — 库存历史（用 TransactionTemplate）
- `FavoriteStoreService(+Impl/Mapper/Entity/DTO)` — 收藏（`FavoriteStoreEntity`：userId/locationId/uniqId/storeType/name/type/distance/deleted）

### L2 上游 HTTP 层（实例化适配）
- `XiaochanHttp` 补 `searchMeituanList/getMeituanList`（美团赏金），**改成本地实例方法 + 走 executeWithProxy（带代理）**
- `WmmtHttp`（全新增）— 歪麦独立抓取，用 waimaiToken

### L3 上层业务服务
- `XiaoChanService(+Impl)` 补 `getXcMeituanshangjinPageVO` + fillFavoriteIds；**static→实例调用适配**
- `WmmtService(+Impl)` — 歪麦（用 UserEntity.waimaiToken）
- `StoreSearchService(+Impl)` — 门店搜索（聚合 XiaoChan + Wmmt）
- `MessageService` — 消息合并/iframe（依赖 SystemConfig 可缺省）

### L4 控制器（新增）
- `WmmtController` / `StoreSearchController` / `FavoriteStoreController` / `StoreInventoryHistoryController`

---

## Schema 增量（只加新表/新列，不动本地表）
- 新表：`favorite_store`、`store_inventory_history`(+`sku_id`/`sku_name` 列)、`message_batch_record`
- 新列：`user.waimai_token`（VARCHAR(255)）
- **不执行**上游对 `store_pushed_history` 的破坏性重构（store_id→uniq_id、DROP distance/if_new/open_hours）。
- `monitor_config.store_type`：**本专项不强制加**（歪麦分流需它时再补，当前独立能力叠加用不到）。
- `user` 删 xc* 字段：一律不做（本地保留，上文已证为死字段，留着无害）。

## 移植适配规则
- 新增文件原样带 `upstream/main`，但**编译适配**：`XiaochanHttp.xxx` 静态调用 → 实例调用；`StoreInfo` 因保留 Integer promotionId 导致的 String 赋值冲突 → 按本地口径改。
- 需要 proxy 的上游抓取（美团赏金）套本地 `executeWithProxy`，不用直连。
- 凡是本地主权区文件（task 层、抢单、login_state、StorePushedHistory 现有实现）**不整文件替换**。

## 验证
1. `mvn -o -DskipTests compile` 全通过。
2. 新增 Controller 自动装配（组件扫描，无需改启动类）。
3. 本地回归：本地抢单/去重/3km/登录态/代理不受影响（这些文件未动）。
4. 新增能力接口冒烟（可选，起服务测）。

## 待办 / 风险
- 强耦合网内部依赖链长，按 L0→L4 顺序逐步编译推进，每层编译验证再进下一层。
- `TransactionTemplate`、`SystemConfig`、`MessageBatchRecordService` 三个缺失 bean 需确认 Spring Boot 默认是否满足，否则显式补。
- 歪麦 `WmmtHttp` 抓取走直连（上游如此），未走本地代理——需提示用户（上游歪麦接口可能也在被监控/代理范围）。
