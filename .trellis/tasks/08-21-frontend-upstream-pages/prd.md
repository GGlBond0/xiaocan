# 前端接入 upstream 五模块页面 + 导航

## Goal

在本地前端仓库 `xiaocan-front-main` 中,为已部署的后端 upstream 五模块接口(歪麦门店 / 门店搜索 / 收藏 / 库存历史 / 消息批量相关信息)新建前端页面与导航入口,让用户在页面上能看到并使用这些能力。

参考上游 `https://github.com/lyrric/xiaocan-front`(已 clone 到 `xiaocan/upstream-xiaocan-front`)——但上游前端也只完整实现了**库存历史**一个页面;歪麦/收藏/门店搜索需基于后端接口新设计(自由设计)。

## Scope

- **新增页面**(暂定,以 design 细化):
  - 歪麦门店(`/api/wmmt/shopList`)— 门店列表 + 游标翻页
  - 门店搜索(`/api/store/search`)— 按名称/坐标聚合搜索三平台
  - 收藏(`/api/favorite/*`)— 收藏列表/保存/删除
  - 库存历史(`/api/store-inventory-history/{uniqueId}`)— 移植上游 echarts 折线图页
- **导航**:NavBar 加对应菜单项 + router 加路由 + 新 view 文件。
- **登录态**:沿用现有 `token` header(api 层已自动带),新页面用 `inject('authState')` 认证模式 + element-plus 组件。

## Acceptance Criteria

1. 前端新增页面可通过 NavBar 菜单进入,路由正常。
2. 页面调后端接口,数据展示正确(带 token 认证)。
3. 库存历史页可展示折线图(echarts)。
4. 收藏页支持保存/查看/删除闭环。
5. 歪麦/门店搜索接口若上游受限(如 wmmt SocketTimeout),页面需优雅提示而非白屏。
6. 构建 `npm run build`(含 vue-tsc 类型检查)通过。

## Non-Goals

- 不改后端代码/接口(后端已部署,仅前端消费)。
- 不部署前端 dist(本任务只做代码;部署另议或一并确认)。
- 不做 `BaseTask` batchId 等后端逻辑。
