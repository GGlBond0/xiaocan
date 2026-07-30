# Design: 监控生效平台过滤推送与抢单

## Overview

复用 `monitor_config.grab_platforms`，语义从「仅 autoGrab 平台门禁」升级为「监控生效平台」。在 `BaseTask.runSingle` 公共路径上按平台裁剪命中列表，使推送 / 历史 / 计数 / autoGrab 自然一致；同步修正 `AutoGrabServiceImpl` 空值默认与前端提交/展示。

## Architecture

```
fetchStoreInfos
  → filterStoreInfos（规则）
  → withinOpenHours
  → filterByEffectivePlatforms(config)   ← 新增共用步骤
  → notifyStoreCount / sendMessage / savePushedHistory / triggerAutoGrab
```

- **单一事实来源**：平台是否生效只在这一处裁剪；`AutoGrabServiceImpl` 内仍按优先级排序/再滤，作为防御性第二道（空值语义必须与第一道一致）。
- **不改表结构**：无 migration。

## Contracts

### 解析语义 `parseEffectivePlatforms(String grabPlatforms)`

| 输入 | 输出（有序） |
|------|----------------|
| null / blank | `[1, 2, 3]` |
| `"1,2"` | `[1, 2]`（去空白、跳过非法 token；保留首次出现顺序） |
| 解析后空（如 `","`） | 视为非法配置：保存期应拒绝；运行期兜底 **全开** 或 **跳过本轮并打 warn**（实现选：运行期全开 + warn，与 D2 一致，避免静默停推） |

合法码：1 / 2 / 3。其它数字丢弃。

### 放置位置

- 推荐：小工具方法，例如 `io.github.xiaocan.util.MonitorPlatforms` 或 `StorePlatformEnum` 旁静态方法，供 `BaseTask` 与 `AutoGrabServiceImpl` 共用，避免两处 copy 漂移。
- `AutoGrabServiceImpl.parsePlatformOrder` 改为调用同一实现（删除「空→仅美团」）。

### BaseTask 插入点

在 `withinOpenHours` 过滤之后、`availableStores.isEmpty()` 判断之前：

```java
availableStores = filterByEffectivePlatforms(notifyConfig, availableStores);
```

`filterByEffectivePlatforms`：`type == null` 的 StoreInfo **丢弃**（无法判平台，不推不抢），并 log debug/warn 计数。

### 保存校验（后端）

- `monitorConfigDTO.grabPlatforms`：
  - null：允许（D2 全开）。
  - non-null：trim 后 split，至少 1 个 ∈ {1,2,3}，否则业务异常/400。
- 不强制 autoGrab 才接收该字段。

### 前端

| 点 | 现状 | 目标 |
|----|------|------|
| 表单项 | `v-if="form.autoGrab"` 标签「抢单平台」 | **始终显示**，标签「生效平台」 |
| 提交 | autoGrab false → null | **始终** `join(',')`；空数组前端先校验不提交 |
| 新建默认 | `[1]` | 保持 `[1]`（D9） |
| 编辑空/null | 回显 `[1]` | 回显 `[1,2,3]`（与 D2 一致） |
| `platformLabels` / 列表 | 空→「仅美团」 | 空→「全平台」 |
| tip | 「不勾选只通知不抢。默认仅美团」 | 「仅对勾选平台推送；开启自动抢单时顺序为抢单优先级。新建默认仅美团；未配置(空)为全平台。」 |
| 卡片/详情 | 平台信息绑在 autoGrab 文案里 | **始终展示**生效平台；autoGrab 文案可另附账号/模式 |

账号/模式控件仍可 `v-if="form.autoGrab"`。

## Data flow

1. 用户保存生效平台 `"1"` → DB `grab_platforms=1`。
2. Cron 命中美团+饿了么各一 → BaseTask 只留美团 → 推 1 条、history 1 条、count=1；autoGrab 只见美团组合。
3. 存量 `grab_platforms=NULL` + autoGrab → 解析全开 → 推三平台；抢单也对三平台建任务（相对旧版变宽，已接受）。

## Compatibility / migration

- **无 DB 迁移**。
- 行为变更仅 null/空 + 推送侧新增过滤。
- 不批量把 null 写成 `"1,2,3"`（保持 null=全开约定即可）。
- 前端曾把 autoGrab 关闭写成 null：升级后用户再保存会带上表单值；编辑打开 null 时看到三平台全选，若直接保存会从 null 变成 `"1,2,3"`（显式全开，行为等价）。

## Trade-offs

| 选择 | 利 | 弊 |
|------|----|----|
| 空=全开（D2） | 存量推送不缩水 | 抢单空配置变宽，可能多抢饿了么/京东 |
| 新建默认仅美团（D9） | 新配置更克制 | 与空值语义不一致，需文案说清 |
| 运行期只在 BaseTask 滤一次 | 推/历史/count 自然一致 | AutoGrab 仍保留第二道以防直接调用 |

## Rollback

- 代码回滚即可；无 schema 依赖。
- 若上线后全开抢单过猛：可热修将空值改回仅美团，或让用户把存量显式改为 `"1"`。

## Spec follow-up（完成期）

- 更新 `.trellis/spec` 中监控/抢单相关约定：`grab_platforms` 空=全开；监控推送按生效平台过滤。
- 前端无独立 spec 包则在 task 归档 note 记 UI 约定即可。
