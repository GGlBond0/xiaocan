# 部署小蚕到 Ubuntu 云服务器

## Goal

在 Ubuntu 22.04.5 LTS 云服务器（IP `121.91.175.192`）上部署「小蚕」前后端分离项目，
实现通过浏览器以 IP + HTTP 方式访问全部功能。

## Background

- 项目仓库：后端 https://github.com/lyrric/xiaochan ，前端 https://github.com/lyrric/xiaocan-front
- 本地工作目录：`xiaochan-main`（后端源码）、`xiaocan-front-main`（前端源码），二者非本地 git 仓库
- 技术栈：Spring Boot 3.4.1 (Java 17, MyBatis-Plus, MySQL, Redis)、Vue 3.5 (Vite 8, TypeScript, Element Plus)
- 后端默认端口 `10234`；前端 axios 全部以相对路径 `/api/*` 发起请求，生产环境需 Nginx 反代 `/api/*` 到 `127.0.0.1:10234`
- 配置项（`application.yaml`）均支持环境变量覆盖：`MYSQL_HOST/PORT/DB/USER/PASSWORD`、`REDIS_HOST/PASSWORD`
- 数据库表结构在 `ddl.sql`，含 `DROP TABLE IF EXISTS`，全新部署直接导入

## User Decisions（本轮已确认）

1. 创建 Trellis 任务并进入规划
2. 后端 jar 与前端 dist 通过 **GitHub Actions** 构建，**手动下载 artifact** 上传服务器（不在 workflow 内做服务器部署）
3. 前端对外访问方式：**仅 IP + HTTP**，走**独立端口 8088**（`http://121.91.175.192:8088/`），不占用/不抢占 80 默认站（80 上有他人站点 cpa/napi/note）
4. 前后端**保持两个独立 GitHub 仓库**，各自一个 workflow
5. 服务器构建/装包**默认配置国内镜像源**（apt 阿里云、Maven 阿里云、npm 淘宝）
6. 后端 workflow **重写**：用环境变量注入 MySQL/Redis 配置，不在 jar 内硬编码密码；原 `build-prod.yml` 的硬编码方案废弃
7. 旧 xiaocan 库**清掉重建**（用 ddl.sql，干净）

## Requirements

### R1 服务器基础环境
- R1.1 Ubuntu 22.04 上安装并自启动：MySQL 8、Redis、JDK 17、Nginx
- R1.2 配置防火墙/安全组：开放 80（对外）；10234、3306、6379 仅本机访问，不对外暴露
- R1.3 国内镜像源：apt 换阿里云源；Maven `settings.xml` 用阿里云仓库；npm 用淘宝源
- R1.4 服务器时区设为 Asia/Shanghai（与后端 `jackson.time-zone=GMT+8` 一致）

### R2 数据库
- R2.1 创建库 `xiaocan`（utf8mb4，utf8mb4_0900_ai_ci，与 ddl.sql 一致）
- R2.2 创建专用 MySQL 账号（非 root）并授予 `xiaocan.*` 权限，设强密码
- R2.3 导入 `ddl.sql`，5 张表（location / monitor_config / store_pushed_history / task_exec_history / user）+ 2 条 cron 相关 ALTER 建表成功
- R2.4 root 仅限本机登录；部署用账号也仅本机访问

### R3 Redis
- R3.1 Redis 监听 `127.0.0.1:6379`，不对外
- R3.2 无密码（与 application.yaml 默认一致）或设密码并同步后端配置 —— 默认无密码

### R4 后端
- R4.1 在你 fork 的后端仓库内重写 `.github/workflows/build-prod.yml`：构建时不写死配置，
      `application.yaml` 保持用环境变量占位（沿用源码现状即可），artifact 名 `xiaocan.jar`
- R4.2 服务器上用 systemd 托管 jar，环境变量（`MYSQL_*` / `REDIS_*`）写入 systemd unit 的 `Environment=` 或 `/etc/xiaocan/xiaocan.env`（600 权限）
- R4.3 服务监听 `10234`，仅 `127.0.0.1`，开机自启，崩溃自动重启
- R4.4 日志输出到文件并按 logback 配置滚动

### R5 前端
- R5.1 在你 fork 的前端仓库内新增 `.github/workflows/build-prod.yml`：Node 构建 `dist/`，artifact 压缩为 `dist.zip`
- R5.2 服务器上由 Nginx 提供 `dist/` 静态文件
- R5.3 前端无 baseURL，axios 走相对 `/api/*`，由 Nginx 反代到后端

