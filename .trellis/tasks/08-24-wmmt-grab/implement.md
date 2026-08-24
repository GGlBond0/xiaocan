# 歪麦抢单 — 执行规划

## 前置

- 逆向契约已归档：`.trellis/spec/backend/wmmt-grab-contract.md`。
- 本任务结束后与歪麦监控一起部署（见 `08-24-wmmt-monitor-prod-pending`）。

## 执行清单（按依赖序）

### A. 数据层（DDL + 实体）
- [ ] A1. `ddl.sql` + 生产 DDL：
  - `ALTER TABLE wmmt_login_state ADD COLUMN user_id INT DEFAULT NULL COMMENT '歪麦用户id(数字)' AFTER token;`
  - `ALTER TABLE grab_config ADD COLUMN wmmt_overbear_food_id VARCHAR(64) NULL COMMENT '歪麦活动键(overbearfoodId, String)', ADD COLUMN source INT NULL DEFAULT 1 COMMENT '1小蚕 2歪麦';`
  - 用 `xiaocan` 用户执行,禁整文件导入。验证 `SHOW COLUMNS`。
- [ ] A2. `WmmtLoginStateEntity`/`DTO`/`VO` + `userId`;录入校验必填、回显。
- [ ] A3. `GrabConfigEntity`/`DTO` + `wmmtOverbearFoodId`/`source`。

### B. 加密客户端 WmmtHttp
- [ ] B1. `fetchKeys` 缓存 `newSignUpFlag`(读 `data.newSignUpFlag`)。
- [ ] B2. 新增 `signUp(token, userId, DTO)` overbear 外卖分支:按缓存 flag 选 baseURL2(新版)/baseURL(老版);
  body 构造含 `businessId/overbearfoodId/serviceNoStr/userId/buyChannel/type/redIds/province/city/area`;新版 AES+encrypt-key 头,老版 `{json: AES}`。
- [ ] B3. 解析响应返回 `(code, buyOverbearId, payAmount, occupyPayAmount, secKillPayAmount, message)`。
- [ ] B4. 报名费判定:任一 payAmount/occupyPy/secKillPayAmount>0。

### C. 抢单服务 AutoGrabService / GrabServiceImpl
- [ ] C1. `AutoGrabServiceImpl` 内按 `config.source` 分支:source=2→歪麦账号(`wmmtLoginStateIds`→`wmmt_login_state`),活动键=`wmmtOverbearFoodId`。
- [ ] C2. 组装歪麦 grab_config:`storePlatform` 语义、`ifAdvanceOrder=false`、`enableRetry` 沿用。
- [ ] C3. `GrabServiceImpl.doGrab` 按登录态来源分:`wmmt_login_state`(歪麦) → `wmmtHttp.signUp`,`grab_state`(小蚕) → 原链路。
- [ ] C4. 歪麦 success 判定 + saveHistory + 推送;失败矩阵(D6)按 design 表。
- [ ] C5. `addUpdateConfig` 放宽 source=2 的 `autoGrab=false` 强制;校验须绑歪麦账号+账号有 userId。

### D. 前端（xiaocan-front-main 独立仓库）
- [ ] D1. 歪麦登录态页增加 userId 录入/回显。
- [ ] D2. 监控配置页(source=2)允许开启 autoGrab;若需后端返回的错误提示透传。
- [ ] D3. 抢单自动产生的记录(可参考现有监控自动抢展示)。

### E. 验证 / 冒烟
- [ ] E1. 本地 `mvn -o clean package -DskipTests` 编译通过。
- [ ] E2. 生产部署:DB DDL → JAR(rsync/分片,勿 scp 大文件)→ dist → 重启 `xiaocan.service`。
- [ ] E3. 冒烟:建一条 source=2 + autoGrab=true 的歪麦监控,命中活动触发 signup,观察 grab_history/推送。

## 验证命令

```bash
# 本地编译
mvn -o clean package -DskipTests
# 生产 DDL(用 xiaocan 用户)
mysql -uxiaocan -p -e "ALTER TABLE wmmt_login_state ADD COLUMN user_id ...; ALTER TABLE grab_config ADD COLUMN ...;"
## 冒烟看日志
ssh root@121.91.175.192 "tail -100 /opt/xiaocan/logs/xiaocan.log"
```

## 风险 / 回滚点

- 回滚:DB 加列可留(非破坏);JAR 回退到旧版本即恢复(前端 dist 同步回退)。
- 风险:存量为 source=2 的监控配置此前 autoGrab 被强 false,本轮放宽后若字段缺失会报错 → 校验前置(C5)。
- 风险:`newSignUpFlag` 动态值变化——后端每次拉密钥后刷新缓存,避免写死单轨。

## 完成后

- 按 `trellis:finish-work` / 部署清单更新 `08-24-wmmt-monitor-prod-pending` 待办。