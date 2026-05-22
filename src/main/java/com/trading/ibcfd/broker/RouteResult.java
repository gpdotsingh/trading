package com.trading.ibcfd.broker;

/** Immutable outcome of one broker route attempt (success or failure). */
public record RouteResult(
        String broker,
        String orderId,
        String saxoSymbol,
        String assetType,
        double quantity,
        String status,
        String failReason,
        String note,        // non-null when quantity was adjusted (e.g. raised to minimum)
        double entryPrice   // 0 if unknown; used for trailing stop registration
) {
    public boolean placed() { return "PLACED".equals(status); }

    public RouteResult(String broker, String orderId, String saxoSymbol, String assetType,
                       double quantity, String status, String failReason) {
        this(broker, orderId, saxoSymbol, assetType, quantity, status, failReason, null, 0);
    }
}
