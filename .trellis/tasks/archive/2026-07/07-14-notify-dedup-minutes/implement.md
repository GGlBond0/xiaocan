# Implement: 通知去重与过期清理按分钟数

执行顺序。每个 Gate 必须通过才进下一步。

## 步骤 1 后端 ext config 字段
- `MinimumPayExtNotifyConfig` 加 `private Integer dedupMinutes = 60;` + `@Min(value=1)` 注解（import `jakarta.validation.constraints.Min`）。
- Gate: 编译通过。

## 步骤 2 后端 service 去重/清理/查询方法
- `StorePushedHistoryService` 接口加：
  - `StorePushedHistoryEntity findByNotifyIdAndStoreIdWithinMinutes(Integer notifyId, Integer storeId, int minutes);`
  - `int deleteByNotifyIdOlderThanMinutes(Integer notifyId, int minutes);`
- `StorePushedHistoryServiceImpl` 实现：
  - WithinMinutes: `lambdaQuery().eq(notifyConfigId).eq(storeId).ge(createTime, LocalDateTime.now().minusMinutes(minutes)).last("limit 1").one()`
  - deleteByNotifyIdOlderThanMinutes: `lambdaUpdate().eq(StorePushedHistoryEntity::getNotifyConfigId, notifyId).lt(StorePushedHistoryEntity::getCreateTime, LocalDateTime.now().minusMinutes(minutes)).remove()` 返回 `?` —— MyBatis-Plus `remove` 返回 boolean，需用 mapper 或 `getBaseMapper().delete(queryWrapper)` 返回行数。用 `getBaseMapper().delete(LambdaQueryWrapper)` 返回删除行数。
- Gate: 编译通过。

## 步骤 3 后端记录页查询按分钟过滤
- `NotifyHistoryQueryDTO` 加 `private Integer recentMinutes;`
- `StorePushedHistoryServiceImpl.pageByUser`：当 `dto.getRecentMinutes()!=null && >0` 时追加 `.ge(StorePushedHistoryEntity::getCreateTime, LocalDateTime.now().minusMinutes(dto.getRecentMinutes()))`（在现有 lambdaQuery 链上 conditionally 加）。
- Gate: 编译通过。

## 步骤 4 后端去重改用 N 分钟
- `MinimumPayService.filterStoreInfos`：
  - 取 `int dedupMin = extNotifyConfig.getDedupMinutes() == null ? 60 : extNotifyConfig.getDedupMinutes();`（extNotifyConfig 已在该方法解析，第 42 行）。
  - 第 50 行 `.filter(s -> storePushedHistoryService.findByNotifyIdAndStoreIdWithinMinutes(notifyConfig.getId(), s.getStoreId(), dedupMin) == null)`。

## 步骤 5 后端清理钩子
- `BaseTask` 加 `protected void cleanupExpired(MonitorConfigEntity notifyConfig){}`（空默认）。
- `BaseTask.runSingle`：在 try 块内、`fetchStoreInfos` 之前调用 `cleanupExpired(notifyConfig)`（先清理过期再判断），确保无命中也清理。位置：第 93 行 `List<StoreInfo> storeInfos = fetchStoreInfos(...)` 之前插入 `cleanupExpired(notifyConfig);`。
- `MinimumPayService` 重写 `cleanupExpired`：解析 ext config 取 N（null→60），调 `storePushedHistoryService.deleteByNotifyIdOlderThanMinutes(notifyConfig.getId(), dedupMin)`，日志记录删除数。
- Gate: `mvn -o compile` 通过；`mvn -o clean package -DskipTests` 出 jar。

## 步骤 6 前端监控配置页加输入框
- `MonitorConfigView.vue`：
  - `form.minimumPayExtNotifyConfig` 加 `dedupMinutes: 60`。
  - `resetForm` 同步重置 `dedupMinutes: 60`。
  - `showEditDialog` MINIMUM_PAY 回填：`form.minimumPayExtNotifyConfig.dedupMinutes = config.minimumPayExtNotifyConfig.dedupMinutes ?? 60`。
  - MINIMUM_PAY 模板块（`<template v-if="...MINIMUM_PAY...">` 内，3km 内 之前或之后）加：
    ```
    <el-form-item label="去重/过期分钟">
      <el-input-number v-model="form.minimumPayExtNotifyConfig.dedupMinutes" :min="1" :step="10" controls-position="right" style="width: 100%" />
      <p class="cron-tip">同店 N 分钟内不重复通知；超过 N 分钟的旧记录自动删除，记录页只显示最近 N 分钟</p>
    </el-form-item>
    ```
  - submitForm：MINIMUM_PAY 已整体提交 `form.minimumPayExtNotifyConfig`，含 dedupMinutes 自动带上，无需改。
- Gate: `npm run type-check` 通过。

## 步骤 7 前端记录页按 N 过滤
- `NotifyHistoryView.vue`：
  - `searchForm` 加 `recentMinutes: null as number | null`。
  - `handleSearch` 内、请求前：若 `searchForm.notifyConfigId` 非空，从 `notifyConfigList` 找该配置 `minimumPayExtNotifyConfig.dedupMinutes`，赋给 `searchForm.recentMinutes`；否则 null。
  - `handleConfigChange` 触发 `handleSearch` 会重新计算，OK。
  - 注意：`handleSearch` 在 onMounted 时 notifyConfigId 为 null（全部配置），此时不传 recentMinutes（显示所有）。选了具体配置才按该配置 N 过滤。
- Gate: `npm run type-check` + `npm run build` 通过。

## 步骤 8 部署
- 后端 jar scp → /opt/xiaocan/xiaocan.jar，备份旧 jar `xiaocan.jar.bak.<ts>`，chown 644，`systemctl restart xiaocan`，确认 active + HikariPool Start completed。
- 前端 build → tar → scp → 备份 dist → 解压 → chown www-data。
- 无 DB schema 变更，无需 ALTER。

## 步骤 9 生产实测
- 监控配置页：编辑 id=3 配置，设 dedupMinutes=5，保存，确认 ext_config 含 `"dedupMinutes":5`。
- 等 cron 一次执行（每 10 分钟）或观察日志 `cleanupExpired` 删除数。
- 查 `store_pushed_history`：该 config 超过 5 分钟的记录已删除。
- 打开通知记录页选中该配置：仅显示最近 5 分钟记录。
- 验证去重：同店 5 分钟内不重复通知，5 分钟后可再通知（若仍满足条件）。
- Gate: 实测符合预期。

## 步骤 10 提交推送 + spec 更新
- 后端 commit + push origin main。
- 前端 commit + push origin main。
- 更新 .trellis spec（如需）。
- 归档任务。

## 回滚点
- 后端旧 jar：`/opt/xiaocan/xiaocan.jar.bak.<ts>`
- 前端旧 dist：`/var/www/xiaocan/dist.bak.<ts>`
- 代码：git revert。
- 旧 14 条 store_pushed_history 若被清理不可恢复（用户已知，符合需求）。
