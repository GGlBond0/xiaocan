# Implement: ProxyHolder 多池轮换

## 有序执行清单

后端（xiaocan-main，本地构建）：

1. **DDL**：`ddl.sql` 加 `proxy_config` 表的 `pool_list VARCHAR(200)` 列（含 ALTER 语句，供生产补列）。生产执行 ALTER。
2. **Entity** `ProxyConfigEntity`：加 `poolList` 字段。
3. **DTO** `ProxyConfigDTO`：加 `poolList` 字段（可空）。
4. **VO** `ProxyConfigVO`：加 `poolList` 字段。
5. **Service** `ProxyConfigServiceImpl`：`updateConfig` 落库 poolList；`ensureRow` 惰性初始化兼容（旧行无列→null）。
6. **ProxyHolder**：
   - 新增 `parsePools(cfg)` / `nextPool(AtomicInteger)` / `resolveActUrl(url, poolId)`。
   - `getExtractProxy`：poolList 非空时 `apiUrl = resolveActUrl(apiUrl, nextPool(pools))` 后再 `fetchProxyList`。
   - 新增池轮换日志（`切换隧道池 act=getturn{N} (n/m)`）。
   - `apiUrlOf` / `fetchProxyList` 其余不动。
7. **本地构建**：`mvn -o clean package -DskipTests` 出 jar。
8. **生产部署**：scp jar → 备份旧 jar → systemctl restart xiaocan → 确认 HikariPool Started。
9. **生产 ALTER**：`ALTER TABLE proxy_config ADD COLUMN pool_list ...`。
10. **配置**：`PUT /api/proxy/config` 设 `poolList="51,82,57,61,62,76"`，观察日志跨池切换。

前端（xiaocan-front-main，独立仓库）：
11. Headerbar 设置页 SettingsView.vue：加"隧道池列表"输入 + 保存 poolList + 回显。构建 dist 部署。

## 验证命令

```bash
# 后端单测(本机)
mvn -o -Dtest=ProxyHolderRoundRobinTest test

# 本地取代理验证(设 poolList 后,日志应跨池)
# 触发任务或直接观察 /opt/xiaocan/logs/info.log

# 生产验证(高峰段 14-20)
grep '状态码错误: -1' /opt/xiaocan/logs/error.log | wc -l   # 与 8-23 同段对比下降
grep '切换隧道池' /opt/xiaocan/logs/info.log | tail -20     # 确认轮换发生
```

> 注意：实施会话已设 14:20 一次性 CronCreate 检查高峰段（session-only，会话关闭即失效）。
> 若失效，请在 14-20 点任意时刻手动执行上面两行对比，或在 8-24 下午用
> `grep -c '状态码错误: -1' /opt/xiaocan/logs/error.log` 与 8-23 作对比。

## 风险/回滚点

- 改动集中 ProxyHolder + 配置承载；**回滚 = 把 poolList 置空**（PUT）即回单池；或回退 jar。
- 池组号替换用 `replaceAll("act=getturn\\d+", ...)`，若 api_url 模板非该格式会不匹配 → 需保留原 url 兜底（不 replace 则照旧用原池，不崩溃）。
- 构建走本地（[[prod-build-avoid-server]] 禁服务器 mvn）。
- **生产高峰段验证是核心 gate**：部署后今天 14-20 点观察，若 -1 未降，评估降频/换源。

## 待办（task.py start 前 gate）

- [ ] prd.md / design.md / implement.md 三者就绪被用户审查。
- [ ] implement.jsonl / check.jsonl 各含 ≥1 真实条目（子代理上下文）。
- [ ] task.py start 后进入 Phase 2 实现。