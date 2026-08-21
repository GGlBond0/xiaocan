# Finish: Task10 schema增量+全量编译+收尾

## Completion Criteria

- [x] ddl.sql 末尾追加三表 + waimai_token + batch_id（注释禁止整文件跑生产）
- [x] StorePushedHistoryEntity VO 四字段 `@TableField(exist = false)`
- [x] 本地 `mvn -o compile` 与 `test-compile` BUILD SUCCESS
- [x] 主权区文件（Grab/BaseTask/StoreTask/MinimumPay/login_state/ProxyHolder/OrderExchangeReq）无本任务 diff
- [x] 生产探活通过（`xiaocan` 用户可连本机 MySQL，列已存在）
- [x] spec 已更新（.trellis/spec/backend/upstream-modules-merge.md）

## Rollback

- 表：`DROP TABLE` 三张新表（空表可丢）
- 列：`ALTER TABLE user DROP COLUMN waimai_token`；`ALTER TABLE store_pushed_history DROP COLUMN batch_id`
- 代码：`git revert` schema commit

## Update spec

```bash
cat .trellis/spec/backend/upstream-modules-merge.md
```

## Commit

```bash
git add ddl.sql
git commit -m "feat(merge): L5 - schema增量(收藏/库存历史/消息批次表+waimai_token列)"
git push origin feat/upstream-modules
```

## Wrap-up reminder

专项 L0–L5 全部完成。Task10 待手工执行（生产迁移已探活，无需本任务执行）。生产 JAR 仍旧，旧版本可继续跑（新列可空）。

**End of finish.md**