# 强耦合网合入专项实现计划（歪麦/收藏/库存/搜索/消息）

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development 或 superpowers:executing-plans 逐任务实施。步骤用 `- [ ]` 勾选跟踪。

**Goal:** 把上游 lyrric/xiaochan 的五模块（歪麦/收藏/库存历史/门店搜索/消息批量）作为独立能力叠加进本地二开底座，不改本地抢单/去重/3km/登录态/代理/监控任务链。

**Architecture:** 最小侵入加法移植。分层 L0（地基/补字段）→ L1（基础设施服务）→ L2（上游 HTTP 层）→ L3（上层业务服务）→ L4（控制器）。每层编译验证再进下一层，最后全量编译 + 增 schema 迁移脚本。

**Tech Stack:** Spring Boot 3 / MyBatis-Plus / fastjson2 / hutool / Lombok / JDK17 / Maven（本地编译，绝不生产跑 mvn）。

**Spec:** `.trellis/spec/backend/upstream-modules-merge.md`（随计划一起读）。

## Global Constraints
- **本地主权区一律不动**：`GrabService*`/`AutoGrabService*`/`OrderExchangeReq`/`GrabConfigEntity`/`GrabHistoryEntity`、`login_state`(LoginStateEntity/Service)、`ProxyHolder`/`executeWithProxy`、`BaseTask`/`StoreTask`/`MinimumPayService`、`StorePushedHistory*` 现有实现、`store_pushed_history` 现有表结构。**绝不整文件替换这些文件**。
- **StoreInfo 只做加法**：新增 `uniqId/storeTypeEnum/distanceStr/rebateRatio/rebateMax/rebateConditionStr/favoriteId/exists`；保留 `storeId/promotionId(Integer)/distance/rebateCondition/openHours/ifNew`。
- **UserEntity 加 `waimaiToken`**（列 `waimai_token`）；保留 `xc*`/`notifyDedupMinutes`。
- **promotionId 保持 Integer**（不随上游改 String）。
- **XiaochanHttp 保持实例方法 + new 实例 + 代理**；上游 static 调用改实例调用；新增抓取（美团赏金）走本地 `executeWithProxy`。
- 新增文件从 `upstream/main` 导出后**据上列规则做编译适配**，非原样照抄。
- 本地编译：`JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"` + `/c/D/tools/apache-maven-3.9.16/bin/mvn -o -DskipTests compile`。

---

## 文件结构（新增 / 修改）

**L0 地基**
- Modify `src/main/java/io/github/xiaocan/utils/PageConvertUtil.java`：加 `convertList`
- Create `src/main/java/io/github/xiaocan/config/SystemConfig.java`
- Modify `src/main/java/io/github/xiaocan/model/StoreInfo.java`：+8 字段
- Modify `src/main/java/io/github/xiaocan/model/entity/UserEntity.java`：+`waimaiToken`
- Modify `src/main/java/io/github/xiaocan/config/MybatisPlusConfig.java`：+`TransactionTemplate` bean

**L1 基础设施服务（新增）**
- `service/MessageBatchRecordService.java` + `impl/MessageBatchRecordServiceImpl.java` + `mapper/MessageBatchRecordMapper.java` + `model/entity/MessageBatchRecordEntity.java`
- `service/StoreInventoryHistoryService.java` + `impl/StoreInventoryHistoryServiceImpl.java` + `mapper/StoreInventoryHistoryMapper.java` + `model/entity/StoreInventoryHistoryEntity.java` + `model/vo/StoreInventoryHistoryVO.java` + `model/vo/BookVO.java` + `model/vo/IgnoreStoreVO.java`
- `service/FavoriteStoreService.java` + `impl/FavoriteStoreServiceImpl.java` + `mapper/FavoriteStoreMapper.java` + `model/entity/FavoriteStoreEntity.java` + `model/SimpleStoreInfo.java` + `model/dto/{SaveFavoriteDTO,FavoriteStoreQueryDTO,RemoveFavoriteDTO}.java`

**L2 上游 HTTP 层（新增/修改）**
- Modify `src/main/java/io/github/xiaocan/http/XiaochanHttp.java`：+`searchMeituanList`/`getMeituanList`（实例方法+代理）
- Create `src/main/java/io/github/xiaocan/http/WmmtHttp.java` + `model/dto/WmmtShopListDTO.java` + `model/vo/WmPageVO.java` + `model/dto/XcMeituanshangjinDTO.java` + `model/vo/XcMeituanshangjinPageVO.java`

