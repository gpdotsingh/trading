package com.trading.ibcfd.service;

import com.trading.ibcfd.config.SaxoConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Performs the multi-tier UIC keyword-lookup against Saxo /ref/v1/instruments.
 * Extracted from InstrumentService to keep each class under 200 lines.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstrumentUicResolver {

    private final SaxoConfig    config;
    private final RestTemplate  restTemplate;

    @SuppressWarnings("unchecked")
    public int resolve(String symbol, String assetType) {
        String url = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/ref/v1/instruments/")
                .queryParam("AssetTypes", assetType)
                .queryParam("Keywords",   symbol)
                .queryParam("$top",       20)
                .build().toUriString();

        log.info("UIC lookup: symbol='{}' assetType={}", symbol, assetType);
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);

        if (response == null)
            throw new IllegalArgumentException("No response for symbol: " + symbol);

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("Data");
        if (data == null || data.isEmpty())
            throw new IllegalArgumentException("Instrument not found: '" + symbol + "' (" + assetType + ")");

        log.info("  candidates ({}):", data.size());
        data.forEach(d -> log.info("    UIC={} symbol='{}' desc='{}'",
                d.get("Identifier"), d.get("Symbol"), d.get("Description")));

        return pickBestMatch(symbol, data);
    }

    private int pickBestMatch(String symbol, List<Map<String, Object>> data) {
        String kw = symbol.toLowerCase();

        Optional<Map<String, Object>> best = data.stream()
                .filter(d -> symbol.equalsIgnoreCase(str(d, "Symbol"))).findFirst();
        if (best.isEmpty()) best = data.stream()
                .filter(d -> symbol.equalsIgnoreCase(str(d, "Description"))).findFirst();
        if (best.isEmpty()) best = data.stream()
                .filter(d -> str(d, "Description").toLowerCase().startsWith(kw)).findFirst();
        if (best.isEmpty()) best = data.stream()
                .filter(d -> str(d, "Description").toLowerCase().contains(kw)).findFirst();

        Map<String, Object> match = best.orElse(data.get(0));
        int uic = ((Number) match.get("Identifier")).intValue();
        log.info("Resolved '{}' → UIC={} symbol='{}' desc='{}'",
                symbol, uic, match.get("Symbol"), match.get("Description"));
        return uic;
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString();
    }
}
