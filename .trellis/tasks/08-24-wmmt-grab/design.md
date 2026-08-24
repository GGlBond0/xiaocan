# 歪麦抢单 — 技术设计

## 1. 架构与边界

```
监控命中(source=2) ── AutoGrabService.tryCreateFromMonitor ──▶ source分支 ──▶ WmmtHttp.signUp()
        ╱                    │ 按 config.source 分流                 │
   WmmtTask (已覆写抓取)     │ 小蚕: 原链路(grabPromotionQuota/orderExchange)
        ╲                    │ 歪麦: 新链路(signup)                 │
   BaseTask.runSingle:241    ▼                                       ▼
   统一调 autoGrab                          grab_config(grabService.save)
```

- **不改** `BaseTask`/`WmmtTask`(它们只负责"命中→传 sameStoreCombos")。
- **改** `AutoGrabServiceImpl.tryCreateFromMonitor`/`runSingle`/`runAllForAccount` 内:当
  `config.source==2` 时走歪麦分支(账号=wmmt_login_state、活动键=overbearfoodId、signup 调用、判定表换轨)。
- **新增** `WmmtHttp.signUp(...)`(或独立 `WmmtGrabHttp`)。
- `grab_config` 复用同一张表;`grab_history` 复用。

## 2. 数据流

### 2.1 登录态扩充 userId
- `wmmt_login_state` + `userId INT`(歪麦用户 id,数字)。
- `WmmtLoginState{Entity, DTO, VO, Service}` 增加 userId 字段;录入必填校验、回显。
- 存量 token 无 userId → 抢单时提示"该歪麦账号缺 userId,需在微信端补录"。

### 2.2 歪麦活动键 + 账号存储
- 歪麦提交要 `overbearfoodId`(String)。`grab_config.promotion_id`(INT) 不足以承载原始串。
- **方案**:`grab_config` + `wmmt_overbear_food_id VARCHAR(64)`(歪麦活动键)。`promotion_id` 保留
  为可解析的 INT 键(经 `parsePromotionId` 转),原始串存新列;signup 用 `wmmt_overbear_food_id`。
- **歪麦账号独立存**:小蚕抢单用 `loginStateId`(指向 login_state.id)。歪麦账号是指向 `wmmt_login_state.id`,
  **不能用** `loginStateId` 栏(会与小蚕混淆)。故 `grab_config` + `wmmt_login_state_id INT` 存歪麦账号 id;
  歪麦场景 `loginStateId` 留空。`GrabServiceImpl.doGrab` 按 `source==2` 走 `wmmtLoginStateId` 解析歪麦账号日志。
- **歪麦门店 id 独立存**(实现中发现):歪麦提交请求体 `businessId`=门店 id(`StoreInfo.uniqId`,String),
  `overbearfoodId`=活动键(`StoreInfo.overbearFoodId`)。`grab_config` + `wmmt_business_id VARCHAR(64)`
  存门店 id、`wmmt_overbear_food_id` 存活动键。`StoreInfo` 增 `overbearFoodId`(WmmtHttp 解析 sku 时回填)。

### 2.3 signup 调用(WmmtHttp 新增)
```
WmmtHttp.signUp(loginState, dto):
  newSignUpFlag = fetchKeys 缓存的值 (拉密钥时一并读 data.newSignUpFlag)
  if newSignUpFlag(或页 km):  baseURL2 + "/order/waimaimt-web-order/overbear/signup"
        body = AES_encrypt(JSON(dto), aesKey)   # dto 含 businessId/overbearfoodId/serviceNoStr/userId/...
        headers += {"encrypt-key": RSA(base64(aesKey))}
        成功 code∈{200,0,"0"} + buyOverbearId
  else:                   baseURL + "/api/v2/overbearfood/api_overbear_sign_up"
        body = {"json": AES_encrypt(JSON(dto), LEGACY_AES_KEY)}
        成功 code==1 + data.buyOverbearId
```
- 请求头复用 `buildCommonHeaders`(token/nonce/timestamp/sign/application=overbear_one/city)。

### 2.4 抢单判定表(歪麦分支)
成功判据:`buyOverbearId` 非空。失败 code 语义(逆向已知部分):
- 报名费>0(响应含 `payAmount/occupyPayAmount/secKillPayAmount`)→ 记失败 + 推送「需手动支付报名费」。
- 复购资格失败/无 userId/登录态缺失 → 失败 + 明确提示。
- 其余失败 → 按 AutoGrabService 换号/降级语义继续(与 source=1 一致)。

## 3. 关键组件改动清单

| 文件 | 改动 |
|---|---|
| `ddl.sql` / 生产 DDL | `wmmt_login_state` + `userId`;`grab_config` + `wmmt_overbear_food_id` |
| `WmmtHttp.java` | +`signUp()`;`fetchKeys` 缓存 `newSignUpFlag` |
| `WmmtLoginState*` | + userId 字段/校验/回显 |
| `GrabServiceImpl.doGrab` | 按 `loginState` 判定歪麦 vs 小蚕(歪麦登录态来自 `wmmt_login_state`) |
| `AutoGrabServiceImpl` | 按 `config.source` 分支,歪麦组装配对 + 判定表 |
| `GrabConfigEntity/DTO` | + `wmmtOverbearFoodId`;source 语义透传 |

## 4. 兼容性 / 迁移

- 存量 `grab_config`(小蚕)不受影响(无 `wmmt_overbear_food_id`,source 默认 1)。
- `wmmt_login_state` 存量 token 无 userId —— 不阻塞非抢单路径(门店/监控抓取不受影响);仅抢单需补录。
- `addUpdateConfig` 现有对 source=2 强制 `autoGrab=false` 需**放宽**,允许 source=2 开 autoGrab。
- 歪麦 `grabMode`(SINGLE/ALL)对多账号换号/降级语义沿用;账号来源为 `wmmtLoginStateIds`。

## 5. 边界 / 错误矩阵(design 确认版)

| 条件 | 行为 |
|---|---|
| source=2 且未绑歪麦账号 | BusinessException「歪麦监控必须选择歪麦账号」,不建 grab_config |
| 歪麦账号缺 userId | 记失败 + 推送「该账号需在微信端补录 userId 才能自动抢」 |
| newSignUpFlag 取不到 | 回退老版 LEGACY_AES,记 warn |
| 报名费>0 | 记失败 + 推送「需手动支付报名费」,不继续,不静默 |
| 复购资格/信用分/品鉴门槛不足 | 按 code 记失败;可考虑换号(AutoGrabService 语义) |
| 成功 | `grab_history.success=1` + `promotionOrderId=buyOverbearId`;配置置 DISABLE;推送成功 |

## 6. 风险与权衡

- **只能抢外卖霸王餐**:美团动手餐分支(`meituan/signup`)本轮不做,知悉取舍。
- **报名费不可自动付**:遇报名费>0 终止并提示(C-6),避免静默失败。
- **userId 补录是硬依赖**:存量账号无 userId 无法抢;需用户配合。
- **歪麦活动键 String**:用新列承载原始串,不动 `promotion_id` INT 语义,避免影响小蚕兼容。

## 7. 相关能力已具备(不必重写)

- `WmmtHttp` RSA+AES/LEGACY_AES/sign/encrypt-key/token/city 全部在。
- `grab_config`/`grab_history`/`AutoGrabService`/`GrabCronScheduler` 调度复用。
- `monitor_config.source` 路由已存在(`WmmtTask` 已按 source 抓取)。