**L3 上层业务服务（新增/修改）**
- Modify `service/XiaoChanService.java` + `impl/XiaoChanServiceImpl.java`：+`getXcMeituanshangjinPageVO`、fillFavoriteIds、static→实例
- Create `service/WmmtService.java` + `impl/WmmtServiceImpl.java`
- Create `service/StoreSearchService.java` + `impl/StoreSearchServiceImpl.java` + `model/dto/StoreSearchDTO.java`
- Create `service/MessageService.java`

**L4 控制器（新增）**
- `controller/WmmtController.java`、`controller/StoreSearchController.java`、`controller/FavoriteStoreController.java`、`controller/StoreInventoryHistoryController.java`

**Schema 增量**
- Modify `ddl.sql`：仅新增 `favorite_store`、`store_inventory_history`(+sku)、`message_batch_record` 三表 + `user.waimai_token` 列。

---

### Task 1: L0 地基 — PageConvertUtil.convertList + SystemConfig + TransactionTemplate

**Files:**
- Modify: `src/main/java/io/github/xiaocan/utils/PageConvertUtil.java`
- Create: `src/main/java/io/github/xiaocan/config/SystemConfig.java`
- Modify: `src/main/java/io/github/xiaocan/config/MybatisPlusConfig.java`

**Interfaces:**
- Produces: `PageConvertUtil.convertList(List<?>, Class<T>) -> List<T>`；`SystemConfig.getWebUrl() -> String`；可注入 `TransactionTemplate`

- [ ] **Step 1: PageConvertUtil 加 convertList**

在本地 `PageConvertUtil.java` 的 `convert` 方法后追加（与上游一致）：

```java
public static <T> List<T> convertList(List<?> sourceList, Class<T> clazz) {
    List<T> list = new ArrayList<>();
    sourceList.forEach(item -> {
        try {
            T t = clazz.getDeclaredConstructor().newInstance();
            BeanUtils.copyProperties(item, t);
            list.add(t);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                 NoSuchMethodException e) {
            log.error(e.getMessage(), e);
            throw new BusinessException("数据转换失败");
        }
    });
    return list;
}
```

（确认文件已有 `java.util.ArrayList`、`java.util.List`、`BusinessException`、`BeanUtils`、`InvocationTargetException` import；没有则补。）

- [ ] **Step 2: 新建 SystemConfig**

```java
package io.github.xiaocan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SystemConfig {
    @Value("${system.web-url:}")
    private String webUrl;
    public String getWebUrl() { return webUrl; }
}
```

- [ ] **Step 3: MybatisPlusConfig 加 TransactionTemplate bean**

在本地 `MybatisPlusConfig.java` 加：

```java
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Bean
public TransactionTemplate transactionTemplate(PlatformTransactionManager ptm) {
    return new TransactionTemplate(ptm);
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn -o -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/xiaocan/utils/PageConvertUtil.java src/main/java/io/github/xiaocan/config/SystemConfig.java src/main/java/io/github/xiaocan/config/MybatisPlusConfig.java
git commit -m "feat(merge): L0地基 - PageConvertUtil.convertList/SystemConfig/TransactionTemplate"
```

---

### Task 2: L0 — StoreInfo 加 8 字段 + UserEntity 加 waimaiToken

**Files:**
- Modify: `src/main/java/io/github/xiaocan/model/StoreInfo.java`
- Modify: `src/main/java/io/github/xiaocan/model/entity/UserEntity.java`

**Interfaces:**
- Produces: `StoreInfo.getUniqId()/setUniqId(String)`、`getStoreTypeEnum()/setStoreTypeEnum(StoreTypeEnum)`、`getDistanceStr()/setDistanceStr(String)`、`getRebateRatio()/setRebateRatio(BigDecimal)`、`getRebateMax()/setRebateMax(BigDecimal)`、`getRebateConditionStr()/setRebateConditionStr(String)`、`getFavoriteId()/setFavoriteId(Long)`、`getExists()/setExists(Boolean)`；`UserEntity.getWaimaiToken()/setWaimaiToken(String)`

- [ ] **Step 1: StoreInfo 加 8 字段**

