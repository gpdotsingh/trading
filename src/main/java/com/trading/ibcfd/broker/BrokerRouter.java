package com.trading.ibcfd.broker;

import com.trading.ibcfd.model.TradeJournalEntry;
import com.trading.ibcfd.service.DynamicStopLossService;
import com.trading.ibcfd.service.TradeJournal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerRouter {

    private final List<BrokerGateway>   gateways;
    private final TradeJournal          tradeJournal;
    private final DynamicStopLossService stopLossService;

    @Value("${trading.broker:saxo}")
    private String tradingBroker;

    public List<RouteResult> route(String tickerOrSymbol, String action,
                                   String source, double alertPrice, String alertName) {
        return routeWith(tickerOrSymbol, action, source, alertPrice, alertName, tradingBroker, 0);
    }

    public List<RouteResult> routeWith(String tickerOrSymbol, String action,
                                       String source, double alertPrice, String alertName,
                                       String brokerOverride) {
        return routeWith(tickerOrSymbol, action, source, alertPrice, alertName, brokerOverride, 0);
    }

    public List<RouteResult> routeWith(String tickerOrSymbol, String action,
                                       String source, double alertPrice, String alertName,
                                       String brokerOverride, double requestedQty) {
        String effective = (brokerOverride == null || brokerOverride.isBlank()
                || brokerOverride.equalsIgnoreCase("auto"))
                ? tradingBroker : brokerOverride;
        return gateways.stream()
                .filter(gw -> gw.isActiveFor(effective))
                .map(gw -> execute(gw, tickerOrSymbol, action, source, alertPrice, alertName, requestedQty))
                .toList();
    }

    private RouteResult execute(BrokerGateway gw, String ticker, String action,
                                String source, double alertPrice, String alertName, double requestedQty) {
        try {
            PlaceResult placed = gw.place(ticker, action, requestedQty);
            record(gw.brokerName(), ticker, placed, action, source, alertPrice, alertName, "PLACED", null);

            // Register with trailing stop service — use entryPrice from gateway; fall back to alertPrice
            double entry = placed.entryPrice() > 0 ? placed.entryPrice() : alertPrice;
            if (entry > 0) {
                String capitalDealId = "CAPITAL".equals(gw.brokerName()) ? placed.orderId() : null;
                stopLossService.register(ticker, placed.saxoSymbol(), placed.assetType(),
                        action, placed.quantity(), entry, placed.orderId(),
                        gw.brokerName(), capitalDealId);
            }

            return new RouteResult(gw.brokerName(), placed.orderId(), placed.saxoSymbol(),
                    placed.assetType(), placed.quantity(), "PLACED", null, placed.note(), placed.entryPrice());
        } catch (Exception e) {
            log.error("{} order FAILED for {} {}: {}", gw.brokerName(), action, ticker, e.getMessage());
            record(gw.brokerName(), ticker, null, action, source, alertPrice, alertName, "FAILED", e.getMessage());
            return new RouteResult(gw.brokerName(), null, null, null, 0, "FAILED", e.getMessage());
        }
    }

    private void record(String broker, String ticker, PlaceResult placed,
                        String action, String source, double alertPrice,
                        String alertName, String status, String failReason) {
        tradeJournal.record(TradeJournalEntry.builder()
                .timestamp(Instant.now())
                .source(source).broker(broker).ticker(ticker)
                .saxoSymbol(placed != null ? placed.saxoSymbol() : null)
                .assetType(placed != null ? placed.assetType()  : null)
                .action(action)
                .quantity(placed != null ? placed.quantity() : 0)
                .alertPrice(alertPrice).alertName(alertName)
                .orderId(placed != null ? placed.orderId() : null)
                .status(status).failReason(failReason)
                .build());
    }
}
