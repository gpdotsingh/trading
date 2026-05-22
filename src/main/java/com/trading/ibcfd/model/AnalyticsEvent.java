package com.trading.ibcfd.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AnalyticsEvent {

    /** TRADE_OPEN | TRADE_CLOSE | PNL_UPDATE */
    private String type;

    private String symbol;

    /** BUY | SELL | HOLD */
    private String action;

    private double price;
    private double quantity;

    /** P&L of this specific closed trade (TRADE_CLOSE only, else 0) */
    private double tradePnl;

    /** Cumulative total P&L across all trades */
    private double cumPnl;

    private double realizedPnl;
    private double unrealizedPnl;
    private double winRate;
    private int    tradeCount;

    /** ISO-8601 timestamp from Python */
    private String timestamp;
}
