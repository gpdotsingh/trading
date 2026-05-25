package com.trading.ibcfd.controller;

import com.trading.ibcfd.broker.BrokerRouter;
import com.trading.ibcfd.broker.RouteResult;
import com.trading.ibcfd.broker.SaxoBrokerGateway;
import com.trading.ibcfd.config.TrendSpiderSymbolConfig;
import com.trading.ibcfd.model.MarketPrice;
import com.trading.ibcfd.model.OrderRequest;
import com.trading.ibcfd.model.TradeJournalEntry;
import com.trading.ibcfd.service.CapitalComService;
import com.trading.ibcfd.service.MarketDataService;
import com.trading.ibcfd.service.OrderService;
import com.trading.ibcfd.service.TradeJournal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/trade")
@RequiredArgsConstructor
@Tag(name = "Manual Trade", description = "Manually place or close orders on Saxo or Capital.com from the dashboard")
public class ManualTradeController {

    private final BrokerRouter             brokerRouter;
    private final OrderService             orderService;
    private final CapitalComService        capitalComService;
    private final TrendSpiderSymbolConfig  symbolConfig;
    private final TradeJournal             tradeJournal;
    private final SaxoBrokerGateway        saxoGateway;
    private final MarketDataService        marketDataService;

    /**
     * POST /api/trade/manual
     * Body: { "broker": "saxo"|"capital"|"both"|"auto", "ticker": "CL1!", "action": "BUY", "quantity": 0.1 }
     * quantity is optional; 0 or absent means use the mapping default.
     */
    @PostMapping("/manual")
    @Operation(summary = "Manually place a BUY or SELL order on Saxo, Capital.com, or both")
    public ResponseEntity<Map<String, Object>> placeManual(@RequestBody Map<String, Object> req) {
        String broker   = str(req.get("broker"));
        String ticker   = str(req.get("ticker")).toUpperCase();
        String action   = str(req.get("action")).toUpperCase();
        double quantity = toDouble(req.get("quantity"));
        if (ticker.isBlank() || (!action.equals("BUY") && !action.equals("SELL")))
            return ResponseEntity.badRequest().body(Map.of("error", "ticker and action (BUY or SELL) are required"));

        List<RouteResult> results = brokerRouter.routeWith(ticker, action, "MANUAL", 0.0, null, broker, quantity);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ticker", ticker);
        response.put("action", action);
        for (RouteResult r : results) {
            String prefix = r.broker().toLowerCase();
            String idKey  = "SAXO".equals(r.broker()) ? prefix + "OrderId" : prefix + "DealRef";
            response.put(idKey, r.orderId() != null ? r.orderId() : "");
            response.put(prefix + "Status", r.placed() ? "PLACED" : "FAILED: " + r.failReason());
            if (r.note() != null) response.put(prefix + "Note", r.note());
        }
        return ResponseEntity.ok(response);
    }

