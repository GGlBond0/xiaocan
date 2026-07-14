# Design：小蚕霸王餐抽奖浏览任务自动完成

## 边界
后端新增"刷霸王餐浏览任务"能力：解析 mini 登录态 → 调 `LotteryInfo` 查未完成浏览任务 → 逐个调 `AddLotteryTimes(type)` 完成浏览任务 → `GetLotteryProgress` 确认机会数上升。前端设置页加"霸王餐刷任务"入口：录入登录态 + 一键触发 + 结果展示。**不碰执行抽奖**（被腾讯防水墙挡）。

## 不改动
- 不动 `GrabAuth` / `GrabLoginStateEntity` / `grab_login_state` 表 / 抢单相关任何代码（隔离风险）。
- 不动 `XiaochanHttp` 现有 public 方法签名（仅追加新 public 方法）。

## 新增文件清单

### 后端
| 文件 | 作用 |
|---|---|
| `model/entity/LotteryAuthEntity.java` | mini 登录态入库实体，`@TableName("lottery_auth")`，软删除 |
| `mapper/LotteryAuthMapper.java` | `extends BaseMapper<LotteryAuthEntity>` |
| `model/dto/LotteryAuthDTO.java` | 录入/更新请求体 `{id?, name, rawHeaders}` |
| `model/dto/LotteryTaskResultVO.java` | 触发结果 `{authName, before:{lottery_count}, after:{lottery_count}, doneTasks:[{type,desc,ok}], failed:[]}` |
| `http/LotteryAuth.java` | mini 登录态 POJO `@Data @Builder`，字段 silkId/userId/sessionId/nami，`isComplete()` 校验 sessionId+silkId |
| `http/LotteryHttp.java` | 小蚕抽奖 RPC 调用类（`new` 实例，参照 XiaochanHttp 模式，自带签名+代理） |
| `service/LotteryService.java` + `service/impl/LotteryServiceImpl.java` | 业务编排：录入登录态解析、一键刷任务流程 |
| `controller/LotteryController.java` | `@RestController("/api/lottery")` 暴露 REST |

### 前端（xiaocan-front-main）
| 文件 | 作用 |
|---|---|
| `src/views/LotteryView.vue` 或并入 `SettingsView.vue` | 录入登录态 + 一键刷任务按钮 + 结果展示 |

### DDL
| 文件 | 作用 |
|---|---|
| `ddl_lottery_auth.sql`（任务 research 目录或项目根） | 建 `lottery_auth` 表 |

## 数据模型

### lottery_auth 表（独立于 grab_login_state）
```sql
CREATE TABLE lottery_auth (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,          -- 标记名
  silk_id BIGINT NOT NULL,             -- x-Teemo / body.silk_id
  user_id BIGINT,                      -- X-Vayne
  session_id VARCHAR(128) NOT NULL,    -- X-Session-Id
  nami VARCHAR(32),                    -- X-Nami（可选，空则随机）
  raw_headers TEXT,                    -- 原始粘贴内容（留底）
  expire_at DATETIME,                  -- 估算过期（若有线索，否则 null）
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);
```
> 不复用 grab_login_state：那套是 Android 抢单登录态（强依赖 X-Sivir JWT），mini 登录态无 X-Sivir，语义不同，另建避免 `isComplete` 互扰。

### LotteryAuth POJO
```java
@Data @Builder
public class LotteryAuth {
    private Integer silkId;    // x-Teemo
    private Long userId;       // X-Vayne
    private String sessionId;  // X-Session-Id
    private String nami;        // 可空→随机
    public boolean isComplete() { return silkId != null && sessionId != null && !sessionId.isEmpty(); }
}
```

## 核心契约

### RPC 调用（LotteryHttp，参照 XiaochanHttp 抓包实测值）
| 方法 | serverName.methodName | body | 用途 |
|---|---|---|---|
| `lotteryInfo(auth)` | SilkwormLotteryMobile.LotteryInfo | {silk_id,app_id:20} | 查 is_view_xxx 未完成项 |
| `addLotteryTimes(auth,type)` | SilkwormLotteryMobile.AddLotteryTimes | {silk_id,type,app_id:20} | 完成浏览任务+1机会 |
| `getLotteryProgress(auth)` | SilkwormLotteryMobile.GetLotteryProgress | {silk_id,app_id:20} | 读 lottery_count |
| `userTaskV2(auth)` | ActivityTaskMobileService.UserTaskV2 | {silk_id,app_id:20} | 任务/积分状态(可选) |

