package com.trading.ibcfd.controller;

import com.trading.ibcfd.config.AppProperties;
import com.trading.ibcfd.config.CapitalComConfig;
import com.trading.ibcfd.config.TrendSpiderSymbolConfig;
import com.trading.ibcfd.service.NgrokService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final String PRESET_PREFIX      = "preset.";
    private static final String LABEL_SUFFIX       = ".label";
    private static final String SYMBOLS_SUFFIX     = ".symbols";
    private static final String ASSET_TYPE_SUFFIX  = ".assetType";

    @Autowired private Environment             env;
    @Autowired private AppProperties           appProperties;
    @Autowired private NgrokService            ngrokService;
    @Autowired private TrendSpiderSymbolConfig symbolConfig;
    @Autowired private CapitalComConfig        capitalComConfig;

    @Value("${trading.broker:saxo}")
    private String tradingBroker;

    /**
     * GET /api/config/model
     * Returns all application settings as one structured JSON — presets,
     * server info, ngrok tunnel, TrendSpider webhook URL.
     * Sensitive values (tokens, secrets) are masked.
     */
    @GetMapping("/model")
    public Map<String, Object> getModel() {
        Map<String, Object> model = new LinkedHashMap<>();

        // ── Server ────────────────────────────────────────────────────────────
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("appName",   appProperties.getAppName());
        server.put("port",      appProperties.getServerPort());
        server.put("localUrl",  "http://localhost:" + appProperties.getServerPort());
        model.put("server", server);

        // ── Saxo ──────────────────────────────────────────────────────────────
        Map<String, Object> saxo = new LinkedHashMap<>();
        saxo.put("baseUrl",    appProperties.getSaxoBaseUrl());
        saxo.put("accountKey", mask(appProperties.getSaxoAccountKey()));
        model.put("saxo", saxo);

        // ── ngrok ─────────────────────────────────────────────────────────────
        Map<String, Object> ngrok = new LinkedHashMap<>();
        String publicUrl = ngrokService.getPublicUrl();
        ngrok.put("enabled",   appProperties.isNgrokEnabled());
        ngrok.put("active",    ngrokService.isActive());
        ngrok.put("domain",    appProperties.getNgrokDomain());
        ngrok.put("publicUrl", publicUrl != null ? publicUrl : "not started");
        model.put("ngrok", ngrok);

        // ── TrendSpider ───────────────────────────────────────────────────────
        Map<String, Object> trendspider = new LinkedHashMap<>();
        String secret = appProperties.getTrendspiderSecret();
        String webhookUrl = publicUrl != null
                ? publicUrl + "/api/webhook/trendspider" + (secret.isBlank() ? "" : "?secret=" + secret)
                : "ngrok not active";
        trendspider.put("webhookUrl",    webhookUrl);
        trendspider.put("secretEnabled", !secret.isBlank());
        trendspider.put("historyUrl",    (publicUrl != null ? publicUrl : "http://localhost:" + appProperties.getServerPort())
                                         + "/api/webhook/trendspider/history");
        trendspider.put("signalHubUrl",  (publicUrl != null ? publicUrl : "http://localhost:" + appProperties.getServerPort())
                                         + "/signal-hub.html");
        model.put("trendspider", trendspider);

        // ── Presets ───────────────────────────────────────────────────────────
        model.put("presets", getPresets());

        return model;
    }

    /**
     * GET /api/config/presets
     * Returns all instrument presets defined in application.properties.
     */
    @GetMapping("/presets")
    public List<Map<String, String>> getPresets() {
        List<Map<String, String>> presets = new ArrayList<>();
        for (String key : resolvePresetKeys()) {
            Map<String, String> preset = buildPreset(key);
            if (preset != null) presets.add(preset);
        }
        log.debug("Returning {} presets", presets.size());
        return presets;
    }

    /**
     * GET /api/config/broker
     * Returns active broker and Capital.com status — used by the dashboard header.
     */
    @GetMapping("/broker")
    public Map<String, Object> getBroker() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("broker",          tradingBroker);
        info.put("capitalEnabled",  capitalComConfig.isEnabled());
        info.put("capitalDemo",     capitalComConfig.isDemo());
        info.put("saxoActive",      !tradingBroker.equalsIgnoreCase("capital"));
        info.put("capitalActive",   capitalComConfig.isEnabled() &&
                                    (tradingBroker.equalsIgnoreCase("capital") || tradingBroker.equalsIgnoreCase("both")));
        return info;
    }

    /**
     * GET /api/config/symbol-map
     * Shows every TrendSpider ticker alias and the Saxo instrument it maps to.
     * Use this to verify a signal will be routed correctly before going live.
     */
    @GetMapping("/symbol-map")
    public List<Map<String, Object>> getSymbolMap() {
        List<Map<String, Object>> rows = new ArrayList<>();
        symbolConfig.getSymbols().forEach((ticker, mapping) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trendSpiderTicker", ticker);
            row.put("saxoSymbol",        mapping.getSaxoSymbol());
            row.put("assetType",         mapping.getAssetType());
            row.put("quantity",          mapping.getQuantity());
            rows.add(row);
        });
        rows.sort(Comparator.comparing(r -> r.get("saxoSymbol").toString()));
        return rows;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private Set<String> resolvePresetKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : new String[]{"indices", "metals", "energy", "stocks"}) {
            if (env.getProperty(PRESET_PREFIX + key + LABEL_SUFFIX) != null) keys.add(key);
        }
        return keys;
    }

    private Map<String, String> buildPreset(String key) {
        String label     = env.getProperty(PRESET_PREFIX + key + LABEL_SUFFIX);
        String symbols   = env.getProperty(PRESET_PREFIX + key + SYMBOLS_SUFFIX);
        String assetType = env.getProperty(PRESET_PREFIX + key + ASSET_TYPE_SUFFIX);
        if (label == null || symbols == null || assetType == null) {
            log.warn("Incomplete preset config for key '{}' — skipping", key);
            return null;
        }
        Map<String, String> preset = new LinkedHashMap<>();
        preset.put("key",       key);
        preset.put("label",     label);
        preset.put("symbols",   symbols);
        preset.put("assetType", assetType);
        return preset;
    }

    private String mask(String value) {
        if (value == null || value.length() < 6) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
