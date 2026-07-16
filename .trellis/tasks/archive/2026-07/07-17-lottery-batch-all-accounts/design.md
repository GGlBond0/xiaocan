# Design — 霸王餐刷任务全选账号

## 范围与边界

仅改前端独立仓库 `xiaocan-front-main` 的 `SettingsView.vue`「霸王餐刷任务」卡片。后端 `LotteryController.runTask` / `LotteryService.runTask` 契约零改动。

## 现状数据流

```
selectedLoginStateId: number | null  (单值)
   └─> handleRun()
         └─> POST /api/lottery/run?authId=<单个>
               └─> LotteryTaskResultVO (单个) -> runResult (单对象)
                     └─> 模板渲染单块结果
```

## 目标数据流

```
selectedAuthIds: number[]  (多值)
   └─> handleRun()
         └─> for (const id of selectedAuthIds) {  // 串行 await，不并发
              progress = { idx, total, currentName }
              try { res = POST /api/lottery/run?authId=id } catch { res = {error} }
              batchResults.push({ authId, authName, result: res })
            }
         └─> batchResults (数组) -> 模板逐块渲染 + 顶部汇总
```

## 关键设计决策

### D1 选择控件：`el-select` + `multiple`

- `v-model` 由 `selectedLoginStateId: number|null` 改为 `selectedAuthIds: number[]`。
- 加 `collapse-tags` + `collapse-tags-tooltip`，避免选中多个时标签撑爆布局。
- `placeholder="选择登录态（可多选）"`。
- 旁加「全选」「清空」两个小按钮，操作 `selectedAuthIds`。全选 = 当前列表所有 id；清空 = `[]`。
- 选中计数 `selectedCount` / `totalCount` 用 `computed`，显示「已选 N/M 个」。

### D2 串行执行（核心）

```ts
async function handleRun() {
  if (selectedAuthIds.value.length === 0) { ElMessage.warning('请先选择账号'); return }
  runLoading.value = true
  batchResults.value = []
  const ids = [...selectedAuthIds.value]
  for (let i = 0; i < ids.length; i++) {
    const id = ids[i]
    const st = loginStateList.value.find(s => s.id === id)
    const name = st?.name ?? `#${id}`
    runProgress.value = { idx: i + 1, total: ids.length, currentName: name }
    let result: any = null
    try {
      const res = await api.post('/api/lottery/run', undefined, { params: { authId: id } })
      if (res.data.success) result = res.data.data
      else result = { authName: name, error: res.data.msg || '失败' }
    } catch (e: any) {
      result = { authName: name, error: e?.message || '请求异常' }
    }
    batchResults.value.push({ authId: id, authName: name, result })
  }
  runProgress.value = null
  runLoading.value = false
}
```

- 用 `for` + `await`：天然串行，等当前请求 resolve/reject 后才进下一轮，**绝不 `Promise.all`**。
- 单个 `try/catch` 包住单个账号：失败只产出带 `error` 的 result，`continue` 进下一个。

### D3 结果聚合结构

```ts
interface BatchResult {
  authId: number
  authName: string
  result: LotteryTaskResultVO | { authName: string; error: string }
}
const batchResults = ref<BatchResult[]>([])
```

- 顶部汇总 `computed`：`successCount`（result 无 error 视为成功）、`failCount`、`totalChanceDelta`（各账号 `afterCount - beforeCount` 求和，缺失跳过）。
- 单账号展示：把现有「结果块」抽成一个内联片段，遍历 `batchResults` 渲染；复用 `chanceDelta` / `taskDisplay` / `friendlyError` 逻辑（改为接收单个 result 参数）。

### D4 兼容单账号

只选 1 个时 `batchResults.length === 1`，渲染与旧版单块一致；无需分支判断，模板遍历天然适配。

### D5 进度与禁用

- `runProgress = { idx, total, currentName } | null`，模板显示「正在刷第 idx/total 个 · currentName」。
- `runLoading` 期间 `el-select :disabled="runLoading"`，全选/清空按钮 `:disabled="runLoading"`。

## 改动文件

- `xiaocan-front-main/src/views/SettingsView.vue`（唯一文件）
  - script: `selectedLoginStateId` → `selectedAuthIds`；新增 `batchResults` / `runProgress` / `handleSelectAll` / `handleClearAll` / 汇总 computed；改造 `handleRun` 为串行循环；`chanceDelta` / `taskDisplay` / `friendlyError` 改为接收 result 入参。
  - template: `el-select` 多选 + 全选/清空按钮 + 选中计数；结果区改为遍历 `batchResults` + 顶部汇总 + 进度条。
  - style: 复用现有 `lottery-result*` 样式，新增少量汇总/进度样式。

## 风险与回滚

- 风险低：单文件、纯前端、后端不变。
- 回滚：`git checkout -- xiaocan-front-main/src/views/SettingsView.vue` + 重新 `npm run build` 部署。
- 注意：前端 dist 部署打包必须用绝对路径（见 [[frontend-deploy-dist-absolute-path]]）。

## 验证

- `npm run build` 本地通过。
- browser-relay 实测：选 2 个账号，观察网络请求串行、结果聚合、进度显示。
