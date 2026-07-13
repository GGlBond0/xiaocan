package io.github.xiaocan.http;

import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.github.xiaocan.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 代理持有者：从环境变量配置的代理 API 拉取代理 IP，TTL 缓存，失效可换。
 * 配置经 Spring Environment（systemd EnvironmentFile 注入）：
 *   PROXY_ENABLED / PROXY_API_URL / PROXY_TTL / PROXY_RETRY / PROXY_REQUEST_TIMEOUT
 * 行为与生产旧 JAR 字节码 1:1 还原。
 */
@Slf4j
public class ProxyHolder {

    private static volatile String[] cachedProxy;
    private static volatile long cachedAt;

    public static boolean enabled() {
        return env("PROXY_ENABLED", "false").equalsIgnoreCase("true");
    }

    public static int retry() {
        return Integer.parseInt(env("PROXY_RETRY", "3"));
    }

    public static int requestTimeout() {
        return Integer.parseInt(env("PROXY_REQUEST_TIMEOUT", "5000"));
    }

    public static synchronized String[] getProxy(boolean force) {
        if (!enabled()) {
            return null;
        }
        long ttl = Long.parseLong(env("PROXY_TTL", "28")) * 1000L;
        if (!force && cachedProxy != null && System.currentTimeMillis() - cachedAt < ttl) {
            return cachedProxy;
        }
        String[] p = fetchProxy();
        if (p != null) {
            cachedProxy = p;
            cachedAt = System.currentTimeMillis();
            log.info("获取代理: {}:{}", p[0], p[1]);
        }
        return p;
    }

    public static synchronized void invalidate() {
        cachedProxy = null;
        cachedAt = 0;
    }

    private static String[] fetchProxy() {
        String url = env("PROXY_API_URL", "");
        if (url == null || url.isEmpty()) {
            log.error("PROXY_API_URL 未配置，无法取代理");
            return null;
        }
        String body;
        try {
            body = HttpUtil.createGet(url).timeout(8000).execute().body();
        } catch (Exception e) {
            log.error("代理 API 请求异常: {}", e.getMessage());
            return null;
        }
        JSONObject obj = JSONObject.parseObject(body);
        if (obj == null || obj.getInteger("code") == null || obj.getInteger("code") != 0) {
            log.error("代理 API 返回异常: {}", body);
            return null;
        }
        JSONArray data = obj.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            log.error("代理 API 无可用代理: {}", body);
            return null;
        }
        JSONObject first = data.getJSONObject(0);
        String ip = first.getString("IP");
        Integer port = first.getInteger("Port");
        if (ip == null || port == null) {
            return null;
        }
        return new String[]{ip, String.valueOf(port)};
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
