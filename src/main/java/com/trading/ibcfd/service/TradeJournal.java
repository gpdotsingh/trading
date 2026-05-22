package com.trading.ibcfd.service;

import com.trading.ibcfd.model.TradeJournalEntry;

import java.util.List;
import java.util.Map;

public interface TradeJournal {
    void record(TradeJournalEntry entry);
    List<TradeJournalEntry> getAll();
    List<TradeJournalEntry> getBySource(String source);
    Map<String, Object> summary();
}
