package com.trading.ibcfd.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MarketPrice {
    private String symbol;
    private String assetType;
    private int    uic;
    private double bid;
    private double ask;
    private double mid;
    private String timestamp;
}
