package com.trading.ibcfd.controller;

import com.trading.ibcfd.model.TrendSpiderAlert;
import com.trading.ibcfd.service.TrendSpiderWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/webhook/trendspider")
@RequiredArgsConstructor
@Tag(name = "TrendSpider", description = "Receives TrendSpider alert webhooks and broadcasts to /topic/trendspider")
public class TrendSpiderWebhookController {

    private final TrendSpiderWebhookService webhookService;

    /**
     * POST /api/webhook/trendspider
     *
     * Configure this URL in TrendSpider → Alerts → Webhook.
     * If trendspider.webhook.secret is set, pass it as:
     *   Header:  X-TrendSpider-Secret: <secret>
     *   OR query: ?secret=<secret>
     *
     * Example body to put in TrendSpider alert (Entry webhook = BUY, Exit webhook = SELL):
     * {
     *   "ticker":    "{{ticker}}",
     *   "price":     {{close}},
     *   "action":    "BUY",
     *   "alertName": "{{alert_name}}",
     *   "interval":  "{{interval}}",
     *   "message":   "{{message}}",
     *   "timestamp": "{{time}}",
     *   "mfi":       {{MFI(14)}},
     *   "rsi":       {{RSI(14)}},
     *   "volume":    {{volume}}
     * }
     * Note: MFI(14) requires the Money Flow Index indicator to be added to your TrendSpider chart.
     * If MFI is not on the chart, remove that line from the body to avoid errors.
     */
    @PostMapping
    @Operation(summary = "Receive TrendSpider webhook alert")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody TrendSpiderAlert alert,
            @RequestHeader(value = "X-TrendSpider-Secret", required = false) String headerSecret,
            @RequestParam(value = "secret", required = false) String querySecret) {

        String provided = headerSecret != null ? headerSecret : (querySecret != null ? querySecret : "");

        boolean accepted = webhookService.process(alert, provided);
        if (!accepted) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid webhook secret"));
        }

        return ResponseEntity.ok(Map.of(
                "status",    "received",
                "ticker",    alert.getTicker() != null ? alert.getTicker() : "",
                "action",    alert.getAction() != null ? alert.getAction() : "",
                "price",     alert.getPrice() != null ? alert.getPrice() : 0.0
        ));
    }

    /**
     * GET /api/webhook/trendspider/history?limit=50
     * Returns the last N received alerts.
     */
    @GetMapping("/history")
    @Operation(summary = "Get recent TrendSpider alerts")
    public ResponseEntity<List<TrendSpiderAlert>> history(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(webhookService.getHistory(limit));
    }
}
