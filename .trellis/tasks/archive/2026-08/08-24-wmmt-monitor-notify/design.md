# 歪麦监控设计

## 架构总览

歪麦监控是「复用小蚕监控管线 + 新增歪麦数据源」，不改既有小蚕行为。三条核心原则：

1. **数据源路由**：`monitor_config.source`（1 小蚕 / 2 歪麦）决定 `fetchStoreInfos` 抓哪家。既有配置 `source=1` 零改动。
2. **执行体隔离**：新增 `WmmtTask extends BaseTask` 专跑歪麦，`MonitorCronScheduler`/`StoreTask.start()` 按 `source` 分派。
3. **组件全复用**：调度/去重/推送/历史/启停/删除/平台过滤 100% 复用，不重写。

## 数据模型变更（DDL）

`monitor_config` 新增 3 列：
```sql
ALTER TABLE monitor_config
  ADD COLUMN `source` INT NOT NULL DEFAULT 1 COMMENT '数据源 1小蚕 2歪麦',
  ADD COLUMN `wmmt_login_state_id` INT DEFAULT NULL COMMENT '歪麦账号(单值回填首个)',
  ADD COLUMN `wmmt_login_state_ids` VARCHAR(255) DEFAULT NULL COMMENT '歪麦多账号优先级串,顺序即优先级';
```
- `source=1` 兼容既有配置（默认列默认 1）。
- `wmmt_login_state_id` 回填 `wmmt_login_state_ids` 首个，语义仿小蚕 `grabLoginStateId(s)`。

## 后端组件改动

### 1. `MonitorConfigEntity`
新增字段 `source`(Integer)、`wmmtLoginStateId`(Integer)、`wmmtLoginStateIds`(String)。MyBatis-Plus 自动映射新列。

### 2. `monitorConfigDTO`
新增 `source`、`wmmtLoginStateId`、`wmmtLoginStateIds` 字段。

### 3. `MonitoryConfigServiceImpl.addUpdateConfig` — 歪麦分支
- 若 `source==2`（歪麦）：
  - 校验 `wmmtLoginStateIds`（或单值回退）非空且每个 `wmmt_login_state.id` 存在且属当前用户（仿 `loginStateService.getEntity(id)`，用 `WmmtLoginStateService` 校验）。**autoGrab 强制 false**（本轮歪麦不抢单）。
  - 规整化写回 `wmmtLoginStateIds` + 回填 `wmmtLoginStateId`。
- 若 `source==1`（小蚕）：现有逻辑不变，`wmmtLoginState*` 字段置 null。

### 4. `WmmtTask extends BaseTask`（新增执行体）
- `@Component`；注入 `WmmtService`/`WmmtLoginStateService`。
- 覆写 `fetchStoreInfos(notifyConfig, execHistory, location)`：
  - 若 `source==2`：读 `wmmtLoginStateIds` 优先级列表，取首个可用账号 token（`WmmtLoginStateService.getById(id).getToken()`），调 `WmmtHttp.getShopList(token, city, dto)` 遍历页码 → `List<StoreInfo>`。城市取 `location` 或固定 "长沙市"（歪麦接口固定长沙市）。
  - 若 `source==1`：小蚕逻辑不变（本类只在 source==2 走）。
- 覆写 `filterStoreInfos`：按三种监控类型过滤，语义与小蚕 `StoreTask` 对齐（STORE_ACTIVITY 指定门店 id+库存+返现/价格阈值；STORE_KEYWORD 关键字+距离+去重；最低实付）。**歪麦分支不依赖 `MerchantBlacklistHolder`**（歪麦门店名黑名单可暂不接，或后续接）。
- 覆写 `cleanupExpired`：仿 STORE_KEYWORD 清理。
- 继承 `sendMessage`/`savePushedHistory`/`withinOpenHours`/`filterByEffectivePlatforms`（平台过滤对歪麦复用，因歪麦 `type` 已是 1/2/3）。

### 5. 调度分派（两处）
- `MonitorCronScheduler.execute`：`latest.getSource()==2` → `wmmtTask.execute(latest, true)`，否则 `storeTask.execute`。
- `StoreTask.start()`（静态兜底）:改造为同时查 `source==2` 配置→ `wmmtTask.execute`。或更简：`StoreTask.start()` 只跑小蚕，歪麦由新增 `WmmtTask` 兜底方法（`@Scheduled` 同 cron）跑 `source==2`。**推荐后者**（职责分离、少动 StoreTask）。

### 6. 推送模板
- `WmmtTask` 用歪麦专用 summary/body（标注"歪麦"来源），可继承 `BaseTask.sendMessage` 或覆写 `buildMessage` 加来源。至少 body 里平台显示 `StorePlatformEnum` 名称即可（歪麦门店 type 已是平台码）。

## 前端改动（`MonitorConfigView.vue`）

1. 「数据源」下拉（小蚕/歪麦），`form.source`。新建选歪麦时：展示歪麦账号多选（`wmmtLoginStateIds`）+ 优先级排序；`autoGrab` 强制隐藏/禁用（歪麦不抢）。
2. 加载歪麦登录态列表：`/api/wmmt-login-state`（已有接口）→ `wmmtLoginStateList`。
3. `showEditDialog`：回填 `source`/`wmmtLoginStateIds`（字符串 → number[]）。
4. `submitForm`：`source` 传参；`wmmtLoginStateIds` 仅歪麦时提交；`autoGrab` 歪麦时强制 false。
5. 列表/详情展示「数据源」tag + 歪麦账号名。

## 数据流

```
用户配置 (source=2 + wmmtLoginStateIds=[id1,id2]) 
  → MonitoryConfigService.addUpdateConfig 校验/规整 
  → monitor_config 落库
定时触发 (cron 或 StoreTask.start)
  → MonitorCronScheduler.execute → source==2 → WmmtTask.execute
  → fetchStoreInfos: 取 wmmtLoginStateIds[0] 账号 → WmmtHttp.getShopList(token,city) 遍历页 → List<StoreInfo>
  → filterStoreInfos(类型/距离/去重) → withinOpenHours → filterByEffectivePlatforms(平台1/2/3)
  → sendMessage(PushService) + savePushedHistory + (autoGrab 强制 false)
```

## 兼容性 / 迁移 / 回滚

- **兼容**：`source` 列默认 1，既有 SQL 不显式传 source 的配置保持小蚕行为；`wmmtLoginState*` 为新列不影响旧配置。
- **部署顺序**：
  1. 生产库执行 `ALTER TABLE monitor_config ADD ...`（DDL）。
  2. 后端本地构建 `mvn -o clean package -DskipTests` → scp JAR → 重启。
  3. 前端 `npm run build` → 部署 dist。
- **回滚**：后端回滚只需恢复旧 JAR；新列存在不影响旧代码（默认 1）。前端回滚用旧 dist。

## 关键风险

- **歪麦防重放/WAF**：歪麦有防重放（`sign=aesEncrypt(timestamp+nonce)`），`WmmtHttp` 已封装；监控高频扫描可能触发限流，需观察（参考 [[proxy-xiequ-pool-all-503]] 教训）。
- `MerchantBlacklistHolder` 对小蚕门店按名过滤；歪麦门店名结构不同，本轮不接黑名单，后续评估。
- 多账号取 token 需处理账号失效（token 空/401）：取首个可用，全部失效记日志。