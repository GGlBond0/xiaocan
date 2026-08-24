# 精简上游模块 - 执行计划

> 前置：`prd.md` + `design.md` 已就绪。执行前请用户 review。

## 关键路径（按依赖顺序执行）

### Step 1：拆编译断点（保留文件先去依赖）
- [x] (designed) `XiaoChanService` 接口：删 `getXcMeituanshangjinPageVO` 签名 + XcMeituanshangjinDTO/PageVO import
- [x] (designed) `XiaoChanServiceImpl`：摘 3 处注入 + 2 处调用（fillFavoriteIds/insertBatch）+ 删 getXcMeituanshangjinPageVO 方法 + import 清理
- [x] (designed) `WmmtServiceImpl`：摘 2 处注入 + 2 处调用（fillFavoriteIds/insertBatch）+ import 清理
- [x] (designed) `XiaochanHttp`：删 getMeituanList(l77-89)/searchMeituanList(l100-113)/parseMeituanListBody(l119-168)

### Step 2：整删 5 块模块文件 + WmmtController + 孤立 VO
- [ ] 收藏：`FavoriteStoreController/Service/ServiceImpl/Mapper/Entity` + `FavoriteStoreQueryDTO/SaveFavoriteDTO/RemoveFavoriteDTO`
- [ ] 库存历史：`StoreInventoryHistoryController/Service/ServiceImpl/Mapper/Entity/VO`
- [ ] 消息批量：`MessageService/MessageBatchRecordService/ServiceImpl/Mapper/Entity`
- [ ] 美团赏金：`XcMeituanshangjinDTO/XcMeituanshangjinPageVO`
- [ ] 聚合搜索：`StoreSearchController/Service/ServiceImpl/DTO`
- [ ] 孤立 VO（无引用）：`SimpleStoreInfo/BookVO/IgnoreStoreVO`（确认无引用后删）
- [ ] **保留**：`WmmtController` / `fetchWmStoreInfos`（用户决定留，不作为删除对象；前端契约不变）

### Step 3：精简 StoreInfo / StorePushedHistoryEntity / VO / DDL
- [ ] `StoreInfo`：删 `favoriteId`、`exists`（仅收藏用）；保留其余字段（含 uniqId/storeTypeEnum/distanceStr/rebateRatio 等，见 design D）
- [ ] `StorePushedHistoryEntity`：删 `batchId/locationId/uniqId/storeTypeEnum/favoriteId` 5 字段（读点全在待删模块）
- [ ] `StorePushedHistoryVO`：删同 5 字段（如存在同名）
- [ ] `ddl.sql`：删 `favorite_store/store_inventory_history/message_batch_record` 三表段 + `store_pushed_history.batch_id` ALTER 段；**保留 `user.waimai_token`**

### Step 4：编译验证
- [ ] grep 复核无残留引用：`Favorite|StoreInventoryHistory|MessageBatch|StoreSearch|XcMeituanshangjin|gjt|queueMessage|getXcMeituanshangjin`
- [ ] 本地 `mvn package`（JDK17/Maven 绝对路径，非生产）。命令见下。

## 验证命令
```powershell
# 编译（本机绝对路径，见记忆 local-build-toolchain）
# 切到后端目录后：
& "C:\path\to\mvn" -q clean package -DskipTests
# 若编译报错，逐个按残留引用修
# 残留引用扫描
git grep -n -E "FavoriteStore|StoreInventoryHistory|MessageBatchRecord|MessageService|StoreSearch|XcMeituanshangjin"
```

## Review Gates
1. **执行前**：本 implement.md + design.md + prd.md 用户确认
2. **Step 2 后**：编译中途验证一次（可选）
3. **Step 4 后**：`mvn package` 绿 + 无残留引用 → 才算完成

## 回滚点
- Step 2 前：任何问题直接 `git checkout -- <file>` 恢复
- 整体：一个 commit，`git revert` 可回退
- 全程不碰生产库/生产服务器

## 遗留&用户已拍板
- ✅ `WmmtServiceImpl.fetchWmStoreInfos` — 用户决定**保留**（虽是展示辅助，用户选择留）
- ✅ `WmmtController` — 用户决定**保留**（前端契约不变，不 404）
- `user.waimai_token` 保留（歪麦地基）
- `MessageService` 删除后前端若曾调用相关推送则不可用（本地监控推送不依赖它，已确认无调用方）