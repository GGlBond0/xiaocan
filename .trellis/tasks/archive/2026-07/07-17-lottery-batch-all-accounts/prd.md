# 霸王餐刷任务支持全选账号批量刷

## Goal

「霸王餐刷任务」模块（设置页 `SettingsView.vue`）目前只能选单个登录态逐个刷任务。本次优化：支持多选 / 全选登录态，一次性为多个账号批量刷霸王餐浏览/领取类任务，并聚合展示每个账号的结果。后端接口契约不变，纯前端串行循环实现。

## Background

- 刷任务模块位置：前端独立仓库 `xiaocan-front-main` 的 `SettingsView.vue`「霸王餐刷任务」卡片。
- 当前实现：`selectedLoginStateId` 单值 → `POST /api/lottery/run?authId=<单个>` → 展示单个 `LotteryTaskResultVO`。
- 后端 `/api/lottery/run` 单次执行含多次上游 HTTP 调用（lotteryInfo + 5 个 addLotteryTimes + 前后快照），单账号耗时可观；生产小机仅 1.7G 内存，**禁止并发**多账号，避免拖垮同机服务（见 [[prod-build-avoid-server]]、[[server-services]]）。
- 登录态来自统一池 `/api/login-state/list`（见 [[login-state-unified-pool]]）。

## Requirements

### 功能需求

- R1 账号选择由单选 `el-select` 改为**多选**（`multiple`），支持全选/反选/清空。
  - 提供「全选」一键勾选所有登录态。
  - 选中的账号数量实时显示（如「已选 3/8 个」）。
- R2 点「刷任务」时，对选中账号**串行**逐个调用 `POST /api/lottery/run?authId=<id>`，禁止并发。
- R3 执行过程展示进度：当前第 N/M 个、正在刷的账号名；执行中按钮置 loading 且禁用选择变更。
- R4 结果区由「单账号单块」改为「多账号列表」，每个账号一个区块展示其 `LotteryTaskResultVO`（机会数、完成明细、失败信息沿用现有展示）。
  - 顺序与选择顺序一致。
  - 单个账号失败不中断后续账号。
  - 顶部汇总：成功 X 个 / 失败 Y 个 / 机会净增 Z。
- R5 兼容空选：未选任何账号时「刷任务」按钮禁用并提示「请先选择账号」。

### 约束

- C1 **不改后端**：沿用 `POST /api/lottery/run?authId=<单个>`，不新增批量接口。
- C2 串行执行：前一个账号返回（或失败）后再发起下一个，不得 `Promise.all` 并发。
- C3 单次刷任务整体较长，执行期间给用户可感知的进度反馈，避免误以为卡死。
- C4 不破坏现有单账号场景：只选一个时行为与改动前等价（结果区仅一块）。
- C5 失败提示文案沿用现有 `friendlyError` 映射（代理 403、登录态不完整、无权操作等）。

### 不在范围内

- 后端批量接口、并发执行。
- 刷任务结果持久化/历史记录。
- 定时/计划刷任务（cron）。

## Acceptance Criteria

- [ ] AC1 账号选择为多选框，含「全选」操作；选中数实时显示。
- [ ] AC2 选中多个账号点「刷任务」，串行逐个调用 `/api/lottery/run`，网络面板可见请求按序逐个发出、无并发。
- [ ] AC3 执行中显示「第 N/M 个 · <账号名>」进度，按钮 loading 且账号选择禁用。
- [ ] AC4 结果区按账号逐个展示，单账号失败不影响后续；顶部有成功/失败/净增机会汇总。
- [ ] AC5 只选 1 个账号时，结果展示与改动前一致。
- [ ] AC6 未选账号时按钮禁用并有提示。
- [ ] AC7 本地 `npm run build` 通过，无 TS/构建错误。
- [ ] AC8 浏览器自动化（browser-relay）实测多账号场景下串行执行、结果正确聚合。
