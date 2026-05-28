package com.trading.ibcfd.service;

import com.trading.ibcfd.config.CapitalComConfig;
import com.trading.ibcfd.model.MarketPrice;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    private final CapitalComConfig      capitalConfig;
    private final CapitalSessionManager capitalSession;

    @Retry(name = "brokerApi")
    @CircuitBreaker(name = "brokerApi", fallbackMethod = "getMarketPriceFallback")
    public MarketPrice getMarketPrice(String symbol, String assetType) {
        capitalSession.ensureSession();
        String epic = resolveEpic(symbol);
        return fetchFromCapital(symbol, assetType, epic);
    }

    public MarketPrice getMarketPriceFallback(String symbol, String assetType, Exception ex) {
        log.warn("Circuit open for market price {}/{} — returning empty price: {}", symbol, assetType, ex.getMessage());
        return MarketPrice.builder().symbol(symbol).assetType(assetType).bid(0).ask(0).mid(0)
                .timestamp(Instant.now().toString()).build();
    }

    @Retry(name = "brokerApi")
    @CircuitBreaker(name = "brokerApi", fallbackMethod = "getBulkPricesFallback")
    public Map<String, MarketPrice> getBulkPrices(String symbols, String assetType) {
        capitalSession.ensureSession();
        List<String> symbolList = Arrays.stream(symbols.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        Map<String, MarketPrice> result = new LinkedHashMap<>();
        for (String sym : symbolList) {
            try {
                String epic = resolveEpic(sym);
                result.put(sym, fetchFromCapital(sym, assetType, epic));
            } catch (Exception e) {
                log.warn("Skipping bulk price for {}: {}", sym, e.getMessage());
            }
        }
        return result;
    }

    public Map<String, MarketPrice> getBulkPricesFallback(String symbols, String assetType, Exception ex) {
        log.warn("Circuit open for bulk prices — returning empty map: {}", ex.getMessage());
        return Map.of();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private MarketPrice fetchFromCapital(String symbol, String assetType, String epic) {
        Map<String, Object> resp = capitalSession.get("/api/v1/markets/" + epic);

        double bid = 0, ask = 0, mid = 0;
        if (resp != null && resp.get("snapshot") instanceof Map<?, ?> snap) {
            Map<String, Object> s = (Map<String, Object>) snap;
            bid = toDouble(s.get("bid"));
            ask = toDouble(s.get("offer"));   // Capital.com uses "offer" for ask
            mid = bid > 0 && ask > 0 ? (bid + ask) / 2.0 : (bid > 0 ? bid : ask);
        }

        log.debug("Capital price {} (epic={}) bid={} ask={}", symbol, epic, bid, ask);
        return MarketPrice.builder()
                .symbol(symbol.toUpperCase())
                .assetType(assetType)
                .uic(0)
                .bid(bid).ask(ask).mid(mid)
                .timestamp(Instant.now().toString())
                .build();
    }

    /**
     * Resolves a symbol/saxo-name to a Capital.com epic.
     * Tries: exact key → uppercase → spaces-to-underscores → epic value match.
     */
    String resolveEpic(String symbol) {
        Map<String, CapitalComConfig.SymbolMapping> symbols = capitalConfig.getSymbols();

        // 1. exact match
        if (symbols.containsKey(symbol)) return symbols.get(symbol).getEpic();

        // 2. uppercase
        if (symbols.containsKey(symbol.toUpperCase())) return symbols.get(symbol.toUpperCase()).getEpic();

        // 3. spaces → underscores, uppercase  (e.g. "Crude Oil WTI" → "CRUDE_OIL_WTI")
        String normalized = symbol.toUpperCase().replace(" ", "_");
        if (symbols.containsKey(normalized)) return symbols.get(normalized).getEpic();

        // 4. epic value direct match (e.g. symbol IS already "OIL_CRUDE")
        for (CapitalComConfig.SymbolMapping m : symbols.values()) {
            if (m.getEpic().equalsIgnoreCase(symbol)) return m.getEpic();
        }

        throw new IllegalArgumentException("No Capital.com epic mapped for symbol '" + symbol + "'");
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return 0.0; }
    }
}
