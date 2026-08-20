package io.github.xiaocan.http;

import java.net.Proxy;

/**
 * 一次上游请求要挂的国内 IP 代理（仅提取模式）。
 * 只含 host/port/协议类型；无用户名密码——小蚕仅支持国内直连 IP 代理，无鉴权。
 * 不用 Lombok @Data。
 */
public final class ProxySpec {

    private final String host;
    private final int port;
    private final Proxy.Type proxyType;

    public ProxySpec(String host, int port, Proxy.Type proxyType) {
        this.host = host;
        this.port = port;
        this.proxyType = proxyType == null ? Proxy.Type.HTTP : proxyType;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public Proxy.Type getProxyType() {
        return proxyType;
    }

    public boolean isSocks5() {
        return proxyType == Proxy.Type.SOCKS;
    }
}
