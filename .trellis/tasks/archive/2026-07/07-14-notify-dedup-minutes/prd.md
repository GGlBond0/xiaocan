# 通知去重与过期清理按分钟数

## Goal

通知记录页不再被永久去重「冻住」，且过期旧记录不再一直显示。引入一个用户可填写的分钟数 N，统一管控：同店去重窗口、旧记录删除、记录页可见范围。

## Background

- 现状：`MinimumPayService.filterStoreInfos` 用 `findByNotifyIdAndStoreIdAll`（按 notifyId+storeId **全历史**去重），同店推过一次永不再通知。生产 `store_pushed_history` 仅 14 条且停在 2026-07-14 02:40，监控每 10 分钟正常跑、代理与上游均通，但命中门店全被永久屏蔽 → 通知记录页长期无新增。
- 代码里已有 `findByNotifyIdAndStoreIdToday`（按当天去重）但未被 MinimumPayService 使用。
- 通知记录页前端 `NotifyHistoryView` 调 `/api/notify-history/page` → `StorePushedHistoryService.pageByUser`，查 `store_pushed_history` 表。

## Requirements

- R1 引入「去重分钟数」N，配在监控配置的 `ext_config`（`MinimumPayExtNotifyConfig` 新增 `dedupMinutes`，整数，默认 60，≥1）。前端监控配置页提供输入框。
- R2 去重改为「过去 N 分钟内推过的店不重复推」：`MinimumPayService` 去重调用由永久去重改为按 N 分钟窗口查询（新增/复用 service 方法 `findByNotifyIdAndStoreIdWithinMinutes(notifyId, storeId, minutes)`）。
- R3 旧记录清理：超过 N 分钟的 `store_pushed_history` 记录从数据库删除。在每次监控任务执行时顺带按本配置 N 清理该 notifyConfig 的过期记录（无需独立定时器）。删除用 `create_time < now()-N minutes` 且 `notify_config_id = X` 条件。
- R4 记录页只显示最近 N 分钟内记录：`pageByUser` 增加「仅最近 N 分钟」过滤。N 来源——由前端传 `recentMinutes` 参数；前端从所选监控配置（或唯一配置）的 `ext_config.dedupMinutes` 读取并传入。无配置时不过滤（兼容）。
- R5 范围限定：本次只改 `MINIMUM_PAY`（MinimumPayService）+ 通知记录页 + 监控配置页对应字段。`STORE_ACTIVITY`/`STORE_KEYWORD` 及其 `StoreTask` 暂不动。
- R6 不改库 schema：`dedupMinutes` 存在 ext_config JSON；`store_pushed_history` 已有 `create_time` 与 `notify_config_id` 索引，无需新增列/索引。
- R7 默认行为兼容：未填 `dedupMinutes`（旧配置）时按默认 60 分钟处理；不破坏既有 `findByNotifyIdAndStoreIdAll`/`Today` 方法签名（保留，不删）。

## Acceptance Criteria

- [ ] `MinimumPayExtNotifyConfig` 新增 `dedupMinutes`（Integer，默认 60，校验 ≥1）。
- [ ] 监控配置页能填写并保存 `dedupMinutes`；保存后 `monitor_config.ext_config` 含该字段。
- [ ] `MinimumPayService` 去重改为按 N 分钟窗口，不再永久屏蔽同店。
- [ ] 每次监控执行后，该 notifyConfig 超过 N 分钟的 `store_pushed_history` 记录被删除。
- [ ] 通知记录页 `/api/notify-history/page` 仅返回最近 N 分钟内记录（N 由前端传 `recentMinutes`）。
- [ ] 后端编译通过；前端类型检查 + 构建通过。
- [ ] 部署到生产并实测：配置 N=5，触发一次监控后，>5 分钟的旧记录被清理；记录页只显示最近 5 分钟记录。
- [ ] 生产库无需 schema 变更。

## Notes

- 部署链路：本地构建 jar（见 memory [[local-build-toolchain]]、[[backend-proxy-and-build]]，弃用 Actions），前端 build+scp，见 [[deploy-topology]]。
- 清理逻辑放在 `BaseTask.runSingle` 成功路径之后或 `MinimumPayService` 内，需可拿到 N；注意只在命中或每次执行时清理（即便本次无命中也应清理，否则无命中时永不清理）——清理应独立于「是否命中」。
