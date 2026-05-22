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
 * Monitors open positions on a 20-second schedule and applies dynamic trailing stop loss.
 * Math is delegated to StopLossCalculator; position storage to OpenPositionRegistry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicStopLossService {

    private final MarketDataService     marketDataService;
    private final OrderService          orderService;
    private final OpenPositionRegistry  registry;
    private final StopLossCalculator    calc;

    public OpenPosition register(String ticker, String saxoSymbol, String assetType,
                                 String action, double quantity,
                                 double entryPrice, String orderId) {
        double sl = calc.stopLossPrice(action, entryPrice);
        double tp = calc.takeProfitPrice(action, entryPrice);

        OpenPosition pos = OpenPosition.builder()
                .positionId(UUID.randomUUID().toString())
                .ticker(ticker).saxoSymbol(saxoSymbol).assetType(assetType)
                .action(action).quantity(quantity).entryPrice(entryPrice)
                .stopLossPrice(sl).initialStopLossPrice(sl).takeProfitPrice(tp)
                .openedAt(Instant.now()).orderId(orderId)
                .status("OPEN").lastLockedMarginPct(0.0)
                .build();

        registry.put(pos);
        log.info("Position registered: {} {} {} entry={} SL={} TP={}", action, quantity, ticker, entryPrice, sl, tp);
        return pos;
    }

    public List<OpenPosition> getOpenPositions() { return registry.getOpen(); }

    public List<OpenPosition> getAllPositions()   { return registry.getAll(); }

    @Scheduled(fixedDelay = 20_000)
    public void monitor() {
        List<OpenPosition> open = registry.getOpen();
        if (open.isEmpty()) return;
        open.forEach(pos -> {
            try { checkAndAdjust(pos); } catch (Exception e) {
                log.warn("Price check failed for {}: {}", pos.getTicker(), e.getMessage());
            }
        });
        registry.broadcast();
    }

    private void checkAndAdjust(OpenPosition pos) {
        MarketPrice mp = marketDataService.getMarketPrice(pos.getSaxoSymbol(), pos.getAssetType());
        double current = mp.getMid() > 0 ? mp.getMid()
                : "BUY".equals(pos.getAction()) ? mp.getAsk() : mp.getBid();
        if (current <= 0) { log.debug("No price for {} — skipping", pos.getTicker()); return; }

        double margin = calc.marginPct(pos.getAction(), pos.getEntryPrice(), current);

        if (calc.takeProfitHit(pos.getAction(), current, pos.getTakeProfitPrice())) {
            closePosition(pos, current, "CLOSED_PROFIT",
                    String.format("Take profit hit at %.4f (margin +%.2f%%)", current, margin));
            return;
        }
        if (calc.stopLossHit(pos.getAction(), current, pos.getStopLossPrice())) {
            closePosition(pos, current, "CLOSED_LOSS",
                    String.format("Stop loss hit at %.4f (margin %.2f%%)", current, margin));
            return;
        }
        if (margin > 0) {
            double locked = margin * StopLossCalculator.TRAIL_RATIO;
            double newSl  = calc.trailingStopPrice(pos.getAction(), pos.getEntryPrice(), locked);
            if (calc.shouldUpdateStopLoss(pos.getAction(), newSl, pos.getStopLossPrice())) {
                log.info("Trailing SL adjusted: {} {} margin={}% locked={}% SL {} → {}",
                        pos.getAction(), pos.getTicker(), margin, locked, pos.getStopLossPrice(), newSl);
                pos.setStopLossPrice(newSl);
                pos.setLastLockedMarginPct(locked);
            }
        }
    }

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
