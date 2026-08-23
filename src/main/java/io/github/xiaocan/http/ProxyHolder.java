package io.github.xiaocan.http;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.github.xiaocan.model.entity.ProxyConfigEntity;
import io.github.xiaocan.service.ProxyConfigService;
import io.github.xiaocan.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 代理持有者：从全局配置（数据库 proxy_config 表）读取代理参数，按账号 key 分缓存。
 * 配置来源优先级：运行时读 ProxyConfigService.getEntity()（带内存快照缓存），
 * 异常或容器未就绪时回退 Spring Environment（systemd EnvironmentFile 注入）默认值。
 * 修改配置经 /api/proxy/config 落库后调用 invalidate() 即时生效，无需重启服务。
 *
 * 仅支持【提取模式】：api_url 为提取接口地址，GET 返回国内 IP:Port（小蚕仅支持国内 IP，
 * 不支持境外 IP / 带鉴权的代理网关）。
 * - 纯文本 ip:port（如 xiequ）按 SOCKS5 使用；
 * - 旧 bilinip JSON（{code,data:[{IP,Port}]}）按 HTTP 使用。
 *
 * 账号维度：
 * - getProxy(accountKey, force)：同 key 在 TTL 内复用同一 IP；不同 key 独立缓存。
 * - 无登录态/ silk_id=0 使用 key "shared"。
 * - 失败换代理应 invalidate(accountKey)，避免拖垮其它账号缓存。
 *
 * 并发设计：
 * - getProxy 不在持锁期间执行同步 HTTP，避免阻塞 invalidate 与并发取代理；
 *   仅在读/写 cache 时短暂持锁。
 * - loadCfg 不在持 ProxyHolder.class 锁时调用 ProxyConfigService，避免与
 *   ProxyConfigServiceImpl.updateConfig 形成反向锁顺序死锁。
 */
@Slf4j
public class ProxyHolder {

    /** 无账号/匿名请求共用 key */
    public static final String SHARED_KEY = "shared";

    private static final class CacheEntry {
        /** 缓存的 IP:Port */
        final String[] proxy;
        /** 代理协议；xiequ plain ip:port 是 SOCKS5，旧 JSON 默认 HTTP */
        final Proxy.Type proxyType;
        /** 隧道池模式下本次分配到的列表索引；普通模式 -1 */
        final int endpointIndex;
        final long cachedAt;

        CacheEntry(String[] proxy, Proxy.Type proxyType, int endpointIndex, long cachedAt) {
            this.proxy = proxy;
            this.proxyType = proxyType;
            this.endpointIndex = endpointIndex;
            this.cachedAt = cachedAt;
        }
    }

    /** 提取到的端点列表槽位游标：按账号 key 轮流分配不同隧道端口（实现每账号独立出口） */
    private static final AtomicInteger ROUND_ROBIN = new AtomicInteger(0);

    /**
     * 多隧道池轮换游标：poolList 非空时，每 ttl 周期换一个池(act=getturn{N})，
     * 摊薄高峰段单池 IP 劣化影响。与端点槽位游标 ROUND_ROBIN 语义独立。
     */
    private static final AtomicInteger POOL_ROUND_ROBIN = new AtomicInteger(0);

    private static final class ExtractProxy {
        final String host;
        final int port;
        final Proxy.Type type;

        ExtractProxy(String host, int port, Proxy.Type type) {
            this.host = host;
            this.port = port;
            this.type = type;
        }
    }

    /** 按账号 key 缓存出口代理 */
    private static final Map<String, CacheEntry> cacheByKey = new ConcurrentHashMap<>();

    /** 配置内存快照：减少运行时打 DB 频率 */
    private static volatile ProxyConfigEntity cfgSnapshot;
    private static volatile long cfgLoadedAt;
    /** 配置快照刷新间隔（毫秒）：保存后 invalidate() 也会立即清快照，无需等过期 */
    private static final long CFG_TTL = 5000L;

    public static boolean enabled() {
        return enabledOf(loadCfg());
    }

