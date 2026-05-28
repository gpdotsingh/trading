package com.trading.ibcfd.controller;

import com.trading.ibcfd.service.CapitalPriceStreamingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/stream")
@RequiredArgsConstructor
@Tag(name = "Streaming", description = "Manage Capital.com WebSocket price streaming")
public class StreamController {

    private final CapitalPriceStreamingService streamingService;

    @PostMapping("/reconnect")
    @Operation(summary = "Reconnect streaming", description = "Force a reconnect to Capital.com streaming WebSocket")
    public ResponseEntity<Map<String, Object>> reconnect() {
        streamingService.reconnect();
        return ResponseEntity.ok(Map.of(
                "message",    "Reconnect triggered",
                "wsEndpoint", "/ws",
                "topic",      "/topic/prices"
        ));
    }

    @GetMapping("/status")
    @Operation(summary = "Streaming status", description = "Returns whether the Capital.com WebSocket is connected")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("connected", streamingService.isConnected()));
    }
}
