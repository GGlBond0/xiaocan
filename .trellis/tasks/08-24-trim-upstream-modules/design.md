# 精简上游模块 - 技术设计

## 背景与目标
见 `prd.md`。核心：删除收藏/库存历史/消息批量/小蚕美团赏金/聚合搜索展示层，**保留歪麦（Wmmt）数据层作为后续监控/抢单地基**，精简项目。

## 依赖分析结论（依据全仓调查，file:line 见 agent 输出）

### 删除安全性判定
| 模块 | 孤立性 | 判定 |
|---|---|---|
| 消息批量 MessageService/MessageBatchRecord | `queueMessage` 全仓无调用方；实现反向依赖待删 Favorite / 待保留 StorePushedHistory | 完全孤立，可整删 |
| 收藏 Favorite | 只有展示 Controller + 被 Wmmt/XiaoChan 注入 | 展示孤岛，可删，需拆两处注入 |
| 库存历史 StoreInventoryHistory | 被 XiaoChan/Wmmt 注入 `insertBatch`（副作用），无其它读点 | 可删，需拆两处注入 |
| 小蚕美团赏金 XC_MTSJ | `getXcMeituanshangjinPageVO` 仅被待删类（Favorite/StoreSearch）调用 | 可删方法级，XiaoChanService 接口同步删 |
| 聚合搜索 StoreSearch | 纯展示孤岛，无外部 import | 整删 |
| WmmtController | 纯展示，无核心入口引用 | 整删 |
| **抢单/监控核心（Grab/AutoGrab/Monitor/StoreTask/MinimumPay/BaseTask）** | 不 import 任何待删类 | 完全不受影响 |

### 关键牵连（编译断点，必须先拆）
1. `XiaoChanServiceImpl`：注入 FavoriteStoreService(l8,55-57)、StoreInventoryHistoryService(l9,53) + 调用 fillFavoriteIds(l142)/insertBatch(l99)；getXcMeituanshangjinPageVO(l129-145)
2. `WmmtServiceImpl`：注入 FavoriteStoreService(l42)、StoreInventoryHistoryService(l36) + fillFavoriteIds(l50)/insertBatch(l65)
3. `XiaoChanService` 接口：getXcMeituanshangjinPageVO 签名(l65) + 两个 import
4. `XiaochanHttp`：getMeituanList/searchMeituanList/parseMeituanListBody 三个方法(l77-168)

### 保留/删除字段边界（StoreInfo）
- **必须保留**（抢单/监控核心读点）：tpStorePlatform、storePlatformOrderMoney、promotionSilkAmount、storeCategorySubType、promotionType、storeId、cityCode（GrabServiceImpl/XiaochanHttp/OrderExchangeReq 在用）
- **可删**（仅收藏用，随收藏删除）：favoriteId、exists
- **保留但回收展示端读点**：uniqId、storeTypeEnum、distanceStr、rebateRatio、rebateMax、rebateConditionStr（写入点全在 WmmtHttp/XiaochanHttp 地基，读点在待删 MessageService/StoreSearch 等）

### 孤儿列/字段
- `store_pushed_history.batch_id` 列 + `StorePushedHistoryEntity.batchId`：唯一读点 MessageBatchRecordServiceImpl:67（随删）→ 可撤
- `StorePushedHistoryEntity` 的 locationId/uniqId/storeTypeEnum/favoriteId（@TableField exist=false）：读点全在收藏 → 可撤
- `user.waimai_token`：歪麦地基 WmmtServiceImpl 在用 → **必须保留**

### 保留清单（地基，不动）
WmmtHttp、WmmtService(Impl) 的 getShopList（去两个注入）、WmmtShopListDTO、WmPageVO、UserEntity.waimaiToken、StoreTypeEnum、StoreConstant、ImageProxyController、PageConvertUtil、SimpleStoreInfo（或一并删，无引用）、BookVO/IgnoreStoreVO（无引用孤立，可删）。

## 设计决策

**D1 删除范围**：5 块模块文件整删 + WmmtController 整删 + XiaochanHttp 3 方法删 + StoreInfo 的 favoriteId/exists 删 + StorePushedHistory 5 字段删 + 孤立 VO（BookVO/IgnoreStoreVO/SimpleStoreInfo）评估删。

**D2 保留边界**：WmmtHttp/WmmtService(Impl) 地基完整保留；拆掉 WmmtServiceImpl 对 Favorite/库存历史的两次注入；`getShopList` 保留（WmmtHttp↔前端契约）。**`fetchWmStoreInfos` 与 `WmmtController` 用户决定保留**（不做删除对象，前端契约不变）。

**D3 StoreTypeEnum 处理**：保留枚举本体（WM_MANJIAN/WM_MTSJ 是 WmmtHttp 地基写入点），但随删除收敛读点。

**D4 DDL 处理**：只改仓库 ddl.sql 源码文本，删三表 + store_pushed_history.batch_id 段；保留 user.waimai_token；**不动生产库**（生产升级另作决策，遵循「运行期去服务器」守则但本次只动源码）。

**D5 XiaoChanService/XiaochanHttp 收窄**：接口删 getXcMeituanshangjinPageVO；XiaochanHttp 删美团赏金三方法，保留 getList/searchList/getStorePromotionDetail/orderExchange 等小蚕核心。

**D6 编译验证**：本地 JDK17+Maven 绝对路径 `mvn package`，不在生产跑。

## 风险与缓解
- **字段误删导致编译炸**：已按读点逐字段核实，删除顺序严格按「先拆注入点 → 再删文件 → 再精 simple 字段」。
- **StorePushedHistoryServiceImpl 经 BeanUtils 承载 storeTypeEnum**：删字段前确认该 Service 无显式读写该字段（agent 确认无保留读点），BeanUtils 静默拷贝不报错。
- **前端契约破坏**：WmmtController 删后前端若仍调 /api/wmmt/shopList 会 404。需在 implement.md 标注「同步告知前端 / 前端页面若保留则接口 404 待前端改」。本任务按后端精简为准，前端另行处理。

## 回滚
- git 层面：删除是一个 commit，`git revert` 可整体回退。不涉及生产，无数据风险。
- 分支策略：提交留在 feat/upstream-modules，暂不合并 main；合并在删除验证通过后另行决策。