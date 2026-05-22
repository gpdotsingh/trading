package com.trading.ibcfd.service;

import com.trading.ibcfd.config.SaxoConfig;
import com.trading.ibcfd.model.InstrumentDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves symbols to Saxo UICs and fetches instrument details.
 * UIC lookup is delegated to InstrumentUicResolver; results are cached.
 * Catalog operations (search/browse) live in InstrumentCatalogService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentService implements InstrumentLookup {

    private final SaxoConfig             config;
    private final RestTemplate           restTemplate;
    private final InstrumentUicResolver  uicResolver;

    private final ConcurrentHashMap<String, Integer>           uicCache     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, InstrumentDetails> detailsCache = new ConcurrentHashMap<>();

    @Override
    public int findUic(String symbol, String assetType) {
        return uicCache.computeIfAbsent(cacheKey(symbol, assetType),
                k -> uicResolver.resolve(symbol, assetType));
    }

    @Override
    public InstrumentDetails getDetails(String symbol, String assetType) {
        return detailsCache.computeIfAbsent(cacheKey(symbol, assetType),
                k -> fetchDetails(symbol, assetType));
    }

    @SuppressWarnings("unchecked")
    private InstrumentDetails fetchDetails(String symbol, String assetType) {
        int uic = findUic(symbol, assetType);
        String url = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/ref/v1/instruments/details")
                .queryParam("AssetTypes",  assetType)
                .queryParam("Uics",        uic)
                .queryParam("FieldGroups", "TradingConditions")
                .build().toUriString();

        log.info("Fetching instrument details: symbol={} uic={}", symbol, uic);
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);
        if (response == null) throw new IllegalStateException("No details response for UIC=" + uic);

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("Data");
        if (data == null || data.isEmpty()) throw new IllegalStateException("Empty details for UIC=" + uic);
        return parseDetails(symbol, assetType, uic, data.get(0));
    }

    @SuppressWarnings("unchecked")
    private InstrumentDetails parseDetails(String symbol, String assetType,
                                           int uic, Map<String, Object> item) {
        double minSize = extractDouble(item, "MinimumTradeSize", 1.0);
        double lot     = extractDouble(item, "LotSize",          1.0);
        double maxSize = extractDouble(item, "MaximumTradeSize",  1_000_000.0);

        if (item.containsKey("TradingConditions")) {
            Map<String, Object> tc = (Map<String, Object>) item.get("TradingConditions");
            minSize = extractDouble(tc, "MinimumTradeSize", minSize);
            lot     = extractDouble(tc, "LotSize",          lot);
            maxSize = extractDouble(tc, "MaximumTradeSize",  maxSize);
        }
        log.info("Details for {}: minTradeSize={} lotSize={}", symbol, minSize, lot);
        return InstrumentDetails.builder()
                .symbol(symbol).assetType(assetType).uic(uic)
                .description(String.valueOf(item.getOrDefault("Description", symbol)))
                .currencyCode(String.valueOf(item.getOrDefault("CurrencyCode", "USD")))
                .minimumTradeSize(minSize).lotSize(lot).maximumTradeSize(maxSize)
                .build();
    }

    private static String cacheKey(String symbol, String assetType) {
        return symbol.toUpperCase() + ":" + assetType;
    }

    private static double extractDouble(Map<String, Object> map, String key, double fallback) {
        Object val = map.get(key);
        return val instanceof Number n ? n.doubleValue() : fallback;
    }
}
