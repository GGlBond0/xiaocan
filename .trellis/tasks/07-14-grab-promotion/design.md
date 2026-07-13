# 抢单与定时抢单 — 技术设计

## 1. 现状与边界

### 1.1 可复用的现有能力
- `XiaochanHttp.getAshe()`：X-Ashe 加密 = `MD5(MD5((server+"."+method).toLowerCase()) + X-Garen + X-Nami)`。抢单接口 server/method 不同，算法相同 → 直接复用。
- `XiaochanHttp.getNami()`：随机 Nami 生成。README 确认 Nami「固定或随机均可」→ 复用。
- `ProxyHolder` + `executeWithProxy()`：代理请求 + 403 换代理重试。抢单请求经此发出（N1）。
- `MonitorCronScheduler` 模式：基于 `TaskScheduler` + `CronTrigger` 的动态 cron 调度，`refreshAll/refresh/cancel`。抢单调度器完全仿此。
- `MessageHttp.sendMessage(spt, body, summary)`：WxPusher 推送。
- `UserEntity` / `LocationEntity`：用户与位置。

### 1.2 关键差异：登录态
现有活动列表接口匿名（`silk_id=0`，`X-Platform=mini`）。抢单接口需登录态：

| Header | 抓包样例 | 含义 / 处理 |
|---|---|---|
| `X-Platform` | `Android` | 固定 `Android`（现有是 `mini`） |
| `X-Sivir` | `eyJ...` JWT | 登录 JWT，`exp` 决定有效期，需持久化 |
| `X-Teemo` | `222559356` | **silk_id**（与 body silk_id 一致，非用户id） |
| `X-Vayne` | `5263106` | 用户 id（= JWT UserId） |
| `X-Session-Id` | UUID | 会话 id，需持久化 |
| `X-Nami` | `6762225593567970` | 随机即可，沿用 `getNami()`；抓包原值作可选 |
| `X-Garen` | 毫秒时间戳 | 每次请求现生成 |
| `User-Agent` | `XC;Android;3.18.3;` | 固定 |
| `X-Version` | `3.18.3.3` | 固定 |
| `servername` | `Silkworm` | — |
| `methodname` | `SilkwormService.GrabPromotionQuota` | — |

> A0 实测结论（`research/grab-replay.md`）：**随机 Nami 可行**——服务端不校验 Nami 内容，仅参与 X-Ashe 签名计算。直接沿用 `XiaochanHttp.getNami()` 随机生成，无需持久化原 Nami、无需逆向。`user.xc_nami` 列保留为可选（默认不填，随机生成）。

### 1.3 请求体（来自抓包）
```json
{"latitude":23.250941,"city_code":440111,"store_platform":1,
 "longitude":113.310739,"if_advance_order":false,
 "promotion_id":118060132,"silk_id":222559356}
```
### 1.4 响应
- 成功：`{"status":{"code":0},"promotion_order_id":713193211,"timeout":1783933200}`
- 活动未开始：`{"status":{"code":4,"msg":"活动未开始"}}`（抓包中文为 UTF-8 按 Latin-1 解码的乱码，解析时按 UTF-8）

## 2. 数据模型变更

### 2.1 `user` 表扩展（增量 ALTER，追加到 ddl.sql）
```sql
ALTER TABLE `user`
  ADD COLUMN `xc_user_id`    INT          NULL COMMENT '小蚕用户id(X-Teemo/Vayne)',
  ADD COLUMN `xc_sivir`      VARCHAR(800) NULL COMMENT '登录JWT(X-Sivir)',
  ADD COLUMN `xc_session_id` VARCHAR(64) NULL COMMENT '会话id(X-Session-Id)',
  ADD COLUMN `xc_nami`       VARCHAR(32) NULL COMMENT 'X-Nami(可选,随机失效时回退)',
  ADD COLUMN `xc_login_update_time` DATETIME NULL COMMENT '登录态录入时间';
```
- `UserEntity` 增加对应字段（MyBatis-Plus 自动映射）。
- 不加唯一约束：登录态随用户重录覆盖。

