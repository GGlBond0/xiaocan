# 精简上游模块：删除收藏/库存/消息/小蚕美团赏金，保留歪麦数据层

## Goal

从 `feat/upstream-modules` 分支合入的上游（lyrric/xiaochan）强耦合网模块中，删掉本项目不需要的四块功能，**保留歪麦（Wmmt）门店数据抓取层**作为后续开发歪麦监控/抢单的地基，精简项目、降低维护面。

背景：用户核心业务是「小蚕 + 歪麦两个平台的监控通知和抢单」。歪麦的监控/抢单尚未开发，当前分支只合入了歪麦的门店抓取（`WmmtHttp` 数据层）与各辅助模块。收藏/库存历史/消息批量/小蚕美团赏金与本项目核心无关，需删除。歪麦抢单未来将基于 `WmmtHttp` 独立开发（歪麦自有 token/加密/接口，与小蚕完全脱钩）。

## Requirements

### R1 删除收藏模块（Favorite）
删 `FavoriteStoreController/Service/Serviceimpl/Mapper/Entity` 及 `FavoriteStoreQueryDTO/SaveFavoriteDTO/RemoveFavoriteDTO`。同步拆掉其它类中对收藏的所有调用。

### R2 删除库存历史模块（StoreInventoryHistory）
删 `StoreInventoryHistoryController/Service(Impl)/Mapper/Entity/VO`。同步拆掉调用点（含 `WmmtServiceImpl`、`XiaoChanServiceImpl` 里的 `insertBatch` 调用）。

### R3 删除消息批量模块（MessageBatch）
删 `MessageService`、`MessageBatchRecordService(Impl)/Mapper/Entity`。确认 `MessageService` 是纯上游增量、未被本地监控推送链路引用（若被引用需一并改）。

### R4 删除小蚕美团赏金（XC_MTSJ）
删 `XcMeituanshangjinDTO/XcMeituanshangjinPageVO`；删 `XiaochanHttp` 里 `getMeituanList/searchMeituanList/parseMeituanListBody` 方法及相关私有成员。小蚕本体（`XiaochanHttp` 满减/抢单/活动详情）保留。

### R5 删除聚合搜索模块（StoreSearch）与歪麦"展示"入口
删 `StoreSearchController/Service(Impl)/DTO`。**保留** `StoreSearchService.search` 里歪麦那一路所需能力由 `WmmtService` 承接，或直接移除聚合搜索入口（歪麦展示不作为核心）。`WmmtController` 按保留/删除评估（若只服务展示搜索可删，若未来监控/抢单走它则留权）。

### R6 保留歪麦数据层（地基，不动）
`WmmtHttp`、`WmmtService(Impl)`、`WmmtShopListDTO`、`WmPageVO`、`UserEntity.waimaiToken`、`StoreTypeEnum` 的歪麦枚举（WM_MANJIAN/WM_MTSJ）、`ImageProxyController`、`StoreConstant`、`SimpleStoreInfo`、`BookVO`、`IgnoreStoreVO` 中歪麦需要者。这是后续歪麦监控/抢单的 API 底座，**不删**。

### R7 精简 StoreInfo / StorePushedHistoryEntity / UserEntity 中仅服务于被删模块的字段
- `StoreInfo`：`favoriteId/exists/storeTypeEnum/distanceStr/rebateRatio/rebateMax/rebateConditionStr/uniqId` 中仅被收藏/库存/搜索/歪麦展示使用的字段，删除并拆依赖；被**保留的抢单/监控/歪麦地基使用的字段必须保留**（以 agent 依赖分析为准）。
- `StorePushedHistoryEntity`：`favoriteId/locationId/uniqId/storeTypeEnum/batchId` 中无用的字段删除。
- `UserEntity.waimaiToken`：歪麦 token，歪麦地基需要，**保留**。

### R8 精简 DDL（ddl.sql）
删 `favorite_store`、`store_inventory_history`、`message_batch_record` 三张表及对应 mapper/entity 的 create。`user.waimai_token` 列保留（歪麦地基）。`store_pushed_history.batch_id` 列若仅消息批量用则删。**只更新 ddl.sql 源码文本**，不动生产库（生产库改表另作风险评估，本次不改线上）。

### R9 编译无残留
删除后本地 `mvn package` 必须通过，无对已删类的编译引用残留。

## Constraints

- **保留核心**：小蚕（`XiaochanHttp` 满减/抢单/活动详情/地址）、监控（Monitor）、抢单（Grab）、登录态（login_state）、代理（ProxyHolder）、推送（SptService）—— 这些是项目主线，任何删除不得波及。
- 歪麦抢单未来**独立基于 WmmtHttp 开发**，与小蚕抢单（`orderExchange`/`grabPromotionQuota`）无共享，删除小蚕美团赏金不影响歪麦。
- 只更新仓库源码/DDL 文档，**不动生产数据库与生产服务器**；生产是否升级另行决策。
- DDL 中 `CREATE TABLE IF NOT EXISTS` 段落（2026-08-21 强的耦合网）整段按需删除，但**不得**删前置 `DROP TABLE` 结尾的 ALTER 基建或影响登录态/抢单/代理的段。
- 提交放在当前分支 `feat/upstream-modules`，先不合并 main；随后按用户意图评估是否合并/删分支。

## Acceptance Criteria

- [ ] 收藏/库存历史/消息批量/小蚕美团赏金/聚合搜索相关的 Controller、Service、Mapper、Entity、DTO、VO 文件已删除。
- [ ] `.trellis/spec/backend/upstream-modules-merge.md` 等 spec 已同步删除/改写对应章节。
- [ ] `WmmtHttp`、`WmmtService(Impl)`、`WmmtShopListDTO`、`WmPageVO`、`UserEntity.waimaiToken`、歪麦 `StoreTypeEnum`、`ImageProxyController` 等歪麦地基完整保留。
- [ ] `StoreInfo` 等公共实体的被删字段已清理，残留引用全部拆除。
- [ ] `ddl.sql` 已删除三张表与无用列；保留 `waimai_token`；生产库未改动。
- [ ] 本地 `mvn package` 编译通过，grep 无对已删类型的引用。
- [ ] 提交记录在 `feat/upstream-modules` 分支，未合并 main；向用户说明了是否合并/删分支的下步选择。

## Notes

- 强耦合网为一次性上游合入，删除是逆操作；务必用依赖分析（agent 产出）逐条核对，避免删字段导致编译/运行炸。
- 保留判断以 agent 依赖清单 + 本项目 `CLAUDE.md` 运行期守则（去服务器、本地构建）为准。