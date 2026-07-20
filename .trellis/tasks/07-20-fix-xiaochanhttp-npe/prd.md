# PRD: 修复 XiaochanHttp NPE 隐患

## Goal

修复审查 A-1/A-2/A-3：`XiaochanHttp` 解析上游响应时多处 NPE/越界隐患。上游返回缺字段时整批 `fetchStoreInfos` 失败（冒泡到监控本轮异常），属高频路径的健壮性问题。改为缺字段时优雅跳过该平台/该条，不致整批崩。

## 背景（审查结论）

- A-1 Integer 拆箱 NPE：`:518 meituan_status == 1`、`:528 eleme_status == 1`、`:544 tp_status == 1`——`getInteger` 缺字段返回 null，`== 1` 拆箱 NPE。
- A-2 链式 NPE：`:503 store.getString("name")` 前置 `store = getJSONObject("store")` 未判 null；`:423 parseBodyToAddress` 与 `:564 checkResult` 链式 `getJSONObject("status").getInteger("code")` 未判 status null。
- A-3 越界：`:168/191 getStorePromotionDetail` `storeInfos.get(0)` 空 list 时 IndexOutOfBounds。

## Requirements

- R1 A-1：`parsePromotion` 三个 `getInteger("xxx_status") == 1` 改为 null 安全比较（`Integer.valueOf(1).equals(...)` 或 `Objects.equals(getInteger(...), 1)`），缺字段视为该平台不命中、跳过该平台分支，不影响其它平台解析。
- R2 A-2：`parsePromotion` 取 `store` 后判 null（null 则跳过整条 promotion，记 warn）；`parseBodyToAddress` / `checkResult` 取 `status` 后判 null（null 抛 BusinessException 走原有错误路径，而非 NPE）。
- R3 A-3：`getStorePromotionDetail` 两个重载在 `storeInfos` 空/越界时返回 null 或抛 BusinessException，不抛 IndexOutOfBounds。调用方 `GrabServiceImpl.doGrab` 已对 promoSnapshot==null 做兜底（:236），返回 null 安全。
- R4 不改变正常路径行为（字段齐全时输出完全一致）。
- R5 不动 `XiaochanHttp` 其它逻辑（代理/重试/加密/抢单请求组装）。

## 严重度

A-1/A-2：P1（高频路径，上游字段缺失即整批崩）；A-3：P1（抢单前查详情，空响应即崩）。

## Acceptance Criteria

- [ ] 后端 `mvn -o compile` BUILD SUCCESS。
- [ ] A-1：三处 `xxx_status` 改为 null 安全比较，缺字段不 NPE。
- [ ] A-2：`store`/`status` null 时走优雅路径（跳过或抛 BusinessException），不 NPE。
- [ ] A-3：`getStorePromotionDetail` 空 list 时返回 null（不越界）。
- [ ] 正常路径（字段齐全）输出与改动前一致。
- [ ] 部署上线后，此前偶发的 parsePromotion NPE 不再出现（观察 error.log）。

## Out of Scope

- 不改其它文件的 NPE（XiaoChanServiceImpl C-17 等另开任务）。
- 不改业务逻辑，仅加 null 防御。

## Open Questions

无。
