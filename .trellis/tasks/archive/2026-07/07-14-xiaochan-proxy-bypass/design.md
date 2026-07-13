# Design — XiaochanHttp 动态代理绕过 WAF

## 0. 研究结论（2026-07-14 已实测）

- 服务器公网 IP 直连 `https://gw.xiaocantech.com/rpc` → 403
- 代理 API（xiequ.cn）可用：返回 JSON `{"code":0,"data":[{"IP":"...","Port":3828,...}]}`
- 代理本身能通外网（httpbin 经代理 200）
- 经代理 curl 小蚕网关仍 403 —— 但那是**裸 curl 无签名**，不能据此判断方案无效
- **决定性验证**：让后端**带签名**的请求走代理（本设计的目标），实测搜索地址接口
- DNS 坑：本机 DNS（华为云 100.125.1.250）把 `api.xiequ.cn` 污染成 127.0.0.1；真实 IP 是 `112.132.221.32`（经 114.114.114.114 查到）。需修本机 DNS 或代码层规避

## 1. 改造目标

让 `XiaochanHttp` 所有调 `gw.xiaocantech.com` 的请求经动态 HTTP 代理发出：
1. 调代理 API 取一个代理 IP:Port（30 秒有效）
2. 缓存该代理，30 秒内复用，过期重新取
3. 用该代理发带签名的请求到小蚕网关
4. 代理失败/小蚕返回 403 时，换下一个代理重试 N 次

## 2. 代理 API 细节（已确认）

- URL：`http://api.xiequ.cn/VAD/GetIp.aspx?act=get&uid=183587&vkey=<KEY>&num=1&time=30&plat=0&re=0&type=1&so=1&ow=1&spl=1&addr=&db=1`
- `type=1` = HTTP/HTTPS 代理；`db=1` = JSON 格式；`num=1` = 1 个；`time=30` = 30 秒有效
- 返回：`{"code":0,"data":[{"IP":"x.x.x.x","Port":3828,"IpAddress":"..."}]}`
- 限额：每日 1000 IP，调用 ≤10 次/秒
- 白名单：已加 `121.91.175.192`（必须，否则报"请先添加白名单"）

## 3. 环境变量注入（与现有配置一致）

新增到 `/etc/xiaocan/xiaocan.env`：
```
PROXY_ENABLED=true
PROXY_API_URL=http://api.xiequ.cn/VAD/GetIp.aspx?act=get&uid=183587&vkey=<KEY>&num=1&time=30&plat=0&re=0&type=1&so=1&ow=1&spl=1&addr=&db=1
PROXY_TTL=28            # 复用阈值，留 2 秒缓冲（代理 30s 有效）
PROXY_RETRY=3           # 单次业务请求最大换代理重试次数
PROXY_REQUEST_TIMEOUT=5000  # 经代理的请求超时（代理比直连慢，从 3000 提到 5000）
```
`vkey` 是敏感值，只进 env（640），不入仓库、不入 jar。

## 4. 代码改造

### 4.1 新增 ProxyHolder（代理获取与缓存）
- 单例/静态持有，线程安全（后端有定时任务并发调用）
- 字段：当前代理 `ip:port`、获取时间戳、TTL
- `getProxy()`：若缓存未过期返回缓存；否则调 PROXY_API_URL 取新代理，更新缓存
- 取代理本身用 hutool `HttpUtil.createGet(proxyApiUrl)`，**不带代理**（取代理不能走代理）
- 解析 JSON 拿 `data[0].IP` 和 `data[0].Port`
- 取代理失败（code!=0 或网络异常）→ 抛清晰异常或返回 null

### 4.2 改 postWithRes / searchAddress 走代理
- hutool 支持：`HttpUtil.createPost(url).setHttpProxy(host, port).headerMap(...).body(...).execute()`
- 仅当 `PROXY_ENABLED=true` 时套代理；false 时保持直连（便于排错/降级）
- 失败重试逻辑（新增 `executeWithProxy` 包装）：
  ```
  for i in 1..PROXY_RETRY:
    proxy = ProxyHolder.getProxy()   // 第 1 次用缓存，失败后强制刷新
    try: resp = createPost(...).setHttpProxy(proxy).execute(); if ok return; else if 403 -> 强制刷新代理重试
    catch (连接失败/超时): ProxyHolder.invalidate(); continue
  throw BusinessException("代理不可用或小蚕持续 403")
  ```
- 403 视为代理 IP 也被风控 → 强制换新代理重试（不直接抛，给别的 IP 机会）

### 4.3 配置读取
- `XiaochanHttp` 当前是 `new` 出来的（非 Spring bean，见 LocationController `private final XiaochanHttp xiaochanHttp = new XiaochanHttp();`）
- 两种选择：
  - A) 改成 Spring `@Component`，`@Value` 注入 env（动 controller，但更规范）
  - B) 用 `SpringContextUtil`（项目已有 `utils/SpringContextUtil.java`）在 XiaochanHttp 内 `getEnvironment()` 读 env，不改 controller、不破坏现有 `new` 用法
- **选 B**：侵入最小，不碰 controller。ProxyHolder 通过 SpringContextUtil 读 PROXY_* env

### 4.4 DNS 污染规避
- 本机 DNS 把 api.xiequ.cn 解析成 127.0.0.1。取代理请求会失败。
- 方案：给 systemd-resolved 加国内 DNS。改 `/etc/systemd/resolved.conf`：`DNS=223.5.5.5 114.114.114.114`，`systemctl restart systemd-resolved`。
- 影响面：全服务器 DNS 多了国内公共源，更准，无负面影响（华为云 DNS 仍作 fallback）。
- 备选（不动全局 DNS）：代码层在取代理时直接用真实 IP `112.132.221.32` + Host 头 —— 但 IP 会变（CDN），不稳。**优先改 DNS**。

## 5. 部署更新流程

1. 本地改 `xiaochan-fork` 代码（XiaochanHttp + 新增 ProxyHolder）
2. push fork → 手动触发 Build workflow → 下载新 jar
3. scp 新 jar 到 `/opt/xiaocan/xiaocan.jar`
4. 更新 `/etc/xiaocan/xiaocan.env` 加 PROXY_* 变量
5. 修本机 DNS（resolved.conf）
6. `systemctl restart xiaocan`
7. 验证搜索地址 + 活动列表

## 6. 回滚点

- 代码出问题：保留旧 jar `xiaocan.jar.bak`，env 设 `PROXY_ENABLED=false` 直连回退（直连会 403，但至少不崩），或还原旧 jar
- DNS 改坏：还原 `resolved.conf`，`systemctl restart systemd-resolved`
- 代理服务挂：`PROXY_ENABLED=false`，直连（接受 403 直到代理恢复）

## 7. Tradeoffs

- HTTP 代理（非 SOCKS5）：hutool 原生支持，改动最小；若小蚕对代理协议无要求则足够
- 30 秒短有效期 + 缓存 28 秒：平衡代理消耗（每日 1000 限额）与可用性
- 403 即换代理重试：住宅 IP 大多没被风控，给几次机会比直接失败好；但若全 IP 段被封则仍会失败（届时需换思路：换代理产品/协议）
- 不改 controller（选 B SpringContextUtil）：保持现有 `new XiaochanHttp()` 调用不变，最小侵入
