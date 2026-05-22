package com.trading.ibcfd.service;

import com.trading.ibcfd.config.SaxoConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages Saxo infoprices subscriptions and broadcasts price updates via STOMP.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaxoPriceSubscriptionService {

    private final SaxoConfig             config;
    private final RestTemplate           restTemplate;
    private final SimpMessagingTemplate  messagingTemplate;

    private final Map<String, String>  activeSubscriptions = new ConcurrentHashMap<>();
    private final Map<Integer, String> uicToSymbol         = new ConcurrentHashMap<>();

    public void addUicMappings(Map<Integer, String> uicNames) {
        uicToSymbol.putAll(uicNames);
    }

    @SuppressWarnings("unchecked")
    public void createSubscription(List<Integer> uics, String assetType, String contextId) {
        deleteSubscription(assetType, contextId);

        String referenceId = "prices_" + assetType + "_" + System.currentTimeMillis();
        activeSubscriptions.put(assetType, referenceId);

        String uicsCsv = uics.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<String, Object> body = Map.of(
                "ContextId",   contextId,
                "ReferenceId", referenceId,
                "Arguments",   Map.of("AssetType", assetType, "Uics", uicsCsv, "FieldGroups", List.of("Quote"))
        );

        log.info("Creating subscription assetType={} referenceId={} UICs={}", assetType, referenceId, uics);
        Map<String, Object> response = restTemplate.postForObject(
                config.getBaseUrl() + "/trade/v1/infoprices/subscriptions", body, Map.class);

        if (response != null) {
            Map<String, Object> snapshot = (Map<String, Object>) response.get("Snapshot");
            if (snapshot != null) broadcast((List<Map<String, Object>>) snapshot.get("Data"));
        }
    }

    public void deleteSubscription(String assetType, String contextId) {
        String referenceId = activeSubscriptions.remove(assetType);
        if (referenceId == null || contextId == null) return;
        try {
            restTemplate.delete(config.getBaseUrl() + "/trade/v1/infoprices/subscriptions/"
                    + contextId + "/" + referenceId);
            log.info("Deleted subscription: assetType={}", assetType);
        } catch (Exception e) {
            log.warn("Could not delete subscription for {}: {}", assetType, e.getMessage());
        }
    }

    public void deleteAll(String contextId) {
        new HashSet<>(activeSubscriptions.keySet()).forEach(a -> deleteSubscription(a, contextId));
    }

    public boolean isActive(String referenceId) {
        return activeSubscriptions.containsValue(referenceId);
    }

    public void broadcast(List<Map<String, Object>> items) {
        if (items == null) return;
        for (Map<String, Object> item : items) {
            Object uicObj = item.get("Uic");
            if (uicObj == null) continue;
            int uic = ((Number) uicObj).intValue();
            String symbol = uicToSymbol.get(uic);
            if (symbol == null) continue;
            broadcastOne(symbol, uic, item);
        }
    }

    @SuppressWarnings("unchecked")
    private void broadcastOne(String symbol, int uic, Map<String, Object> item) {
        Map<String, Object> quote = (Map<String, Object>) item.get("Quote");
        if (quote == null) return;
        double bid = toDouble(quote.get("Bid"));
        double ask = toDouble(quote.get("Ask"));
        double mid = toDouble(quote.get("Mid"));
        if (bid == 0 && ask == 0 && mid == 0) return;
        messagingTemplate.convertAndSend("/topic/prices", Map.of(
                "symbol", symbol, "uic", uic,
                "bid", bid, "ask", ask, "mid", mid,
                "timestamp", Instant.now().toString()));
        log.debug("Broadcast {} bid={} ask={}", symbol, bid, ask);
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return 0.0; }
    }
}