### R6 Nginx 反向代理
- R6.1 监听 **8088**（独立端口，不占 80，不影响 cpa/napi/note 他人站点），`server_name _`
- R6.2 `location /api/` → `proxy_pass http://127.0.0.1:10234`，原样透传 `/api` 前缀（controller 自带），透传 Host 与必要请求头
- R6.3 `location /` → 前端 `dist/`，`try_files $uri $uri/ /index.html`
- R6.4 静态资源合理缓存头
- R6.5 云安全组/防火墙放行 8088

### R7 GitHub Actions 构建链路（手动下载 artifact）
- R7.1 后端 fork workflow：手动触发（`workflow_dispatch`），产出 `xiaocan.jar` artifact
- R7.2 前端 fork workflow：手动触发，产出 `dist.zip` artifact
- R7.3 你从两个 Actions 运行页下载 artifact，scp 上传服务器，按 deploy 文档放置/重启服务

## Acceptance Criteria

- [ ] 浏览器访问 `http://121.91.175.192:8088/` 能打开前端首页，静态资源 200，路由不 404
- [ ] 前端调用 `/api/*` 经 Nginx 反代到后端，返回正常业务数据（非 502/504）
- [ ] 后端 systemd 服务 `active(running)`，重启服务器后自动拉起
- [ ] MySQL `xiaocan` 库 5 表存在且字段与 `ddl.sql` 一致；`monitor_config` 含 cron 列
- [ ] Redis 仅 `127.0.0.1` 监听；3306/6379/10234 不在公网端口扫描中暴露
- [ ] 后端配置经环境变量注入，jar 内不含明文 MySQL 密码
- [ ] 后端 fork 与前端 fork 各自 workflow 能成功构建并产出 artifact
- [ ] 部署全程未把 root 密码或 MySQL 密码写进任何提交到仓库的文件

## Environment（已补齐）

- GitHub 用户名：`GGlBond0`
- 后端 fork：`GGlBond0/xiaochan`
- 前端 fork：`GGlBond0/xiaocan-front`
- 服务器：`121.91.175.192`（root / 本会话提供，已配 SSH 免密）

## Server Reality（勘察于 2026-07-14，只读）

- Ubuntu 22.04.5 / 2 核 / **内存 1.7G（可用仅 217M）+ 1.8G swap** / 40G 盘用 43%
- 已装在跑：JDK 17.0.19、MySQL 8（127.0.0.1:3306/33060）、Nginx（80 + 8080）
- **Redis 未装**（需新装）
- **UFW 未启用**，iptables INPUT 全 ACCEPT；防火墙靠云安全组
- 旧 xiaocan 残留（待清理）：
  - 后端 jar `/opt/xiaocan/xiaocan.jar`，root 裸跑无守护（PID 736528），**监听 `*:10234` 公网暴露**
  - 前端 `/opt/xiaocan_front/xiaocan-front-main/dist/`
  - Nginx 旧站点 2 个：`/etc/nginx/sites-enabled/xiaocan`（80，`server_name _` 抢默认站）+ `xiaocan-front`（8080）
- 他人项目（**绝不触碰**）：openclaw-gateway、new-api、fast-note-sync、CLIProxyAPI、apimain、containerserver，及多个 docker-proxy 端口（9000/21115-21120/3480/8317 等）；他人 Nginx 站点 cpa/napi/note.20030704.xyz
- MySQL root 需密码（`-uroot` 无密码被拒），密码待用户提供

## Constraints（由勘察导出）

- 内存极紧张：JVM 必须**压到 -Xmx256m 甚至更低**，先停旧 java 释放 ~220M；避免与 openclaw(359M)/mysqld(247M) 抢内存导致 OOM
- 10234 必须只绑 127.0.0.1（旧版绑 `*` 是安全洞），由 Nginx 反代
- Nginx 改动**只动 xiaocan 两个旧站点**，禁碰 cpa/napi/note
- 不启用 UFW（避免影响他人 docker/端口），端口隔离靠「服务绑 127.0.0.1」+ 云安全组

## Notes

- 你在本会话明文提供了 root 密码。部署完成后建议立即改 root 密码、改用 SSH 密钥登录。
- 本任务为复杂任务：需补 `design.md` + `implement.md` 后再 `task.py start`。
- 构建在 fork 上做，原 `lyrric/*` 仓库无写权限、不可改其 workflow。