在本地 `StoreInfo.java` 加字段（保留所有现有字段不动）：

```java
private String uniqId;                 // storeId or wm_poi_id
private StoreTypeEnum storeTypeEnum;
private String distanceStr;            // 带单位距离, 如 1.2km
private BigDecimal rebateRatio;        // 返现百分比(美团赏金)
private BigDecimal rebateMax;          // 最高返现金额(美团赏金)
private String rebateConditionStr;     // 返现条件(字符串)
private Long favoriteId;
private Boolean exists;
```

`StoreTypeEnum` 已在 `model/enums/`（上批已合）。补 `import io.github.xiaocan.model.enums.StoreTypeEnum`、`import java.math.BigDecimal`、`import lombok.Data`（确认已带 lombok）。

加 `setDistance`/`setDistanceStr` 互转 setter（与上游一致，可选，若本地已有 setter 则在其基础上补 distanceStr 同步）：

```java
public void setDistanceStr(String distanceStr) {
    this.distanceStr = distanceStr;
    if (distanceStr != null && this.distance == null) {
        // 解析 "500m"/"1.5km" 到 distance(米)
        String lower = distanceStr.trim().toLowerCase();
        if (lower.endsWith("km")) {
            this.distance = (int)(Double.parseDouble(lower.replace("km","")) * 1000);
        } else if (lower.endsWith("m")) {
            this.distance = (int)Double.parseDouble(lower.replace("m",""));
        }
    }
}
```

- [ ] **Step 2: UserEntity 加 waimaiToken**

在本地 `UserEntity.java` 加：

```java
private String waimaiToken;   // 歪麦token, 映射列 waimai_token
```

（Lombok `@Data` 自动生成 getter/setter；列名默认驼峰→`waimai_token`，与上游 DDL 一致。）

- [ ] **Step 3: 编译验证**

Run: `mvn -o -DskipTests compile`
Expected: SUCCESS（StoreInfo/UserEntity 现有调用点不受影响，因只加字段）

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/xiaocan/model/StoreInfo.java src/main/java/io/github/xiaocan/model/entity/UserEntity.java
git commit -m "feat(merge): L0地基 - StoreInfo加8字段/UserEntity加waimaiToken"
```

---

### Task 3: L1 — 消息批量 + 库存历史 基础设施

**Files:**
- Create: `model/entity/MessageBatchRecordEntity.java`、`mapper/MessageBatchRecordMapper.java`、`service/MessageBatchRecordService.java`、`service/impl/MessageBatchRecordServiceImpl.java`
- Create: `model/entity/StoreInventoryHistoryEntity.java`、`model/vo/StoreInventoryHistoryVO.java`、`model/vo/BookVO.java`、`model/vo/IgnoreStoreVO.java`、`mapper/StoreInventoryHistoryMapper.java`、`service/StoreInventoryHistoryService.java`、`service/impl/StoreInventoryHistoryServiceImpl.java`

**Interfaces:**
- Produces: `MessageBatchRecordService.recordBatch(Long userId, List<String> batchIds)`；`StoreInventoryHistoryService.insertBatch(List<StoreInfo>)`、`listTodayByUniqueId(String uniqId)`

- [ ] **Step 1: 从 upstream 导出消息批量实体/服务**

```bash
for f in \
  model/entity/MessageBatchRecordEntity.java \
  mapper/MessageBatchRecordMapper.java \
  service/MessageBatchRecordService.java \
  service/impl/MessageBatchRecordServiceImpl.java ; do
  git show "upstream/main:src/main/java/io/github/xiaocan/$f" > "src/main/java/io/github/xiaocan/$f"
done
```

- [ ] **Step 2: 从 upstream 导出库存历史实体/服务**

```bash
for f in \
  model/entity/StoreInventoryHistoryEntity.java \
  model/vo/StoreInventoryHistoryVO.java \
  model/vo/BookVO.java \
  model/vo/IgnoreStoreVO.java \
  mapper/StoreInventoryHistoryMapper.java \
  service/StoreInventoryHistoryService.java \
  service/impl/StoreInventoryHistoryServiceImpl.java ; do
  git show "upstream/main:src/main/java/io/github/xiaocan/$f" > "src/main/java/io/github/xiaocan/$f"
