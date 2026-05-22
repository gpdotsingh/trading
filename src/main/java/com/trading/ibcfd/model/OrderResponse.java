package com.trading.ibcfd.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OrderResponse {
    private String orderId;
    private String symbol;
    private String assetType;
    private int    uic;
    private String action;
    private double quantity;
    private String orderType;
    private String status;
    private String message;
}