### 签名与 header（复刻 XiaochanHttp，已实测通过）
```
tms = currentTimeMillis()
nami = auth.nami ?: genNami(silkId)        // UUID去横线插入silk_id→16位hex
ashe = md5( md5((server+"."+method).toLowerCase()) + tms + nami )   // 32位hex
headers: serverName, methodName, appid=20, X-Ashe, X-Nami, X-Garen=tms,
         X-Platform=mini, X-Version=3.18.3.37, X-Session-Id, X-Model, x-Teemo=silkId,
         X-Vayne=userId, x-City=0, Content-Type=application/json
```
代理/403 重试复用 `ProxyHolder` + `executeWithProxy` 模式（LotteryHttp 内自建一份，或把 XiaochanHttp 的 executeWithProxy 抽公共——本任务倾向 LotteryHttp 自建以隔离改动）。

### type → 浏览任务映射（待实现时补全）
仅实测 `type:10` 一种。`LotteryInfo.lottery_info` 的 `is_view_xxx` 标志位对应不同浏览任务（view_bwc_page/view_welfare_page/view_tp_ad/view_douyin_mall 等），每个对应一个 `AddLotteryTimes` 的 type 值。
**实现阶段必须先补全 type 枚举**：
- 方式：在小程序逐个点浏览任务时抓包，记录每个 `AddLotteryTimes` 的 type。
- 兜底：若无法补全全部 type，先实现已知的 type=10，其余在前端列为"待补抓"项，可逐步加。
- type 枚举存为 `LotteryHttp` 内静态常量 Map 或配置，便于后续扩展。

## 一键刷任务流程（LotteryServiceImpl）
```
runTask(authId):
  auth = lotteryAuthMapper.selectById(authId); 校验 isComplete
  before = lotteryHttp.getLotteryProgress(auth).lottery_count
  info = lotteryHttp.lotteryInfo(auth)
  // 遍历 is_view_xxx==false 的浏览任务，按 type 映射调 addLotteryTimes
  for each unfinished browse task (type 已知):
     r = lotteryHttp.addLotteryTimes(auth, type)
     记录 {type, desc, ok=(code==0)}
  after = lotteryHttp.getLotteryProgress(auth).lottery_count
  return {authName, before, after, doneTasks, failed}
```
> 若 `AddLotteryTimes` 实际不改 lottery_count（风控判无效），after==before，前端如实展示，不造假。

## REST 接口（LotteryController @ /api/lottery）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /auth/list | 登录态列表 |
| POST | /auth | 录入/更新（body=LotteryAuthDTO，解析 rawHeaders） |
| DELETE | /auth/{id} | 删除 |
| POST | /run | 一键刷任务（body={authId}）→ LotteryTaskResultVO |

### rawHeaders 解析（复用 GrabServiceImpl.saveLoginState 模式）
粘贴内容含小蚕 mini 抓包 header，正则解析：
- `X-Session-Id` → sessionId
- `x-Teemo` → silkId
- `X-Vayne` → userId
- `X-Nami` → nami（可选）
- body 里 `silk_id` 作为 silkId 兜底校验

## 前端（SettingsView.vue 追加区块 或 新 LotteryView.vue）
- 登录态管理：列表 + 录入框（粘贴抓包原文）+ 删除
- "一键刷霸王餐浏览任务"按钮 → POST /api/lottery/run
- 结果展示：任务清单（type/描述/成功否）、前后机会数对比

## 兼容性 / 回滚
- 全部新增文件 + 新建表，不改既有表/既有类签名 → 回滚=删除新增 + DROP 表，零副作用。
- mitmproxy-mcp 已注册但未生效（需重启会话）；本任务实现阶段若需重放可重启启用，不影响实现。

## 待实现时验证点
1. type 枚举补全（补抓浏览任务 RPC）。
2. `AddLotteryTimes` 跑通后 `lottery_count` 是否真涨（风控有效性）。
3. mini 登录态过期表现（接口返回什么 code），是否需刷新机制。
