package com.trading.ibcfd.broker;

/**
 * Abstraction over a trading venue (Saxo, Capital.com, …).
 * Implement this to add a new broker without touching existing code.
 */
public interface BrokerGateway {

    /** Logical name used in journal entries — e.g. "SAXO" or "CAPITAL". */
    String brokerName();

    /**
     * True when this gateway should execute for the given trading.broker value.
     * Examples: Saxo is active for "saxo" and "both" but not "capital".
     */
    boolean isActiveFor(String tradingBroker);

    /**
     * Place a BUY or SELL order with an explicit quantity.
     *
     * @param tickerOrSymbol  TrendSpider ticker OR Saxo-style symbol name
     * @param action          "BUY" or "SELL"
     * @param requestedQty    desired quantity; ≤ 0 means "use mapping default"
     * @return PlaceResult with order id, resolved symbol, asset type, actual quantity, and optional note
     * @throws Exception on mapping failure or broker rejection
     */
    PlaceResult place(String tickerOrSymbol, String action, double requestedQty) throws Exception;

    /** Convenience overload — uses the mapping default quantity. */
    default PlaceResult place(String tickerOrSymbol, String action) throws Exception {
        return place(tickerOrSymbol, action, 0);
    }
}
