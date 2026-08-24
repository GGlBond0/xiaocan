# 歪麦登录态录入（多账号池）

## Goal

为歪麦（waimaimingtang）平台建立**多账号登录态池**：一个系统用户可录入多组歪麦账号（token），供未来的歪麦抢单/监控引用。当前歪麦 token 只能直接改库（`user.waimai_token` 单值），无法管理多账号。本任务补齐「歪麦登录态」管理页 + 后端多账号 CRUD。

**关键认知**：歪麦门店浏览**不需要账号**（未登录可浏览，`WmmtHttp.getShopList` 传空 token 可跑，对齐小蚕门店浏览无auth）。因此本任务**不改门店查询逻辑**；账号池纯为未来的抢单/监控预埋。

## Background & Confirmed Facts

- 歪麦地基已就绪：`WmmtHttp.getShopList(token, city, dto)` 用 header `token`；`WmmtServiceImpl` 从 `UserEntity.waimaiToken`（列 `user.waimai_token`）取 token，但**门店浏览不依赖它**（空 token 可看，前端 WmmtView 选地址后即可看，不校验登录）。
- `user.waimai_token` 是上游遗赠的单 token 列，**不是多账号**。本任务不改它（门店浏览用不到），多账号存独立新表。
- 小蚕登录态池 `login_state` 是成熟参照：`LoginStateController`（`POST /api/login-state`、`GET /list`、`DELETE /{id}`）+ `LoginStateService`(save/list/delete/getEntity) + 管理页。歪麦多账号对齐该形态，但字段私有（无 JWT/rawHeaders 解析）。
- 生产库现有 `user.waimai_token` 列（无待删/待改）；本任务新增表**需 ddl.sql 追加 + 生产执行**（ali神`wmmt_login_state` 表）。
- 前端 api 模块（`src/api/index.ts`）有 `get/post/put/delete` 标准方法。
- 鉴权沿用：拦截器注入当前用户，`UserService.getByCurrentRequest()`。

## Requirements

### R1 数据表：歪麦登录态池
- 新表 `wmmt_login_state`：`id PK AUTO`、`user_id`（系统用户）、`name`（别名，展示用）、`token`（歪麦 token）、`city`（默认"长沙市"，只读展示）、`create_time/update_time/deleted`。
- 每系统用户可多行；**不标记默认账号**（未来抢单/监控自行指定引用）。

### R2 后端：歪麦登录态 CRUD
- `POST /api/wmmt-login-state`：新增（body `{ name, token }`）。校验 token 非空；`name` 空则默认"账号N"。当前用户隔离。
- `GET /api/wmmt-login-state/list`：当前用户全部账号（id/name/token掩码/city/updateTime）。
- `DELETE /api/wmmt-login-state/{id}`：删除当前用户某账号（归属校验）。
- 对齐 LoginStateController 风格；沿用现有鉴权。

### R3 前端：歪麦登录态管理页 `/wmmt-login`
- 新建 `WmmtLoginView.vue`，路由 `/wmmt-login`，NavBar 入口「歪麦登录态」。
- 列表展示当前用户多账号：别名、token（掩码）、城市（固定"长沙市"）、更新时间、操作（删除）。
- 新增弹窗：别名（可选）+ token（必填，明文输入）。
- 城市字段固定"长沙市"（只读，不提供切换）。

### R4 不改动
- **门店查询 `WmmtService.getShopList` 不改**（无账号照样看）；`UserEntity.waimaiToken` 保留但不作为本任务的写入目标。

## Acceptance Criteria

- [ ] AC1 新表 `wmmt_login_state` 定义在 ddl.sql，含 `user_id/name/token/city/create_time/update_time/deleted`。
- [ ] AC2 `POST /api/wmmt-login-state`：新增账号，空 token 报错，重复 name 允许（多账号按 id 区分），当前用户隔离。
- [ ] AC3 `GET /api/wmmt-login-state/list`：仅当当前用户账号，token 掩码返回（不回显明文）。
- [ ] AC4 `DELETE /api/wmmt-login-state/{id}`：删除当前用户账号；他人账号返回无权。
- [ ] AC5 前端 `/wmmt-login` 页可新增/删除/查看多账号，token 掩码、城市固定"长沙市"，NavBar 有入口。
- [ ] AC6 后端本地编译通过（`mvn package`/`test-compile`）；前端 `npm run build` 通过。
- [ ] AC7 门店查询逻辑未因本任务改动（git diff 不含 getShopList/WmmtHttp/WmmtService）。

## Out of Scope

- 通信多城市（`WmmtServiceImpl.CITY` 拆常量改用户自选城市）。
- 默认账号标记 / 绑定当前账号到门店查询。
- 歪麦监控/抢单本身（未来任务引用本账号池）。
- 改造/复用 `login_state` 池（歪麦独立表）。
- 生产库迁移执行（任务只出 ddl.sql 文本；真上线时执行段另决策）。

## Open Questions

（无——产品决策已敲定：纯账号池、不改门店查询、不标记默认。）