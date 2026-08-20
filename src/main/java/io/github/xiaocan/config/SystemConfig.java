package io.github.xiaocan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SystemConfig {
    @Value("${system.web-url:}")
    private String webUrl;
    public String getWebUrl() { return webUrl; }
}
