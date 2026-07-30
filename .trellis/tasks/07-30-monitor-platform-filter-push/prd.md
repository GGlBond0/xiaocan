# 监控生效平台过滤推送与抢单

## Goal

把监控配置上的平台勾选从「仅控制自动抢单」升级为「本条监控的**生效平台**」：未勾选平台的活动整条当未命中——不推送、不写推送历史、不建自动抢单、不计入命中数。推送与抢单共用同一套平台集合。

## Background

- `monitor_config.grab_platforms` 已存在：逗号分隔 int（1美团/2饿了么/3京东），顺序=抢单平台优先级。
- **推送**（`BaseTask.sendMessage`）：规则命中后**不按平台过滤**，三平台都可推。
- **自动抢单**（`AutoGrabServiceImpl.parsePlatformOrder` L501–513）：按 `grabPlatforms` 过滤；**空/null → 仅美团 `[1]`**。
- 前端 `MonitorConfigView.vue`：平台控件仅 `v-if="form.autoGrab"`；`autoGrab=false` 提交 `grabPlatforms: null`；默认/空回显 `[1]`；文案「抢单平台」「不勾选只通知不抢」。
- 三种监控共用 `BaseTask.runSingle`（滤规则 → 营业时间 → 计数 → 推送 → 历史 → autoGrab）。

## Decisions

| ID | 决策 |
|----|------|
| D1 | 一套共用：`grabPlatforms` = 生效平台（推+抢），不新增列 |
| D2 | 空/null = **三平台全开** `{1,2,3}`（推+抢）；有意改掉抢单原「空=仅美团」 |
| D3 | 未勾选平台 = 整条未命中：不推、不写 history、不 autoGrab、不计入 `notifyStoreCount` |
| D4 | 指定门店 / 关键字 / 最小实付 全部覆盖；`BaseTask` 统一过滤 |
| D5 | 至少选 1 个平台（前后端校验） |
| D6 | 文案「生效平台」；字段名可仍 `grabPlatforms` |
| D7 | 控件始终展示；autoGrab 开时副文案「顺序=抢单平台优先级」 |
| D8 | 保存始终提交 `grabPlatforms`（不因 autoGrab=false 置 null） |
| D9 | **新建**表单默认仅美团 `[1]`（显式落库 `"1"`，与空值全开区分） |

## Requirements

- R1 推送前按生效平台过滤 `StoreInfo`（`type` ∈ 解析集合）。
- R2 过滤后为空：与现「无满足条件门店」一致（不推/不历史/不抢）。
- R3 解析：有值按逗号有序去重；**空/null → `[1,2,3]`**。推送侧与 `AutoGrabServiceImpl.parsePlatformOrder` **同一语义**。
- R4 三种监控类型行为一致。
- R5 前端：始终展示「生效平台」、可调序、至少 1 个、始终提交；列表/详情空显示「全平台」；编辑 null/空回显为三平台全选（与 D2 一致，**禁止**再回显成仅美团）。
- R6 autoGrab 开：同一集合过滤推送，并作为抢单平台优先级（顺序保留）。
- R7 后端保存：`grabPlatforms` 若提交则解析后至少 1 个合法平台码，否则 4xx；允许 null（语义=全开）。
- R8 更新 tip：去掉「不勾选只通知不抢」；改为未勾选平台不推不抢。

## Constraints

- C1 不新增 DB 列（复用 `grab_platforms`）。
- C2 存量显式串（`"1"` / `"1,2"`）不变；仅 null/空的抢单语义变宽为全开。
- C3 后端 `xiaocan-main` + 前端 `xiaocan-front-main`。
- C4 推送仍走 `PushService.pushToLocation`。

## Acceptance Criteria

- [ ] AC1 生效平台仅美团：饿了么/京东活动不推、不写 history、不 autoGrab。
- [ ] AC2 同配置美团活动：正常推（含平台名）；autoGrab 开则按现逻辑抢。
- [ ] AC3 null/空存量：三平台可推；autoGrab 开时三平台可抢。
- [ ] AC4 三种监控类型均满足 AC1–AC3。
- [ ] AC5 关闭 autoGrab 仍可改生效平台并持久化；刷新保持。
- [ ] AC6 0 个平台：前端拦 + 后端拦（非 null 的空串/非法）。
- [ ] AC7 `notifyStoreCount` 与 summary「数量」只含生效平台命中。
- [ ] AC8 列表/详情：空平台展示「全平台」，不再「仅美团」。
- [ ] AC9 新建默认勾选仅美团，保存后 DB 为 `"1"`（或等价），行为仅美团而非全开。

## Out of scope

- 推/抢两套独立平台集合
- WxPusher / 地址 spt 路由改造
- 上游多平台抢单协议（已完成）
- `GrabConfigView` 活动选择临时筛选

## Notes

- 复杂任务：`design.md` + `implement.md` 齐备并经用户审阅后再 `task.py start`。
- 相关：`07-17-multi-platform-grab`、`07-17-monitor-grab-multi-account-priority`。
