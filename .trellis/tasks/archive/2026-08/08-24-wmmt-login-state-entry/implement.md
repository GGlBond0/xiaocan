# 歪麦登录态多账号池 — 实施计划

## 前置确认

- 已确认：歪麦门店浏览不需账号（`WmmtHttp.getShopList` 空 token 可跑；`WmmtView` 未登录可看门店）。故本任务不动 `WmmtService/WmmtHttp/UserEntity.waimaiToken`。
- 已确认：多账号池为纯账号库，不标默认、不改门店查询。

## 实施清单

### Step 1：DDL（ddl.sql）
- [ ] 在 `ddl.sql` 末尾追加 `wmmt_login_state` 建表段（见 design.md），带注释「禁止整文件跑生产，生产只执行本段」。
- 验证：`grep -c "CREATE TABLE.*wmmt_login_state" ddl.sql` = 1；无整文件跑生产提示。

### Step 2：后端 Entity + Mapper
- [ ] 新建 `model/entity/WmmtLoginStateEntity.java`（`@TableName("wmmt_login_state")`，字段对齐 DDL，`@TableId(AUTO)`，`@TableLogic deleted`）。
- [ ] 新建 `mapper/WmmtLoginStateMapper.java`（`extends BaseMapper<WmmtLoginStateEntity>`）。
- 验证：本地 `mvn compile` 通过。

### Step 3：后端 Service + Impl
- [ ] 新建 `service/WmmtLoginStateService.java`（接口）：`save(dto)`、`list()`、`delete(id)`。
- [ ] 新建 `service/impl/WmmtLoginStateServiceImpl.java`：
  - `save`：校验 token 非空 → 当前用户 `user_id` → insert。name 空则 `"账号N"`（N=当前用户第几个，可 `count+1`）。
  - `list`：按当前 `user_id` 查，映射 VO（token 掩码、city）。
  - `delete`：按 `user_id` 归属校验，非本人抛 BusinessException；本人则 deleteById（逻辑删）。
  - 注入 `UserService` 拿当前用户。
- 验证：`mvn compile`。

### Step 4：后端 Controller
- [ ] 新建 `controller/WmmtLoginStateController.java`：`@RequestMapping("/api/wmmt-login-state")`，含 POST（save）、GET `/list`、DELETE `/{id}`。对齐 LoginStateController 风格。
- 验证：`mvn compile` + `mvn test-compile`。

### Step 5：后端 VO/DTO
- [ ] 新建 `model/dto/WmmtLoginStateDTO.java`（`name` 可空 + `token` 必填，`@NotBlank token`）。
- [ ] 新建 `model/vo/WmmtLoginStateVO.java`（id/name/maskedToken/city/updateTime），Service 内做掩码映射。

### Step 6：前端
- [ ] 新建 `src/views/WmmtLoginView.vue`：列表（别名/token掩码/城市/更新时间/删除）+ 新增弹窗（name+token）。城市固定"长沙市"只读。参照 LoginStateView.vue。
- [ ] `src/router/index.ts` 加路由 `/wmmt-login`（name `wmmt-login`）。
- [ ] `src/components/NavBar.vue` 加「歪麦登录态」入口。
- 验证：`npm run build` 通过。

### Step 7：整体验证
- [ ] 后端 `mvn package` 通过；本地启动冒烟（可选）：`curl -X POST /api/wmmt-login-state`。
- [ ] 前端 `npm run build` 通过。
- [ ] `git diff` 确认不含 `WmmtService/WmmtHttp/UserEntity.waimaiToken/getShopList` 改动（AC7）。

## 关键风险 / 回滚点

| 风险 | 缓解 / 回滚 |
|---|---|
| 生产未建表导致接口 500 | 上线前必须单独执行建表段；回滚删接口即可 |
| token 明文 | 对齐项目现状，不做额外加密 |
| 掩码实现 | 用工具函数，不足 8 位显 `****` |
| 前端路由冲突 | `/wmmt-login` 不与现有 `/wmmt` 冲突（不同 path） |

## 上线部署（implement 完成后另行决策）

- 后端：本地 `mvn package` → scp jar → systemctl restart xiaocan（见 [[deploy-topology]]）。
- 需先在生产库执行 `wmmt_login_state` 建表 SQL。
- 前端：`npm run build` → scp dist（见 [[frontend-deploy-dist-absolute-path]]）。