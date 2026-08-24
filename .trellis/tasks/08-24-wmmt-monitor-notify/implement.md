# 歪麦监控实现清单

## 前置（DB DDL）
- [ ] 生产库执行（含 store_pushed_history 加列，缺则歪麦插历史报错）：
  ```sql
  ALTER TABLE monitor_config ADD COLUMN source INT NOT NULL DEFAULT 1, ADD COLUMN wmmt_login_state_id INT DEFAULT NULL, ADD COLUMN wmmt_login_state_ids VARCHAR(500) DEFAULT NULL;
  ALTER TABLE store_pushed_history ADD COLUMN uniq_id VARCHAR(64) NULL DEFAULT NULL AFTER store_id;
  ```
  - 验证：`SHOW COLUMNS FROM monitor_config;` 含 3 新列；`SHOW COLUMNS FROM store_pushed_history;` 含 uniq_id。
  - **原因**：歪麦门店无 Integer storeId（store_id 列 NOT NULL），历史去重键需 String uniqId。

## 后端
- [ ] `MonitorConfigEntity` 加 `source`/`wmmtLoginStateId`/`wmmtLoginStateIds` 字段。
- [ ] `monitorConfigDTO` 加同名字段。
- [ ] `MonitoryConfigServiceImpl.addUpdateConfig`：`source==2` 分支——校验歪麦账号（`WmmtLoginStateService` 校验属主，仿 `loginStateService.getEntity`）、规整 `wmmtLoginStateIds` + 回填单值、**autoGrab 强制 false**；`source==1` 保持现有逻辑。
- [ ] 新增 `WmmtTask extends BaseTask`：`fetchStoreInfos`（按 `wmmtLoginStateIds` 优先级取账号 token，调 `WmmtHttp.getShopList` 遍历页）、`filterStoreInfos`（三种类型对齐）、`cleanupExpired`。
  - [ ] `WmmtServiceImpl` 加重载 `fetchWmStoreInfos(String token, StoreTypeEnum storeType, LocationEntity location, String keyword)`（现实现硬取 `user.getWaimaiToken()`，需按自定义 token 遍历 100 页）。保持旧方法转发到新重载（取 user token），兼兼容现调用方。
- [ ] `MonitorCronScheduler.execute`：按 `source` 分派 `WmmtTask`/`StoreTask`。
- [ ] 新增 `WmmtTask` 静态兜底 `@Scheduled`（同 `StoreTask.start` cron），只跑 `source==2` 无 cron 配置。
- [ ] `listByUserId` 的 VO 已含新列（BeanUtils 自动），无需改。

### 验证（后端）
- [ ] `mvn -o clean package -DskipTests` 编译通过。
- [ ] 本地起服务（或 curl 后段）：POST `/api/notify/config` 传 `{"source":2,"type":"MINIMUM_PAY","locationId":X,"minimumPayExtNotifyConfig":{...},"wmmtLoginStateIds":"1,2"}` → 保存成功且 `autoGrab=false`。
- [ ] 传 `source:2` 无账号 → 400 "必须选择歪麦账号"。
- [ ] 传 `source:2` 且非法账号 id → 400。
- [ ] 传 `source:1` 现有配置 → 行为不变（autoGrab 仍可用）。
- [ ] GET `/api/notify/config/list` → 返回含 `source`/`wmmtLoginStateIds`。

## 前端（xiaocan-front-main）
- [ ] `MonitorConfigView.vue`：加「数据源」下拉；歪麦时展示歪麦账号多选 + 优先级；`autoGrab` 歪麦禁用。
- [ ] `loadWmmtLoginStates()` 调 `/api/wmmt-login-state`。
- [ ] `showEditDialog`/`submitForm` 回填与提交 `source`/`wmmtLoginStateIds`。
- [ ] 列表/详情加「数据源」tag。

### 验证（前端）
- [ ] `npm run build`（含 vue-tsc 类型检查）通过。
- [ ] 页面新增配置选歪麦 → 保存成功、返回列表显示「歪麦」。
- [ ] 编辑既有小蚕配置 → source 保留「小蚕」，autoGrab 可用。

## 生产部署
- [ ] DDL 已执行。
- [ ] 后端 JAR 分片 scp → 重启 → HikariPool 正常。
- [ ] 前端 dist 分片部署。
- [ ] 冒烟：创建一条歪麦监控（cron 一次性「测试」配置，如 `0 20 * * * ?` 等 1 分钟）→ 观察 `task_exec_history` / 推送。

## 回滚点
- 后端：换回旧 JAR（新列不影响旧代码，source 默认 1）。
- 前端：换回旧 dist。
- DB：`ALTER TABLE monitor_config DROP COLUMN source, DROP COLUMN wmmt_login_state_id, DROP COLUMN wmmt_login_state_ids;`（若需彻底回滚表结构）。