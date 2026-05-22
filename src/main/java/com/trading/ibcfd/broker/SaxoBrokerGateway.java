package com.trading.ibcfd.broker;

import com.trading.ibcfd.config.TradingRiskConfig;
import com.trading.ibcfd.config.TrendSpiderSymbolConfig;
import com.trading.ibcfd.config.TrendSpiderSymbolConfig.SymbolMapping;
import com.trading.ibcfd.model.InstrumentDetails;
import com.trading.ibcfd.model.MarketPrice;
import com.trading.ibcfd.model.OrderRequest;
import com.trading.ibcfd.service.InstrumentLookup;
import com.trading.ibcfd.service.MarketDataService;
import com.trading.ibcfd.service.OrderService;
import com.trading.ibcfd.service.StopLossCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SaxoBrokerGateway implements BrokerGateway {

    private final OrderService            orderService;
    private final TrendSpiderSymbolConfig symbolConfig;
    private final InstrumentLookup        instrumentLookup;
    private final MarketDataService       marketDataService;
    private final StopLossCalculator      stopLossCalculator;
    private final TradingRiskConfig       riskConfig;

    @Override
    public String brokerName() { return "SAXO"; }

    @Override
    public boolean isActiveFor(String tradingBroker) {
        return !tradingBroker.equalsIgnoreCase("capital");
    }

    @Override
    public PlaceResult place(String tickerOrSymbol, String action, double requestedQty) throws Exception {
        SymbolMapping m   = resolve(tickerOrSymbol);
        double qty        = effectiveQty(m, requestedQty);
        String note       = buildNote(m, requestedQty, qty);
        double slPrice    = 0;
        double tpPrice    = 0;

        double entryPrice = 0;
        if (riskConfig.isEnabled()) {
            entryPrice = fetchEntryPrice(m.getSaxoSymbol(), m.getAssetType(), action);
            if (entryPrice > 0) {
                slPrice = stopLossCalculator.stopLossPrice(action, entryPrice);
                tpPrice = stopLossCalculator.takeProfitPrice(action, entryPrice);
                log.info("Saxo risk: entry={} SL={} TP={} ({}% / {}%)",
                        entryPrice, slPrice, tpPrice,
                        riskConfig.getStopLossPct(), riskConfig.getTakeProfitPct());
            }
        }

        OrderRequest req = OrderRequest.builder()
                .symbol(m.getSaxoSymbol())
                .action(action)
                .quantity(qty)
                .assetType(m.getAssetType())
                .orderType("MKT")
                .stopLossPrice(slPrice)
                .takeProfitPrice(tpPrice)
                .build();
        var r = orderService.placeOrder(req);
        log.info("Saxo order placed: {} {} qty={} orderId={}", action, tickerOrSymbol, qty, r.getOrderId());
        return new PlaceResult(r.getOrderId(), m.getSaxoSymbol(), m.getAssetType(), qty, note, entryPrice);
    }

    private double fetchEntryPrice(String saxoSymbol, String assetType, String action) {
        try {
            MarketPrice mp = marketDataService.getMarketPrice(saxoSymbol, assetType);
            return "BUY".equalsIgnoreCase(action) ? mp.getAsk() : mp.getBid();
        } catch (Exception e) {
            log.warn("Could not fetch entry price for {} — stop loss skipped: {}", saxoSymbol, e.getMessage());
            return 0;
        }
    }

    /** Fetch minimum trade size for a symbol — used by the UI to show the hint. */
    public double getMinimumTradeSize(String tickerOrSymbol) {
        SymbolMapping m = resolve(tickerOrSymbol);
        try {
            InstrumentDetails d = instrumentLookup.getDetails(m.getSaxoSymbol(), m.getAssetType());
            return d.getMinimumTradeSize();
        } catch (Exception e) {
            log.warn("Could not fetch min trade size for {}: {}", tickerOrSymbol, e.getMessage());
            return 1.0;
        }
    }

    private double effectiveQty(SymbolMapping m, double requestedQty) {
        double base = requestedQty > 0 ? requestedQty : m.getQuantity();
        try {
            InstrumentDetails d = instrumentLookup.getDetails(m.getSaxoSymbol(), m.getAssetType());
            double minSize = d.getMinimumTradeSize();
            if (base < minSize) {
                log.warn("Requested qty {} < minTradeSize {} for {} — adjusting to minimum",
                        base, minSize, m.getSaxoSymbol());
                return minSize;
            }
        } catch (Exception e) {
            log.debug("Min size check skipped for {}: {}", m.getSaxoSymbol(), e.getMessage());
        }
        return base;
    }

    private String buildNote(SymbolMapping m, double requestedQty, double effectiveQty) {
        if (requestedQty > 0 && effectiveQty != requestedQty) {
            return String.format("Requested %.4f but minimum trade size for %s is %.4f — adjusted automatically",
                    requestedQty, m.getSaxoSymbol(), effectiveQty);
        }
        return null;
    }

    private SymbolMapping resolve(String tickerOrSymbol) {
        SymbolMapping m = symbolConfig.getSymbols().get(tickerOrSymbol.toUpperCase());
        if (m != null) return m;
        m = symbolConfig.getSymbols().get(tickerOrSymbol);
        if (m != null) return m;
        Optional<SymbolMapping> found = symbolConfig.getSymbols().values().stream()
                .filter(s -> s.getSaxoSymbol().equalsIgnoreCase(tickerOrSymbol))
                .findFirst();
        return found.orElseThrow(() ->
                new IllegalArgumentException("No Saxo mapping for '" + tickerOrSymbol + "'"));
    }
}
