# 歪麦监控通知

## Goal

在现有小蚕监控管线上增加**歪麦（waimaimingtang）数据源**监控：用户复用现有 `monitor_config` 配置页，通过「数据源」下拉选择小蚕或歪麦，歪麦源按三种监控类型（STORE_ACTIVITY/MINIMUM_PAY/STORE_KEYWORD）扫描对应门店活动，命中后走现有推送/历史/去重链路通知用户。歪麦接口/加密与小蚕脱钩，复用 `WmmtHttp`（[[wmmt-crypto-cracked]]）。

## Requirements

### 数据源路由
- `monitor_config` 新增 `source` 列：`1`=小蚕（默认，既有配置零改动）、`2`=歪麦。`fetchStoreInfos` 按 `source` 路由到 `XiaoChanHttp.searchList`（小蚕）或 `WmmtServiceImpl.fetchWmStoreInfos`（歪麦）。
- 前端「数据源」下拉（小蚕/歪麦），编辑已有配置时保留。

### 歪麦账号绑定（多账号优先级）
- `monitor_config` 新增 `wmmtLoginStateIds`（逗号分隔有序列表，顺序=优先级）与 `wmmtLoginStateId`（单值，保存时回填列表首个，兼容单账号）。指向 `wmmt_login_state.id`（[[wmmt-foundation-ready]]）。
- 歪麦抓取按优先级取账号 token；`getShopList` 门店浏览免费但按绑定账号抓取。

### 组件复用
- 三种监控类型/定时（cron+时间窗+星期）/去重/推送/运行历史/启停/删除，**全部复用**现有 `BaseTask`/`MonitorCronScheduler`/`StoreTask.start()`/`MonitorController`/`NotifyHistoryController`/`PushService`。
- 新增 `WmmtTask extends BaseTask`（歪麦执行体），覆写 `fetchStoreInfos`/`filterStoreInfos`/`cleanupExpired`。

### 范围边界
- **本轮不做 autoGrab**：歪麦命中只通知 + 写历史，`autoGrab` 对歪麦源禁用（抢单是另一条主线，见 [[wmmt-foundation-ready]]）。
- 歪麦还复用现有平台过滤（`filterByEffectivePlatforms`）：歪麦门店 `StoreInfo.type` 已是小蚕平台码 1/2/3（`WmmtHttp.getType`: meituan→1, ele→2, 其余→3），故平台下拉/过滤不改。

## Acceptance Criteria

- [ ] `monitor_config` 有 `source` 列；既有小蚕配置 `source=1` 运行行为不变。
- [ ] 后端创建/编辑监控：小蚕配置行为不变；歪麦配置（`source=2`+`wmmtLoginStateIds`）能保存并校验（要求至少一个歪麦账号）。
- [ ] 歪麦监控配置在 `MonitorCronScheduler`/`StoreTask.start()` 正确路由到 `WmmtTask.execute`，而不是 `StoreTask` 的小蚕逻辑。
- [ ] `WmmtTask.fetchStoreInfos` 按 `wmmtLoginStateIds` 优先级取账号，调 `WmmtHttp.getShopList` 抓歪麦门店。
- [ ] `filterStoreInfos` 三种类型命中逻辑与小蚕对齐（STORE_ACTIVITY 指定门店/STORE_KEYWORD 关键字+距离/最低实付）。
- [ ] 命中后 `PushService.pushToLocation` 推送 + `savePushedHistory` 写历史，歪麦推送 body 含门店/平台/满返/距离/库存。
- [ ] 歪麦源 `autoGrab` 强制 false 不建抢单任务。
- [ ] 前端 `MonitorConfigView.vue` 加「数据源」下拉 + 歪麦账号多选；选歪麦时展示歪麦账号下拉。
- [ ] 本地 `mvn -o clean package -DskipTests` 编译通过；`/api/notify/config/list` 返回含 `source` 字段。生产库需执行新 DDL（`monitor_config` 加列）。

## Out of Scope
- 歪麦抢单（overbearfood 提交）、歪麦 autoGrab 联动——后续主线。
- 歪麦独立监控页——本轮在前端统一页内加数据源下拉。