# Implement — 部署小蚕到 Ubuntu 云服务器

> 服务器现状见 design §0。内存极紧张(1.7G/可用217M)，复用已装 MySQL/Nginx/JDK，只新装 Redis。
> 先清旧残留释放内存与端口，再建新部署。⚠️ 标注步骤需谨慎。

## 阶段 A：在你的 fork 上准备 workflow（本地 Windows）

### A0 clone 两个 fork
```powershell
cd C:\D\AI\Projects
git clone https://github.com/GGlBond0/xiaochan.git xiaochan-fork
git clone https://github.com/GGlBond0/xiaocan-front.git xiaocan-front-fork
```
- 验证：`cd xiaochan-fork; git remote -v` → GGlBond0/xiaochan。

### A1 重写后端 workflow
- 写 `xiaochan-fork/.github/workflows/build-prod.yml`（design §4）：
  `workflow_dispatch` → checkout → setup-java 17 temurin cache maven → `mvn clean package -DskipTests` → upload-artifact `target/xiaocan.jar` name `xiaocan-jar`。
- **删除**原文件里 `cat > application.yaml` 整段（不再硬编码配置），保留源码环境变量占位 yaml。
- 验证：`git diff` 只改 workflow；`xiaochan-fork/src/main/resources/application.yaml` 仍是 `${MYSQL_HOST:...}` 占位。
- commit + push。

### A2 新增前端 workflow
- 写 `xiaocan-front-fork/.github/workflows/build-prod.yml`（design §5）：
  `workflow_dispatch` → checkout → setup-node 20 → `npm config set registry https://registry.npmmirror.com` → `npm ci || npm install` → `npm run build` → 打包 `dist` 为 `dist.zip` → upload-artifact name `xiaocan-dist`。
- commit + push。

### A3 触发并下载 artifact
- 两个 fork 的 Actions 页手动各跑一次 `Build Production`。
- 下载 `xiaocan-jar.zip`、`xiaocan-dist.zip`。
- 验证：本地解压得到 `xiaocan.jar` 和 `dist/`。

## 阶段 B：清旧残留（服务器，只读勘察已完成，本阶段开始改动）

### B1 停旧后端 ⚠️
```bash
ssh root@121.91.175.192 'P=$(pgrep -f "java -jar xiaocan.jar"); echo "kill $P"; kill $P; sleep 2; pgrep -f "java -jar xiaocan.jar" || echo STOPPED'
```
- 验证：`STOPPED`；`ss -tlnp | grep 10234` 为空。释放 ~220M。

### B2 删旧 Nginx 站点 ⚠️（只删 xiaocan 两个，禁碰 cpa/napi/note）
```bash
ssh root@121.91.175.192 'rm -f /etc/nginx/sites-enabled/xiaocan /etc/nginx/sites-enabled/xiaocan-front && nginx -t && systemctl reload nginx'
```
- 验证：`ls /etc/nginx/sites-enabled/` 只剩 cpa/napi/note；`curl -I http://127.0.0.1/` 不再返回旧 xiaocan 前端。

### B3 备份旧目录（mv 不直接删，便于回滚）
```bash
ssh root@121.91.175.192 'mv /opt/xiaocan /opt/xiaocan.old.$(date +%s); mv /opt/xiaocan_front /opt/xiaocan_front.old.$(date +%s); ls -d /opt/xiaocan*'
```
- 验证：旧目录已改名，`/opt/xiaocan` 不存在。

## 阶段 C：服务器基础（补装 Redis、镜像源、时区）

### C1 apt 换阿里云源
```bash
ssh root@121.91.175.192 'cp /etc/apt/sources.list /etc/apt/sources.list.bak.$(date +%s) 2>/dev/null; sed -i "s|http://archive.ubuntu.com|https://mirrors.aliyun.com|g; s|http://security.ubuntu.com|https://mirrors.aliyun.com|g" /etc/apt/sources.list; apt update'
```
- 验证：`apt update` 无 ERR。

### C2 装 Redis ⚠️（内存紧，装完确认不爆）
```bash
ssh root@121.91.175.192 'apt install -y redis-server && systemctl enable --now redis-server'
```
- 验证：`redis-cli ping` → PONG；`ss -tlnp | grep 6379` 仅 127.0.0.1。
- 确认 `free -h` 仍有余量。

### C3 时区
```bash
ssh root@121.91.175.192 'timedatectl set-timezone Asia/Shanghai && date'
```
- 验证：`date` 显示 CST。

## 阶段 D：数据库（复用已装 MySQL 8）

### D1 建账号、重建库 ⚠️ 含密码
- 用用户提供的 MySQL root 密码 `$MYSQL_ROOT_PW`。
```bash
RAND_PW=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-20)
# 记下 $RAND_PW（xiaocan 账号密码），写进 env
ssh root@121.91.175.192 "mysql -uroot -p'$MYSQL_ROOT_PW' <<SQL
DROP DATABASE IF EXISTS xiaocan;
CREATE DATABASE xiaocan CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'xiaocan'@'localhost' IDENTIFIED BY '$RAND_PW';
GRANT ALL PRIVILEGES ON xiaocan.* TO 'xiaocan'@'localhost';
FLUSH PRIVILEGES;
SQL"
```

