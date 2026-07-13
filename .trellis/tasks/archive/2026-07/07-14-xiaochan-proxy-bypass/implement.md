# Implement — XiaochanHttp 动态代理

## 阶段 1：先修本机 DNS（让 api.xiequ.cn 正确解析）⚠️ 影响全服务器 DNS

```bash
ssh root@121.91.175.192 'cp /etc/systemd/resolved.conf /etc/systemd/resolved.conf.bak.$(date +%s); grep -q "^DNS=" /etc/systemd/resolved.conf && sed -i "s/^DNS=.*/DNS=223.5.5.5 114.114.114.114/" /etc/systemd/resolved.conf || sed -i "/^\[Resolve\]/a DNS=223.5.5.5 114.114.114.114" /etc/systemd/resolved.conf; systemctl restart systemd-resolved; sleep 1; echo "==verify=="; getent hosts api.xiequ.cn'
```
- 验证：`getent hosts api.xiequ.cn` 不再是 127.0.0.1，应为真实公网 IP
- 若仍 127.0.0.1：改用 `resolvectl dns eth0 223.5.5.5 114.114.114.114` 追加，再验证

## 阶段 2：改代码（xiaochan-fork）

### 2.1 新增 ProxyHolder.java
路径 `src/main/java/io/github/xiaocan/http/ProxyHolder.java`
- 静态持有当前代理 + 获取时间 + TTL
- `getProxy()` 返回 `String[] {host, port}` 或 null
- `invalidate()` 清缓存（403/失败时调用）
- 通过 `SpringContextUtil` 读 env：PROXY_ENABLED / PROXY_API_URL / PROXY_TTL / PROXY_RETRY
- 取代理：`HttpUtil.createGet(proxyApiUrl).timeout(5000).execute()` 解析 JSON data[0]
- 线程安全（synchronized 或 AtomicReference）

### 2.2 改 XiaochanHttp.java
- `postWithRes` 与 `searchAddress` 的 `execute()` 前加 `.setHttpProxy(host, port)`（仅 PROXY_ENABLED=true）
- 抽 `executeWithProxy(Function<Proxy, HttpResponse> reqFn)` 包装重试：
  - 取代理 → 发请求 → 200 返回；403 或异常 → invalidate + 重试，最多 PROXY_RETRY 次
- 超时从 3000 提到 PROXY_REQUEST_TIMEOUT（5000）

### 2.3 不改 controller（用 SpringContextUtil 读 env）
- 确认 `SpringContextUtil` 能 `getEnvironment()`；若只持有 ApplicationContext 用 `ctx.getEnvironment()`

### 2.4 本地编译验证
```powershell
cd C:\D\AI\Projects\xiaochan-fork
# 需 JDK17 + Maven；若本机无，靠 GitHub Actions 编译验证
mvn -q -DskipTests compile 2>&1 | tail -20
```
- 若本机无 Maven：跳过本地编译，靠 Actions 构建结果验证

## 阶段 3：push + 构建新 jar
```powershell
cd C:\D\AI\Projects\xiaochan-fork
git add -A; git -c user.name=lz -c user.email=lz@local commit -m "XiaochanHttp: 加动态代理池绕过小蚕 WAF 403"
git push origin main
```
- GitHub Actions → Build Production JAR → 手动触发 → 下载 xiaocan-jar artifact
- 放到本地，scp 上传

## 阶段 4：服务器部署更新

### 4.1 上传新 jar + 备份旧 jar
```bash
scp <新jar> root@121.91.175.192:/opt/xiaocan/xiaocan.jar.new
ssh root@121.91.175.192 'cp /opt/xiaocan/xiaocan.jar /opt/xiaocan/xiaocan.jar.bak; mv /opt/xiaocan/xiaocan.jar.new /opt/xiaocan/xiaocan.jar; chown xiaocan:xiaocan /opt/xiaocan/xiaocan.jar; chmod 644 /opt/xiaocan/xiaocan.jar'
```

### 4.2 更新 env 加 PROXY_* ⚠️ 含 vkey
```bash
ssh root@121.91.175.192 'cat >> /etc/xiaocan/xiaocan.env <<EOF
PROXY_ENABLED=true
PROXY_API_URL=http://api.xiequ.cn/VAD/GetIp.aspx?act=get&uid=183587&vkey=<KEY>&num=1&time=30&plat=0&re=0&type=1&so=1&ow=1&spl=1&addr=&db=1
PROXY_TTL=28
PROXY_RETRY=3
PROXY_REQUEST_TIMEOUT=5000
EOF
chmod 640 /etc/xiaocan/xiaocan.env'
```

### 4.3 重启 + 验证
```bash
ssh root@121.91.175.192 'systemctl restart xiaocan && sleep 6; systemctl is-active xiaocan; echo "==日志=="; tail -20 /var/log/xiaocan/xiaocan.log'
```

## 阶段 5：端到端验证（Acceptance Criteria）

```bash
# 搜索地址（带签名经代理）
curl -s -m 20 "http://127.0.0.1:10234/api/location/searchAddress?keyword=北京&cityCode=110100" | head -c 500
# 活动 /api/xiaochan/query 需先有用户 token，用前端实际操作验证
```
- [ ] 搜索地址返回真实地址列表（非"状态码错误:403"）
- [ ] 后端日志无 403（或 403 后自动换代理成功）
- [ ] 代理 API 调用频率可控（不超 10/秒）
- [ ] jar/env 无明文 vkey 入仓库（vkey 仅在 env）
- [ ] 他人项目不受影响
- [ ] DNS 修改后服务器其他服务正常（ping 测试）

## 阶段 6：收尾
- 验证稳定后，记录代理配置到 memory
- 提醒：代理每日 1000 IP 限额，监控用量
