package com.trading.ibcfd.service;

import com.trading.ibcfd.config.SaxoConfig;
import com.trading.ibcfd.model.OrderRequest;
import com.trading.ibcfd.model.OrderResponse;
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

        log.info("Placing Saxo order: {} {} {} UIC={} type={}",
                req.getAction(), req.getQuantity(), req.getSymbol(), uic, req.getOrderType());

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
