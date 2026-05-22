package com.trading.ibcfd.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class InstrumentDetails {

    private String symbol;
    private String assetType;
    private int    uic;
    private String description;
    private String currencyCode;
    private double minimumTradeSize;
    private double lotSize;
    private double maximumTradeSize;
}
