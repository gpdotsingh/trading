package com.trading.ibcfd.controller;

import com.trading.ibcfd.model.ScheduledOrder;
import com.trading.ibcfd.service.ScheduledOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trade/scheduled")
@RequiredArgsConstructor
@Tag(name = "Scheduled Orders", description = "Place an order at a specific future time on Saxo or Capital.com")
public class ScheduledOrderController {

    private final ScheduledOrderService scheduledOrderService;

    /**
     * GET /api/trade/scheduled
     * List all scheduled orders (PENDING, FIRED, CANCELLED, FAILED).
     */
    @GetMapping
    @Operation(summary = "List all scheduled orders")
    public ResponseEntity<List<ScheduledOrder>> list() {
        return ResponseEntity.ok(scheduledOrderService.getAll());
    }

    /**
     * POST /api/trade/scheduled
     * Body: { "ticker": "GOLD", "action": "BUY", "quantity": 0.1, "broker": "saxo", "scheduledAt": "2025-06-01T09:00:00Z" }
     * quantity 0 or absent → use mapping default.
     * scheduledAt must be an ISO-8601 UTC instant in the future.
     */
    @PostMapping
    @Operation(summary = "Schedule an order for a future time")
    public ResponseEntity<Object> create(@RequestBody Map<String, Object> req) {
        String ticker   = str(req.get("ticker"));
        String action   = str(req.get("action")).toUpperCase();
        String broker   = str(req.get("broker"));
        double quantity = toDouble(req.get("quantity"));
        String atRaw    = str(req.get("scheduledAt"));

        if (ticker.isBlank())  return bad("ticker is required");
        if (!action.equals("BUY") && !action.equals("SELL")) return bad("action must be BUY or SELL");
        if (atRaw.isBlank())   return bad("scheduledAt (ISO-8601 UTC) is required");

        Instant scheduledAt;
        try { scheduledAt = Instant.parse(atRaw); }
        catch (Exception e) { return bad("Invalid scheduledAt — use ISO-8601 e.g. 2025-06-01T09:00:00Z"); }

        try {
            ScheduledOrder order = scheduledOrderService.schedule(ticker, action, quantity, broker, scheduledAt);
            return ResponseEntity.ok(order);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /**
     * DELETE /api/trade/scheduled/{id}
     * Cancel a PENDING scheduled order before it fires.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel a pending scheduled order")
    public ResponseEntity<Object> cancel(@PathVariable String id) {
        boolean cancelled = scheduledOrderService.cancel(id.toUpperCase());
        if (!cancelled) return ResponseEntity.badRequest()
                .body(Map.of("error", "Order " + id + " not found or not cancellable"));
        return ResponseEntity.ok(Map.of("status", "CANCELLED", "id", id.toUpperCase()));
    }

    private static ResponseEntity<Object> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private static String str(Object v)    { return v == null ? "" : v.toString(); }
    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0.0; }
    }
}
