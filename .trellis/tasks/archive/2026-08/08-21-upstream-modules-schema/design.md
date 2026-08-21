# Design: Task10 schema 增量

## Overview

仓库 DDL 与实体映射对齐，生产只跑加法 SQL。不改监控落库逻辑。

## Key Decisions

| 决策 | 选择 | 理由 |
|------|------|------|
| 生产执行面 | 仅增量段，禁止整文件 `ddl.sql` | 文件前半 `DROP TABLE IF EXISTS` 会毁库 |
| `user.waimai_token` 位置 | 不加 `AFTER spt` | 生产 `spt` 后已有 `xc_*` |
| `ADD COLUMN` | 先查 `information_schema` 再 `ALTER` | MySQL 8 无 `IF NOT EXISTS` |
| `store_pushed_history` | 只加可空 `batch_id` | `getPushedHistoryByRecordId` 会生成 `WHERE batch_id IN (...)` |
| VO 四字段 | `@TableField(exist = false)` | 注释已写不写库；`BaseTask` 也不写 `locationId`；加列无写入方 |
| 不改 `BaseTask` | 明确延期 | 主权区；写 `batchId` 才能让 iframe 有数据 |
| 部署 JAR | 本任务不做 | 旧 JAR 忽略新表/新列，可先迁 schema |

## Schema contract

### favorite_store

与 `FavoriteStoreEntity`：`userId Integer`、`locationId Long`、`uniqId`、`storeType`（枚举名）、`icon`、`name`、`type`、`distance` 字符串、`createTime`、`@TableLogic deleted`。

索引：`(user_id, location_id, store_type)`、`uniq_id`。

### store_inventory_history

与实体：`uniqueId`→`unique_id`，`storeType` 枚举，`skuId`/`skuName` 默认 `''`。无逻辑删除。

### message_batch_record

`user_id INT`、`batch_ids TEXT`、`create_time`。无逻辑删除。

### user.waimai_token

`VARCHAR(255) NULL`。旧 JAR 不读此列。

### store_pushed_history.batch_id

`VARCHAR(64) NULL` + `KEY idx_batch_id (batch_id)`。`FieldStrategy=NOT_NULL` 下 `batchId==null` 的 `saveBatch` 不写该列。

## Mapping

```java
private String batchId; // 真实列 batch_id

@TableField(exist = false)
private Long locationId;
@TableField(exist = false)
private String uniqId;
@TableField(exist = false)
private StoreTypeEnum storeTypeEnum;
@TableField(exist = false)
private Long favoriteId;
```

`BeanUtils.copyProperties(storeInfo, entity)` 仍会填这些 Java 字段，但不进 INSERT。

## Production apply

1. SSH `root@121.91.175.192`，连本机 MySQL `xiaocan`。
2. `SHOW TABLES` / `SHOW COLUMNS FROM user` / `SHOW COLUMNS FROM store_pushed_history`。
3. `CREATE TABLE IF NOT EXISTS` 三表。
4. 缺列才 `ALTER TABLE`。
5. 验证表/列存在；抽查 `store_pushed_history` 行数不降。
6. 不 `systemctl restart xiaocan`。

## Risks

- 误跑全量 `ddl.sql` → 灾难。缓解：增量段注释写死「禁止整文件」。
- 未标 `exist=false` 就部署本分支 → 通知历史 Unknown column。本任务修映射。
- `batchId` 无写入 → iframe 空列表。文档化，不修 BaseTask。
- 歪麦 `WmmtHttp` 直连。已有 spec 风险，本任务不改。

## Rollback

- 表：`DROP TABLE` 三张新表（空表可丢）。
- 列：`ALTER TABLE user DROP COLUMN waimai_token`；`ALTER TABLE store_pushed_history DROP COLUMN batch_id`（仅确认无依赖后）。
- 代码：`git revert` schema commit。
