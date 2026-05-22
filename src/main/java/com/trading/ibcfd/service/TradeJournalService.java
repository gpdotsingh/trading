package com.trading.ibcfd.service;

import com.trading.ibcfd.model.TradeJournalEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TradeJournalService implements TradeJournal {

    private static final int MAX_ENTRIES = 500;

    private final Deque<TradeJournalEntry> journal = new ArrayDeque<>();
    private final AtomicLong counter = new AtomicLong(1);

    public void record(TradeJournalEntry entry) {
        synchronized (journal) {
            if (entry.getId() == null) {
                entry = TradeJournalEntry.builder()
                        .id("TJ-" + counter.getAndIncrement())
                        .timestamp(entry.getTimestamp() != null ? entry.getTimestamp() : Instant.now())
                        .source(entry.getSource())
                        .broker(entry.getBroker())
                        .ticker(entry.getTicker())
                        .saxoSymbol(entry.getSaxoSymbol())
                        .assetType(entry.getAssetType())
                        .action(entry.getAction())
                        .quantity(entry.getQuantity())
                        .alertPrice(entry.getAlertPrice())
                        .alertName(entry.getAlertName())
                        .orderId(entry.getOrderId())
                        .status(entry.getStatus())
                        .failReason(entry.getFailReason())
                        .build();
            }
            if (journal.size() >= MAX_ENTRIES) journal.pollFirst();
            journal.addLast(entry);
        }
        log.info("Trade journal: [{}] {} {} {} qty={} price={} orderId={}",
                entry.getSource(), entry.getStatus(), entry.getAction(),
                entry.getSaxoSymbol(), entry.getQuantity(), entry.getAlertPrice(), entry.getOrderId());
    }

    /** All entries, newest first */
    public List<TradeJournalEntry> getAll() {
        synchronized (journal) {
            List<TradeJournalEntry> list = new ArrayList<>(journal);
            Collections.reverse(list);
            return list;
        }
    }

    /** Filter by source (TRENDSPIDER / MANUAL) */
    public List<TradeJournalEntry> getBySource(String source) {
        return getAll().stream()
                .filter(e -> source.equalsIgnoreCase(e.getSource()))
                .collect(Collectors.toList());
    }

    /** P&L summary per ticker — requires closed positions to be fed in, but at minimum shows trade counts and exposure */
    public Map<String, Object> summary() {
        List<TradeJournalEntry> all = getAll();
        long total      = all.size();
        long fromTs     = all.stream().filter(e -> "TRENDSPIDER".equals(e.getSource())).count();
        long fromManual = all.stream().filter(e -> "MANUAL".equals(e.getSource())).count();
        long placed     = all.stream().filter(e -> "PLACED".equals(e.getStatus())).count();
        long failed     = all.stream().filter(e -> "FAILED".equals(e.getStatus())).count();

        // exposure per symbol: sum of (quantity × price) for PLACED orders
        Map<String, Double> exposure = new LinkedHashMap<>();
        for (TradeJournalEntry e : all) {
            if ("PLACED".equals(e.getStatus())) {
                String key = e.getSaxoSymbol() + " (" + e.getAssetType() + ")";
                double notional = e.getQuantity() * e.getAlertPrice();
                exposure.merge(key, notional, (a, b) -> a + b);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTrades",       total);
        result.put("fromTrendSpider",   fromTs);
        result.put("fromManual",        fromManual);
        result.put("placed",            placed);
        result.put("failed",            failed);
        result.put("notionalExposure",  exposure);
        return result;
    }
}