### 2.2 新表 `grab_config`
```sql
CREATE TABLE `grab_config` (
  `id`            INT NOT NULL AUTO_INCREMENT,
  `user_id`       INT NOT NULL,
  `location_id`   BIGINT NULL COMMENT '位置(提供lat/lng/city_code)',
  `promotion_id`  INT NOT NULL COMMENT '要抢的活动id(当天有效)',
  `silk_id`       INT NULL DEFAULT 0 COMMENT 'silk_id(抓包值)',
  `store_platform` INT NOT NULL DEFAULT 1,
  `if_advance_order` TINYINT NOT NULL DEFAULT 0,
  `cron`          VARCHAR(50) NULL COMMENT '定时抢单cron(6位含秒),空=仅手动/一次性',
  `execute_at`    DATETIME(3) NULL COMMENT '一次性精确执行时间,命中后停用',
  `lead_ms`       INT NOT NULL DEFAULT 0 COMMENT '提前量(ms)',
  `enable_retry`  TINYINT NOT NULL DEFAULT 1 COMMENT 'code4是否重试',
  `max_retry`     INT NOT NULL DEFAULT 3,
  `retry_interval_ms` INT NOT NULL DEFAULT 500,
  `status`        VARCHAR(20) NOT NULL DEFAULT 'ENABLE',
  `last_result`   VARCHAR(200) NULL,
  `last_grab_time` DATETIME NULL,
  `promotion_order_id` BIGINT NULL,
  `create_time`   DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY(`id`),
  INDEX `idx_user_id`(`user_id`),
  INDEX `idx_status`(`status`)
) ENGINE=InnoDB COMMENT='抢单配置';
```
- 复用 `MonitorConfigStatusEnums`（ENABLE/DISABLE）。
- `location_id` 可空：允许用户直接填经纬度；非空时从 `LocationEntity` 取 lat/lng/city_code。

### 2.3 新表 `grab_history`
```sql
CREATE TABLE `grab_history` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `user_id` INT NOT NULL,
  `grab_config_id` INT NOT NULL,
  `promotion_id` INT NOT NULL,
  `start_time` DATETIME(3) NOT NULL,
  `end_time` DATETIME(3) NOT NULL,
  `success` TINYINT NOT NULL DEFAULT 0,
  `resp_code` INT NULL,
  `resp_msg` VARCHAR(200) NULL,
  `promotion_order_id` BIGINT NULL,
  `attempt` INT NOT NULL DEFAULT 1,
  `trigger_type` VARCHAR(16) NULL COMMENT 'MANUAL/CRON/ONESHOT',
  PRIMARY KEY(`id`),
  INDEX `idx_grab_config_id`(`grab_config_id`),
  INDEX `idx_user_id`(`user_id`)
) ENGINE=InnoDB COMMENT='抢单执行记录';
```

## 3. 代码结构

```
http/
  XiaochanHttp.java            ← 新增 grabPromotionQuota() + 带登录态 header 构造
model/entity/
  GrabConfigEntity.java        ← 新增
  GrabHistoryEntity.java       ← 新增
model/enums/
  GrabTriggerTypeEnum.java     ← MANUAL/CRON/ONESHOT (新增,或直接用字符串)
model/dto/
  GrabConfigDTO.java           ← 新增
  GrabLoginStateDTO.java       ← 新增(抓包header原文)
  GrabHistoryQueryDTO.java     ← 新增
model/vo/
  GrabConfigVO.java, GrabHistoryVO.java, GrabResultVO.java
mapper/
  GrabConfigMapper.java, GrabHistoryMapper.java
service/
  GrabService.java
service/impl/
  GrabServiceImpl.java        ← 核心抢单逻辑 + 重试 + 推送
controller/
  GrabController.java          ← /api/grab/*
tasks/
  GrabCronScheduler.java       ← 仿 MonitorCronScheduler
```

## 4. 核心流程设计

### 4.1 登录态录入（F1）
`POST /api/grab/login-state`，body = 抓包 header 原文（多行 `Key: Value`）或抓包 JSON。
- 解析：正则/分割提取 `X-Sivir / X-Teemo / X-Vayne / X-Session-Id / X-Nami`。
- 解析 `X-Sivir` JWT payload（base64url）取 `exp`、`UserId`，校验与 `X-Teemo` 一致。
- 写入 `UserEntity.xc*` 字段并 `update`。
- 返回录入摘要（用户id、JWT 过期时间）。

### 4.2 手动抢单（F2）
`POST /api/grab/config/{id}/execute` 或 `POST /api/grab/execute`(带 promotion_id 等一次性参数)。
- 取当前 user 登录态；缺失则报错「未绑定登录态」。
- 取位置（location_id → LocationEntity，或入参直传 lat/lng/city_code）。
- 调 `XiaochanHttp.grabPromotionQuota(...)`。
- 落 `grab_history`(trigger_type=MANUAL)。
- code=0 → 存 `promotion_order_id` 到 config + history，推送成功通知。
- 不对手动触发做自动重试（重试是定时场景的配置；手动由用户自行再点）。

### 4.3 定时抢单（F3/F4）
`GrabCronScheduler`（仿 `MonitorCronScheduler`）：
- `@EventListener(ApplicationReadyEvent) refreshAll()`：加载所有 ENABLE 且有 cron 或 execute_at 的配置。
- `schedule(config)`：
  - 有 `cron` → `taskScheduler.schedule(task, new CronTrigger(cron))`，提前 `lead_ms` 由 task 内部 `Thread.sleep` 或计算触发后立即发（lead_ms 主要用于一次性精准场景）。
  - 有 `execute_at` → `taskScheduler.schedule(task, instant.minus(lead_ms, ms))`，执行后置 status=DISABLE 并 `cancel`。