done
```

- [ ] **Step 3: 编译适配**

Run: `mvn -o -DskipTests compile`
若失败，逐类核对并修复：
- `MessageBatchRecordServiceImpl` 若依赖 `FavoriteStoreServiceImpl.fillFavoriteIdsForPushedHistory`（L1 尚未建）→ 把该依赖暂留待 Task 4/5，或先让接口方法存在。
- 若 `MessageBatchRecordServiceImpl` 依赖 `StorePushedHistoryVO.getLocationId()`（本地无）→ 见 Task 5 局部适配。**本 Task 可先不引入该依赖方法调用，若编译失败则把 `fillFavoriteIdsForPushedHistory` 调用点延后**。
- `StoreInventoryHistoryEntity`/`ServiceImpl` 用到 `StoreInfo.getStoreTypeEnum/getUniqId/getRebateRatio/getRebateMax`（Task 2 已加）→ 应已满足。
- `StoreInventoryHistoryServiceImpl` 注入 `TransactionTemplate`（Task 1 已加 bean）→ 应已满足。

只要本层不依赖 FavoriteStore/StoreSearch/Wmmt 的，编译应过。**若 Task 3 编译被强耦合阻塞，调整顺序为 Task 4（FavoriteStore）先行或同层合并**，以"可编译"为准，计划允许此调整。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/xiaocan/model/entity/MessageBatchRecordEntity.java src/main/java/io/github/xiaocan/mapper/MessageBatchRecordMapper.java src/main/java/io/github/xiaocan/service/MessageBatchRecordService.java src/main/java/io/github/xiaocan/service/impl/MessageBatchRecordServiceImpl.java src/main/java/io/github/xiaocan/model/entity/StoreInventoryHistoryEntity.java src/main/java/io/github/xiaocan/model/vo/StoreInventoryHistoryVO.java src/main/java/io/github/xiaocan/model/vo/BookVO.java src/main/java/io/github/xiaocan/model/vo/IgnoreStoreVO.java src/main/java/io/github/xiaocan/mapper/StoreInventoryHistoryMapper.java src/main/java/io/github/xiaocan/service/StoreInventoryHistoryService.java src/main/java/io/github/xiaocan/service/impl/StoreInventoryHistoryServiceImpl.java
git commit -m "feat(merge): L1 - 消息批量/库存历史基础设施"
```

---

### Task 4: L1 — 收藏 FavoriteStore 基础设施

**Files:**
- Create: `model/entity/FavoriteStoreEntity.java`、`model/SimpleStoreInfo.java`、`model/dto/{SaveFavoriteDTO,FavoriteStoreQueryDTO,RemoveFavoriteDTO}.java`、`mapper/FavoriteStoreMapper.java`、`service/FavoriteStoreService.java`、`service/impl/FavoriteStoreServiceImpl.java`

**Interfaces:**
- Produces: `FavoriteStoreService.fillFavoriteIdsForPushedHistory(...)`、`fillFavoriteIds(List<StoreInfo>)`、`getShopList` 所需能力

- [ ] **Step 1: 导出收藏相关文件**

```bash
for f in \
  model/entity/FavoriteStoreEntity.java \
  model/SimpleStoreInfo.java \
  model/dto/SaveFavoriteDTO.java \
  model/dto/FavoriteStoreQueryDTO.java \
  model/dto/RemoveFavoriteDTO.java \
  mapper/FavoriteStoreMapper.java \
  service/FavoriteStoreService.java \
  service/impl/FavoriteStoreServiceImpl.java ; do
  git show "upstream/main:src/main/java/io/github/xiaocan/$f" > "src/main/java/io/github/xiaocan/$f"
done
```

- [ ] **Step 2: 编译适配**

Run: `mvn -o -DskipTests compile`
`FavoriteStoreServiceImpl` 依赖：`StoreInfo.getUniqId/getStoreTypeEnum/favoriteId/exists`（Task2 已加）、`XiaoChanService.getXcMeituanshangjinPageVO`（L3，未建）、`WmmtService.getShopList`（L3，未建）、`StorePushedHistoryVO.getLocationId/getUniqId`（本地无）。

