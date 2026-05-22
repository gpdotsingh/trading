package com.trading.ibcfd.controller;

import com.trading.ibcfd.model.AnalyticsEvent;
import com.trading.ibcfd.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "Python strategy P&L events — received here, broadcast to /topic/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    /**
     * POST /api/analytics/event
     * Called by Python analytics_reporter.py on every signal/trade.
     */
    @PostMapping("/event")
    @Operation(summary = "Receive analytics event", description = "Python posts BUY/SELL/PNL_UPDATE events here; Spring broadcasts to /topic/analytics")
    public ResponseEntity<Void> receiveEvent(@RequestBody AnalyticsEvent event) {
        analyticsService.recordEvent(event);
        return ResponseEntity.ok().build();
    }

    /**
     * GET /api/analytics/history?limit=200
     * Returns the last N events for chart replay on page load.
     */
    @GetMapping("/history")
    @Operation(summary = "Get event history", description = "Returns last N analytics events for chart page-load replay")
    public ResponseEntity<List<AnalyticsEvent>> getHistory(
            @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(analyticsService.getHistory(limit));
    }

    /**
     * GET /api/analytics/summary
     * Returns the most recent event (latest P&L snapshot).
     */
    @GetMapping("/summary")
    @Operation(summary = "Get latest P&L summary")
    public ResponseEntity<AnalyticsEvent> getSummary() {
        AnalyticsEvent summary = analyticsService.getLatestSummary();
        if (summary == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(summary);
    }
}
