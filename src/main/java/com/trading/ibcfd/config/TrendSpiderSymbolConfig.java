package com.trading.ibcfd.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "trendspider")
public class TrendSpiderSymbolConfig {

    /** Set to true to place real orders on Saxo when TrendSpider fires BUY/SELL */
    private boolean autoTradeEnabled = false;

    /** Key = TrendSpider ticker (uppercase), value = Saxo instrument details */
    private Map<String, SymbolMapping> symbols = new LinkedHashMap<>();

    @Data
    public static class SymbolMapping {
        private String saxoSymbol;
        private String assetType = "CfdOnIndex";
        private double quantity  = 1.0;
    }
}
