package com.trading.ibcfd.controller;

import com.trading.ibcfd.config.CapitalComConfig;
import com.trading.ibcfd.service.CapitalComService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/capital")
@RequiredArgsConstructor
@Tag(name = "Capital.com", description = "Capital.com CFD trading — BUY and SELL positions")
@ConditionalOnProperty(name = "capital.enabled", havingValue = "true")
public class CapitalComController {

    private final CapitalComService    capitalComService;
    private final CapitalComConfig     config;

    @PostMapping("/session")
    @Operation(summary = "Create/refresh Capital.com session")
    public ResponseEntity<Map<String, String>> createSession() {
        capitalComService.createSession();
        return ResponseEntity.ok(Map.of("status", "session created"));
    }

    /**
     * Open a BUY (long) or SELL (short) CFD position.
     *
     * Body: { "ticker": "CL1!", "direction": "BUY" }
     *
     * BUY  = go long  → profit when price rises
     * SELL = go short → profit when price falls
     */
    @PostMapping("/positions")
    @Operation(
        summary = "Open a CFD position (BUY or SELL)",
        description = "BUY = long (profit when price rises). SELL = short (profit when price falls). " +
                      "Ticker must be mapped in capital.symbols config."
    )
    public ResponseEntity<Map<String, Object>> openPosition(@RequestBody Map<String, Object> req) {
        String ticker    = str(req.get("ticker"));
        String direction = str(req.get("direction"));

        double stopDistance  = toDouble(req.get("stopDistance"));
        double limitDistance = toDouble(req.get("limitDistance"));

        String dealRef = (stopDistance > 0 || limitDistance > 0)
                ? capitalComService.openPositionWithRisk(ticker, direction, stopDistance, limitDistance)
                : capitalComService.openPosition(ticker, direction);

        return ResponseEntity.ok(Map.of(
                "status",        "opened",
                "ticker",        ticker,
                "direction",     direction,
                "dealReference", dealRef
        ));
    }

    @DeleteMapping("/positions/{dealId}")
    @Operation(summary = "Close an open position by dealId")
    public ResponseEntity<Map<String, String>> closePosition(@PathVariable String dealId) {
        capitalComService.closePosition(dealId);
        return ResponseEntity.ok(Map.of("status", "closed", "dealId", dealId));
    }

    @PutMapping("/positions/{dealId}")
    @Operation(summary = "Update stop loss / take profit on an open position")
    public ResponseEntity<Map<String, String>> updatePosition(
            @PathVariable String dealId,
            @RequestBody Map<String, Object> req) {
        capitalComService.updatePosition(
                dealId,
                toDouble(req.get("stopLevel")),
                toDouble(req.get("limitLevel")));
        return ResponseEntity.ok(Map.of("status", "updated", "dealId", dealId));
    }

    @GetMapping("/positions")
    @Operation(summary = "Get all open Capital.com positions")
    public ResponseEntity<List<Map<String, Object>>> getPositions() {
        return ResponseEntity.ok(capitalComService.getOpenPositions());
    }

    @GetMapping("/account")
    @Operation(summary = "Get Capital.com account balance and equity")
    public ResponseEntity<Map<String, Object>> getAccount() {
        return ResponseEntity.ok(capitalComService.getAccountDetails());
    }

    @GetMapping("/activity")
    @Operation(summary = "Capital.com activity history — placed orders, executions, rejections",
               description = "lastPeriod is in seconds. Default 86400 = last 24 hours.")
    public ResponseEntity<List<Map<String, Object>>> getActivity(
            @RequestParam(defaultValue = "86400") int lastPeriod) {
        return ResponseEntity.ok(capitalComService.getActivityHistory(lastPeriod));
    }

    @GetMapping("/workingorders")
    @Operation(summary = "Capital.com pending working orders (limit/stop)")
    public ResponseEntity<List<Map<String, Object>>> getWorkingOrders() {
        return ResponseEntity.ok(capitalComService.getWorkingOrders());
    }

    @GetMapping("/markets")
    @Operation(summary = "Search instruments by keyword to find the correct epic")
    public ResponseEntity<List<Map<String, Object>>> searchMarkets(
            @RequestParam String q) {
        return ResponseEntity.ok(capitalComService.searchInstruments(q));
    }

    @GetMapping("/symbol-map")
    @Operation(summary = "Show TrendSpider ticker → Capital.com epic mapping")
    public ResponseEntity<Map<String, CapitalComConfig.SymbolMapping>> getSymbolMap() {
        return ResponseEntity.ok(config.getSymbols());
    }

    private String str(Object val) { return val == null ? "" : val.toString(); }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return 0.0; }
    }
}
