# 歪麦登录态多账号池 — 技术设计

## 决策

- **独立表** `wmmt_login_state`：不复用 `login_state`（歪麦 token 无 X-Sivir/JWT，硬塞会破坏小蚕池解析契约）。字段私有。
- **不改门店查询**：`WmmtService.getShopList` 保持现状（无账号浏览）。`UserEntity.waimaiToken` 不动。
- **不标记默认**：账号池仅存账号，未来抢单/监控配置引用 `wmmt_login_state.id`（类比小蚕 `grab_config.login_state_id`），本轮不预支状态。
- **后端结构**：`WmmtLoginStateEntity` + `WmmtLoginStateMapper`（MyBatis-Plus BaseMapper）+ `WmmtLoginStateService(Impl)` + `WmmtLoginStateController`（`/api/wmmt-login-state`）。对齐 `LoginState*` 命名族。
- **前端结构**：`WmmtLoginView.vue`（`/wmmt-login`）+ NavBar 入口「歪麦登录态」。参照 `LoginStateView.vue` 交互。

## 数据表 DDL（追加到 ddl.sql 末尾）

```sql
CREATE TABLE IF NOT EXISTS `wmmt_login_state` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `user_id` INT NOT NULL COMMENT '系统用户id',
  `name` VARCHAR(100) DEFAULT NULL COMMENT '别名',
  `token` VARCHAR(255) NOT NULL COMMENT '歪麦token',
  `city` VARCHAR(50) DEFAULT '长沙市' COMMENT '城市',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT(1) DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='歪麦登录态池';
```

## 接口契约

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| POST | `/api/wmmt-login-state` | `{ name?, token }` | 保存后单条 VO |
| GET | `/api/wmmt-login-state/list` | - | `[{ id,name,maskedToken,city,updateTime }]` |
| DELETE | `/api/wmmt-login-state/{id}` | - | Void |

- token 校验：POST 时 `token` 非空（BusinessException）；多账号按 id，重复 name 允许。
- token 掩码：`前4 + **** + 后4`（不足 8 位显 `****`）。**不回显明文**。
- 归属校验：list/delete 均按当前 `user_id`；delete 时非本人抛 BusinessException。

## 数据流

```
WmmtLoginView.vue
  onMounted → GET list → 多账号列表(掩码token)
  新增弹窗(name+token) → POST → 刷新 list
  删除 → confirm → DELETE → 刷新 list
```

## 兼容 / 回滚

- 兼容：新增表 + 接口不影响现有 `/api/wmmt/shopList` 等；不动 `WmmtHttp/WmmtService`。
- 回滚：删表段 + 后端 4 文件 + 前端页/路由/导航即可。
- 上线：需在 ddl.sql 追加建表段，并在生产库执行该段（建表）后接口才可用。执行策略遵循`不整跑 ddl.sql`守则。

## 风险

- token 明文 POST：对齐项目现状（拦截器 token 明文），不额外加密。
- 生产建表：需单独执行建表 SQL，勿整文件跑 ddl.sql。
- 掩码长度：VARCHAR(255) token，掩码策略已覆盖。