**适配规则**（本 Task 可先只建依赖链骨架）：
- 若 `FavoriteStoreServiceImpl` 调 `xiaoChanService.getXcMeituanshangjinPageVO`/`wmmtService.getShopList` → 这些方法到 L3 才存在。**本 Task 先把 FavoriteStoreServiceImpl 里这两处调用所在的私有方法暂留接口存在**，或在 L3 前让服务接口声明占位方法。**允许编译不过时把这两处调用点注释并留 TODO，待 L3 恢复**，以保持本 Task 可编译交付。
- `StorePushedHistoryVO.getLocationId/getUniqId/getStoreTypeEnum` → 见 Task 5 局部适配；本 Task 若被它阻塞，把该调用点延后。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/xiaocan/model/entity/FavoriteStoreEntity.java src/main/java/io/github/xiaocan/model/SimpleStoreInfo.java src/main/java/io/github/xiaocan/model/dto/SaveFavoriteDTO.java src/main/java/io/github/xiaocan/model/dto/FavoriteStoreQueryDTO.java src/main/java/io/github/xiaocan/model/dto/RemoveFavoriteDTO.java src/main/java/io/github/xiaocan/mapper/FavoriteStoreMapper.java src/main/java/io/github/xiaocan/service/FavoriteStoreService.java src/main/java/io/github/xiaocan/service/impl/FavoriteStoreServiceImpl.java
git commit -m "feat(merge): L1 - 收藏FavoriteStore基础设施"
```

---

### Task 5: 局部适配 — StorePushedHistoryVO 加 locationId/uniqId/storeTypeEnum（可选，按编译需要）

**Files:**
- Modify: `src/main/java/io/github/xiaocan/model/vo/StorePushedHistoryVO.java`、`src/main/java/io/github/xiaocan/model/entity/StorePushedHistoryEntity.java`

**Interfaces:**
- Produces: `StorePushedHistoryVO.getLocationId()/getUniqId()/getStoreTypeEnum()`

- [ ] **Step 1: 只在 VO/Entity 加字段（不动表结构/现有逻辑）**

在本地 `StorePushedHistoryVO.java` 加（lombok）：
```java
private Long locationId;
private String uniqId;
private StoreTypeEnum storeTypeEnum;
private Long favoriteId;
```
若 `FavoriteStoreServiceImpl` 需要 Entity 也有对应字段，则 `StorePushedHistoryEntity.java` 加同样字段（lombok）。**这些字段仅作 VO 展示/收藏关联读取，不写库**（不新增列，除非 DDL 需要）。

- [ ] **Step 2: 编译验证**

Run: `mvn -o -DskipTests compile`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/xiaocan/model/vo/StorePushedHistoryVO.java src/main/java/io/github/xiaocan/model/entity/StorePushedHistoryEntity.java
git commit -m "feat(merge): L0 - StorePushedHistoryVO/Entity加locationId/uniqId/storeTypeEnum字段"
```

---

### Task 6: L2 — 上游 HTTP 层（XiaochanHttp 美团赏金 + WmmtHttp）

**Files:**
- Modify: `src/main/java/io/github/xiaocan/http/XiaochanHttp.java`（+`searchMeituanList`/`getMeituanList`）
- Create: `src/main/java/io/github/xiaocan/http/WmmtHttp.java`、`model/dto/WmmtShopListDTO.java`、`model/vo/WmPageVO.java`、`model/dto/XcMeituanshangjinDTO.java`、`model/vo/XcMeituanshangjinPageVO.java`

**Interfaces:**
- Produces: 实例方法 `XiaochanHttp.searchMeituanList(lng,lat,name,pvId)` / `getMeituanList(lng,lat,pvId)`（走 executeWithProxy）；`WmmtHttp.getShopList(token, city, dto)`（静态，直连）

- [ ] **Step 1: 导出上游 DTO/VO**

```bash
for f in WmmtShopListDTO WmPageVO XcMeituanshangjinDTO XcMeituanshangjinPageVO ; do
  # 找出实际路径
  file=$(git ls-tree -r --name-only upstream/main | grep -i "$f.java")
  [ -n "$file" ] && git show "upstream/main:$file" > "$(echo $file | sed 's#^src/main/java/##' | sed 's#^##')"
done
```
（用 `git ls-tree -r --name-only upstream/main | grep -iE "WmmtShopListDTO|WmPageVO|XcMeituanshangjin"` 先列真实路径，再逐个导出。）

- [ ] **Step 2: XiaochanHttp 加美团赏金实例方法**

