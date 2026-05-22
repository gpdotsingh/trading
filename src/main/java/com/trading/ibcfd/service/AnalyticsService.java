package com.trading.ibcfd.service;

import com.trading.ibcfd.broker.BrokerRouter;
import com.trading.ibcfd.model.AnalyticsEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final SimpMessagingTemplate messagingTemplate;
    private final BrokerRouter          brokerRouter;

    @Value("${analytics.auto-trade-enabled:false}")
    private boolean autoTradeEnabled;

    private static final int MAX_HISTORY = 500;

    private final ArrayDeque<AnalyticsEvent> history = new ArrayDeque<>();
    private volatile AnalyticsEvent latestSummary = null;

    public synchronized void recordEvent(AnalyticsEvent event) {
        if (event == null) return;
        if (history.size() >= MAX_HISTORY) history.pollFirst();
        history.addLast(event);
        latestSummary = event;
        messagingTemplate.convertAndSend("/topic/analytics", (Object) event);
        log.debug("Analytics: type={} symbol={} action={} cumPnl={}",
                event.getType(), event.getSymbol(), event.getAction(), event.getCumPnl());

        if (autoTradeEnabled && "TRADE".equals(event.getType())
                && ("BUY".equals(event.getAction()) || "SELL".equals(event.getAction()))) {
            log.info("Analytics auto-trade triggered: {} {} @ {}",
                    event.getAction(), event.getSymbol(), event.getPrice());
            brokerRouter.route(event.getSymbol(), event.getAction(),
                    "ANALYTICS", event.getPrice(), "Python Lévy signal");
        }
    }

    public synchronized List<AnalyticsEvent> getHistory(int limit) {
        List<AnalyticsEvent> all = new ArrayList<>(history);
        int start = Math.max(0, all.size() - limit);
        return all.subList(start, all.size());
    }

    public AnalyticsEvent getLatestSummary() { return latestSummary; }
}
