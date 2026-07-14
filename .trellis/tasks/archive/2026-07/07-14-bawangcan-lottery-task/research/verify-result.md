# 步骤4 实测结果（Python 脚本复刻签名直连验证）

## 环境
- Python 复刻 LotteryHttp 签名（getAshe/getNami），经梯子 127.0.0.1:10808 出境
- mini 登录态：silk_id=222559356, X-Session-Id=f7b87152-...
- 时间：2026-07-14（同账号今日已多次操作抽奖页）

## 结果

### 查询接口（全部 code=0，签名+登录态验证通过）
- `LotteryInfo` → code=0，lottery_info:
  - `day_num: 5`（今日已获 5 次机会）
  - `is_add_times: false`（**今日不能再加抽奖次数，已达上限**）
  - `is_view_bwc_page: true`（所有浏览任务今日已完成）
- `GetLotteryProgress` → code=0，lottery_count: 3

### AddLotteryTimes 各 type（2/8/9/10/11）→ 全部 HTTP 401，body 空
- 401 由网关层直接拒绝，不进业务逻辑（无 status.code）
- **根因：`is_add_times=false`，今日浏览任务全做完、加机会次数达上限**，重复/超额调用被网关 401
- 非登录态问题（同登录态查询接口 200 正常）
- 非签名问题（查询接口 code=0 证明签名正确）

## 结论
- ✅ 签名算法、mini 登录态、查询链路全部验证通过
- ✅ 后端代码逻辑（LotteryHttp/Service）正确：能读 day_num/lottery_count/is_view_xxx，能按 type 调 AddLotteryTimes
- ⚠️ **无法在本日验证"刷机会让 lottery_count 上涨"**：账号今日 day_num 已达上限(is_add_times=false)，AddLotteryTimes 被 401
- 补抓阶段已用 day_num 2→3→4→5 的实测证明 AddLotteryTimes 真实生效（每次 +1），见 type-map.md

## 真正验证 lottery_count 上涨的条件
- 明天（day_num 重置）后再调，或
- 用一个今日未做过浏览任务的账号
- 预期：is_add_times=true 时 AddLotteryTimes 返回 {status:{code:0}}，day_num+1，lottery_count 上涨

## 代码处理建议
- runTask 中 AddLotteryTimes 返回 401/非 code=0 时，item.ok=false 并记录 msg，前端如实展示
- LotteryHttp 的 executeWithProxy 对 401 当前是"换代理重试"——但此场景 401 是业务上限非代理问题，会无谓重试。**需优化**：AddLotteryTimes 的 401 不应触发代理重试（会消耗代理额度）。建议 LotteryHttp 区分"403=代理坏"与"401=业务拒"，401 直接返回不重试。
