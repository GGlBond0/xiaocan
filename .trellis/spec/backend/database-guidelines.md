# Database Guidelines

> 数据库约定（MyBatis-Plus + MySQL）。

---

## Overview

ORM 用 MyBatis-Plus 3.5.9（`mybatis-plus-spring-boot3-starter`）+ MySQL（`mysql-connector-j`）。**无 XML mapper**——查询全部用 Lambda 链式。数据库 schema 见仓库根 `ddl.sql`。

---

## Query Patterns

- mapper 继承 `BaseMapper<XxxEntity>` + `@Mapper`，**不写自定义 SQL 方法**。
- 查询用 `lambdaQuery()` / `LambdaQueryWrapper`，例：
  ```java
  // service/impl 内
  lambdaQuery().eq(XxxEntity::getField, value).page(new Page<>(pageNum, pageSize));
  ```
- 批量用 `saveBatch` / `removeByIds`（IService 提供）。
- 分页用 `Page<T>`，`MybatisPlusConfig` 已注册 `PaginationInnerInterceptor(DbType.MYSQL)`；controller 可直接返回 `Page<VO>`。
- 实体字段可直接用枚举类型（如 `MonitorTypeEnums type`），依赖 MyBatis-Plus 枚举处理。

---

## Migrations

- **无迁移工具**（无 Flyway/Liquibase）。schema 由根目录 `ddl.sql` 手工维护。
- 改表：直接编辑 `ddl.sql` + 生产手动执行。本任务不强求引入迁移工具。

---

## Naming Conventions

- 表名/字段名全下划线：`monitor_config`、`store_pushed_history`、`user_id`、`create_time`。
- 实体依赖驼峰自动转下划线（`map-underscore-to-camel-case: true`），**未见 `@TableField` 自定义映射**。
- 主键：`@TableId(type = IdType.AUTO)`（数据库自增），字段名 `id`。`id` 类型因表而异：`location`/`store_pushed_history` 用 `Long`，`monitor_config`/`task_exec_history`/`user` 用 `Integer`。
- 逻辑删除：`@TableLogic private Boolean deleted`，DDL 对应 `deleted tinyint(1) DEFAULT 0`。**注意**：`store_pushed_history`、`task_exec_history` 表无 `deleted` 字段（无逻辑删除）。
- 全局配置见 `application.yaml` `mybatis-plus.global-config.db-config`：`id-type: auto`、`logic-delete-field: deleted`、`logic-delete-value: 1`、`logic-not-delete-value: 0`。

---

## Common Mistakes

- **不要给 DTO/VO 加 `@TableName`/`@TableId`**——既有 `LocationDTO`/`LocationVO` 误带持久化注解，是技术债，新代码勿重复。
- mapper 名与实体名可不一致（`NotifyConfigMapper`→`MonitorConfigEntity`），新增时尽量保持一致以避免混淆。
- 事务：service 写操作加 `@Transactional(rollbackFor = Exception.class)`（见 `LocationServiceImpl`）。
- JDBC URL 必须含 `allowPublicKeyRetrieval=true`，否则 MySQL 重启后 caching_sha2 缓存清空会导致连接失败（事故教训，见 `application.yaml`）。
- 跨表关联：本项目**不用**联表查询，用多次查询 + 内存组装。
