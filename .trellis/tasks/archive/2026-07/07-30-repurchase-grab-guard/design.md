# Design: 复购活动识别与 code107 处理

## 1. Scope & Boundaries

| 层 | 改动 | 不改 |
|----|------|------|
| 模型 | `StoreInfo` 增 `Boolean ifRepurchasePromotion` | DB schema、GrabConfig 表 |
| HTTP 解析 | `XiaochanHttp.parsePromotion` 读详情/列表 JSON 字段 | 抢单请求体、签名 |
| 手动/定时抢 | `GrabServiceImpl.doGrab` 对 107 文案规范化 | 重试条件仍「仅 code==4」 |
| 自动抢 | `AutoGrabServiceImpl.shouldSwitchAccount` 含 107 | 组合排序、防重键、模式状态机 |
| Spec | `xiaocan-rpc-contract.md` 补契约 | 前端仓库 |

## 2. Data Flow

```
GetStorePromotionDetail / list
  → parsePromotion
      → StoreInfo.ifRepurchasePromotion  // 活动类型标记，仅展示/日志
  → doGrab (manual/auto/oneshot)
      → GrabPromotionQuota
          → code==0 + orderId → 成功
          → code==4 → 重试（不变）
          → code==107 → 失败文案规范化，不重试
          → 其它 → 既有逻辑
  → AutoGrab 读 result.code
      → shouldSwitchAccount(107)=true → 换号（SINGLE）/ 放弃门店（ALL）
```

**不变式**

- `ifRepurchasePromotion` **从不**单独阻断 `doGrab`（D2）。
- 107 是 **账号×门店** 资格失败，语义对齐「换号类」而非「库存耗尽类」。

## 3. Contracts

### 3.1 上游字段

```json
// promotion_detail（及列表项若存在）
"if_repurchase_promotion": true,
"promotion_condition": { "rp": true }
```

解析优先级：`if_repurchase_promotion == true` → true；否则若 `promotion_condition.rp == true` → true；否则 false/null。推荐存 `Boolean`：缺字段为 `null` 或 `false`（实现选 `Boolean.TRUE.equals` 友好：缺省当 false）。

### 3.2 上游错误

| code | 语义 | doGrab | AutoGrab |
|------|------|--------|----------|
| 4 | 未开始/可重试 | 重试 | （由 doGrab 内重试） |
| 6 | 已抢完等组合级 | 失败推送 | 降级组合 |
| 70 | 账号×门店限频 | 失败推送 | **换号** |
| **107** | 复购且本号无该店单 | 失败推送（规范文案） | **换号**（本任务） |
| -1 | 本地饭票/登录态等 | 失败 | **换号** |

### 3.3 文案

- 优先使用上游 `status.msg`（已是中文完整句）。
- 若 msg 空：回落 `"复购活动：当前账号未在该店铺下过单，无法参加"`。
- 推送前缀仍走 `buildPushPrefix`；后缀带规范化 msg + `(code=107)`。

## 4. Component Changes

### 4.1 `StoreInfo`

```java
/** 是否复购活动（if_repurchase_promotion / promotion_condition.rp） */
private Boolean ifRepurchasePromotion;
```

`BeanUtils.copyProperties` 会复制到美团/饿了么/京东分支副本，三平台一致（字段在 promotion 级，非平台级）。

### 4.2 `parsePromotion`

在设置 `rebateCondition` 等公共字段处：

```java
Boolean repurchase = jsonObject.getBoolean("if_repurchase_promotion");
if (!Boolean.TRUE.equals(repurchase)) {
    JSONObject cond = jsonObject.getJSONObject("promotion_condition");
    if (cond != null && Boolean.TRUE.equals(cond.getBoolean("rp"))) {
        repurchase = true;
    }
}
storeInfo.setIfRepurchasePromotion(Boolean.TRUE.equals(repurchase));
```

### 4.3 `GrabServiceImpl.doGrab`

在解析 `code/msg` 后、写历史前：

```java
if (code == 107 && !StringUtils.hasText(msg)) {
    msg = "复购活动：当前账号未在该店铺下过单，无法参加";
}
// 可选：code==107 时若 msg 已有则原样；日志 debug 带 ifRepurchasePromotion
```

重试条件保持 `code == 4` 才 continue；107 走现有 `code != 4` 分支推送并 break。  
**无需**为 107 新增提前 return（避免与「有历史单应可抢」冲突）。

### 4.4 `AutoGrabServiceImpl.shouldSwitchAccount`

```java
return code == 70 || code == -1 || code == 107;
```

类注释/判定表注释同步：107 = 复购资格（账号相关）。

## 5. Compatibility

- JSON 响应多字段 `ifRepurchasePromotion`：前端忽略则无影响。
- 自动抢行为变化：仅「原会因首号 107 降级」的路径改为换号 → **更激进试号**，符合 D1。
- 无 DB 迁移、无配置项。

## 6. Risks & Rollback

| 风险 | 缓解 |
|------|------|
| 列表无字段导致标记恒 false | 可接受；107 路径仍正确 |
| 107 换号增加上游调用 | 符合产品选择 A；账号池有限 |
| 推送噪音（每号 107 一推） | 与现网 70 行为一致，本任务不消噪 |

回滚：还原 `shouldSwitchAccount` 与字段解析即可，无数据残留。

## 7. Test Plan (manual / local)

1. 单元级：构造含 `if_repurchase_promotion` 的 JSON 走 `parsePromotion`（若无单测则用临时 main/或现有 GrabReplay 风格）——以编译 + 代码审阅为底线，有现成测试则补断言。
2. 逻辑审阅：`shouldSwitchAccount(107)==true`；`doGrab` 循环对 107 不 sleep 重试。
3. 不在生产对真实账号刷 107（已知会失败的号仅日志验证即可）。
)
