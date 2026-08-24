# 生产补wmmt_login_state建表

## Goal

在生产数据库 `xiaocan` 补上歪麦登录态多账号池表 `wmmt_login_state`（源码/DDL 已在 main，但生产库未执行建表段，导致 `/api/wmmt-login-state` 接口暂不可用），为后续歪麦抢单/监控开发打通数据落地。

## Requirements

- 只执行 `ddl.sql` 末尾 `wmmt_login_state` 建表段（424-440 行，`CREATE TABLE IF NOT EXISTS`）。
- 严禁整文件导入 `ddl.sql`（前半含危险 `DROP TABLE IF EXISTS`）。
- 用生产库有权限的 `xiaocan` 用户执行（凭据来自 `/etc/xiaocan/xiaocan.env`，非 root）。
- 执行后重启 `xiaocan.service`，确认 HikariPool 正常、接口不再 500。

## Acceptance Criteria

- [ ] 生产 `xiaocan` 库存在 `wmmt_login_state` 表，字段 9 个（id/user_id/name/token/city/create_time/update_time/deleted）与 DDL 一致，空表 0 行。
- [ ] `/api/wmmt-login-state` GET 返回 200（非 500），`/api/wmmt/shopList` 不受影响。
- [ ] 不修改生产其它表/数据；不改动 ddl.sql 本身。
- [ ] 重建/重启后端 `xiaocan.service` 后 HikariPool 连接正常、服务健康。

## Notes

- 凭据与部署拓扑见 auto-memory `deploy-topology` / `backend-proxy-and-build` / `server-services`；生产库 root 密码已失效，明确用 `xiaocan` 用户。
- 相关记忆 [[wmmt-foundation-ready]]。