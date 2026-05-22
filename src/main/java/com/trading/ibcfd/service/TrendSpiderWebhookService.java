package com.trading.ibcfd.service;

import com.trading.ibcfd.broker.BrokerRouter;
import com.trading.ibcfd.broker.RouteResult;
import com.trading.ibcfd.config.TrendSpiderSymbolConfig;
import com.trading.ibcfd.model.TrendSpiderAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrendSpiderWebhookService {

    private static final int    MAX_HISTORY = 200;
    private static final String TOPIC       = "/topic/trendspider";

    private final SimpMessagingTemplate   messagingTemplate;
    private final TrendSpiderSymbolConfig symbolConfig;
    private final DynamicStopLossService  stopLossService;
    private final BrokerRouter            brokerRouter;

    @Value("${trendspider.webhook.secret:}")
    private String webhookSecret;

    private final Deque<TrendSpiderAlert> history = new ArrayDeque<>();

    public boolean process(TrendSpiderAlert alert, String providedSecret) {
        if (!webhookSecret.isBlank() && !webhookSecret.equals(providedSecret)) {
            log.warn("TrendSpider webhook rejected — invalid secret");
            return false;
        }
        log.info("TrendSpider alert: ticker={} action={} price={} alert='{}'",
                alert.getTicker(), alert.getAction(), alert.getPrice(), alert.getAlertName());
        store(alert);
        messagingTemplate.convertAndSend(TOPIC, alert);
        if (symbolConfig.isAutoTradeEnabled()) executeOrder(alert);
        else log.info("Auto-trade disabled — signal logged only.");
        return true;
    }

    public List<TrendSpiderAlert> getHistory(int limit) {
        synchronized (history) {
            List<TrendSpiderAlert> list = new ArrayList<>(history);
            int from = Math.max(0, list.size() - limit);
            return list.subList(from, list.size());
        }
    }

    private void executeOrder(TrendSpiderAlert alert) {
        String action = alert.getAction();
        if (action == null || (!action.equalsIgnoreCase("BUY") && !action.equalsIgnoreCase("SELL"))) {
            log.info("Signal action '{}' is not BUY/SELL — skipping", action);
            return;
        }
        String ticker = alert.getTicker();
        if (ticker == null || ticker.isBlank()) {
            log.warn("TrendSpider alert has no ticker — cannot place order");
            return;
        }
        double alertPrice = alert.getPrice() != null ? alert.getPrice() : 0;
        List<RouteResult> results = brokerRouter.route(
                ticker, action.toUpperCase(), "TRENDSPIDER", alertPrice, alert.getAlertName());

        results.stream()
                .filter(r -> "SAXO".equals(r.broker()) && r.placed())
                .findFirst()
                .ifPresent(r -> stopLossService.register(
                        ticker, r.saxoSymbol(), r.assetType(),
                        action.toUpperCase(), r.quantity(), alertPrice, r.orderId()));
    }

    private void store(TrendSpiderAlert alert) {
        synchronized (history) {
            if (history.size() >= MAX_HISTORY) history.pollFirst();
            history.addLast(alert);
        }
    }
}
