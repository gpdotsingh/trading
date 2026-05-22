package com.trading.ibcfd.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Strongly-typed binding of application.properties into a single model.
 * Accessible anywhere via @Autowired AppProperties.
 */
@Data
@Component
public class AppProperties {

    // ── Saxo ─────────────────────────────────────────────────────────────────
    @Value("${saxo.base-url}")
    private String saxoBaseUrl;

    @Value("${saxo.account-key}")
    private String saxoAccountKey;

    // ── Server ────────────────────────────────────────────────────────────────
    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${spring.application.name:saxo-cfd-api}")
    private String appName;

    // ── TrendSpider ───────────────────────────────────────────────────────────
    @Value("${trendspider.webhook.secret:}")
    private String trendspiderSecret;

    // ── ngrok ─────────────────────────────────────────────────────────────────
    @Value("${ngrok.enabled:true}")
    private boolean ngrokEnabled;

    @Value("${ngrok.domain:}")
    private String ngrokDomain;
}
