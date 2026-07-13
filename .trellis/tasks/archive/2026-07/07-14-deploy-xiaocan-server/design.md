# Design — 部署小蚕到 Ubuntu 云服务器

## 0. 服务器现状（2026-07-14 勘察）

- Ubuntu 22.04.5 / 2 核 / 内存 1.7G（可用仅 217M）+ swap 1.8G / 40G 盘用 43%
- 已装在跑：JDK 17.0.19、MySQL 8、Nginx；**Redis 未装**
- 旧 xiaocan 残留：`/opt/xiaocan/xiaocan.jar`（root 裸跑无守护，10234 绑 `*` 公网暴露）、`/opt/xiaocan_front/`、2 个旧 Nginx 站点（80 的 `xiaocan` + 8080 的 `xiaocan-front`）
- UFW 未启用，靠云安全组；他人项目（openclaw/new-api/多个 docker 端口/他人站点）绝不触碰
- 旧库 `xiaocan` 决定**清掉重建**

## 1. 架构总览

```
浏览器 --HTTP:8088--> Nginx (121.91.175.192)
                        ├── location /        -> /var/www/xiaocan/dist  (前端静态)
                        └── location /api/    -> proxy_pass http://127.0.0.1:10234  (后端)

127.0.0.1:10234  Spring Boot (xiaocan.jar, systemd: xiaocan.service)  ← 仅本机
127.0.0.1:3306   MySQL 8   (库 xiaocan)                              ← 已存在，复用
127.0.0.1:6379   Redis     (无密码)                                  ← 新装
80              他人站点 cpa/napi/note                              ← 绝不触碰
```

公网暴露 22(SSH) + 8088(小蚕)；80 上是他人站点不动。10234/3306/6379 仅 127.0.0.1。
**复用已装的 MySQL/Nginx/JDK，不重装**；只新装 Redis。小蚕走独立端口 8088，避免抢占/影响 80 默认站。

## 2. 关键技术约束（已核实源码）

- **后端路由自带 `/api` 前缀**：所有 controller 均为 `@RequestMapping("/api/...")`，
  无 `server.servlet.context-path`。因此 Nginx 对 `/api/` 必须**原样透传**，
  不可 rewrite 去掉前缀。`proxy_pass http://127.0.0.1:10234;`（不带尾斜杠/不带 `/api`）。
- **前端无 baseURL**：axios 用相对路径 `/api/*`。dev 靠 vite proxy，prod 靠 Nginx location。
- **配置走环境变量**：源码 `application.yaml` 已是 `${MYSQL_HOST:127.0.0.1}` 等占位，
  无需改源码即可在服务器用 env 注入。后端 workflow 只做「构建产物」，不碰配置。
- **DDL 含 `DROP TABLE IF EXISTS`**：全新部署安全，导入前确保库为空/可重建。

## 3. 部署链路（手动下载 artifact）

```
你的 fork (GGlBond0/xiaochan)
   └── .github/workflows/build-prod.yml  (重写) --手动触发--> 产物 xiaocan.jar artifact
你的 fork (GGlBond0/xiaocan-front)
   └── .github/workflows/build-prod.yml  (新增) --手动触发--> 产物 dist.zip artifact

  Actions 页下载 -> scp 上传到 121.91.175.192:/opt/xiaocan-deploy/
  服务器上部署脚本放置 jar / dist，重启 systemd
```

不使用 workflow 内 SSH 自动部署（按用户选择「手动下载 artifact」）。

## 4. 后端 workflow（重写，GGlBond0/xiaochan）

替换原 `.github/workflows/build-prod.yml`。要点：

- 触发：`workflow_dispatch`，无输入参数（构建不需要任何配置，配置全在服务器注入）。
- 步骤：checkout -> setup-java 17 temurin -> `mvn clean package -DskipTests` -> 上传 `target/xiaocan.jar` 为 artifact。
- **不再** 用 `cat > application.yaml` 写死配置。保留源码里的环境变量占位 yaml。
- artifact 名：`xiaocan-jar`，路径 `target/xiaocan.jar`，retention 30 天。

为什么这样：原 workflow 把 MySQL 密码硬编码进 jar 内 yaml，泄密且无法换库；重写后 jar 是配置无关的，靠 systemd env 注入，符合 R4.1 与 R7.1。

## 5. 前端 workflow（新增，GGlBond0/xiaocan-front）

新建 `.github/workflows/build-prod.yml`。要点：

- 触发：`workflow_dispatch`。
- 步骤：checkout -> setup-node 20 -> `npm config set registry https://registry.npmmirror.com` -> `npm ci`（无 lockfile 用 `npm install` 兜底）-> `npm run build` -> 将 `dist/` 压成 `dist.zip` -> 上传 artifact。
- artifact 名：`xiaocan-dist`，路径 `dist.zip`。

## 6. 服务器端配置设计

### 6.1 镜像源（国内，按 R1.3）
- apt：备份后换阿里云 `mirrors.aliyun.com` 的 22.04 源（含 `jammy` main/restricted/universe/multiverse + updates + security）。
- Maven：构建在 Actions 上跑，workflow 内用 `mvn clean package -DskipTests`（用 GitHub 默认中央仓；若慢可在 workflow 加阿里云 settings，可选）。
- npm：构建在 Actions 上跑，workflow 内 `npm config set registry https://registry.npmmirror.com`。
- 注：服务器端不构建，故服务器侧镜像源只需 apt（装 Redis/后续维护用）。

