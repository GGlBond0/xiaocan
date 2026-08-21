# Implement: 前端接入 upstream 五模块页面 + 导航

## Checklist

1. [ ] 安装 `echarts` 依赖
2. [ ] 移植上游 `StoreInventoryHistoryView.vue` 到本地 views(库存折线图)
3. [ ] 新建 `FavoriteStoreView.vue`(收藏列表/删除/分页,地址下拉)
4. [ ] 新建 `StoreSearchView.vue`(聚合搜索,地址下拉)
5. [ ] 新建 `WmmtView.vue`(歪麦门店,游标翻页,上游受限降级提示)
6. [ ] `router/index.ts` 加路由,`NavBar.vue` 加菜单入口
7. [ ] 本地 `npm run build`(含 vue-tsc 类型检查)通过,无 TS 报错
8. [ ] 验证页面可访问(本地 dev 或 build 后 server)

## 关键实现点

- 地址下拉:所有门店页顶部,`/api/location` 取 `id/longitude/latitude/cityCode/name`;未选禁用操作。
- 收藏:列表 `POST /api/favorite/stores {locationId, pageNum, pageSize}`;删除 `DELETE /api/favorite/{favoriteId}`。
- 门店搜索:`POST /api/store/search {name, cityCode, latitude, longitude}` → List<StoreInfo>。
- 歪麦:`POST /api/wmmt/shopList {name?, latitude, longitude, locationId, scrollPageData}`;游标翻页原样回传;stockout 提示。
- 库存历史:`GET /api/store-inventory-history/{uniqueId}`;移植上游 echarts 页,skuid 分组折线。
- 类型:新增 view 用 `lang="ts"`,axios 返回 `any` 规避类型问题(与现有 LocationView 一致用 `any[]`),避免 vue-tsc 卡。

## Validation

```bash
cd /c/D/AI/Projects/xiaocan/xiaocan-front-main
npm install echarts
npm run build   # 含 vue-tsc --build,必须通过
# 可本地起 dev 验证: npm run dev
```

## 注意

- 不部署前端 dist(本任务只做代码;若需上线另一步)。
- wmmt 上游受限时页面降级提示,不白屏。
- 遵循现有页面风格(element-plus + api 封装 + authState inject)。