- `refresh(id)/cancel(id)`：配置增删改 / 启停时由 service 调用，保持内存调度与库一致。
- `execute(config)`：
  1. 重查 latest config，校验 status/cron/execute_at 有效性，无效则 cancel。
  2. 取登录态、位置。
  3. 调 `grabPromotionQuota`，落 history(trigger=CRON/ONESHOT)。
  4. code=0 → 存 order_id，推送成功，DISABLE 配置 + cancel（一次性任务结束）。
  5. code=4 且 `enable_retry` 且 attempt<max_retry → `Thread.sleep(retry_interval_ms)` 后重试（循环内），每次落一条 history(attempt++)。
  6. code=6（名额已抢完，A0 实测）→ 直接失败不重试（重试无意义），落 history，推送失败通知。
  7. 达上限仍失败 → 推送失败通知。
- service 增删改配置后调用 `grabCronScheduler.refresh(id)` 同步调度。

### 4.4 时钟校准（N3，best-effort）
抢单前（仅 ONESHOT/CRON 首次）可选打一次轻量网关请求取响应 `Date` 头，计算 `serverNow - localNow` 偏移，用于 `execute_at` 的实际触发修正。**为避免额外请求被风控，默认关闭，仅 ONESHOT 且 lead_ms 配置时启用**。一期可先不做，留扩展点。

### 4.5 JWT 过期提醒（F8）
`GrabService` 提供定时检查（可挂一个独立 cron 或复用现有调度），扫描 `user.xc_sivir` 的 `exp`，临过期（默认 1 天）推送一次。去重：记录已提醒时间，避免重复推送。

## 5. 抢单 HTTP 实现要点（XiaochanHttp）

新增方法，复用 `getAshe / getNami / executeWithProxy`：
```java
public JSONObject grabPromotionQuota(GrabAuth auth, int cityCode,
        String lat, String lng, int promotionId, int silkId) {
    String server = "Silkworm", method = "SilkwormService.GrabPromotionQuota";
    Map<String,Object> body = new HashMap<>();
    body.put("latitude",  new BigDecimal(lat));
    body.put("longitude", new BigDecimal(lng));
    body.put("city_code", cityCode);
    body.put("store_platform", 1);
    body.put("if_advance_order", false);
    body.put("promotion_id", promotionId);
    body.put("silk_id", silkId);
    long ts = System.currentTimeMillis();
    String nami = StringUtils.hasText(auth.getNami()) ? auth.getNami() : getNami();
    String ashe = getAshe(ts, server, method, nami);
    String res = postWithResAuth(BASE_URL, JSONObject.toJSONString(body),
        cityCode, server, method, ts, ashe, nami, auth);
    return JSONObject.parseObject(res);
}
```
`postWithResAuth` = 现有 `postWithRes` 变体：`getHeaders` 替换为带登录态的 header 集合（`X-Platform=Android`、加 `X-Sivir/X-Teemo/X-Vayne/X-Session-Id`、`User-Agent=XC;Android;3.18.3;`、`X-Version=3.18.3.3`）。代理/403 重试逻辑不变。

`GrabAuth` 为轻量值对象：`sivir / teemo(userId) / sessionId / nami`，由 `UserEntity` 映射。

## 6. 兼容性与回滚

- DDL 全部增量 ALTER / 新建表，不动既有列；回滚 = DROP 新表 + 还原 ALTER（脚本附于 ddl.sql 末尾注释）。
- 新增 controller / service / scheduler 独立，不修改现有 `MonitorCronScheduler` / `StoreTask` 逻辑，零回归风险。
- 登录态字段可空，未绑定用户现有功能不受影响。
- 回滚点：实现按阶段 A（手动）→ 阶段 B（定时）切分，阶段 A 可独立上线。

## 7. 部署

- 后端本地 `mvn -DskipTests package` 构建 `xiaocan.jar`，按现有部署链路发布（不在生产服务器跑 mvn）。
- DDL 在生产 MySQL 手动执行增量语句。
- 前端（xiaocan-front）配套页面另行发布。

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 随机 X-Nami 被拒 | 实现前用抓包原值重放验证；持久化原 Nami 作回退 |
| 高频重试被封 | 默认 retry 间隔 500ms、上限 3；经 ProxyHolder；成功即停 |
| promotion_id 跨天失效 | UI 提示 + 一键从当日活动带入 |
| JWT 过期 | exp 解析 + 临过期推送；抢单前校验 exp 已过则直接失败提示 |
| 时钟偏差 | 留时钟校准扩展点，一期 best-effort |