从上游 `XiaochanHttp.getMeituanList/searchMeituanList/parseMeituanListBody` 移植，但**改为本地实例方法 + 走 `executeWithProxy(..., ProxyHolder.SHARED_KEY)`**，不用上游 direct 直连。签名：
```java
public List<StoreInfo> getMeituanList(String lng, String lat, String pvId)
public List<StoreInfo> searchMeituanList(String lng, String lat, String name, String pvId)
```
产出 `StoreInfo` 时设 `storeTypeEnum = StoreTypeEnum.XC_MTSJ`、`uniqId`、`rebateRatio/rebateMax` 等（Task2 字段已就绪）。

- [ ] **Step 3: WmmtHttp 编译**

`WmmtHttp` 用 `StoreInfo` 的 `uniqId/storeTypeEnum/rebateRatio/...` setter（Task2 已有）。若上游对 `promotionId` 赋 String 值到 Integer 冲突，改为 `.promotionId(Integer.parseInt(...))` 或豁免（歪麦 sku 的 skuId 另存）。

- [ ] **Step 4: 编译验证**

Run: `mvn -o -DskipTests compile`
Expected: SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/xiaocan/http/XiaochanHttp.java src/main/java/io/github/xiaocan/http/WmmtHttp.java src/main/java/io/github/xiaocan/model/dto/WmmtShopListDTO.java src/main/java/io/github/xiaocan/model/vo/WmPageVO.java src/main/java/io/github/xiaocan/model/dto/XcMeituanshangjinDTO.java src/main/java/io/github/xiaocan/model/vo/XcMeituanshangjinPageVO.java
git commit -m "feat(merge): L2 - XiaochanHttp美团赏金实例方法+WmmtHttp"
```

---

### Task 7: L3 — WmmtService + StoreSearchService + XiaoChanService 增强

**Files:**
- Create: `service/WmmtService.java`、`service/impl/WmmtServiceImpl.java`、`service/StoreSearchService.java`、`service/impl/StoreSearchServiceImpl.java`、`model/dto/StoreSearchDTO.java`
- Modify: `service/XiaoChanService.java`、`service/impl/XiaoChanServiceImpl.java`

**Interfaces:**
- Produces: `WmmtService.getShopList(WmmtShopListDTO)->WmPageVO`、`fetchWmStoreInfos(StoreTypeEnum,LocationEntity,String)`；`StoreSearchService.search(StoreSearchDTO)`；`XiaoChanService.getXcMeituanshangjinPageVO(XcMeituanshangjinDTO)`

- [ ] **Step 1: 导出 WmmtService/StoreSearch 相关**

```bash
for f in \
  service/WmmtService.java service/impl/WmmtServiceImpl.java \
  service/StoreSearchService.java service/impl/StoreSearchServiceImpl.java \
  model/dto/StoreSearchDTO.java ; do
  git show "upstream/main:src/main/java/io/github/xiaocan/$f" > "src/main/java/io/github/xiaocan/$f"
done
```

- [ ] **Step 2: XiaoChanService 加 getXcMeituanshangjinPageVO**

在本地 `XiaoChanService.java` 接口加：
```java
XcMeituanshangjinPageVO getXcMeituanshangjinPageVO(XcMeituanshangjinDTO dto);
```
并在本地 `XiaoChanServiceImpl.java` 实现（`XiaochanHttp.searchMeituanList/getMeituanList` 实例调用，Task6 已加）。同时把上游 `searchList/query` 里新增的 `storeInventoryHistoryService.insertBatch()`、`favoriteStoreService.fillFavoriteIds` 调用按需接入（若已注入）。

- [ ] **Step 3: Wmmt/StoreSearch 编译适配**

- `WmmtServiceImpl` 用 `userEntity.getWaimaiToken()`（Task2 已加）✅
- `WmmtServiceImpl` 注入 `StoreInventoryHistoryService`/`FavoriteStoreService`（Task3/4 已建）✅
- `StoreSearchServiceImpl` 用 `xiaoChanService.getXcMeituanshangjinPageVO`（本 Task 加）✅
- 上游 static `XiaochanHttp.xxx` 调用 → 改本地实例 `xiaochanHttp.xxx`

- [ ] **Step 4: 编译验证**

Run: `mvn -o -DskipTests compile`
Expected: SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/xiaocan/service/WmmtService.java src/main/java/io/github/xiaocan/service/impl/WmmtServiceImpl.java src/main/java/io/github/xiaocan/service/StoreSearchService.java src/main/java/io/github/xiaocan/service/impl/StoreSearchServiceImpl.java src/main/java/io/github/xiaocan/model/dto/StoreSearchDTO.java src/main/java/io/github/xiaocan/service/XiaoChanService.java src/main/java/io/github/xiaocan/service/impl/XiaoChanServiceImpl.java
git commit -m "feat(merge): L3 - Wmmt/StoreSearch/XiaoChanService增强"
```

