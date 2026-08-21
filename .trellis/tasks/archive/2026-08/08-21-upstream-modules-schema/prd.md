# Task10 schema增量 + 全量编译 + 收尾

## Goal

完成强耦合网合入专项的最后一层：把 L0–L4 已落地的实体/服务所需 schema 写进 `ddl.sql`，本地全量编译确认不破主权区，并给出（必要时执行）生产可重复的增量迁移。本任务不部署 JAR、不改抢单/监控任务链。

来源：`docs/superpowers/plans/2026-08-21-upstream-modules-merge.md` Task 10；前会话 `ef266df6-73f8-4a35-b818-755b6f2fc852` 在 Task9 reviewer 后卡死，Task1–9 已合入 `feat/upstream-modules`（HEAD `3338918`）。

## Background（已核实）

- `ddl.sql` 末尾停在 2026-07-17 监控抢单字段，**没有** `favorite_store` / `store_inventory_history` / `message_batch_record` / `user.waimai_token`。
- 实体已存在：`FavoriteStoreEntity`、`StoreInventoryHistoryEntity`（含 `skuId`/`skuName`）、`MessageBatchRecordEntity`、`UserEntity.waimaiToken`。
- `StorePushedHistoryEntity` 已加 `batchId` + 四个 VO 字段（`locationId/uniqId/storeTypeEnum/favoriteId`），**无 `@TableField(exist=false)`**。`pageByUser` / `saveBatch` 会把它们编进 SQL。未加列就部署本分支会让通知历史/监控落库炸列。
- `MessageBatchRecordServiceImpl.getPushedHistoryByRecordId` 按 `batchId` 查推送历史；`BaseTask.savePushedHistory` **不写** `batchId`（主权区，本任务不改）。因此 `batch_id` 列只需可空，查询不崩即可。
- 四个 VO 字段注释写明「不写库」；`fillFavoriteIdsForPushedHistory` 依赖 VO 上的 `locationId/uniqId`，而 `BaseTask` 也不会写 `locationId`。本任务不改 `BaseTask`，这四个字段标 `exist=false`。
- 仓库根 `ddl.sql` 前半有 `DROP TABLE IF EXISTS`。**禁止**对生产整文件导入。

## Requirements

### R1 仓库 DDL 只追加

- 在 `ddl.sql` 末尾追加三段 `CREATE TABLE IF NOT EXISTS`：`favorite_store`、`store_inventory_history`（含 `sku_id`/`sku_name`）、`message_batch_record`。
- `user` 追加可空列 `waimai_token VARCHAR(255)`。**不要** `AFTER spt`（生产 `spt` 后已有 `xc_*`）。
- `store_pushed_history` **只加**可空 `batch_id VARCHAR(64)` + 索引，不 DROP/RENAME 任何现有列（禁止上游 `store_id→uniq_id`、DROP `distance/if_new/open_hours`）。
- 不改 `monitor_config`，不删 `user.xc*`。

### R2 实体映射与列对齐

- `locationId/uniqId/storeTypeEnum/favoriteId` 标 `@TableField(exist = false)`，避免 SELECT/INSERT 引用不存在的列。
- `batchId` 保持真实列映射，对应 `batch_id`。
- 不改 `GrabService*` / `BaseTask` / `StoreTask` / `MinimumPayService` / `login_state` / `ProxyHolder` / `OrderExchangeReq`。

### R3 编译与主权区

- 本地 `mvn -o -DskipTests compile` 与 `test-compile` 通过。
- `git diff` 相对本专项：主权区实现文件无行为改动（`XiaochanHttp` 仅允许已合入的美团赏金加法）。

### R4 生产增量（可重复、可空跑）

- 只执行本任务增量 SQL，先 `information_schema` 探活再加列。
- 当前生产仍跑 `main` JAR：新表/新列必须对旧 JAR 无害。
- 不在生产跑 `mvn`，不重启 `xiaocan`，不上传 JAR。

### R5 收尾

- 更新 `.trellis/spec/backend/upstream-modules-merge.md`：L0–L4 已合入、schema 已追加、生产迁移状态、剩余风险（`batchId` 无写入、消息 iframe 历史为空、歪麦直连未走代理）。
- 提交 `feat/upstream-modules`。

## Constraints

- 无 Flyway；schema 只靠 `ddl.sql` + 手工 SQL。
- MySQL 8 **不支持** `ADD COLUMN IF NOT EXISTS`。
- 生产机内存小，禁止 `mvn`。
- 大 JAR 不用 scp 整包（本任务不传 JAR）。

## Out of Scope

- 部署本分支 JAR / 合入 `main` / 推送 origin（除非收尾 commit 后用户另嘱 push）。
- 改 `BaseTask` 写入 `batchId`/`locationId`。
- 上游对 `store_pushed_history` 的破坏性重构。
- 前端页面。
- 给 `WmmtHttp` 套本地代理。

## Acceptance Criteria

- [ ] AC1 `ddl.sql` 含三张新表 + `user.waimai_token` + `store_pushed_history.batch_id`，且不含对现有表的 DROP/破坏性 ALTER。
- [ ] AC2 `StorePushedHistoryEntity` 四个 VO 字段 `exist=false`；`batchId` 映射 `batch_id`。
- [ ] AC3 本地 `mvn -o -DskipTests compile` 与 `test-compile` BUILD SUCCESS。
- [ ] AC4 主权区文件（Grab/BaseTask/StoreTask/MinimumPay/login_state/ProxyHolder/OrderExchangeReq）本任务无 diff。
- [ ] AC5 生产库（若本任务执行迁移）：三表存在、`waimai_token`/`batch_id` 存在；旧表行数未因本任务减少。
- [ ] AC6 spec 已更新；commit message 标明 schema 增量。

## Notes

- 消息 iframe 按 `batchId` 回查推送历史：本任务后查询不炸，但因监控落库不写 `batchId`，结果仍为空。属已知缺口，不在本任务修。
