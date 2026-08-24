# Wmmt (歪麦) Data Source Contract

> 歪麦（waimaimingtang）数据源接入契约：加解密体系、监控路由、门店唯一键。

## 1. Scope / Trigger

调用歪麦业务接口（门店列表 / 红包 / 订单 / 抢单）前必读。歪麦与我方 server `xiaocan` 脱钩：token/加密/网关/请求头均独立于小蚕（美团/饿了么/京东）。见记忆 [[wmmt-crypto-cracked]]、[[wmmt-foundation-ready]]。

## 2. Signatures

### 网关与加密（已破解）
- 门店接口域 `wmapp-api-v2.waimaimingtang.com`：动态 **RSA+AES**（响应头 `encrypt-key`）→ `WmmtHttp.getShopList(token, city, dto)` 返回 `WmPageVO`(storeInfos + scrollPageData)。
- 红包/订单/抢单域 `fz-gateway.waimaimingtang.com`：固定 **LEGACY_AES_KEY**。外层 JSON `data` 字段 AES 解。
- 防重放：`sign = aesEncrypt(timestamp+nonce, SIGN_AES_KEY)`；每次请求需实时生成。
- `token` 是 32 位 hex 用户身份（非 JWT，无过期）；门店浏览免费（token 可空），**红包/订单/抢单需 token**。

### 门店 StoreInfo 平台码（关键）
`WmmtHttp.parseShopListResponse` 把歪麦门店解析成标准 `StoreInfo`：
- `type`(platform) = 上游 `takeawayPlatform`：`meituan→1`、`ele→2`、其余→`3`。**与 `StorePlatformEnum`(1/2/3) 全同** → 平台过滤/抢单平台逻辑复用小蚕。
- `storeTypeEnum` = `WM_MANJIAN`(shopPlatformType==1 满减) / `WM_MTSJ`(百分比返现)。
- **门店唯一键是 `uniqId`(String, wm_poi_id)；`storeId`(Integer) 为 null**。这是与 `store_pushed_history.store_id NOT NULL` 冲突的根因。

## 3. Contracts

### 监控数据源路由（monitor_config.source）
- `monitor_config.source`：`1`小蚕(默认) / `2`歪麦。既有配置 `source=1` 行为不变。
- `source==2` 时绑定 `wmmt_login_state_id`(单值回填首个) + `wmmt_login_state_ids`(逗号有序=优先级) 指向 `wmmt_login_state.id`。
- `fetchStoreInfos` 按 `source` 路由：`1→XiaoChanHttp.searchList`，`2→WmmtServiceImpl.fetchWmStoreInfos(token, null, location, keyword)`（token 按 ids 优先级取首个可用）。
- `WmmtTask extends BaseTask` 专跑 `source==2`；`MonitorCronScheduler` 按 `source` 分派。
- 小蚕执行体(`StoreTask`/`MinimumPayService`)的静态兜底 `start()` **必须跳 `source==2`**，否则歪麦配置被小蚕接口重复抓取/写错历史。
- **歪麦自动抢单已支持（2026-08-24, task 08-24-wmmt-grab）**：`source==2` 的 `autoGrab` 不再强制 false；
  `AutoGrabService` 按 source 分支建歪麦 `grab_config`（账号取自 `wmmtLoginStateIds`→`wmmt_login_state`），
  `GrabService.doGrab` 分派 `WmmtHttp.signUp`。抢单契约见 [[wmmt-grab-contract]]。

### 门店唯一键 uniq_id（store_pushed_history）
- `store_pushed_history` 新增 `uniq_id VARCHAR(64)`：歪麦记录 `store_id` 填 `0` 占位（列 NOT NULL），真实门店键存 `uniq_id`。
- **小蚕记录 `uniq_id` 为 null**，`store_id` 有值 → 去重键 `storeId:promotionId` 不变。
- 去重/当天去重以 `uniqId` 为准（有值优先）。
- `findPushedWithinMinutes` 的 `.select()` **必须含 `uniq_id`**（省略则该列映射 null → 歪麦去重键退化为 `"0:promotionId"`，同店同活动被误挡、跨店误伤）。

## 4. Validation & Error Matrix

| 条件 | 结果 |
|---|---|
| `source==2` 且 `wmmtLoginStateIds` 空 | `BusinessException "歪麦监控必须选择歪麦账号"` |
| `source==2` 且账号 id 非当前用户 | `BusinessException "所选歪麦账号不存在或无权使用"` |
| `source==2` | `autoGrab` 强制 false（只通知不抢） |
| 歪麦 `token` 全部失效 | `WmmtTask.fetchStoreInfos` 返回空列表 + warn 日志 |
| 歪麦 STORE_ACTIVITY 当天已推送 | `execute` 经 `checkRepeatToday` 跳过（避免重复推） |

## 5. Good/Base/Bad Cases

- Good：`source==2` + `wmmtLoginStateIds="1,2"` → 用账号1 token 抓取 → 过滤 → 推送 + 写 `store_id=0, uniq_id=X` 历史 → 去重键 `X:promotionId`。
- Base：`source=1`（存量配置不传 source）→ 默认小蚕，行为不变。
- Bad：`findPushedWithinMinutes` 漏 select uniq_id → 歪麦去重键全 `"0:promotionId"` 互相误挡。

## 6. Tests Required

- 后端编译 + `source=2` POST `/api/notify/config` 校验（空账号/非法账号/成功）。
- `findPushedWithinMinutes` 返回含 `uniqId`（小蚕记录 storeId 有值、歪麦 uniqId 有值）。
- 静态兜底：`StoreTask.start`/`MinimumPayService.start` 不执行 source==2 配置。
- 歪麦 STORE_ACTIVITY 当天二次命中被 `checkRepeatToday` 跳过。

## 7. Wrong vs Correct

#### Wrong
```java
// findPushedWithinMinutes 只 select store_id/promotion_id → 歪麦 uniqId 恒 null
lambdaQuery().select(getStoreId, getPromotionId)...
// 去重键退化为 "0:promotionId"，不同门店同 promotionId 互相误伤
```

#### Correct
```java
// select 含 uniq_id；歪麦门店键有值优先
lambdaQuery().select(getStoreId, getPromotionId, getUniqId)...
// WmmtTask 去重键：StringUtils.hasText(e.getUniqId()) ? e.getUniqId() : String.valueOf(e.getStoreId())
```

## 8. Design Decision: 歪麦接入复用 monitor_config

**Context**: 加歪麦数据源，新平台 vs 复用现有监控管线二选一。

**Options**:
1. 独立 `wmmt_monitor_config` 表 + 独立页面 —— 实现量大、双维护。
2. 复用 `monitor_config` + `source` 列 —— 调度/去重/推送/历史全继承。

**Decision**: 复用 `monitor_config` + `source` 列（option 2）。执行体 `WmmtTask extends BaseTask` 仅覆写 `fetchStoreInfos`/`filterStoreInfos`/`cleanupExpired`。

**Extensibility**: 新数据源=加 `source` 码 + 新 `XxxTask extends BaseTask` + 调度分派加分支。autoGrab 未来对歪麦启用时放宽 `addUpdateConfig` 的 source==2 强制 false。