# Upstream Modules Merge（上游模块合入 & 精简记录）

> 把上游 lyrric/xiaochan 的强耦合功能网（歪麦/收藏/库存历史/门店搜索/消息批量）在**本地二开底座上**以最小侵入方式移植合入，并于 2026-08-24 精简——删除其中 4 个非核心模块，**仅保留歪麦（Wmmt）数据层**作为后续歪麦监控/抢单的地基。
> 关联记忆：`upstream-fork-merge-analysis`。第一批低风险增量已完成（commit ef4bafb）；本专项五模块 L0→L4 全量合入 + 生产 Schema 增量 + 精简删除均已完成（2026-08-21 合入 / 2026-08-24 精简）。

---

## Overview（目标与边界）

本地 `main` 与上游 `upstream/main` git 历史不相交，无法 merge。上游五模块互相关联且锁死在上游重构底座（StoreInfo 的 `uniqId/storeTypeEnum/rebateRatio/...`、`UserEntity.waimaiToken`、静态化 XiaochanHttp、`promotionId Integer→String`、`store_pushed_history` 字段重构）。

本专项采用**最小侵入的"独立能力叠加"**策略：
- **本地主权区一律不动**：抢单链路(GrabService/AutoGrab/OrderExchange)、去重(storeId+promotionId)、3km 过滤、登录态池(`login_state`/LoginStateEntity)、代理(ProxyHolder/executeWithProxy)、监控任务链(BaseTask/StoreTask/MinimumPayService)、`store_pushed_history` 现有表结构。
- **新增五模块作为独立可用能力**：新增 Controller + Service，内部自取数据，**不接入本地监控任务链**，**不改本地现有数据流**。
- 只做**加法**：补实体字段、补缺失 bean/方法、新增新类，使五模块编译运行。

## 用户决策（原始合入 vs 精简）
**原始合入决策（2026-08-21）**：
1. 五件全做：歪麦 + 收藏 + 库存历史 + 门店搜索 + 消息批量。
2. StoreInfo **加法扩展**：新增 8 字段（`uniqId/storeTypeEnum/distanceStr/rebateRatio/rebateMax/rebateConditionStr/favoriteId/exists`），保留本地 storeId/promotionId(Integer)/distance/rebateCondition/openHours/ifNew。
3. 歪麦加 `waimaiToken`（UserEntity 新字段 + `waimai_token` 列）。
4. 独立能力，不改本地任务链 / 数据流 / 现有表结构。

**精简决策（2026-08-24, trim-upstream-modules）**：
- 删除：收藏 Favorite / 库存历史 StoreInventoryHistory / 消息批量 MessageBatch / 小蚕美团赏金 XC_MTSJ / 聚合搜索 StoreSearch（5 模块）+ 孤立 VO（SimpleStoreInfo/BookVO/IgnoreStoreVO）。
- **保留**：歪麦数据层 `WmmtHttp/WmmtService(Impl)/WmmtShopListDTO/WmPageVO` + `WmmtController`/`fetchWmStoreInfos` + `UserEntity.waimaiToken`（歪麦地基，后续监控/抢单开发用）。
- 拆依赖：`XiaoChanService(Impl)` 去掉收藏/库存/美团赏金引用；`WmmtServiceImpl` 摘收藏/库存注入；`XiaochanHttp` 删美团赏金 3 方法。
- **歪麦抢单走独立开发**（歪麦 token/加密/接口 vs 小蚕完全脱钩），不依赖小蚕抢单（`orderExchange`/`grabPromotionQuota`）。
- DDL：删 `favorite_store/store_inventory_history/message_batch_record` 三表 + `store_pushed_history.batch_id` 列；保留 `user.waimai_token`。生产库未动（本次仅代码/spec 精简，生产升级另决策）。

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
- 新列：`user.waimai_token`（VARCHAR(255)）、`store_pushed_history.batch_id`（VARCHAR(64) NULL + `KEY idx_batch_id`）
- **不执行**上游对 `store_pushed_history` 的破坏性重构（store_id→uniq_id、DROP distance/if_new/open_hours）。本任务仅加**可空** `batch_id` 列，避免 `getPushedHistoryByRecordId` 按 batchId 查询炸列；监控落库暂不写该列。
- `monitor_config.store_type`：**本专项不强制加**（歪麦分流需它时再补，当前独立能力叠加用不到）。
- `user` 删 xc* 字段：一律不做（本地保留，上文已证为死字段，留着无害）。

> 生产执行（2026-08-21 已落地 / 121.91.175.192）：只跑 `ddl.sql` 末尾增量段（三表 + 两列 + `idx_batch_id` 索引），已探活验证三表两列一索引进 information_schema 均存在。**禁止整文件导入 ddl.sql**（前半含 DROP TABLE IF EXISTS 会毁库）。`StorePushedHistoryEntity` VO 四字段（locationId/uniqId/storeTypeEnum/favoriteId）均标 `@TableField(exist = false)`，纯展示不写库。

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
- ✅ 强耦合网 L0→L4 五模块已全量合入并逐层编译通过（2026-08-21，本地 clean compile + test-compile 全绿）。
- ✅ `TransactionTemplate`、`SystemConfig`、`MessageBatchRecordService` 三个缺失 bean 已确认存在（Spring Boot 默认 + 新增 bean），编译自动装配通过。
- ✅ 生产 Schema 增量（三表 + `user.waimai_token` + `store_pushed_history.batch_id` + `idx_batch_id`）已落地并验证。
- ✅ **已部署**（2026-08-21）：新 JAR 本地 package → 分片 scp（禁 scp 整包，见 [[scp-large-jar-hangs-server]]）→ 生产替换 + 重启 → Tomcat 10234 + HikariPool 启动正常。接口回归：favorite save/stores、store-inventory-history、store/search 均 200 正常；`wmmt/shopList` 接口可达但**歪麦上游 SocketTimeout**（外部受限，非应用 bug，见下）。
- ✅ **前端已接入**（2026-08-21，xiaocan-front-main）：新增 收藏/门店搜索/歪麦/库存历史 4 页面 + NavBar 菜单 + 4 路由 + echarts 依赖（前端 commit 08cb260）。库存历史页移植上游；收藏/门店搜索/歪麦基于后端契约新设计，各页面地址下拉取经纬度/cityCode/locationId。`npm run build`(vue-tsc) 通过，browser-relay+视觉验证 4 页渲染正常。
- ⏳ **BaseTask 写 batchId** 未做（主权区延期项）；iframe / 消息批量需其写入才有完整数据。
- ⚠️ 歪麦 `WmmtHttp` 抓取走直连（上游如此）：生产实测 `/api/wmmt/shopList` 报"拉取密钥异常: SocketTimeoutException: Read timed out"，歪麦上游服务器不稳定/慢，非应用缺陷。
