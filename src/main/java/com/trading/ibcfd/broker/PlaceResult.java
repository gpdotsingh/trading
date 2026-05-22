package com.trading.ibcfd.broker;

/** Immutable result returned by a BrokerGateway after successfully placing an order. */
public record PlaceResult(
        String orderId,
        String saxoSymbol,
        String assetType,
        double quantity,
        String note,        // null unless quantity was adjusted (e.g. below minimum)
        double entryPrice   // 0 if not available; used for trailing stop registration
) {
    public PlaceResult(String orderId, String saxoSymbol, String assetType, double quantity) {
        this(orderId, saxoSymbol, assetType, quantity, null, 0);
    }

    public PlaceResult(String orderId, String saxoSymbol, String assetType, double quantity, String note) {
        this(orderId, saxoSymbol, assetType, quantity, note, 0);
    }
}
