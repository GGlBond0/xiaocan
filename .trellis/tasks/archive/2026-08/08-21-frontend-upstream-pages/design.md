# Design: 前端接入 upstream 五模块页面 + 导航

## 目标形态

本地前端 `xiaocan-front-main` 新增 4 个页面 + NavBar 导航,消费已部署的后端五模块接口。全部沿用现有模式:`api.get/post`(自动带 token)+ element-plus + `inject('authState')`。

复用上游 `upstream-xiaocan-front` 的 `StoreInventoryHistoryView.vue`(库存折线图,echarts)作为基础;歪麦/收藏/门店搜索因上游无页面,按后端契约新设计。

## 前置:地址选择器(核心依赖)

门店类接口(save/stores/search/wmmt)强依赖 `locationId / longitude / latitude / cityCode`。这些来自已保存地址(`/api/location` 返回数组,含 `id/longitude/latitude/cityCode/name`)。故**各门店页面顶部放「选择地址」下拉**,取所选地址的坐标/编码;收藏页额外用其 `locationId`。未选地址时禁用操作并提示。

## 页面设计

### 1. 收藏页 `/favorite` (FavoriteStoreView)
- 顶:地址下拉(必需,取 locationId)+ 门店类型筛选(可空)。
- 列表:`POST /api/favorite/stores` `{locationId, storeType?, pageNum, pageSize}` → `Page<StoreInfo>`。
- 每条展示:`name`、`storeTypeEnum`、`distanceStr/distance`、`price/rebatePrice`(满返)、`leftNumber`、`exists`(true=实时/ false=降级 fallback)。
- 删除:DELETE `/api/favorite/{favoriteId}`(favoriteId 来自记录)。
- 分页:el-pagination,pageNum/pageSize;注意 `total` 是收藏记录数(与 records.length 可能不一致,提示按需)。

### 2. 门店搜索页 `/store-search` (StoreSearchView)
- 顶:地址下拉(取 longitude/latitude/cityCode)+ 名称输入 + 搜索按钮。
- 列表:`POST /api/store/search` `{name, cityCode, latitude, longitude}` → `List<StoreInfo>`(不分页)。
- 展示:同 StoreInfo 字段。平台靠 `storeTypeEnum` 分组/标识。
- 说明:某平台失败后端已静默跳过,前端若全空可提示上游受限。

### 3. 歪麦门店页 `/wmmt` (WmmtView)
- 顶:地址下拉(取 longitude/latitude)+ 名称输入 + 查询。
- 列表:`POST /api/wmmt/shopList` `{name?, latitude, longitude, locationId, scrollPageData}` → `WmPageVO{storeInfos, scrollPageData}`。
- **游标翻页**:手点「下一页」把上次 `scrollPageData` 原样回传;第一页传 null。无下一页(空返回)提示到底。
- 收藏状态:`favoriteId != null` 即已收藏(注意 wmmt 不填 `exists`)。
- **上游受限提示**:生产实测 wmmt 上游 SocketTimeout,若接口 500 需 el-message 明确提示"歪麦上游暂时不可用",不白屏。

### 4. 库存历史页 `/store-inventory/:uniqueId` (StoreInventoryHistoryView)
- 从上游 `upstream-xiaocan-front/src/views/StoreInventoryHistoryView.vue` 移植(echarts 折线图)。
- 依赖:`GET /api/store-inventory-history/{uniqueId}` → `List<StoreInventoryHistoryVO{sname,skuId,skuName,inventory,createTime:"HH:mm"}>`,按 skuId 分组多线图。
- 需安装 `echarts` 依赖。
- 入口:从收藏/搜索/歪麦列表点门店名/「库存」跳到 `?uniqueId=..&name=..`。

## 导航改造
- `src/router/index.ts`:加 4 条路由(`/favorite` `/store-search` `/wmmt` `/store-inventory/:uniqueId` 用 query 传 uniqueId/name 更简单)。
- `src/components/NavBar.vue`:menu 区加「收藏/门店搜索/歪麦」三个入口(库存历史是详情页,不占主菜单)。

## 依赖
- 需新增 `echarts`(库存图)。
- 其余 element-plus/vue-router/axios 已具备。

## 兼容 / 风险
- wmmt 上游超时 → 页面需优雅降级提示。
- 门店搜索/歪麦要求坐标:无地址时禁用,引导先去「地址」页添加。
- favorite 的 `total`=收藏记录数语义,前端按 records 展示并注明。
- 构建含 `vue-tsc` 类型检查,新增 TS 需类型正确。
