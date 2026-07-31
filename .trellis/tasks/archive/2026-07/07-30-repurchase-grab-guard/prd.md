# 复购活动识别与 code107 处理

## Goal

正确区分「复购活动类型」与「当前账号无该店历史单」，在手动/自动抢单中给出清晰失败语义；多账号自动抢对 code=107 换号，不把账号级失败误判为活动级失败。

## Background

证据：`har/ProxyPin7-30_12_34_06.har`（2026-07-30）。

| 信号 | 来源 | 含义 |
|------|------|------|
| `if_repurchase_promotion: true` / `promotion_condition.rp: true` | `GetStorePromotionDetail` | 活动类型为复购（须在该店有过订单） |
| `GrabPromotionQuota` → `code=107` | 抢单响应 | **本账号**未在该店下过单，服务端硬拦 |
| `OrderExchange` code=0 且无 order | 同 HAR 旁路 | **不是**美团抢单成功，不可当绕过手段 |

现状缺口：

- `StoreInfo` / `parsePromotion` 未解析复购标记。
- `doGrab` 对 107 已不重试（仅 code=4 重试），但无专项文案。
- `AutoGrabServiceImpl.shouldSwitchAccount` 仅 `70/-1`；107 走「降级组合」，多账号会过早放弃可能有历史单的其他号。

## Decisions

| # | 决策 | 选择 |
|---|------|------|
| D1 | 自动抢是否整类跳过复购活动 | **否**。照常发起；`code=107` 当账号级失败 **换号**（策略 A） |
| D2 | 手动抢是否因详情标记预拦截 | **否**。有历史单账号可成功；仅 107 时失败文案 |
| D3 | 前端专项 UI / 配置开关 | **本任务不做**。字段进 `StoreInfo` 即可随列表/详情 JSON 露出，UI 后续可选 |
| D4 | 伪造/绕过复购资格 | **不做**（服务端校验，客户端不可绕） |

## Requirements

- **R1** `parsePromotion` 解析 `if_repurchase_promotion`（及可选 `promotion_condition.rp`）写入 `StoreInfo.ifRepurchasePromotion`。
- **R2** `doGrab` 遇 **code=107**：不重试；历史/推送文案明确「复购活动且当前账号未在该店下过单」（可规范化上游 msg）；与 code=4/6 等区分。
- **R3** `shouldSwitchAccount(107) == true`：SINGLE 换下一账号；不因首号 107 直接废组合。ALL 模式沿用现有「账号级失败则该账号放弃本门店」语义（与 70 一致）。
- **R4** 在 `.trellis/spec/backend/xiaocan-rpc-contract.md` 记录 `if_repurchase_promotion` 与 code=107 语义。

## Out of Scope

- 绕过服务端复购校验、伪造历史单。
- 独立「是否在该店下过单」资格查询接口（HAR 无）。
- 监控配置「跳过复购」开关、前端复购角标/文案页（D3）。
- 改 `OrderExchange` 成功判定或美团主路径。
- 改变 code=70/-1/4/6 既有语义（仅 **追加** 107 为换号码）。

## Acceptance Criteria

- [x] **AC1** 详情含 `if_repurchase_promotion:true` 时，解析后 `StoreInfo.ifRepurchasePromotion == true`（样本 promotion_id=120917104）。
- [x] **AC2** 手动/ONESHOT `doGrab` 收到 code=107 → 单次结束、历史 success=false code=107、推送含复购/未下单语义；**不**按 code=4 重试。
- [x] **AC3** 自动抢 SINGLE：账号 A 返回 107 → 继续账号 B 同组合；仅账号池耗尽后才降级下一组合。
- [x] **AC4** 自动抢 ALL：账号遇 107 → 与 70 相同，该账号放弃本门店（不换号，因 ALL 本就不换号）。
- [x] **AC5** rpc-contract 已文档化 `if_repurchase_promotion` 与 code=107。
- [x] **AC6** 非复购活动、非 107 路径行为与改前一致（回归：code=4 仍重试，code=70 仍换号）。

## Notes

- 列表接口是否带 `if_repurchase_promotion` 本 HAR 未证；R1 在 `parsePromotion` 统一解析，列表有则列表也有，无则为 null/false。
- 有历史单账号对复购活动应可 `GrabPromotionQuota` 成功；本任务不模拟该成功路径，只保证不误拦、107 处理正确。
)