    public static int retry() {
        ProxyConfigEntity c = loadCfg();
        if (c != null && c.getRetry() != null) {
            return c.getRetry();
        }
        return Integer.parseInt(env("PROXY_RETRY", "3"));
    }

    public static int requestTimeout() {
        ProxyConfigEntity c = loadCfg();
        if (c != null && c.getRequestTimeout() != null) {
            return c.getRequestTimeout();
        }
        return Integer.parseInt(env("PROXY_REQUEST_TIMEOUT", "5000"));
    }

    /**
     * 按账号 key 取代理（仅提取模式）。未命中则锁外 HTTP 拉取再写回。
     *
     * @param accountKey 账号标识（silk_id 字符串）；null/blank/"0" 归一为 {@link #SHARED_KEY}
     * @param force      true 时跳过缓存强制重取（重试换代理）
     */
    public static ProxySpec getProxy(String accountKey, boolean force) {
        ProxyConfigEntity c = loadCfg();
        if (!enabledOf(c)) {
            return null;
        }
        return getExtractProxy(normalizeKey(accountKey), force, c, apiUrlOf(c));
    }

    /**
     * 把 HTTP 或 SOCKS5 代理挂到 Hutool 请求上。国内 IP 代理无鉴权，无需认证。
     */
    public static void attach(HttpRequest req, ProxySpec spec) {
        if (req == null || spec == null) {
            return;
        }
        if (spec.isSocks5()) {
            req.setProxy(new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(spec.getHost(), spec.getPort())));
        } else {
            req.setHttpProxy(spec.getHost(), spec.getPort());
        }
    }

    /**
     * 仅失效指定账号的代理缓存（失败换代理用，不影响其它账号）。
     */
    public static void invalidate(String accountKey) {
        String key = normalizeKey(accountKey);
        cacheByKey.remove(key);
        log.info("失效代理缓存 key={}", key);
    }

    /**
     * 失效全部代理缓存与配置快照，使下次取代理/读配置重读 DB。
     * ProxyConfigServiceImpl.updateConfig 落库后调用本方法实现即时生效。
     */
    public static synchronized void invalidate() {
        cacheByKey.clear();
        cfgSnapshot = null;
        cfgLoadedAt = 0;
        log.info("失效全部代理缓存与配置快照");
    }

    /**
     * 将 silk_id / 原始 key 归一为缓存 key。
     * null、空串、"0" → shared（匿名/无登录态）。
     */
    public static String normalizeKey(String accountKey) {
        if (accountKey == null) {
            return SHARED_KEY;
        }
        String k = accountKey.trim();
        if (k.isEmpty() || "0".equals(k)) {
            return SHARED_KEY;
        }
        return k;
    }

    /** silk_id 数值 → 缓存 key */
    public static String keyOfSilkId(Integer silkId) {
        if (silkId == null || silkId == 0) {
            return SHARED_KEY;
        }
        return String.valueOf(silkId);
    }

    // ---------- 提取模式 ----------

    private static ProxySpec getExtractProxy(String key, boolean force, ProxyConfigEntity c, String apiUrl) {
        long ttl = ttlOf(c) * 1000L;
        if (!force) {
            CacheEntry cached = readCache(key, ttl);
            if (cached != null) {
                Proxy.Type type = cached.proxyType == null ? Proxy.Type.HTTP : cached.proxyType;
                return new ProxySpec(cached.proxy[0], Integer.parseInt(cached.proxy[1]), type);
            }
        }
        // 多池轮换：poolList 非空时，按池游标选一个池，把 url 的 act=getturn{N} 替换到该池
        List<Integer> pools = parsePools(c);
        String fetchUrl = apiUrl;
        if (!pools.isEmpty()) {
            int idx = Math.floorMod(POOL_ROUND_ROBIN.getAndIncrement(), pools.size());
            int poolN = pools.get(idx);
            fetchUrl = resolveActUrl(apiUrl, poolN);
            if (!fetchUrl.equals(apiUrl)) {
                log.info("多池轮换: 切换到隧道池 act=getturn{} ({}/{})", poolN, idx + 1, pools.size());
            }
        }
        List<ExtractProxy> list = fetchProxyList(fetchUrl);
        if (list == null || list.isEmpty()) {
            return null;
        }
        // 按账号 key 轮流分配列表槽位：主账号各走不同隧道端口 = 独立轮换出口
        int index = Math.floorMod(ROUND_ROBIN.getAndIncrement(), list.size());
        ExtractProxy p = list.get(index);
        cacheByKey.put(key, new CacheEntry(new String[]{p.host, String.valueOf(p.port)},
                p.type, index, System.currentTimeMillis()));
        log.info("获取代理 key={} {}:{} type={} slot={}/{}", key, p.host, p.port, p.type, index, list.size());
        if (cacheByKey.size() > 64) {
            pruneExpired(ttl);
        }
        return new ProxySpec(p.host, p.port, p.type);
    }

    // ---------- 多池轮换辅助 ----------

    /**
     * 解析多隧道池组号列表（来自 cfg.poolList，逗号分隔）。
     * 过滤空项与非法组号(1-999)；空/非法输入返回空列表 = 不轮换（单池兼容）。
     */
    private static List<Integer> parsePools(ProxyConfigEntity c) {
        List<Integer> pools = new ArrayList<>();
        String raw = c == null ? null : c.getPoolList();
        if (raw == null || raw.trim().isEmpty()) {
            return pools;
        }
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                int id = Integer.parseInt(t);
                if (id >= 1 && id <= 999) {
                    pools.add(id);
                } else {
                    log.warn("忽略非法隧道池组号: {}", t);
                }
            } catch (NumberFormatException e) {
                log.warn("忽略非法隧道池组号: {}", t);
            }
        }
        return pools;
    }

    /**
     * 把 api_url 中的池组号替换为指定池：`act=getturn{N}` → `act=getturn{poolId}`。
     * 携趣模板其它参数(uid/vkey/group/time...)保持不变。
     * 模板不含 `act=getturn` 时返回原 url（不替换，兜底不崩）。
     */
    private static String resolveActUrl(String apiUrl, int poolId) {
        if (apiUrl == null || !apiUrl.contains("act=getturn")) {
            return apiUrl;
        }
        return apiUrl.replaceAll("act=getturn\\d+", "act=getturn" + poolId);
    }

    // ---------- 配置读取（entity + Environment 兜底） ----------

    private static boolean enabledOf(ProxyConfigEntity c) {
        if (c != null) {
            return Boolean.TRUE.equals(c.getEnabled());
        }
        return env("PROXY_ENABLED", "false").equalsIgnoreCase("true");
    }

    private static int ttlOf(ProxyConfigEntity c) {
        if (c != null && c.getTtl() != null) {
            return c.getTtl();
        }
        return Integer.parseInt(env("PROXY_TTL", "28"));
    }

    private static String apiUrlOf(ProxyConfigEntity c) {
        if (c != null && c.getApiUrl() != null) {
            return c.getApiUrl();
        }
        return env("PROXY_API_URL", "");
    }

    /**
     * 读取配置快照：CFG_TTL 内复用内存快照，过期或无快照则从 ProxyConfigService 重读。
     * service 不可用（容器未就绪/异常）时返回 null，调用方回退 Environment 兜底。
     * 注意：service 调用在锁外执行，避免持 ProxyHolder.class 锁时进入 Service 实例锁
     *       而与 updateConfig 形成反向锁顺序。
     */
    private static ProxyConfigEntity loadCfg() {
        ProxyConfigEntity snap = cfgSnapshot;
        if (snap != null && System.currentTimeMillis() - cfgLoadedAt < CFG_TTL) {
            return snap;
        }
        ProxyConfigEntity entity = null;
        try {
            ProxyConfigService service = SpringContextUtil.getBean(ProxyConfigService.class);
            entity = service.getEntity();
        } catch (Exception e) {
            log.warn("读取代理配置失败，回退环境变量默认值: {}", e.getMessage());
        }
        if (entity == null) {
            return null;
        }
        synchronized (ProxyHolder.class) {
            cfgSnapshot = entity;
            cfgLoadedAt = System.currentTimeMillis();
            return entity;
        }
    }

    // ---------- 代理缓存读写 ----------

    private static CacheEntry readCache(String key, long ttlMs) {
        CacheEntry e = cacheByKey.get(key);
        if (e == null || e.proxy == null) {
            return null;
        }
        if (System.currentTimeMillis() - e.cachedAt >= ttlMs) {
            cacheByKey.remove(key, e);
            return null;
        }
        return e;
    }

    private static void pruneExpired(long ttlMs) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CacheEntry>> it = cacheByKey.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CacheEntry> en = it.next();
            if (now - en.getValue().cachedAt >= ttlMs) {
                it.remove();
            }
        }
    }

    /**
     * 拉取代理端点列表：整体兜底解析异常，任何异常都返回 null（视为无可用代理），
     * 由调用方走重试/换代理路径，避免异常冒泡中断请求处理。
     *
     * 返回多个端点供按账号分配：白金隧道池提取的 JSON data 含多个 {IP,Port}，
     * 入口 IP 同段、端口各异，可让不同账号走不同端口（= 不同轮换出口）。
     * 纯文本 ip:port（普通直达代理）退化为单元素列表。
     */
    private static List<ExtractProxy> fetchProxyList(String url) {
        if (url == null || url.isEmpty()) {
            log.error("PROXY_API_URL 未配置，无法取代理");
            return null;
        }
        String body;
        try (HttpResponse response = HttpUtil.createGet(url).timeout(8000).execute()) {
            body = response.body();
        } catch (Exception e) {
            log.error("代理 API 请求异常: {}", e.getClass().getSimpleName());
            return null;
        }
        String plainEndpoint = body == null ? "" : body.trim();
        if (plainEndpoint.isEmpty()) {
            log.error("代理 API 返回空响应");
            return null;
        }
        ExtractProxy plainProxy = parseEndpoint(plainEndpoint, Proxy.Type.SOCKS);
        if (plainProxy != null) {
            List<ExtractProxy> single = new ArrayList<>(1);
            single.add(plainProxy);
            return single;
        }
        if (!plainEndpoint.startsWith("{")) {
            log.error("代理 API 返回的纯文本端点格式无效");
            return null;
        }
        try {
            JSONObject obj = JSONObject.parseObject(body);
            if (obj == null || obj.getInteger("code") == null || obj.getInteger("code") != 0) {
                log.error("代理 API 返回异常 code={}", obj == null ? null : obj.getInteger("code"));
                return null;
            }
            JSONArray data = obj.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                log.error("代理 API 无可用代理");
                return null;
            }
            List<ExtractProxy> list = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                JSONObject item = data.getJSONObject(i);
                String ip = item.getString("IP");
                Integer port = item.getInteger("Port");
                if (ip == null || port == null) {
                    continue;
                }
                ExtractProxy proxy = parseEndpoint(ip + ":" + port, Proxy.Type.HTTP);
                if (proxy != null) {
                    list.add(proxy);
                }
            }
            if (list.isEmpty()) {
                log.error("代理 API 返回的 IP 或 Port 格式均无效");
            }
            return list;
        } catch (Exception e) {
            log.error("代理 API 响应解析异常: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static ExtractProxy parseEndpoint(String value, Proxy.Type type) {
        if (value == null || value.isEmpty() || value.indexOf(':') <= 0
                || value.indexOf(':') != value.lastIndexOf(':')) {
            return null;
        }
        int colon = value.indexOf(':');
        String host = value.substring(0, colon).trim();
        String portText = value.substring(colon + 1).trim();
        if (!isIpv4(host) || portText.isEmpty()) {
            return null;
        }
        try {
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                return null;
            }
            return new ExtractProxy(host, port, type);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static String env(String key, String def) {
        try {
            String v = SpringContextUtil.getApplicationContext().getEnvironment().getProperty(key);
            return (v == null || v.isEmpty()) ? def : v;
        } catch (Exception e) {
            return def;
        }
    }
}
