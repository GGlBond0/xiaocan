# 执行计划：自动抢单增加饿了么/京东平台并支持按平台选择

任务目录：`.trellis/tasks/07-17-multi-platform-grab`
规划文档：`prd.md`（需求/验收）、`design.md`（技术设计/改动点 D1-D6,F1-F2/风险）。

## 执行顺序（按依赖）

### Step 0 — D2 字段来源处理（用户决策：不补抓，样本值占位先上）

- 已确认来源：`new_level/is_plus` ← `GetClientUserInfo.vip_level_info`；活动字段（store_id/promotion_type/city_code/promotion_silk_amount/store_category_sub_type/store_platform_order_money/bwc_platform）← 活动详情。
- 待定字段（样本值占位，代码注释标注依据+抓包样本 flow）：`ingot`（样本 600）、`store_type`（样本 2）、`store_category_type`（样本 2）、`is_super_brand`（样本 false）、`bwc_type`（样本 0）、`promotion_event_type`（样本 null）。
- 实现时把这些待定字段集中在 `OrderExchangeReq` 构造处，用常量 + TODO 注释标注"依据 HAR flow f30e26fd，需按账号/活动验证"，便于后续补抓替换。
- 风险：占位值可能对其它账号/活动失效，运行时小流量验证后替换。

验证：`design.md` D2 表格已标注每字段来源或"样本占位"；实现代码 TODO 注释齐全。

### Step 1 — D3 活动详情解析扩展

- 扩展 `StoreInfo`（或新增 `PromotionDetail`）保留 `tpPromotion`/`storeType`/`storeCategorySubType`/`storeCategoryType`/`isSuperBrand`/`bwcType`/`promotionType`/`cityCode`。
- `XiaochanHttp.getStorePromotionDetail`/`parseListBody` 解析这些字段（按 D2 结论）。

验证：本地 `mvn -q compile`（绝对路径，见 [[local-build-toolchain]]）通过。

### Step 2 — D1 新增 OrderExchange 调用

- `XiaochanHttp` 新增 `orderExchange(GrabAuth, OrderExchangeReq)`，复用 `postWithResAuth`，`servername=SilkwormCommunity`、`methodname=SilkwormMobileCommunityService.OrderExchange`。
- 新增 `OrderExchangeReq` 模型（字段按 D2 结论）。

验证：本地编译通过；用 HAR replay（`generate_scraper_code` 或手写）对抓到的 promotion_id 回放，对比响应 `status.code==0`。

### Step 3 — D4 doGrab 按平台分叉

- `GrabServiceImpl.doGrab` 重试循环内按 `config.getStorePlatform()` 分派美团/饿了么京东两条路径。
- OrderExchange 成功判据 `status.code==0`；`promotion_order_id` 允许 null。
- `saveHistory`/`push` 复用。

验证：本地编译通过。

### Step 4 — M1 + D5 + D6 监控层门禁与配置

- DDL：`monitor_config` 加 `grab_platforms VARCHAR(64) DEFAULT '1'`（写 `ddl.sql` + 服务器执行 SQL，见 [[deploy-topology]]）。
- `MonitorConfigEntity`/`monitorConfigDTO`/VO 加 `grabPlatforms`。
- `AutoGrabServiceImpl`：门禁改 `!platformSet.contains(store.getType())`；占位任务 `storePlatform=store.getType()`；新增 `parsePlatforms` 辅助（null/空→`{1}`）。

验证：服务器 SSH 读日志/调接口（[[runtime-logs-on-server]]），存量配置仍仅美团抢单（AC4）。

### Step 5 — F1/F2 前端平台多选

- 前端仓库 `xiaocan-front-main`（[[frontend-repo-path]]）：`MonitorConfigView.vue` 加平台多选（autoGrab 开时显示，默认勾美团），保存 `grabPlatforms`，加载反显。
- `monitorConfigDTO` 对应前端类型加字段。
- 打包用绝对路径（[[frontend-deploy-dist-absolute-path]]）。

验证：browser-relay（[[browser-relay-setup]]）驱动 Chrome 进 `/monitor` 页勾选保存刷新保持（AC6）。

### Step 6 — 实测验收

- AC1/AC2：真实饿了么/京东活动命中监控（勾选对应平台）→ 自动建任务、`grab_config.store_platform` 正确、上游请求体 `bwc_platform` 正确。
- AC3：监控未勾某平台 → 该平台命中只通知不抢。
- AC5：手动 execute 饿了么/京东 config 能发 OrderExchange。
- 全程 SSH 读 `/opt/xiaocan/logs` 观察无异常、无风控拦截。

## 验证命令汇总

- 本地编译后端：用 [[local-build-toolchain]] 记录的 JDK17/Maven 绝对路径 `mvn -q compile`。
- 服务器日志：`ssh root@121.91.175.192` 读 `/opt/xiaocan/logs`。
- 浏览器验证：`curl http://127.0.0.1:18795/api/debug` 验活后驱动 Chrome。
- HAR replay：mitmproxy `generate_scraper_code` / `replay_flow`。

## Review Gates

- Gate-0（Step 0 后）：D2 结论是否足以实现？不足则降级或搁置，不进入 Step 1。
- Gate-3（Step 3 后）：doGrab 分叉逻辑 review，确保美团分支零回归。
- Gate-6（Step 6 后）：全部 AC 通过方可进入 finish。

## 回滚点

- Step 1-3（代码）：git revert `XiaochanHttp`/`GrabServiceImpl`/`StoreInfo` 改动恢复纯美团。
- Step 4（DB）：`monitor_config.grab_platforms` 列保留不删，读侧兜底 `{1}`，无需回滚 DDL。
- Step 5（前端）：前端 dist 回滚到上一版。
