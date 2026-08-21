# Implement: Task10 schema 增量

## Checklist

1. [ ] `ddl.sql` 末尾追加三表 + `waimai_token` + `store_pushed_history.batch_id`（注释禁止整文件跑生产）
2. [ ] `StorePushedHistoryEntity`：VO 四字段 `@TableField(exist = false)`
3. [ ] 本地 `mvn -o -DskipTests compile` 与 `test-compile`
4. [ ] `git diff` 确认主权区无本任务改动
5. [ ] SSH 生产探活后执行增量 SQL
6. [ ] 更新 `.trellis/spec/backend/upstream-modules-merge.md`
7. [ ] commit（不 push、不部署 JAR）

## Files

- `ddl.sql`（仅追加）
- `src/main/java/io/github/xiaocan/model/entity/StorePushedHistoryEntity.java`
- `.trellis/spec/backend/upstream-modules-merge.md`

## Validation

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
"/c/D/tools/apache-maven-3.9.16/bin/mvn.cmd" -o -DskipTests compile
"/c/D/tools/apache-maven-3.9.16/bin/mvn.cmd" -o -DskipTests test-compile
```

生产：

```bash
ssh root@121.91.175.192 "mysql -N -e \"SELECT TABLE_NAME FROM information_schema.tables WHERE TABLE_SCHEMA='xiaocan' AND TABLE_NAME IN ('favorite_store','store_inventory_history','message_batch_record');\""
```

## Rollback

见 `design.md`。生产不重启服务，回滚只需 DROP 新表/新列。
