package com.trading.ibcfd.service;

import com.trading.ibcfd.config.SaxoConfig;
import com.trading.ibcfd.model.OrderRequest;
import com.trading.ibcfd.model.OrderResponse;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final SaxoConfig config;
    private final RestTemplate restTemplate;
    private final InstrumentService instrumentService;

    /**
     * Places a CFD order via Saxo.
     *
     * POST /trade/v2/orders
     *
     * Flow:
     *   1. Resolve symbol -> UIC
     *   2. Build order body with AccountKey, AssetType, Uic, BuySell, Amount, OrderType
     *   3. For LMT orders include OrderPrice; for STP include StopLimitPrice
     */
    @Retry(name = "brokerApi")
    @SuppressWarnings("unchecked")
    public OrderResponse placeOrder(OrderRequest req) {
        String assetType = req.getAssetType();
        int uic = instrumentService.findUic(req.getSymbol(), assetType);

        Map<String, Object> body = new HashMap<>();
        body.put("AccountKey",  config.getAccountKey());
        body.put("AssetType",   assetType);
        body.put("Uic",         uic);
        body.put("BuySell",     req.getAction().substring(0, 1).toUpperCase()
                                + req.getAction().substring(1).toLowerCase()); // Buy / Sell
        body.put("Amount",      req.getQuantity());
        body.put("OrderType",   mapOrderType(req.getOrderType()));
        body.put("ManualOrder", false);

        // Duration
        Map<String, String> duration = new HashMap<>();
        duration.put("DurationType", "DayOrder");
        body.put("Duration", duration);

        if ("Limit".equals(body.get("OrderType"))) {
            body.put("OrderPrice", req.getLimitPrice());
        }
        if ("StopIfTraded".equals(body.get("OrderType"))) {
            body.put("OrderPrice", req.getStopPrice());
        }

        // Related stop-loss / take-profit orders placed simultaneously
        String reverseSide = req.getAction().equalsIgnoreCase("BUY") ? "Sell" : "Buy";
        java.util.List<Map<String, Object>> relatedOrders = new java.util.ArrayList<>();
        if (req.getStopLossPrice() > 0) {
            Map<String, Object> sl = new HashMap<>();
            sl.put("AccountKey",  config.getAccountKey());
            sl.put("AssetType",   assetType);
            sl.put("Uic",         uic);
            sl.put("BuySell",     reverseSide);
            sl.put("Amount",      req.getQuantity());
            sl.put("OrderType",   "StopIfTraded");
            sl.put("OrderPrice",  req.getStopLossPrice());
            sl.put("ManualOrder", false);
            sl.put("Duration",    Map.of("DurationType", "GoodTillCancel"));
            relatedOrders.add(sl);
        }
        if (req.getTakeProfitPrice() > 0) {
            Map<String, Object> tp = new HashMap<>();
            tp.put("AccountKey",  config.getAccountKey());
            tp.put("AssetType",   assetType);
            tp.put("Uic",         uic);
            tp.put("BuySell",     reverseSide);
            tp.put("Amount",      req.getQuantity());
            tp.put("OrderType",   "Limit");
            tp.put("OrderPrice",  req.getTakeProfitPrice());
            tp.put("ManualOrder", false);
            tp.put("Duration",    Map.of("DurationType", "GoodTillCancel"));
            relatedOrders.add(tp);
        }
        if (!relatedOrders.isEmpty()) body.put("Orders", relatedOrders);

        log.info("Placing Saxo order: {} {} {} UIC={} type={} SL={} TP={}",
                req.getAction(), req.getQuantity(), req.getSymbol(), uic, req.getOrderType(),
                req.getStopLossPrice() > 0 ? req.getStopLossPrice() : "none",
                req.getTakeProfitPrice() > 0 ? req.getTakeProfitPrice() : "none");

        String url = config.getBaseUrl() + "/trade/v2/orders";
        Map<String, Object> response = restTemplate.postForObject(url, body, Map.class);

        String orderId = response != null ? str(response.get("OrderId")) : "";
        return OrderResponse.builder()
                .orderId(orderId)
                .symbol(req.getSymbol().toUpperCase())
                .action(req.getAction().toUpperCase())
                .quantity(req.getQuantity())
                .orderType(req.getOrderType().toUpperCase())
                .assetType(assetType)
                .uic(uic)
                .status("PLACED")
                .message("Order placed on Saxo simulation")
                .build();
    }

    /**
     * Find the active StopIfTraded order for a symbol and update its price.
     * Used by DynamicStopLossService when the trailing stop moves.
     */
    @Retry(name = "brokerApi")
    @SuppressWarnings("unchecked")
    public void updateStopOrder(String symbol, String assetType, double newStopPrice) {
        int uic = instrumentService.findUic(symbol, assetType);
        String listUrl = config.getBaseUrl() + "/port/v1/orders?AccountKey=" + config.getAccountKey()
                + "&FieldGroups=DisplayAndFormat";
        Map<String, Object> resp = restTemplate.getForObject(listUrl, Map.class);
        if (resp == null || !(resp.get("Data") instanceof java.util.List<?> data)) return;

        for (Object item : data) {
            if (!(item instanceof Map<?, ?> order)) continue;
            Map<String, Object> o = (Map<String, Object>) order;
            if (!"StopIfTraded".equals(o.get("OpenOrderType"))) continue;
            Object oUic = o.get("Uic");
            if (!(oUic instanceof Number) || ((Number) oUic).intValue() != uic) continue;

            String stopOrderId = str(o.get("OrderId"));
            if (stopOrderId.isBlank()) continue;

            Map<String, Object> body = new HashMap<>();
            body.put("AccountKey", config.getAccountKey());
            body.put("OrderId",    stopOrderId);
            body.put("OrderType",  "StopIfTraded");
            body.put("OrderPrice", newStopPrice);
            body.put("AssetType",  assetType);
            body.put("Uic",        uic);

            String putUrl = config.getBaseUrl() + "/trade/v2/orders/" + stopOrderId;
            restTemplate.put(putUrl, body);
            log.info("Saxo stop order {} updated to {}", stopOrderId, newStopPrice);
            return;
        }
        log.debug("No active StopIfTraded order found for symbol={} uic={}", symbol, uic);
    }

    /**
     * Get order status.
     * GET /trade/v2/orders/{orderId}
     */
    @SuppressWarnings("unchecked")
    public OrderResponse getOrderStatus(String orderId) {
        String url = config.getBaseUrl() + "/trade/v2/orders/" + orderId
                + "?AccountKey=" + config.getAccountKey();
        log.debug("Fetching order status for {}", orderId);

        Map<String, Object> data = restTemplate.getForObject(url, Map.class);

        if (data == null) {
            return OrderResponse.builder().orderId(orderId).status("NOT_FOUND").build();
        }

        return OrderResponse.builder()
                .orderId(str(data.get("OrderId")))
                .symbol(str(data.get("Symbol")))
                .action(str(data.get("BuySell")).toUpperCase())
                .quantity(toDouble(data.get("Amount")))
                .orderType(str(data.get("OpenOrderType")).toUpperCase())
                .assetType(str(data.get("AssetType")))
                .status(str(data.get("Status")).toUpperCase())
                .message("Status retrieved from Saxo")
                .build();
    }

    /**
     * Cancel an open order.
     * DELETE /trade/v2/orders/{orderId}/{accountKey}
     */
    public OrderResponse cancelOrder(String orderId) {
        String url = config.getBaseUrl() + "/trade/v2/orders/"
                + orderId + "/" + config.getAccountKey();
        log.info("Cancelling Saxo order {}", orderId);

        restTemplate.delete(url);

        return OrderResponse.builder()
                .orderId(orderId)
                .status("CANCEL_REQUESTED")
                .message("Cancel request sent to Saxo")
                .build();
    }

    // ---- helpers ----

    private String mapOrderType(String type) {
        return switch (type.toUpperCase()) {
            case "LMT" -> "Limit";
            case "STP" -> "StopIfTraded";
            default    -> "Market";
        };
    }

    private String str(Object val) {
        return val == null ? "" : val.toString();
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return 0.0; }
    }
}
