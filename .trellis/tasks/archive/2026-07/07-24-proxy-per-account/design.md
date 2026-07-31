# Design: 代理按账号分配

## Overview

改造 `ProxyHolder` 缓存结构为 `Map<accountKey, CacheEntry>`；`LotteryHttp` / `XiaochanHttp` 在 `executeWithProxy` 时传入账号 key。配置读取与 API 拉取逻辑基本不变。

## Key Design Decisions

| 决策 | 选择 | 理由 |
|------|------|------|
| 缓存 key | `silk_id` 字符串；无账号用 `"shared"` | 两端 HTTP 层都有 silk_id；无需改 DB |
| 全局 vs 仅霸王餐 | 全局 | 用户确认 A |
| 批量预取 | MVP 仍每次 GET 取 data[0] | 改动面小；池化二期 |
| 失效粒度 | `invalidate(key)` + 全量 `invalidate()` | 失败隔离 vs 配置变更 |

## Components

### ProxyHolder
- `record CacheEntry(String[] proxy, long cachedAt)`
- `ConcurrentHashMap` 或同步 Map：`cacheByKey`
- API：
  - `getProxy(String accountKey, boolean force)`
  - `getProxy(boolean force)` → `getProxy("shared", force)` 兼容
  - `invalidate(String accountKey)`：只删该 key
  - `invalidate()`：清空 map + cfg 快照（配置保存用）
- `normalizeKey(String key)`：null/blank/`"0"` → `"shared"`
- 取代理流程与现网一致：锁外 HTTP 拉取，锁内写回；TTL 按 entry.cachedAt

### LotteryHttp
- `postAuth` / `executeWithProxy` 增加 accountKey（来自 `auth.getSilkId()`）
- 失败路径：`ProxyHolder.invalidate(accountKey)` 替代全量 invalidate
- WAF 分支不变

### XiaochanHttp
- `executeWithProxy(reqFn, tag, accountKey)`
- `postWithRes`（无 auth）→ `"shared"`
- `postWithResAuth` → `String.valueOf(auth.getSilkId())`（null 当 shared）

## Data Flow

```
请求(auth.silkId)
  → executeWithProxy(key)
  → ProxyHolder.getProxy(key, force=retry)
       缓存命中 → IP_A
       未命中 → GET api_url → data[0] → 写入 cache[key]
  → setHttpProxy → execute
  → 失败(非WAF) → invalidate(key) → 重试 force=true
```

## Compatibility

- 配置表/设置页/API 契约不变
- 代理未启用仍直连
- 旧调用 `getProxy(force)` 仍可用（shared）

## Risks

- 多账号同时首次 miss 会并发打提取 API：可接受；必要时后续加单飞
- 池子返回重复 IP：无法代码保证绝对不同，仅「尽力」；日志可观察
- 内存：账号数通常个位数～几十，可忽略

## Rollout / Rollback

- 本地 package → 上传 JAR → restart
- 回滚：换回旧 JAR；配置无需回滚
