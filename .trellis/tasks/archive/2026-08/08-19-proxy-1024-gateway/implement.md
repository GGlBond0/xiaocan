# Implement: 1024proxy HTTP 网关按账号 sid

## Checklist

1. [ ] 新增 `ProxySpec`、`ProxyAuth`（ThreadLocal Authenticator + 打开 Basic tunneling）
2. [ ] 改造 `ProxyHolder`：解析网关 URL、sid 缓存、提取模式保留、日志不含密码
3. [ ] 改造 `XiaochanHttp.executeWithProxy`、`LotteryHttp.executeWithProxy`：attach HTTP 代理认证，finally 清理
4. [ ] 本地 `mvn -DskipTests package`
5. [ ] 生产备份 JAR → 上传 → restart
6. [ ] 更新 `proxy_config.api_url` 为网关 URL（设置页或 SQL），确认 invalidate
7. [ ] SSH 看 `info.log` 获取代理；确认不再出现 bilinip `ErrBan`；抽一次上游或等监控 cron

## Files

- `src/main/java/io/github/xiaocan/http/ProxySpec.java`（新）
- `src/main/java/io/github/xiaocan/http/ProxyAuth.java`（新）
- `src/main/java/io/github/xiaocan/http/ProxyHolder.java`
- `src/main/java/io/github/xiaocan/http/XiaochanHttp.java`
- `src/main/java/io/github/xiaocan/http/LotteryHttp.java`
- 不改前端、不改 DDL、不把凭据写入仓库

## Validation

```bash
# 本机（绝对路径 JDK/Maven，见 local-build-toolchain）
mvn -DskipTests package

# 生产部署后
ssh root@121.91.175.192 'grep -E "获取代理|代理 API|ErrBan|代理不可用" /opt/xiaocan/logs/info.log /opt/xiaocan/logs/error.log | tail -n 40'
```

网关连通（生产，凭据不进仓库脚本）：`curl -x hk.1024proxy.io:3000 -U '<user>-region-HK-sid-test-t-5:<pass>' https://ipinfo.io/json`

## Rollback

- `systemctl stop xiaocan`；恢复 `/opt/xiaocan/xiaocan.jar.bak.<ts>`；restart
- 或仅把 `api_url` 改回 bilinip / `enabled=false` 后保存

## Notes

- 直接 SQL 改 `api_url` 不会 `invalidate()`；优先设置页保存，或改完重启。
- 大 JAR 用 rsync，避免 scp 卡死 sshd。
