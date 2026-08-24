# 前端同步删改：移除收藏/库存历史/门店搜索页面与路由导航，保留歪麦

## Goal

后端 `trim-upstream-modules`（已归档）删除了收藏/库存历史/消息批量/聚合搜索后端接口。前端（`xiaocan-front-main`）同步删除对应页面、路由与导航入口，避免调已删接口导致页面报错。**歪麦页面保留**（后端 `WmmtController` + `/api/wmmt/shopList` 仍在）。

## Requirements

- R1 删除 3 个 vue 页面：`src/views/FavoriteStoreView.vue`、`src/views/StoreInventoryHistoryView.vue`、`src/views/StoreSearchView.vue`
- R2 路由 `src/router/index.ts` 移除 `/favorite`、`/store-search`、`/store-inventory` 3 条路由
- R3 导航 `src/components/NavBar.vue` 移除「收藏」「门店搜索」「库存历史」3 个入口
- R4 **保留** 歪麦：`WmmtView.vue`、路由 `/wmmt`、导航「歪麦」入口（后端接口仍在，不删）
- R5 编译验证：`npm run build`（vue-tsc）通过

## Constraints

- 歪麦页面是核心保留项，不删
- 前端独立仓库提交，与后端 commit 分开
- 前端 dist 不部署（本次仅源码删改，上线部署另决策）

## Acceptance Criteria

- [ ] 3 个 vue 文件已删
- [ ] 路由已移除 3 条，`/wmmt` 保留
- [ ] NavBar 已移除 3 入口，「歪麦」保留
- [ ] `grep` src 无对已删页面的 import/路由引用
- [ ] `npm run build` 通过

## Notes

- 轻量任务，PRD-only。
- 后端 `upstream-modules-merge.md` spec 已更新（后端任务），前端本任务不涉及 spec。