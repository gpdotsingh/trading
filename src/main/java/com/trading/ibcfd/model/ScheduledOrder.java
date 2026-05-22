package com.trading.ibcfd.model;

import java.time.Instant;

/**
 * An order queued to be placed at a specific future time.
 * Status lifecycle: PENDING → FIRED | FAILED | CANCELLED
 */
public record ScheduledOrder(
        String  id,
        String  ticker,
        String  action,
        double  quantity,
        String  broker,
        Instant scheduledAt,
        Instant createdAt,
        String  status,
        String  failReason
) {
    public ScheduledOrder withStatus(String newStatus, String reason) {
        return new ScheduledOrder(id, ticker, action, quantity, broker,
                scheduledAt, createdAt, newStatus, reason);
    }
}