### D2 导入 DDL
- 上传 ddl.sql：`scp xiaochan-main/ddl.sql root@121.91.175.192:/root/`
```bash
ssh root@121.91.175.192 "mysql -uroot -p'$MYSQL_ROOT_PW' xiaocan < /root/ddl.sql"
```
- 验证：
```bash
ssh root@121.91.175.192 "mysql -uroot -p'$MYSQL_ROOT_PW' -e \"USE xiaocan; SHOW TABLES; SHOW COLUMNS FROM monitor_config LIKE 'cron';\""
```
  → 5 表 + `cron` 列。

### D3 确认绑本机
```bash
ssh root@121.91.175.192 'ss -tlnp | grep 3306'
```
- 验证：仅 127.0.0.1（已是）。

## 阶段 E：后端部署（systemd + env 注入）

### E1 用户与目录
```bash
ssh root@121.91.175.192 'useradd -r -m -d /opt/xiaocan -s /usr/sbin/nologin xiaocan 2>/dev/null; mkdir -p /opt/xiaocan /etc/xiaocan; chown xiaocan:xiaocan /opt/xiaocan'
```

### E2 上传 jar
- 本地：`scp xiaocan.jar root@121.91.175.192:/opt/xiaocan/`
```bash
ssh root@121.91.175.192 'chown xiaocan:xiaocan /opt/xiaocan/xiaocan.jar; chmod 644 /opt/xiaocan/xiaocan.jar'
```

### E3 写 env（chmod 640）⚠️ 含密码
```bash
ssh root@121.91.175.192 "cat > /etc/xiaocan/xiaocan.env <<EOF
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DB=xiaocan
MYSQL_USER=xiaocan
MYSQL_PASSWORD=$RAND_PW
REDIS_HOST=127.0.0.1
REDIS_PASSWORD=
EOF
chown root:xiaocan /etc/xiaocan/xiaocan.env; chmod 640 /etc/xiaocan/xiaocan.env"
```
- 验证：`ls -l /etc/xiaocan/xiaocan.env` 权限 640。

### E4 systemd unit（JVM -Xms128m -Xmx256m，内存紧）
- 写 `/etc/systemd/system/xiaocan.service`（design §6.4）。
```bash
ssh root@121.91.175.192 'systemctl daemon-reload && systemctl enable --now xiaocan'
```
- 验证：
  - `systemctl status xiaocan` → active(running)
  - `journalctl -u xiaocan -n 80 --no-pager` 无连不上 MySQL/Redis 的异常
  - `ss -tlnp | grep 10234` **仅 127.0.0.1**（关键：不再绑 `*`）
  - `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:10234/api/user/getUserInfo` 非 000
  - `free -h` 确认内存未爆，无 OOM

## 阶段 F：前端部署

### F1 上传并解压 dist
- 本地：`scp dist.zip root@121.91.175.192:/root/`
```bash
ssh root@121.91.175.192 'rm -rf /var/www/xiaocan/dist /var/www/xiaocan/dist-tmp; mkdir -p /var/www/xiaocan; unzip -o /root/dist.zip -d /var/www/xiaocan/dist-tmp; if [ -f /var/www/xiaocan/dist-tmp/index.html ]; then mv /var/www/xiaocan/dist-tmp /var/www/xiaocan/dist; else mv /var/www/xiaocan/dist-tmp/dist /var/www/xiaocan/dist; rm -rf /var/www/xiaocan/dist-tmp; fi; chown -R www-data:www-data /var/www/xiaocan; ls /var/www/xiaocan/dist/index.html'
```
- 验证：`/var/www/xiaocan/dist/index.html` 存在。

## 阶段 G：Nginx（独立端口 8088，禁碰他人 80）

### G1 写配置（design §6.5，listen 8088）
- 写 `/etc/nginx/sites-available/xiaocan.conf`，`ln -sf` 到 sites-enabled。
- **不删 default、不动 80 上的 cpa/napi/note**。
```bash
ssh root@121.91.175.192 'nginx -t && systemctl reload nginx'
```
- 验证：
  - `curl -I http://127.0.0.1:8088/` → 200，text/html
  - `curl -I http://127.0.0.1:8088/api/user/getUserInfo` 非 502/504

### G2 云安全组放行 8088
- 提醒用户在云控制台安全组入方向放行 TCP 8088（代码无法操作，需人工）。
- 验证：外部 `curl -I http://121.91.175.192:8088/` 通（本机验证用 127.0.0.1:8088）。

## 阶段 H：端到端验证（Acceptance Criteria）

- [ ] 浏览器 `http://121.91.175.192:8088/` 打开小蚕首页，路由不 404
- [ ] `/api/*` 返回业务 JSON 非 502
- [ ] `systemctl status xiaocan` active；重启后自启
- [ ] MySQL `xiaocan` 5 表 + monitor_config.cron
- [ ] `ss -tlnp` 中 3306/6379/10234 仅 127.0.0.1；公网 10234 不再暴露；8088 公网可达
- [ ] `unzip -p xiaocan.jar BOOT-INF/classes/application.yaml | grep -i password` 无明文 MySQL 密码
- [ ] 两个 fork Actions 各能构建产出 artifact
- [ ] 提交不含任何密码；他人站点/项目未被改动（`ls sites-enabled` 仍含 cpa/napi/note）
- [ ] `free -h` 无 OOM，服务稳定运行

## 阶段 I：收尾

- 确认新部署稳定后清理旧备份：`rm -rf /opt/xiaocan.old.* /opt/xiaocan_front.old.*`（稳妥起见保留 1-2 天再删）。
- 提醒用户改 root 密码、禁 SSH 密码登录（已配免密）。
- 本地安全保存生成的 `xiaocan` MySQL 账号密码（不入仓库）。
