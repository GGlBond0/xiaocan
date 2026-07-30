# Implement: 监控生效平台过滤推送与抢单

## Checklist

### 1. 共用解析

- [x] 新增共用方法 `MonitorPlatforms.parseEffective`：空→`[1,2,3]`；有序去重；只保留 1/2/3
- [x] `AutoGrabServiceImpl.parsePlatformOrder` 改为委托该方法（删空→仅美团）
- [x] 编译通过；解析逻辑与 design 一致（手测可在联调时再验）

### 2. BaseTask 过滤

- [x] `runSingle` 在营业时间过滤后调用 `filterByEffectivePlatforms`
- [x] `type==null` 丢弃并打日志
- [x] `notifyStoreCount` / 推送 / 历史 / autoGrab 均在过滤之后

### 3. 保存校验（后端）

- [x] non-null 须 ≥1 合法码；`autoGrab=false` **不再清空** `grabPlatforms`
- [x] null 放行
- [x] Entity/DTO/VO 注释已改

### 4. 前端 `xiaocan-front-main` MonitorConfigView.vue

- [x] 始终展示「生效平台」
- [x] 至少 1 个校验
- [x] 始终提交 `grabPlatforms`
- [x] 编辑空→全选；新建仍仅美团
- [x] 空展示「全平台」
- [x] 列表/详情始终展示；tip 已改

### 5. 文档

- [x] `push-routing.md` 已补生效平台约定

## Validation

1. **后端逻辑（可先单测/本地）**
   - 构造 `MonitorConfigEntity.grabPlatforms="1"`，`availableStores` 含 type 1/2 → 过滤后仅 1。
   - `grabPlatforms=null` → 不过滤掉 2/3。
2. **前端**
   - 新建：默认仅美团，关 autoGrab 仍能改平台并保存，网络面板 body 含 `grabPlatforms`。
   - 编辑 null 配置：打开为三平台全选；展示「全平台」。
   - 清空平台点保存：被校验拦住。
3. **联调（有环境时）**
   - 仅美团配置：制造/等待饿了么命中 → 无推送、无新 history。
   - 美团命中 → 有推送。
   - null 配置 + autoGrab：非美团也可建抢（若环境有号）。

## Risky files

| 文件 | 风险 |
|------|------|
| `BaseTask.java` | 过滤位置错会导致仍推或 count 不准 |
| `AutoGrabServiceImpl.java` | 空值变全开，存量 autoGrab 行为变宽 |
| `MonitorConfigView.vue` | 提交条件漏改会继续写 null，编辑回显错会误导用户把全开存成仅美团 |

## Rollback points

- 仅合并前端：展示/提交改善但后端仍全推 → 可接受中间态短时存在，建议前后端同批发布。
- 仅合并后端解析全开：抢单变宽；推送仍全推（与现推送一致）直到 BaseTask 过滤合入。
- 推荐顺序：**共用解析 + BaseTask 过滤 + 保存校验** → 再发前端；或同批。

## Out of implement scope

- DB backfill
- GrabConfigView 临时筛选
