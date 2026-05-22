package com.trading.ibcfd.controller;

import com.trading.ibcfd.model.TradeJournalEntry;
import com.trading.ibcfd.service.TradeJournal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
@Tag(name = "Trade Journal", description = "Full history of auto-trades placed via TrendSpider signals")
public class TradeJournalController {

    private final TradeJournal tradeJournal;

    /**
     * GET /api/trades/journal
     * All trades, newest first. Optionally filter by source.
     *
     * ?source=TRENDSPIDER  — only TrendSpider-triggered trades
     * ?source=MANUAL       — only manually placed trades
     */
    @GetMapping("/journal")
    @Operation(summary = "Full trade journal — all auto and manual orders")
    public ResponseEntity<List<TradeJournalEntry>> journal(
            @RequestParam(required = false) String source) {
        List<TradeJournalEntry> result = (source != null && !source.isBlank())
                ? tradeJournal.getBySource(source)
                : tradeJournal.getAll();
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/trades/summary
     * Counts and notional exposure grouped by symbol.
     */
    @GetMapping("/summary")
    @Operation(summary = "Trade counts and notional exposure summary")
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(tradeJournal.summary());
    }
}
