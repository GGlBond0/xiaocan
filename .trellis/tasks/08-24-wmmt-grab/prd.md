# 歪麦抢单落地

## Goal

让监控命中的歪麦门店活动能**自动抢单**（填补 `autoGrab` 对 source=2 暂时禁用的空缺）。
复用现有 `grab_config`/`grab_history`/`AutoGrabService`/`GrabCronScheduler` 调度与推送体系，
仅在歪麦侧新增"正向提交 signup 接口"与"数据源分支"。本轮**仅监控自动抢**，不做歪麦手动入口。

## 背景（逆向已确认,2026-08-24）

- 抢单正向提交接口已从反编译小程序源码 `wx5762f23d920ad9e5` 定位，契约见
  `.trellis/spec/backend/wmmt-grab-contract.md`。
- **双轨制**：`newSignUpFlag`(服务端下发) 为真 → 新版 `POST /order/waimaimt-web-order/overbear/signup`
  (RSA+AES, wmapp-api-v2) ; 为假 → 老版 `POST /api/v2/overbearfood/api_overbear_sign_up`
  (LEGACY_AES, fz-gateway)。成功判据新版 `code∈{200,0,"0"}`、老版 `code==1`。
- **请求体含 `userId`(数字,≠token)**。现有 `wmmt_login_state` 只存 `token`，**需补存 userId**。
- 加解密/请求头能力 `WmmtHttp` 已具备；新增点 = signup 的 body 构造 + 动态读 newSignUpFlag。

## Requirements

- CR-1. `wmmt_login_state` 新增 `userId`(歪麦用户 id) 列 + 实体/DTO/VO/录入/回显支持。
- CR-2. `WmmtHttp` 新增抢单 signup 调用（overbear 外卖分支），支持新版/老版双轨（按缓存 newSignUpFlag 选）。
- CR-3. `GrabService`/`AutoGrabService` 对 source=2 分支组装歪麦 grab_config 并调 signup。
- CR-4. 监控命中 source=2 且 autoGrab=true → 自动建歪麦 grab_config 并 signup。
- CR-5. 成功/失败写 `grab_history`（复用）+ 推送；成功含 `buyOverbearId`。
- CR-6. 报名费>0（`payAmount/occupyPayAmount/secKillPayAmount`）→ 记失败 + 推送「需手动支付」，不继续。
- CR-7. 前端：歪麦登录态录入支持 userId；监控配置 source=2 允许 autoGrab（取消强制 false）。

## Acceptance Criteria

- [ ] AC-1. `wmmt_login_state.userId` 列可存/回显;录入校验 userId 必填。
- [ ] AC-2. `WmmtHttp.signUp(token,userId,dto)` 新/老轨按缓存 flag 发起,返回解密后的 code/buyOverbearId。
- [ ] AC-3. source=2 监控配置 `autoGrab=true` 时,命中活动经 AutoGrabService 生成歪麦 grab_config 并 signup。
- [ ] AC-4. 未绑账号/无 userId 时给出明确错误提示(不静默失败)。
- [ ] AC-5. 抢单成功 `grab_history.success=1` + `promotionOrderId=buyOverbearId`;失败记录 code/msg。
- [ ] AC-6. 报名费>0 时不再继续,推送「需手动支付报名费」,grab_history 记失败。
- [ ] AC-7. 本地 mvn 编译通过;生产部署后冒烟(DB DDL + JAR + dist)。

## Out of Scope

- 美团动手餐分支(`meituan/signup`)——本轮只做外卖霸王餐 overbear 分支。
- 手动/定时抢单入口的"歪麦数据源"选择——本轮仅监控自动抢。
- 自动化支付(微信 `requestPayment`)——仍需人工。
- 歪麦黑名单接入(知悉取舍,后续)。
- `api_overbear_business_take_away_index_list` 等仅 APP 端业务。

## Technical Notes

- **歪麦活动键存储**：`grab_config.promotion_id`(INT) 不足以承载歪麦 `overbearFoodId`(String)。
  设计见 `design.md`——倾向复用 `promotion_id` 存可解析的 INT 键 + 新增 `wmmt_overbear_food_id`(String)
  存原始串，signup 用原始串。
- **userId 缺口**：`wmmt_login_state` 需补 `userId` 列；现有存量 token 无 userId 的历史账号，
  需用户重登/补录方可自动抢。