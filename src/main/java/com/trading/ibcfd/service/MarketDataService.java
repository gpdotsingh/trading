package com.trading.ibcfd.service;

import com.trading.ibcfd.config.SaxoConfig;
import com.trading.ibcfd.model.MarketPrice;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final SaxoConfig config;
    private final RestTemplate restTemplate;
    private final InstrumentLookup instrumentService;

    /**
     * Fetches a live bid/ask price snapshot for a single CFD instrument.
     * GET /trade/v1/infoprices/?AssetType={assetType}&Uic={uic}&FieldGroups=Quote
     */
    @Retry(name = "brokerApi")
    @CircuitBreaker(name = "brokerApi", fallbackMethod = "getMarketPriceFallback")
    public MarketPrice getMarketPrice(String symbol, String assetType) {
        int uic = instrumentService.findUic(symbol, assetType);
        return fetchPrice(symbol, assetType, uic);
    }

    public MarketPrice getMarketPriceFallback(String symbol, String assetType, Exception ex) {
        log.warn("Circuit open for market price {}/{} — returning empty price: {}", symbol, assetType, ex.getMessage());
        return MarketPrice.builder().symbol(symbol).assetType(assetType).bid(0).ask(0).mid(0)
                .timestamp(java.time.Instant.now().toString()).build();
    }

    /**
     * Fetches prices for multiple symbols in one batch call using Saxo's list endpoint.
     * GET /trade/v1/infoprices/list?AssetType={assetType}&Uics=uic1,uic2,...&FieldGroups=Quote
     *
     * UICs are resolved once and cached by InstrumentService — subsequent calls are fast.
     *
     * @param symbols   comma-separated, e.g. "AAPL,TSLA,MSFT"
     * @param assetType e.g. "CfdOnStock"
     * @return map of symbol -> MarketPrice
     */
    @Retry(name = "brokerApi")
    @CircuitBreaker(name = "brokerApi", fallbackMethod = "getBulkPricesFallback")
    @SuppressWarnings("unchecked")
    public Map<String, MarketPrice> getBulkPrices(String symbols, String assetType) {
        List<String> symbolList = Arrays.stream(symbols.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        // Resolve all UICs (cached after first call)
        Map<String, Integer> uicMap = new LinkedHashMap<>();
        for (String sym : symbolList) {
            try {
                uicMap.put(sym, instrumentService.findUic(sym, assetType));
            } catch (Exception e) {
                log.warn("Skipping {}: {}", sym, e.getMessage());
            }
        }

        if (uicMap.isEmpty()) {
            return Map.of();
        }

        String uicsCsv = uicMap.values().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        String url = config.getBaseUrl()
                + "/trade/v1/infoprices/list"
                + "?AssetType=" + assetType
                + "&Uics=" + uicsCsv
                + "&FieldGroups=Quote";

        log.debug("Bulk price fetch for UICs: {}", uicsCsv);

        Map<String, Object> response = restTemplate.getForObject(url, Map.class);

        // Build a UIC -> price map from the response
        Map<Integer, double[]> priceByUic = new LinkedHashMap<>();
        if (response != null) {
            List<Map<String, Object>> dataList = (List<Map<String, Object>>) response.get("Data");
            if (dataList != null) {
                for (Map<String, Object> item : dataList) {
                    int uic = ((Number) item.get("Uic")).intValue();
                    Map<String, Object> quote = (Map<String, Object>) item.get("Quote");
                    double bid = 0, ask = 0, mid = 0;
                    if (quote != null) {
                        bid = toDouble(quote.get("Bid"));
                        ask = toDouble(quote.get("Ask"));
                        mid = toDouble(quote.get("Mid"));
                    }
                    priceByUic.put(uic, new double[]{bid, ask, mid});
                }
            }
        }

        // Assemble final result keyed by symbol
        String timestamp = Instant.now().toString();
        Map<String, MarketPrice> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : uicMap.entrySet()) {
            String sym = entry.getKey();
            int uic   = entry.getValue();
            double[] p = priceByUic.getOrDefault(uic, new double[]{0, 0, 0});
            result.put(sym, MarketPrice.builder()
                    .symbol(sym)
                    .assetType(assetType)
                    .uic(uic)
                    .bid(p[0])
                    .ask(p[1])
                    .mid(p[2])
                    .timestamp(timestamp)
                    .build());
        }
        return result;
    }

    public Map<String, MarketPrice> getBulkPricesFallback(String symbols, String assetType, Exception ex) {
        log.warn("Circuit open for bulk prices {}/{} — returning empty map: {}", symbols, assetType, ex.getMessage());
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private MarketPrice fetchPrice(String symbol, String assetType, int uic) {
        String url = config.getBaseUrl()
                + "/trade/v1/infoprices/"
                + "?AssetType=" + assetType
                + "&Uic=" + uic
                + "&FieldGroups=Quote";

        Map<String, Object> response = restTemplate.getForObject(url, Map.class);

        double bid = 0, ask = 0, mid = 0;
        if (response != null) {
            Map<String, Object> quote = (Map<String, Object>) response.get("Quote");
            if (quote != null) {
                bid = toDouble(quote.get("Bid"));
                ask = toDouble(quote.get("Ask"));
                mid = toDouble(quote.get("Mid"));
            }
        }

        return MarketPrice.builder()
                .symbol(symbol.toUpperCase())
                .assetType(assetType)
                .uic(uic)
                .bid(bid).ask(ask).mid(mid)
                .timestamp(Instant.now().toString())
                .build();
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return 0.0; }
    }
}
