package com.trading.ibcfd.controller;

import com.trading.ibcfd.model.OrderRequest;
import com.trading.ibcfd.model.OrderResponse;
import com.trading.ibcfd.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Place, query and cancel CFD orders on Saxo Bank simulation")
public class OrderController {

    private final OrderService orderService;

    @Value("${trading.manual-orders-enabled:false}")
    private boolean manualOrdersEnabled;

    /**
     * POST /api/orders
     * Disabled by default — all trades must come through TrendSpider webhook.
     * Set trading.manual-orders-enabled=true in application.properties to re-enable.
     */
    @PostMapping
    @Operation(
        summary = "Place a CFD order (disabled — use TrendSpider webhook)",
        description = "Blocked unless trading.manual-orders-enabled=true. All orders should flow through TrendSpider signals."
    )
    public ResponseEntity<?> placeOrder(@Valid @RequestBody OrderRequest request) {
        if (!manualOrdersEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Manual orders are disabled. Send signals via TrendSpider webhook to place trades."));
        }
        return ResponseEntity.ok(orderService.placeOrder(request));
    }

    @GetMapping("/{orderId}/status")
    @Operation(summary = "Get order status", description = "Returns the current status of a Saxo order by its OrderId")
    public ResponseEntity<OrderResponse> getStatus(
            @Parameter(description = "Saxo OrderId returned when order was placed")
            @PathVariable String orderId) {
        return ResponseEntity.ok(orderService.getOrderStatus(orderId));
    }

    @DeleteMapping("/{orderId}")
    @Operation(summary = "Cancel an order", description = "Cancels an open order on Saxo simulation")
    public ResponseEntity<OrderResponse> cancelOrder(
            @Parameter(description = "Saxo OrderId to cancel")
            @PathVariable String orderId) {
        return ResponseEntity.ok(orderService.cancelOrder(orderId));
    }
}
