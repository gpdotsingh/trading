package com.trading.ibcfd.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "capital")
public class CapitalComConfig {

    private boolean enabled = false;
    private boolean demo = true;
    private String baseUrl = "https://demo-api-capital.backend-capital.com";
    private String apiKey;
    private String identifier;
    private String password;

    /** TrendSpider ticker → Capital.com instrument mapping */
    private Map<String, SymbolMapping> symbols = new HashMap<>();

    @Data
    public static class SymbolMapping {
        /** Capital.com epic, e.g. OIL_CRUDE, US100, GOLD */
        private String epic;
        /** Trade size in contracts */
        private double size = 1.0;
    }
}
