package com.trading.ibcfd.controller;

import com.trading.ibcfd.config.SaxoConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
@Tag(name = "Portfolio", description = "Live P&L, open positions and closed trade history from Saxo")
public class PortfolioController {

    private final SaxoConfig   config;
    private final RestTemplate restTemplate;

    /** Lazily fetched and cached — retrieved once from /port/v1/clients/me */
    private volatile String clientKey;

    // ── ClientKey resolution ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String resolveClientKey() {
        if (clientKey != null) return clientKey;
        try {
            String url = config.getBaseUrl() + "/port/v1/clients/me";
            Map<String, Object> me = restTemplate.getForObject(url, Map.class);
            if (me != null && me.get("ClientKey") != null) {
                clientKey = me.get("ClientKey").toString();
                log.info("Resolved Saxo ClientKey: {}", clientKey);
            }
        } catch (Exception e) {
            log.warn("Could not resolve ClientKey from /port/v1/clients/me: {} — falling back to AccountKey", e.getMessage());
        }
        // Saxo simulation accounts typically share ClientKey == AccountKey
        if (clientKey == null) clientKey = config.getAccountKey();
        return clientKey;
    }

    // ── Endpoints ────────────────────────────────────────────────────────

    /**
     * GET /api/portfolio/positions
     * Open positions with unrealized P&L for this account.
     */
    @GetMapping("/positions")
    @Operation(summary = "Open positions with unrealized P&L")
    public ResponseEntity<Object> openPositions() {
        String url = config.getBaseUrl()
                + "/port/v1/positions?ClientKey=" + resolveClientKey()
                + "&AccountKey=" + config.getAccountKey()
                + "&FieldGroups=PositionBase,PositionView,DisplayAndFormat";
        log.debug("Fetching open positions");
        try {
            Object result = restTemplate.getForObject(url, Object.class);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to fetch open positions: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("error", e.getMessage(), "Data", List.of()));
        }
    }

    /**
     * GET /api/portfolio/closed?top=50
     * Closed positions (realized P&L) — most recent first.
     */
    @GetMapping("/closed")
    @Operation(summary = "Closed trades with realized P&L")
    public ResponseEntity<Object> closedPositions(
            @RequestParam(defaultValue = "50") int top) {
        String url = config.getBaseUrl()
                + "/port/v1/closedpositions?ClientKey=" + resolveClientKey()
                + "&AccountKey=" + config.getAccountKey()
                + "&top=" + top
                + "&FieldGroups=ClosedPosition,DisplayAndFormat";
        log.debug("Fetching closed positions (top {})", top);
        try {
            Object result = restTemplate.getForObject(url, Object.class);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to fetch closed positions: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("error", e.getMessage(), "Data", List.of()));
        }
    }

    /**
     * GET /api/portfolio/orders?top=50
     * Active/pending orders for this account.
     */
    @GetMapping("/orders")
    @Operation(summary = "Active/pending orders")
    public ResponseEntity<Object> activeOrders(
            @RequestParam(defaultValue = "50") int top) {
        String url = config.getBaseUrl()
                + "/port/v1/orders?ClientKey=" + resolveClientKey()
                + "&AccountKey=" + config.getAccountKey()
                + "&FieldGroups=DisplayAndFormat,ExchangeInfo"
                + "&top=" + top;
        log.debug("Fetching active orders");
        try {
            Object result = restTemplate.getForObject(url, Object.class);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to fetch active orders: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("error", e.getMessage(), "Data", List.of()));
        }
    }

    /**
     * GET /api/portfolio/summary
     * Account balance, net equity, margin used.
     */
    @GetMapping("/summary")
    @Operation(summary = "Account balance and total P&L")
    public ResponseEntity<Object> accountSummary() {
        String url = config.getBaseUrl()
                + "/port/v1/accounts/" + config.getAccountKey()
                + "?ClientKey=" + resolveClientKey();
        log.debug("Fetching account summary");
        try {
            Object result = restTemplate.getForObject(url, Object.class);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to fetch account summary: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }
}
