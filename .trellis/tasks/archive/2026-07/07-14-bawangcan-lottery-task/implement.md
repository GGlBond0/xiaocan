# Implement：小蚕霸王餐抽奖浏览任务自动完成

> 执行前先补抓 type 枚举（步骤 0），它是实现能否覆盖全部浏览任务的前提。

## 步骤 0：补抓浏览任务 type 枚举（实现前必做）
- [ ] 系统代理切 8080（mitmproxy 仍在跑），电脑微信小蚕小程序进抽奖页
- [ ] 逐个点未完成的"浏览霸王餐页/浏览福利页/浏览广告/浏览抖音商城"任务
- [ ] 从 mitmweb 拉每个 `AddLotteryTimes` flow 的 `type` 值，记录 `type → 任务描述` 映射
- [ ] 记录到 `research/type-map.md`
- [ ] 系统代理还原 10808
- 验证：research/type-map.md 含 ≥2 个 type 映射；若只能抓到 type=10，注明其余待补，步骤 4 用已知 type 先行

## 步骤 1：建表 + 实体 + Mapper
- [ ] 写 `research/ddl_lottery_auth.sql`（见 design 数据模型）
- [ ] 在生产/测试库执行（或交用户执行）建 `lottery_auth` 表
- [ ] `LotteryAuthEntity.java`（`@TableName("lottery_auth")`，`@TableLogic` deleted）
- [ ] `LotteryAuthMapper.java extends BaseMapper<LotteryAuthEntity>`
- 验证：编译通过；mapper 能查空表

## 步骤 2：LotteryAuth POJO + LotteryHttp RPC 调用
- [ ] `http/LotteryAuth.java`（`@Data @Builder`，isComplete 校验 sessionId+silkId）
- [ ] `http/LotteryHttp.java`：
  - [ ] `getAshe/getNami`（复刻 XiaochanHttp，private static）
  - [ ] `getMiniHeaders(...)`（mini header，含 X-Platform=mini / X-Session-Id / x-Teemo / X-Vayne）
  - [ ] `executeWithProxy(...)`（复刻 ProxyHolder 代理重试，或抽公共）
  - [ ] `postWithAuth(url, body, cityCode, serverName, methodName, auth)` private
  - [ ] `public JSONObject lotteryInfo(LotteryAuth auth)`
  - [ ] `public JSONObject addLotteryTimes(LotteryAuth auth, int type)`
  - [ ] `public JSONObject getLotteryProgress(LotteryAuth auth)`
  - [ ] `public JSONObject userTaskV2(LotteryAuth auth)`（可选）
- 验证：单元/手测调 `lotteryInfo(抓到的登录态)` 返回 code=0（复刻签名已实测通过）

## 步骤 3：Service 编排 + Controller
- [ ] `LotteryServiceImpl`：
  - [ ] `saveAuth(LotteryAuthDTO)`：解析 rawHeaders 正则提 sessionId/silkId/userId/nami，去重(同 silkId 更新)，入库
  - [ ] `listAuth()` / `deleteAuth(id)`
  - [ ] `runTask(authId)` → LotteryTaskResultVO（见 design 流程）
- [ ] `LotteryController @ /api/lottery`：GET/POST/DELETE /auth、POST /run
- [ ] `LotteryAuthDTO`、`LotteryTaskResultVO`
- 验证：启动后端，`POST /api/lottery/auth` 录入抓包登录态成功；`GET /api/lottery/auth/list` 能看到

## 步骤 4：跑通刷任务链路（关键实测）
- [ ] 用录入的登录态调 `POST /api/lottery/run`
- [ ] 观察 doneTasks 各 type 是否 code=0
- [ ] 对比 before/after 的 lottery_count
- 验证：至少已知 type=10 的 addLotteryTimes 返回 code=0；前端能展示结果
- **若 lottery_count 未涨**（风控判无效）：记录现象到 research，前端如实展示，不造假，告知用户"加机会接口被风控，浏览任务重放无效"

## 步骤 5：前端
- [ ] `SettingsView.vue` 追加"霸王餐刷任务"区块 或 新 `LotteryView.vue`
- [ ] 登录态列表 + 粘贴录入 + 删除
- [ ] 一键刷任务按钮 → POST /api/lottery/run，展示结果（任务清单 + 前后机会数）
- 验证：浏览器跑通 录入→刷任务→看结果 全流程

## 步骤 6：质量检查 + 收尾
- [ ] /code-review 或 trellis-check 过一遍
- [ ] 不影响现有抢单/监控（grep 确认未改 GrabAuth/grab_login_state/XiaochanHttp 既有签名）
- [ ] 更新 spec（trellis-update-spec）
- [ ] commit

## 回滚点
- 步骤 1-3 均为新增，回滚=删新增文件 + DROP lottery_auth，零副作用
- 步骤 4 若风控判无效，功能仍可交付（如实展示），不算阻塞
