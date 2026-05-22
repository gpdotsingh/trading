package com.trading.ibcfd.service;

import com.trading.ibcfd.model.MarketPrice;
import com.trading.ibcfd.model.OpenPosition;
import com.trading.ibcfd.model.OrderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Monitors open positions every 20 s and applies a fixed-distance trailing stop loss.
 *
 * Trailing rule:
 *   trailDistance = entry - initialSL  (constant, set once at open)
 *   newSL = currentPrice - trailDistance  (BUY)
 *   newSL = currentPrice + trailDistance  (SELL)
 *   Only update when newSL is strictly better than currentSL — never reverses.
 *
 * When the SL moves, the new level is pushed to the broker:
 *   Capital.com → PUT /api/v1/positions/{dealId}  (stopLevel)
 *   Saxo        → PUT /trade/v2/orders/{stopOrderId} (looked up by symbol/type)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicStopLossService {

    private final MarketDataService    marketDataService;
    private final OrderService         orderService;
    private final CapitalComService    capitalComService;
    private final OpenPositionRegistry registry;
    private final StopLossCalculator   calc;

    public OpenPosition register(String ticker, String saxoSymbol, String assetType,
                                 String action, double quantity, double entryPrice,
                                 String orderId, String broker, String capitalDealId) {
        double sl   = calc.stopLossPrice(action, entryPrice);
        double tp   = calc.takeProfitPrice(action, entryPrice);
        double dist = Math.abs(entryPrice - sl);

        OpenPosition pos = OpenPosition.builder()
                .positionId(UUID.randomUUID().toString())
                .ticker(ticker).saxoSymbol(saxoSymbol).assetType(assetType)
                .action(action).quantity(quantity).entryPrice(entryPrice)
                .stopLossPrice(sl).initialStopLossPrice(sl).takeProfitPrice(tp)
                .trailDistance(dist)
                .broker(broker).capitalDealId(capitalDealId)
                .openedAt(Instant.now()).orderId(orderId)
                .status("OPEN").lastLockedMarginPct(0.0)
                .build();

        registry.put(pos);
        log.info("Position registered [{}]: {} {} {} entry={} SL={} TP={} trail={}",
                broker, action, quantity, ticker, entryPrice, sl, tp, dist);
        return pos;
    }

    /** Backward-compatible overload for existing Saxo-only callers. */
    public OpenPosition register(String ticker, String saxoSymbol, String assetType,
                                 String action, double quantity,
                                 double entryPrice, String orderId) {
        return register(ticker, saxoSymbol, assetType, action, quantity, entryPrice, orderId, "SAXO", null);
    }

    public List<OpenPosition> getOpenPositions() { return registry.getOpen(); }
    public List<OpenPosition> getAllPositions()   { return registry.getAll(); }

    @Scheduled(fixedDelay = 20_000)
    public void monitor() {
        List<OpenPosition> open = registry.getOpen();
        if (open.isEmpty()) return;
        open.forEach(pos -> {
            try { checkAndAdjust(pos); }
            catch (Exception e) { log.warn("Price check failed for {}: {}", pos.getTicker(), e.getMessage()); }
        });
        registry.broadcast();
    }

    // ── Core logic ────────────────────────────────────────────────────────────

    private void checkAndAdjust(OpenPosition pos) {
        MarketPrice mp = marketDataService.getMarketPrice(pos.getSaxoSymbol(), pos.getAssetType());
        double current = "BUY".equals(pos.getAction()) ? mp.getBid() : mp.getAsk();
        if (current <= 0) { log.debug("No price for {} — skipping", pos.getTicker()); return; }

        if (calc.stopLossHit(pos.getAction(), current, pos.getStopLossPrice())) {
            closePosition(pos, current, "CLOSED_LOSS",
                    String.format("Stop loss hit at %.4f", current));
            return;
        }
        if (calc.takeProfitHit(pos.getAction(), current, pos.getTakeProfitPrice())) {
            closePosition(pos, current, "CLOSED_PROFIT",
                    String.format("Take profit hit at %.4f", current));
            return;
        }

        // Fixed-distance trailing: keep SL exactly trailDistance behind best price
        double dist  = pos.getTrailDistance() > 0 ? pos.getTrailDistance()
                     : Math.abs(pos.getEntryPrice() - pos.getInitialStopLossPrice());
        double newSl = "BUY".equals(pos.getAction()) ? current - dist : current + dist;

        if (calc.shouldUpdateStopLoss(pos.getAction(), newSl, pos.getStopLossPrice())) {
            log.info("Trailing SL: {} {} price={} SL {} → {} (trail={})",
                    pos.getAction(), pos.getTicker(), current, pos.getStopLossPrice(), newSl, dist);
            pushStopToBroker(pos, newSl);
            pos.setStopLossPrice(newSl);
        }
    }

    // ── Broker stop update ────────────────────────────────────────────────────

    private void pushStopToBroker(OpenPosition pos, double newSl) {
        try {
            if ("CAPITAL".equals(pos.getBroker()) && pos.getCapitalDealId() != null) {
                capitalComService.updatePosition(pos.getCapitalDealId(), newSl, 0);
                log.info("Capital SL updated: dealId={} newSL={}", pos.getCapitalDealId(), newSl);
            } else if ("SAXO".equals(pos.getBroker())) {
                orderService.updateStopOrder(pos.getSaxoSymbol(), pos.getAssetType(), newSl);
            }
        } catch (Exception e) {
            log.warn("Could not push SL update to broker for {}: {}", pos.getTicker(), e.getMessage());
        }
    }

    // ── Position close ────────────────────────────────────────────────────────

    private void closePosition(OpenPosition pos, double closePrice, String status, String reason) {
        pos.setStatus(status);
        log.info("Position closed [{}]: {} {} @ {} — {}", status, pos.getAction(), pos.getTicker(), closePrice, reason);
        try {
            OrderRequest req = OrderRequest.builder()
                    .symbol(pos.getSaxoSymbol())
                    .action("BUY".equals(pos.getAction()) ? "SELL" : "BUY")
                    .quantity(pos.getQuantity())
                    .assetType(pos.getAssetType())
                    .orderType("MKT")
                    .build();
            orderService.placeOrder(req);
        } catch (Exception e) {
            log.error("Failed to place closing order for {}: {}", pos.getTicker(), e.getMessage());
        }
    }
}
