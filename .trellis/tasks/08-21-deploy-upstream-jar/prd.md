# 08-21 部署upstream模块JAR+接口回归

## Goal

把已合入 `feat/upstream-modules` 分支、含上游五模块(Wmmt歪麦 / StoreSearch门店搜索 / FavoriteStore收藏 / StoreInventoryHistory库存历史 / MessageService消息批量)控制器的后端 JAR 部署到生产 121.91.175.192,并做接口回归验证。本次部署让新增 Controller 在真实环境中可访问(旧 JAR 忽略新表/新列但无新接口)。

## Scope

- **构建**:本地 `mvn -o clean package -DskipTests` 产出完整 `target/xiaocan.jar`(含 ProxyHolder/executeWithProxy 代理逻辑 + 新控制器)。
- **传输**:rsync(小机避免 scp 大文件卡死,见 [[scp-large-jar-hangs-server]])。
- **部署**:备份旧 JAR → 替换 → 重启 `xiaocan.service` → 确认启动成功。
- **接口回归**:对新控制器关键接口做真机冒烟,验证登录态与功能正常。
- **不做**:不改前端、不动其它服务、不跑生产 mvn、不 push。

## Acceptance Criteria

1. 本地 `package` 成功产出 jar(BUILD SUCCESS)。
2. 生产新 JAR 就位,旧 JAR 已备份(`xiaocan.jar.bak.<ts>`)。
3. `xiaocan.service` 重启后 active,HikariPool / Spring Boot Started 日志正常,无启动错误。
4. 新控制器接口经 nginx 反代(80 或 8088)可访问,登录态校验 + 数据读写正常。
5. 原本地主权能力(抢单/登录态池/代理)回归不受影响。

## Non-Goals

- 不部署前端。
- 不执行 `BaseTask` 写 `batchId`(主权区延期项,本次不碰)。
- 不 push 分支(部署只靠本地 build→rsync)。