---

### Task 8: L3 — MessageService（消息合并/iframe）

**Files:**
- Create: `service/MessageService.java`

**Interfaces:**
- Consumes: `SptService.sendMessage(spt,body,summary)`（本地已有）、`SystemConfig.getWebUrl()`（Task1）、`MessageBatchRecordService.recordBatch`（Task3）、`StorePlatformEnum`（本地 constant 包）
- Produces: `MessageService.queueMessage(...)`/`processPendingMessages()`

- [ ] **Step 1: 导出 MessageService**

```bash
git show "upstream/main:src/main/java/io/github/xiaocan/service/MessageService.java" > "src/main/java/io/github/xiaocan/service/MessageService.java"
```

- [ ] **Step 2: 编译适配**

`MessageService` 依赖 `StoreInfo.getRebateConditionStr/getStoreTypeEnum/getIcon/getType/getDistanceStr/getRebateRatio/getRebateMax/getPrice/getRebatePrice/...`（Task2 已加）；`SystemConfig.getWebUrl()`（Task1）；`MessageBatchRecordService`（Task3）。若依赖 `StorePushedHistoryEntity` 的语义 **已有的就不用改**（该实体未被上游用做 batch）。`SystemConfig.webUrl` 空则走纯文本分支（可缺省，不报错）。

- [ ] **Step 3: 编译验证**

Run: `mvn -o -DskipTests compile`
Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/xiaocan/service/MessageService.java
git commit -m "feat(merge): L3 - MessageService消息合并/iframe"
```

---

### Task 9: L4 — 控制器（Wmmt/StoreSearch/FavoriteStore/StoreInventoryHistory）

**Files:**
- Create: `controller/WmmtController.java`、`controller/StoreSearchController.java`、`controller/FavoriteStoreController.java`、`controller/StoreInventoryHistoryController.java`

- [ ] **Step 1: 导出控制器**

```bash
for c in WmmtController StoreSearchController FavoriteStoreController StoreInventoryHistoryController ; do
  git show "upstream/main:src/main/java/io/github/xiaocan/controller/$c.java" > "src/main/java/io/github/xiaocan/controller/$c.java"
done
```

- [ ] **Step 2: 编译适配**

控制器依赖对应 Service/VO/DTO（Task6/7/8 已建）。`StoreInventoryHistoryController` 依赖 `StoreInventoryHistoryVO`，`FavoriteStoreController` 依赖 `FavoriteStoreService` 等。静态/实例调用按上游为准（这些是新增控制器，上游怎么调就怎么调，无本地冲突）。`WmmtController` 依赖 `WmmtService`。

- [ ] **Step 3: 编译验证**

Run: `mvn -o -DskipTests compile`
Expected: SUCCESS（组件扫描自动装配，无需改启动类）

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/xiaocan/controller/WmmtController.java src/main/java/io/github/xiaocan/controller/StoreSearchController.java src/main/java/io/github/xiaocan/controller/FavoriteStoreController.java src/main/java/io/github/xiaocan/controller/StoreInventoryHistoryController.java
git commit -m "feat(merge): L4 - Wmmt/StoreSearch/FavoriteStore/StoreInventoryHistory控制器"
```

---

### Task 10: Schema 增量 + 全量编译 + 收尾

**Files:**
- Modify: `ddl.sql`

- [ ] **Step 1: ddl.sql 只追加新表/新列**

在本地 `ddl.sql` 末尾追加（**不删/不改现有表**）：

