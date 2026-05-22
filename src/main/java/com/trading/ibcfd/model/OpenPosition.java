package com.trading.ibcfd.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class OpenPosition {

    private String  positionId;
    private String  ticker;
    private String  saxoSymbol;
    private String  assetType;

    /** BUY or SELL */
    private String  action;
    private double  quantity;

    private double  entryPrice;

    /** Current dynamic stop loss price — moves up as profit grows */
    private double  stopLossPrice;

    /** Fixed take profit price — never changes */
    private double  takeProfitPrice;

    /** Original stop loss price set at entry — used for reference */
    private double  initialStopLossPrice;

    private Instant openedAt;
    private String  orderId;

    /** OPEN | CLOSED_PROFIT | CLOSED_LOSS | CLOSED_MANUAL */
    private String  status;

    /** % margin locked in at last adjustment */
    private double  lastLockedMarginPct;
}