### 6.2 MySQL 8（复用已装）
- 已装在跑，**不重装**。root 密码由用户提供。
- 旧库 `xiaocan` 清掉重建：`DROP DATABASE xiaocan; CREATE DATABASE xiaocan CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`
- 创建账号：`xiaocan`@`localhost`，随机强密码，`GRANT ALL ON xiaocan.*`。
- 导入：`mysql xiaocan < ddl.sql`（含 cron ALTER）。
- 确认 `bind-address = 127.0.0.1`（已是）。

### 6.3 Redis（新装）
- `apt install -y redis-server`，`bind 127.0.0.1`，无密码（默认），`protected-mode yes`。
- 开机自启（`systemctl enable --now redis-server`）。

### 6.4 后端 systemd（按 R4.2/R4.3）
`/etc/systemd/system/xiaocan.service`：
```ini
[Unit]
Description=Xiaocan Backend
After=network.target mysql.service redis-server.service

[Service]
Type=simple
User=xiaocan
WorkingDirectory=/opt/xiaocan
EnvironmentFile=/etc/xiaocan/xiaocan.env
ExecStart=/usr/bin/java -Xms128m -Xmx256m -jar /opt/xiaocan/xiaocan.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```
`/etc/xiaocan/xiaocan.env`（chmod 600）：
```
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DB=xiaocan
MYSQL_USER=xiaocan
MYSQL_PASSWORD=<生成的强密码>
REDIS_HOST=127.0.0.1
REDIS_PASSWORD=
```
创建系统用户 `xiaocan`，`/opt/xiaocan` 归该用户。

### 6.5 Nginx（按 R6，独立端口 8088）
`/etc/nginx/sites-available/xiaocan.conf`：
```nginx
server {
    listen 8088;
    server_name _;   # IP:8088 访问

    root /var/www/xiaocan/dist;
    index index.html;

    # 后端 API：原样透传，不 rewrite（controller 已带 /api 前缀）
    location /api/ {
        proxy_pass http://127.0.0.1:10234;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Connection "";
        proxy_read_timeout 60s;
    }

    # 前端静态 + history 模式回退
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 静态资源缓存
    location ~* \.(js|css|png|jpg|svg|woff2?)$ {
        expires 7d;
        add_header Cache-Control "public, max-age=604800";
    }
}
```
`ln -sf` 到 sites-enabled。**不删 default、不动 80 上的 cpa/napi/note**。
云安全组需放行 8088（提醒用户检查）。

## 7. 安全

- 10234/3306/6379 绑 127.0.0.1，不对外；**不启用 UFW**（避免影响他人 docker/端口），端口隔离靠「服务绑 127.0.0.1」+ 云安全组只放 22/80。
- `xiaocan.env` 600/640 权限，不入仓库。
- 部署完成后建议：改 root 密码、换 SSH 密钥登录（你已配免密，进一步可禁密码登录）。
- jar 内不含明文 MySQL 密码（配置环境变量注入，R4.1）。

## 7.5 旧残留清理（部署前必做）

1. 停旧后端：`kill 736528`（PID 可能变，按 `pgrep -f 'java -jar xiaocan.jar'` 找）。释放 ~220M 内存。
2. 删旧 Nginx 站点：`rm /etc/nginx/sites-enabled/xiaocan /etc/nginx/sites-enabled/xiaocan-front`（保留 available 备查），`nginx -t && systemctl reload nginx`。**禁碰 cpa/napi/note**。
3. 备份后删旧目录：`mv /opt/xiaocan /opt/xiaocan.old.$(date +%s)`、`mv /opt/xiaocan_front /opt/xiaocan_front.old.$(date +%s)`（先 mv 不直接 rm，便于回滚；确认新部署 OK 后再清）。
4. 旧 jar 绑 `*:10234` 公网暴露问题随停进程自动消除。
- `xiaocan.env` 600 权限，不入仓库。
- 部署完成后建议：改 root 密码、换 SSH 密钥登录。
- jar 内不含明文 MySQL 密码（配置环境变量注入，R4.1）。

## 8. 回滚点

- 任一服务起不来：`systemctl status` / `journalctl -u xiaocan -n 200` 看日志。
- Nginx 起不来：`nginx -t` 校验配置；可 `rm sites-enabled/xiaocan.conf` 回到默认站。
- 数据库导入失败：库可 DROP 重建后重新导入 ddl.sql（已 DROP IF EXISTS）。
- 后端回滚到旧版：保留上一版 jar 为 `xiaocan.jar.bak`，`mv` 还原后 restart。
- 前端回滚：`dist` 上一版备份为 `dist.bak`，`cp -r` 还原后 `nginx -s reload`。

## 9. Tradeoffs

- **手动下载 artifact vs 自动 SSH 部署**：按用户选择手动，省 SSH 密钥 secret 配置，代价是每次改代码要手动下载上传。
- **Redis 无密码**：与服务本机部署 + 仅 127.0.0.1 监听匹配；若日后 Redis 独立/多租户需加密码并同步 env。
- **仅 IP+HTTP**：无 HTTPS，token 经 URL/Header 明文传输。符合用户当前选择；后续上域名时可加 Let's Encrypt。