    /** GET /api/trade/minsize/{ticker} — minimum trade size for a Saxo-mapped ticker */
    @GetMapping("/minsize/{ticker}")
    @Operation(summary = "Get minimum trade size for a ticker (Saxo)")
    public ResponseEntity<Map<String, Object>> getMinTradeSize(@PathVariable String ticker) {
        try {
            double minSize = saxoGateway.getMinimumTradeSize(ticker.toUpperCase());
            return ResponseEntity.ok(Map.of("ticker", ticker.toUpperCase(), "minimumTradeSize", minSize));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ticker", ticker.toUpperCase(), "minimumTradeSize", 0, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/trade/info/{ticker}
     * Returns saxoSymbol, assetType, minTradeSize, and current mid/ask/bid price.
     * Used by the dashboard to calculate quantity from a euro amount before placing.
     */
    @GetMapping("/info/{ticker}")
    @Operation(summary = "Get instrument info + live price for a ticker")
    public ResponseEntity<Map<String, Object>> getTickerInfo(@PathVariable String ticker) {
        String t = ticker.toUpperCase();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("ticker", t);

        TrendSpiderSymbolConfig.SymbolMapping mapping = symbolConfig.getSymbols().get(t);
        String saxoSymbol = mapping != null ? mapping.getSaxoSymbol() : t;
        String assetType  = mapping != null ? mapping.getAssetType()  : "CfdOnFutures";
        info.put("saxoSymbol", saxoSymbol);
        info.put("assetType",  assetType);

        try {
            double minSize = saxoGateway.getMinimumTradeSize(t);
            info.put("minimumTradeSize", minSize);
        } catch (Exception e) {
            info.put("minimumTradeSize", 0);
        }

        try {
            MarketPrice mp = marketDataService.getMarketPrice(saxoSymbol, assetType);
            info.put("mid", mp.getMid());
            info.put("ask", mp.getAsk());
            info.put("bid", mp.getBid());
        } catch (Exception e) {
            log.debug("Price fetch skipped for {}: {}", saxoSymbol, e.getMessage());
        }

        return ResponseEntity.ok(info);
    }

    /**
     * PUT /api/trade/capital/{dealId}/risk
     * Set or update stop loss and/or take profit on an existing Capital.com position.
     * Body: { "stopLevel": 4400.0, "limitLevel": 4800.0 }
     * Omit a field (or set to 0) to leave it unchanged.
     */
    @PutMapping("/capital/{dealId}/risk")
    @Operation(summary = "Set stop loss and/or take profit on a Capital.com position")
    public ResponseEntity<Object> setCapitalRisk(@PathVariable String dealId,
                                                 @RequestBody Map<String, Object> req) {
        double stopLevel  = toDouble(req.get("stopLevel"));
        double limitLevel = toDouble(req.get("limitLevel"));
        if (stopLevel <= 0 && limitLevel <= 0)
            return ResponseEntity.badRequest().body(Map.of("error", "Provide stopLevel and/or limitLevel"));
        try {
            capitalComService.updatePosition(dealId, stopLevel, limitLevel);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("dealId", dealId);
            if (stopLevel  > 0) resp.put("stopLevel",  stopLevel);
            if (limitLevel > 0) resp.put("limitLevel", limitLevel);
            resp.put("status", "updated");
            log.info("Risk updated for Capital dealId={} SL={} TP={}", dealId, stopLevel, limitLevel);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    /** DELETE /api/trade/capital/{dealId} — close a Capital.com position */
    @DeleteMapping("/capital/{dealId}")
    @Operation(summary = "Close a Capital.com position by dealId")
    public ResponseEntity<Map<String, String>> closeCapital(@PathVariable String dealId) {
        log.info("Manual close Capital.com position dealId={}", dealId);
        capitalComService.closePosition(dealId);
        tradeJournal.record(TradeJournalEntry.builder()
                .timestamp(Instant.now()).source("MANUAL").broker("CAPITAL")
                .action("CLOSE").orderId(dealId).status("CLOSED")
                .alertName("Manual close").build());
        return ResponseEntity.ok(Map.of("status", "closed", "dealId", dealId, "broker", "CAPITAL"));
    }

    /** DELETE /api/trade/saxo/{orderId} — cancel a pending Saxo order */
    @DeleteMapping("/saxo/{orderId}")
    @Operation(summary = "Cancel a pending Saxo order by orderId")
    public ResponseEntity<Map<String, String>> cancelSaxo(@PathVariable String orderId) {
        log.info("Manual cancel Saxo order orderId={}", orderId);
        orderService.cancelOrder(orderId);
        return ResponseEntity.ok(Map.of("status", "cancelled", "orderId", orderId, "broker", "SAXO"));
    }

    /**
     * POST /api/trade/saxo/close
     * Close a Saxo open position by placing a reverse market order.
     * Body: { "ticker": "CL1!", "side": "BUY", "quantity": 10, "assetType": "CfdOnFutures" }
     */
    @PostMapping("/saxo/close")
    @Operation(summary = "Close a Saxo open position via a reverse market order")
    public ResponseEntity<Map<String, Object>> closeSaxoPosition(@RequestBody Map<String, Object> req) {
        String ticker    = str(req.get("ticker")).toUpperCase();
        String openSide  = str(req.get("side")).toUpperCase();
        String assetType = str(req.get("assetType"));
        double quantity  = toDouble(req.get("quantity"));
        String closeSide = openSide.equals("BUY") ? "SELL" : "BUY";

        TrendSpiderSymbolConfig.SymbolMapping mapping = symbolConfig.getSymbols().get(ticker);
        String saxoSymbol = mapping != null ? mapping.getSaxoSymbol() : ticker;
        if (assetType.isBlank() && mapping != null) assetType = mapping.getAssetType();

        try {
            OrderRequest orderReq = OrderRequest.builder()
                    .symbol(saxoSymbol).action(closeSide)
                    .quantity(quantity > 0 ? quantity : (mapping != null ? mapping.getQuantity() : 1))
                    .assetType(assetType.isBlank() ? "CfdOnFutures" : assetType)
                    .orderType("MKT")
                    .build();
            var r = orderService.placeOrder(orderReq);
            tradeJournal.record(TradeJournalEntry.builder()
                    .timestamp(Instant.now()).source("MANUAL").broker("SAXO")
                    .ticker(ticker).saxoSymbol(saxoSymbol).assetType(orderReq.getAssetType())
                    .action("CLOSE").quantity(orderReq.getQuantity())
                    .orderId(r.getOrderId()).alertName("Manual close").status("CLOSED").build());
            return ResponseEntity.ok(Map.of("status", "close_order_placed",
                    "closeSide", closeSide, "orderId", r.getOrderId()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "FAILED", "error", e.getMessage()));
        }
    }

    private String str(Object val) { return val == null ? "" : val.toString(); }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return 0.0; }
    }
}
