package com.trading.ibcfd.service;

import com.trading.ibcfd.broker.BrokerRouter;
import com.trading.ibcfd.broker.RouteResult;
import com.trading.ibcfd.model.ScheduledOrder;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledOrderService {

    private final BrokerRouter brokerRouter;

    private final ConcurrentHashMap<String, ScheduledOrder>  orders   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);

    /**
     * Schedule an order to be placed at scheduledAt.
     * Returns the new ScheduledOrder (status=PENDING).
     */
    public ScheduledOrder schedule(String ticker, String action, double quantity,
                                   String broker, Instant scheduledAt) {
        if (scheduledAt.isBefore(Instant.now()))
            throw new IllegalArgumentException("scheduledAt must be in the future");

        String id = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ScheduledOrder order = new ScheduledOrder(id, ticker.toUpperCase(), action.toUpperCase(),
                quantity, broker, scheduledAt, Instant.now(), "PENDING", null);
        orders.put(id, order);

        long delayMs = scheduledAt.toEpochMilli() - Instant.now().toEpochMilli();
        ScheduledFuture<?> future = executor.schedule(() -> fire(id), delayMs, TimeUnit.MILLISECONDS);
        futures.put(id, future);

        log.info("Scheduled order {}: {} {} qty={} broker={} at {}", id, action, ticker, quantity, broker, scheduledAt);
        return order;
    }

    public boolean cancel(String id) {
        ScheduledOrder o = orders.get(id);
        if (o == null || !"PENDING".equals(o.status())) return false;
        ScheduledFuture<?> f = futures.remove(id);
        if (f != null) f.cancel(false);
        orders.put(id, o.withStatus("CANCELLED", null));
        log.info("Cancelled scheduled order {}", id);
        return true;
    }

    public List<ScheduledOrder> getAll() {
        return orders.values().stream()
                .sorted(Comparator.comparing(ScheduledOrder::scheduledAt))
                .toList();
    }

    public Optional<ScheduledOrder> getById(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    private void fire(String id) {
        ScheduledOrder o = orders.get(id);
        if (o == null || !"PENDING".equals(o.status())) return;
        log.info("Firing scheduled order {}: {} {} qty={} broker={}", id, o.action(), o.ticker(), o.quantity(), o.broker());
        try {
            List<RouteResult> results = brokerRouter.routeWith(
                    o.ticker(), o.action(), "SCHEDULED", 0.0, "Scheduled order " + id, o.broker(), o.quantity());
            boolean anyPlaced = results.stream().anyMatch(RouteResult::placed);
            String status = anyPlaced ? "FIRED" : "FAILED";
            String reason = anyPlaced ? null : results.stream()
                    .map(r -> r.broker() + ": " + r.failReason()).reduce((a, b) -> a + "; " + b).orElse("Unknown");
            orders.put(id, o.withStatus(status, reason));
            log.info("Scheduled order {} → {}", id, status);
        } catch (Exception e) {
            orders.put(id, o.withStatus("FAILED", e.getMessage()));
            log.error("Scheduled order {} failed: {}", id, e.getMessage());
        }
        futures.remove(id);
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }
}