```sql
-- 收藏门店表（上游2026-07-24）
CREATE TABLE IF NOT EXISTS `favorite_store` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `location_id` bigint NOT NULL,
  `uniq_id` varchar(100) NOT NULL COMMENT '门店唯一标识',
  `store_type` varchar(50) NOT NULL COMMENT '门店类型: XC_MANJIAN/XC_MTSJ/WM_MANJIAN/WM_MTSJ',
  `icon` varchar(500) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `type` int DEFAULT NULL,
  `distance` varchar(32) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_user_location_store` (`user_id`,`location_id`,`store_type`),
  KEY `idx_uniq_id` (`uniq_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 门店库存历史表（上游2026-07-23 + sku列）
CREATE TABLE IF NOT EXISTS `store_inventory_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `unique_id` varchar(100) NOT NULL,
  `inventory` int NOT NULL,
  `store_type` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `sku_id` varchar(50) NOT NULL DEFAULT '',
  `sku_name` varchar(100) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  KEY `idx_sku_id` (`sku_id`),
  KEY `idx_unique_time` (`unique_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 消息批次记录表（上游2026-08-07）
CREATE TABLE IF NOT EXISTS `message_batch_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `batch_ids` text,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- user 表加 waimai_token（上游2026-07-30）
ALTER TABLE `user`
  ADD COLUMN IF NOT EXISTS `waimai_token` varchar(255) DEFAULT NULL COMMENT '歪麦token' AFTER `spt`;
```

（注：MySQL 8 `ADD COLUMN IF NOT EXISTS` 不支持，改用先查再加或直接 `ALTER TABLE user ADD COLUMN waimai_token ...`；生产执行前先 `SHOW COLUMNS` 确认。）

- [ ] **Step 2: 全量编译 + 测试编译**

Run: `mvn -o compile`
Expected: BUILD SUCCESS

Run: `mvn -o -DskipTests test-compile`
Expected: BUILD SUCCESS（确认测试类也不被新引入破坏）

- [ ] **Step 3: 本地回归确认**

Run: 确认本地主权区文件（GrabService*/BaseTask/StoreTask/MinimumPayService/StorePushedHistory*/login_state/ProxyHolder/OrderExchangeReq）在 `git diff` 中**无改动**。
Expected: 这些文件不在 `git status` 的改动列表（本专项未碰）。

- [ ] **Step 4: Commit**

```bash
git add ddl.sql
git commit -m "feat(merge): L5 - schema增量(收藏/库存历史/消息批次表+waimai_token列)"
```

- [ ] **Step 5: 收尾**

- 把强耦合专项完成情况写回 `.trellis/spec/backend/upstream-modules-merge.md`（标注已达成/剩余风险）。
- 更新任务 #2 为 completed。
- 提示用户：生产库 schema 迁移需在服务器手工执行（ddl.sql 增量段），不能靠代码自动。

---

## Self-Review

**Spec coverage:**
- L0 地基（PageConvertUtil/SystemConfig/StoreInfo 8字段/UserEntity waimaiToken/TransactionTemplate）→ Task1,2,5 ✅
- L1 基础设施（MessageBatchRecord/StoreInventoryHistory/FavoriteStore）→ Task3,4 ✅
- L2 HTTP 层（XiaochanHttp 美团赏金/WmmtHttp）→ Task6 ✅
- L3 业务服务（Wmmt/StoreSearch/XiaoChanService 增强/MessageService）→ Task7,8 ✅
- L4 控制器 → Task9 ✅
- Schema 增量 → Task10 ✅
- 本地主权区不动 → 各 Task 的 Global Constraints + Task10 Step3 验证 ✅
- promotionId 保持 Integer、XiaochanHttp 实例+代理、UserEntity 加 waimaiToken → Global Constraints + Task2/6 ✅

**Placeholder scan:** 无 TBD/TODO 遗留（除 Task4 Step2 明确标注"允许把跨层调用点留 TODO 待 L3 恢复"——这是有意的分层推进策略，非计划失败；L3 必须回填，否则功能不完整，回填是 Task7 的验收项）。

**Type consistency:** `StoreInfo.uniqId(String)/storeTypeEnum(StoreTypeEnum)/distanceStr(String)/rebateRatio(BigDecimal)/rebateMax(BigDecimal)/rebateConditionStr(String)/favoriteId(Long)/exists(Boolean)`，`UserEntity.waimaiToken(String)`，`XiaoChanService.getXcMeituanshangjinPageVO(XcMeituanshangjinDTO)`，`WmmtService.getShopList(WmmtShopListDTO)` 各 Task 前后一致 ✅。
