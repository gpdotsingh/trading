package com.trading.ibcfd.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.ibcfd.config.CapitalComConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Connects to Capital.com streaming WebSocket and broadcasts live bid/ask prices
 * to the frontend via STOMP /topic/prices.
 *
 * Auth: cst + securityToken are sent inside each message (not as HTTP headers).
 * Ping: sent every 9 minutes to keep the 10-minute session alive.
 * URL:  wss://api-streaming-capital.backend-capital.com/connect (demo + live)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapitalPriceStreamingService {

    private static final int RECONNECT_DELAY_S = 10;
    private static final int PING_INTERVAL_MIN = 9;

    private final CapitalComConfig      capitalConfig;
    private final CapitalSessionManager capitalSession;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${capital.streaming-url:wss://api-streaming-capital.backend-capital.com/connect}")
    private String streamingUrl;

    private final ObjectMapper             mapper    = new ObjectMapper();
    private final AtomicBoolean            connected = new AtomicBoolean(false);
    private final AtomicBoolean            stopped   = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private volatile WebSocket webSocket;

    @PostConstruct
    public void start() {
        if (!capitalConfig.isEnabled()) {
            log.info("Capital.com disabled — price streaming not started");
            return;
        }
        Set<String> epics = allEpics();
        if (epics.isEmpty()) {
            log.warn("No Capital.com epics configured — price streaming not started");
            return;
        }
        log.info("Starting Capital.com price streaming for {} epics: {}", epics.size(), epics);
        connect(epics);

        // Ping every 9 min to keep the 10-min session alive
        scheduler.scheduleAtFixedRate(this::ping, PING_INTERVAL_MIN, PING_INTERVAL_MIN, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stop() {
        stopped.set(true);
        scheduler.shutdownNow();
        if (webSocket != null) webSocket.abort();
        log.info("Capital.com price streaming stopped");
    }

    public boolean isConnected() { return connected.get(); }

    public void reconnect() {
        if (stopped.get()) return;
        log.info("Triggering manual reconnect to Capital.com streaming");
        connect(allEpics());
    }

    // ── connection ────────────────────────────────────────────────────────────

    private void connect(Set<String> epics) {
        try {
            capitalSession.ensureSession();
            webSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(streamingUrl), new Listener(epics))
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to connect to Capital.com streaming: {}", e.getMessage());
            scheduleReconnect(epics);
        }
    }

    private void scheduleReconnect(Set<String> epics) {
        if (stopped.get()) return;
        log.info("Reconnecting in {}s...", RECONNECT_DELAY_S);
        scheduler.schedule(() -> connect(epics), RECONNECT_DELAY_S, TimeUnit.SECONDS);
    }

    // ── messaging ─────────────────────────────────────────────────────────────

    private void send(WebSocket ws, Map<String, Object> message) {
        try {
            ws.sendText(mapper.writeValueAsString(message), true);
        } catch (Exception e) {
            log.error("Failed to send WebSocket message: {}", e.getMessage());
        }
    }

    private void subscribe(WebSocket ws, Set<String> epics) {
        send(ws, Map.of(
                "destination",   "marketData.subscribe",
                "correlationId", "sub-1",
                "cst",           capitalSession.getCst(),
                "securityToken", capitalSession.getSecurityToken(),
                "payload",       Map.of("epics", List.copyOf(epics))
        ));
        log.info("Capital.com streaming subscribed to: {}", epics);
    }

    private void ping() {
        if (!connected.get() || webSocket == null) return;
        send(webSocket, Map.of(
                "destination",   "ping",
                "correlationId", "ping-" + System.currentTimeMillis(),
                "cst",           capitalSession.getCst() != null ? capitalSession.getCst() : "",
                "securityToken", capitalSession.getSecurityToken() != null ? capitalSession.getSecurityToken() : ""
        ));
        log.debug("Capital.com streaming ping sent");
    }

    // ── message handling ──────────────────────────────────────────────────────

    private void handleMessage(String text) {
        try {
            Map<String, Object> msg = mapper.readValue(text, new TypeReference<>() {});
            String destination = (String) msg.get("destination");
            if (destination == null) return;

            if ("quote".equals(destination)) {
                if (msg.get("payload") instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) msg.get("payload");
                    broadcastQuote(payload);
                }
            } else if ("marketData.subscribe".equals(destination)) {
                log.info("Capital.com streaming subscription confirmed: {}", msg.get("status"));
            } else if ("ping".equals(destination)) {
                log.debug("Capital.com ping acknowledged");
            } else {
                log.debug("Capital.com streaming msg: {}", destination);
            }
        } catch (Exception e) {
            log.debug("Could not parse streaming message: {}", e.getMessage());
        }
    }

    private void broadcastQuote(Map<String, Object> payload) {
        String epic = (String) payload.get("epic");
        if (epic == null) return;
        double bid = toDouble(payload.get("bid"));
        double ask = toDouble(payload.get("ofr"));  // Capital.com uses "ofr" for ask in streaming
        double mid = bid > 0 && ask > 0 ? (bid + ask) / 2.0 : (bid > 0 ? bid : ask);
        if (bid == 0 && ask == 0) return;

        messagingTemplate.convertAndSend("/topic/prices", Map.of(
                "symbol",    epic,
                "bid",       bid,
                "ask",       ask,
                "mid",       mid,
                "timestamp", Instant.now().toString()
        ));
        log.debug("Broadcast {} bid={} ask={}", epic, bid, ask);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Set<String> allEpics() {
        return capitalConfig.getSymbols().values().stream()
                .map(CapitalComConfig.SymbolMapping::getEpic)
                .collect(Collectors.toSet());
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return 0.0; }
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private class Listener implements WebSocket.Listener {

        private final Set<String>   epics;
        private final StringBuilder buffer = new StringBuilder();

        Listener(Set<String> epics) { this.epics = epics; }

        @Override
        public void onOpen(WebSocket ws) {
            log.info("Capital.com streaming WebSocket opened");
            connected.set(true);
            subscribe(ws, epics);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handleMessage(buffer.toString());
                buffer.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.warn("Capital.com streaming closed: {} — {}", statusCode, reason);
            connected.set(false);
            scheduleReconnect(epics);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("Capital.com streaming error: {}", error.getMessage());
            connected.set(false);
            scheduleReconnect(epics);
        }
    }
}
