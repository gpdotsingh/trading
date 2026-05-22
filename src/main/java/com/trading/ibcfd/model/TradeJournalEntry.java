package com.trading.ibcfd.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class TradeJournalEntry {

    private String  id;
    private Instant timestamp;

    /** TRENDSPIDER or MANUAL */
    private String  source;

    /** Ticker as received from TrendSpider (e.g. XAUUSD) */
    private String  ticker;

    /** Mapped Saxo instrument name (e.g. Gold) */
    private String  saxoSymbol;

    private String  assetType;

    /** BUY or SELL */
    private String  action;

    /** Number of contracts/lots */
    private double  quantity;

    /** Price at alert time from TrendSpider */
    private double  alertPrice;

    /** TrendSpider alert name */
    private String  alertName;

    /** Which broker executed this trade: SAXO, CAPITAL */
    private String  broker;

    /** Saxo OrderId (null if order failed) */
    private String  orderId;

    /** PLACED or FAILED */
    private String  status;

    /** Reason if status = FAILED */
    private String  failReason;
}
