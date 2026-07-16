# Implement — 霸王餐刷任务全选账号

执行顺序按以下步骤，每步可独立验证。仅改 `xiaocan-front-main/src/views/SettingsView.vue`。

## 步骤

### 1. script：状态改造
- `selectedLoginStateId` → `selectedAuthIds: number[]`（初始 `[]`）。
- 新增 `batchResults = ref<BatchResult[]>([])`、`runProgress = ref<{idx,total,currentName}|null>(null)`。
- 新增 `interface BatchResult { authId:number; authName:string; result:any }`。
- `loadLoginStates`：去掉单选默认选中逻辑（多选默认空，由用户主动选）。或保留默认选第一个——按需求默认空更合理，留空。
- 新增 computed `selectedCount` / `totalCount` / `successCount` / `failCount` / `totalChanceDelta`。

### 2. script：改造 handleRun 为串行循环
- 空选校验 → `ElMessage.warning('请先选择账号')` 并 return。
- `runLoading=true`，清空 `batchResults`。
- `for` + `await` 逐个 POST `/api/lottery/run?authId=id`，每轮更新 `runProgress`，`try/catch` 失败产出带 error 的 result。
- 结束清 `runProgress`、`runLoading=false`。
- **禁止** `Promise.all` / `forEach`+async（并发）。

### 3. script：工具函数接收参数化
- `chanceDelta` → `chanceDeltaOf(r: any)`：接收单个 result 返回 delta 或 '—'。
- `friendlyError` → `friendlyErrorOf(r: any)`：接收单个 result 的 error 映射文案。
- `taskDisplay` 已接收 item 入参，保持不变。

### 4. script：全选/清空
- `handleSelectAll()`：`selectedAuthIds.value = loginStateList.value.map(s => s.id)`（空列表则 no-op）。
- `handleClearAll()`：`selectedAuthIds.value = []`。

### 5. template：选择控件
- `el-select` 加 `multiple` `collapse-tags` `collapse-tags-tooltip`，绑定 `selectedAuthIds`，`:disabled="runLoading"`，placeholder「选择登录态（可多选）」。
- 旁加「全选」「清空」按钮（`:disabled="runLoading"`）。
- 显示「已选 {{ selectedCount }}/{{ totalCount }} 个」。

### 6. template：进度 + 结果区
- 进度：`v-if="runProgress"` 显示「正在刷第 {{ runProgress.idx }}/{{ runProgress.total }} 个 · {{ runProgress.currentName }}」。
- 顶部汇总（`v-if="batchResults.length"`）：成功 X / 失败 Y / 净增机会 Z。
- 结果列表：`v-for="(br, i) in batchResults"`，每块复用现有 `lottery-result` 结构，绑定 `br.result`，标题用 `br.authName`。

### 7. 构建
- `cd xiaocan-front-main && npm run build`（按 [[frontend-deploy-dist-absolute-path]] 用绝对路径配置；本机 PATH 不带 node/构建工具时用绝对路径或 npx）。
- 确认无 TS/构建报错。

### 8. 验证（browser-relay）
- `curl http://127.0.0.1:18795/api/debug` 验活（见 [[browser-relay-setup]]）。
- 打开设置页霸王餐刷任务卡片：勾全选 → 点刷任务 → 观察网络请求串行逐个发出、进度更新、结果聚合、汇总正确。
- 单账号回归：只选 1 个，结果单块正常。

## 验证命令

```bash
cd C:/D/AI/Projects/xiaocan/xiaocan-front-main && npm run build
curl -s http://127.0.0.1:18795/api/debug
```

## 回滚点

每步改完即可 `git diff` 自查；构建失败或验证不过，`git checkout -- src/views/SettingsView.vue` 还原。后端无改动，无需回滚后端。

## Review Gates

- 步骤 2 完成后自查：确认无 `Promise.all`、无并发。
- 步骤 7 构建必须绿。
- 步骤 8 browser-relay 实测必须看到串行请求 + 正确聚合（AC2/AC3/AC4）。
