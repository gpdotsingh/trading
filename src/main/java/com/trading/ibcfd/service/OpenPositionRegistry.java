package com.trading.ibcfd.service;

import com.trading.ibcfd.model.OpenPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store for open positions monitored by DynamicStopLossService.
 * Broadcasts the full snapshot to /topic/positions on every mutation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenPositionRegistry {

    private static final String TOPIC = "/topic/positions";

    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, OpenPosition> positions = new ConcurrentHashMap<>();

    public void put(OpenPosition pos) {
        positions.put(pos.getPositionId(), pos);
        broadcast();
    }

    public OpenPosition get(String positionId) {
        return positions.get(positionId);
    }

    public List<OpenPosition> getOpen() {
        return positions.values().stream()
                .filter(p -> "OPEN".equals(p.getStatus()))
                .toList();
    }

    public List<OpenPosition> getAll() {
        return new ArrayList<>(positions.values());
    }

    public void broadcast() {
        messagingTemplate.convertAndSend(TOPIC, new ArrayList<>(positions.values()));
    }
}
