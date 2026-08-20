# 代理结构审计 + 最终决定（2026-08-21）

## 决策
- **小蚕不支持境外 IP** → 1024proxy（HK 网关）方案作废。
- **只保留提取模式**（国内 IP 代理）作为唯一代理模式，网关模式全部移除。
- MessageHttp（wxpusher 微信推送/验证码）**保持直连**，不纳入代理。

## 本次改动（已本地编译通过）
1. `ProxyHolder`：删除网关分支（GatewayCfg/parseGateway/sid 生成/认证 attach），只留提取模式（plain ip:port→SOCKS5 / bilinip JSON→HTTP）。
2. `ProxySpec`：简化为 `(host, port, Proxy.Type)`，去掉 user/pass/sid。
3. `ProxyAuth`：**删除**（国内 IP 代理无鉴权，不再需要 Authenticator/ThreadLocal）。
4. `XiaochanHttp` / `LotteryHttp`：`executeWithProxy` 签名 `Function<String[],HttpRequest>` → `Supplier<HttpRequest>`（lambda 本就不使用代理参数，消除死代码）；移除 `ProxyAuth` 调用与 `asHostPort()`。
5. 业务代理 key 语义不变：抢单/霸王餐按 `silk_id` 隔离，无登录态列表/搜索共用 `shared`。

## 文件
- 删 `ProxyAuth.java`
- 改 `ProxyHolder.java` / `ProxySpec.java` / `XiaochanHttp.java` / `LotteryHttp.java`

## 验证
- 本地 `mvn -o compile` EXIT 0。
- 残留引用检查：无 ProxyAuth / Gateway / asHostPort / sid。

## 追加：白金隧道池适配（2026-08-21）
- 代理源改用 xiequ `getturn51` 白金隧道池：`api.xiequ.cn/VAD/GetIp.aspx?act=getturn51&uid=...&vkey=...&num=10&time=6&...`
- 实测：返回标准 JSON `{code:0,data:[{IP,Port}]}`，入口 IP 同段(117.89.88.x)、端口各异；入口作为 HTTP 代理对小蚕 gw 通（code:0）。
- 协议=HTTP，复用现有 bilinip JSON→HTTP 解析分支；返回格式=JSON(data:[{IP,Port}])，现有解析天然兼容。
- 改造 ProxyHolder：
  - `fetchProxy` → `fetchProxyList` 返回全部端点；
  - 新增 `ROUND_ROBIN` AtomicInteger 游标，按账号 key 轮流分配列表槽位（不同 key→不同隧道端口→独立轮换出口）；
  - 同 key TTL 内经缓存复用同端口（粘性），invalidate(key) 换端口；
  - 日志带 `slot=N/total`，无凭据。
- 注意：提取链接含 vkey（账号凭据），只放 proxy_config.api_url，不入源码/不提交。

## 部署结果（2026-08-21 实测）
- 本地 mvn package BUILD SUCCESS → 42M JAR，md5 与远端一致。
- scp 限速上传超时但数据完整到达（md5 匹配），原子 mv 替换 + chown xiaocan:644。
- 生产 DB proxy_config 更新：enabled=1, api_url=白金隧道getturn51, ttl=28, retry=3。
- 重启 xiaocan 成功（4.38s，HikariPool start completed，127.0.0.1:10234）。
- 实测：POST /api/xiaochan/query 经白金隧道代理返回 200，data 30 家店（首条三叔粥铺）。
  - 关键：生产机**直连** gw 403（生产出口IP已被小蚕WAF封），**经隧道代理**后 200 —— 隧道正是必需。
  - 日志 `获取代理 key=shared 117.89.88.x:port type=HTTP slot=N/10`，按key分配端口正常。
  - 偶发 503 隧道轮换间隙，重试(3)自动换 slot，最终成功。
