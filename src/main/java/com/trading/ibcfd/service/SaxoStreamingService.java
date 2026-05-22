package com.trading.ibcfd.service;

import com.trading.ibcfd.config.SaxoConfig;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Manages the persistent WebSocket connection to Saxo streaming.
 * Frame parsing is handled by SaxoFrameParser.
 * Subscription management and price broadcasting by SaxoPriceSubscriptionService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SaxoStreamingService {

    private final SaxoConfig                    config;
    private final InstrumentLookup              instrumentLookup;
    private final SaxoFrameParser               frameParser;
    private final SaxoPriceSubscriptionService  subscriptionService;

    private WebSocket saxoWebSocket;
    private String    contextId;
    private volatile boolean connected = false;

    public synchronized void subscribe(List<String> symbols, String assetType) {
        List<Integer> uics = resolveUics(symbols, assetType);
        if (uics.isEmpty()) throw new IllegalArgumentException("No valid symbols resolved");
        if (!connected) connectToSaxo();
        subscriptionService.createSubscription(uics, assetType, contextId);
        log.info("Streaming active for {} symbols", uics.size());
    }

    public synchronized void subscribeByUics(Map<Integer, String> uicNames, String assetType) {
        if (uicNames.isEmpty()) throw new IllegalArgumentException("No UICs provided");
        subscriptionService.addUicMappings(uicNames);
        if (!connected) connectToSaxo();
        subscriptionService.createSubscription(new ArrayList<>(uicNames.keySet()), assetType, contextId);
        log.info("Direct UIC streaming started for {} instruments: {}", uicNames.size(), uicNames.values());
    }

    public boolean isConnected() { return connected; }

    @PreDestroy
    public void disconnect() {
        subscriptionService.deleteAll(contextId);
        if (saxoWebSocket != null) {
            saxoWebSocket.abort();
            log.info("Saxo WebSocket closed");
        }
    }

    private List<Integer> resolveUics(List<String> symbols, String assetType) {
        List<Integer> uics = new ArrayList<>();
        for (String symbol : symbols) {
            try {
                int uic = instrumentLookup.findUic(symbol, assetType);
                subscriptionService.addUicMappings(Map.of(uic, symbol));
                uics.add(uic);
            } catch (Exception e) {
                log.warn("Skipping {} — UIC lookup failed: {}", symbol, e.getMessage());
            }
        }
        return uics;
    }

    private void connectToSaxo() {
        contextId = "Dashboard" + System.currentTimeMillis();
        String wsUrl = "wss://sim-streaming.saxobank.com/sim/oapi/streaming/ws/connect"
                + "?authorization=" + URLEncoder.encode("BEARER " + config.getToken(), StandardCharsets.UTF_8)
                + "&contextId=" + contextId;
        log.info("Connecting to Saxo streaming WebSocket, contextId={}", contextId);
        try {
            saxoWebSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), new SaxoWebSocketListener())
                    .get(10, TimeUnit.SECONDS);
            connected = true;
            log.info("Connected to Saxo streaming");
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to Saxo WebSocket: " + e.getMessage(), e);
        }
    }

    private void handleFrame(byte[] frameBytes) {
        for (SaxoFrameParser.FrameMessage msg : frameParser.parse(frameBytes)) {
            dispatchMessage(msg);
        }
    }

    private void dispatchMessage(SaxoFrameParser.FrameMessage msg) {
        switch (msg.referenceId()) {
            case "_heartbeat"          -> log.debug("Heartbeat {}", msg.messageId());
            case "_resetsubscriptions" -> log.warn("Saxo requested subscription reset");
            case "_disconnect"         -> { log.error("Saxo disconnected the client"); connected = false; }
            default -> {
                if (!subscriptionService.isActive(msg.referenceId())) {
                    log.debug("Unhandled referenceId={}", msg.referenceId());
                    return;
                }
                if (msg.payloadFormat() != 0) return;
                try {
                    subscriptionService.broadcast(frameParser.parseJsonPayload(msg.payload()));
                } catch (Exception e) {
                    log.error("Error parsing price update: {}", e.getMessage());
                }
            }
        }
    }

    private class SaxoWebSocketListener implements WebSocket.Listener {

        private final ByteArrayOutputStream fragmentBuffer = new ByteArrayOutputStream();

        @Override public void onOpen(WebSocket ws) { log.info("Saxo WebSocket opened"); ws.request(1); }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            fragmentBuffer.write(bytes, 0, bytes.length);
            if (last) { handleFrame(fragmentBuffer.toByteArray()); fragmentBuffer.reset(); }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.warn("Saxo WebSocket closed: {} — {}", statusCode, reason);
            connected = false;
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("Saxo WebSocket error: {}", error.getMessage());
            connected = false;
        }
    }
}
