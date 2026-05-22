package com.trading.ibcfd.controller;

import com.trading.ibcfd.service.SaxoStreamingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/stream")
@RequiredArgsConstructor
@Tag(name = "Streaming", description = "Manage Saxo WebSocket streaming subscriptions")
public class StreamController {

    private final SaxoStreamingService streamingService;

    /**
     * POST /api/stream/subscribe?symbols=AAPL,TSLA,MSFT&assetType=CfdOnStock
     *
     * Connects to Saxo WebSocket and starts streaming.
     * Once called, prices are pushed to the frontend via STOMP /topic/prices.
     */
    @PostMapping("/subscribe")
    @Operation(
        summary = "Start streaming",
        description = "Connects to Saxo streaming WebSocket and subscribes to live prices. " +
                      "Frontend receives updates via STOMP at /topic/prices."
    )
    public ResponseEntity<Map<String, Object>> subscribe(
            @Parameter(description = "Comma-separated symbols, e.g. AAPL,TSLA,MSFT")
            @RequestParam String symbols,
            @Parameter(description = "CfdOnStock | CfdOnIndex | CfdOnEtf | FxSpot")
            @RequestParam(defaultValue = "CfdOnStock") String assetType) {

        List<String> symbolList = Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        streamingService.subscribe(symbolList, assetType);

        return ResponseEntity.ok(Map.of(
                "symbols",   symbolList,
                "assetType", assetType,
                "wsEndpoint", "/ws",
                "topic",     "/topic/prices",
                "message",   "Streaming started — connect to /ws and subscribe to /topic/prices"
        ));
    }

    /**
     * POST /api/stream/subscribe/direct?uics=51774577&symbolNames=BZN6&assetType=ContractFutures
     *
     * Bypasses keyword lookup — uses the UIC directly.
     * Called by the browser "Stream" button which already knows the UIC.
     */
    @PostMapping("/subscribe/direct")
    @Operation(
        summary = "Stream by UIC",
        description = "Subscribe using known UICs directly, skipping keyword lookup. " +
                      "Use this when you already have the UIC from the instrument browser."
    )
    public ResponseEntity<Map<String, Object>> subscribeDirect(
            @Parameter(description = "Comma-separated UICs, e.g. 51774577,43073980")
            @RequestParam String uics,
            @Parameter(description = "Comma-separated display names, same order as uics")
            @RequestParam String symbolNames,
            @Parameter(description = "Saxo AssetType returned by the browser, e.g. ContractFutures")
            @RequestParam(defaultValue = "CfdOnFutures") String assetType) {

        String[] uicArr  = uics.split(",");
        String[] nameArr = symbolNames.split(",");

        Map<Integer, String> uicNames = new LinkedHashMap<>();
        for (int i = 0; i < uicArr.length; i++) {
            int uic = Integer.parseInt(uicArr[i].trim());
            String name = i < nameArr.length ? nameArr[i].trim() : "UIC:" + uic;
            uicNames.put(uic, name);
        }

        streamingService.subscribeByUics(uicNames, assetType);

        return ResponseEntity.ok(Map.of(
                "symbols",    new java.util.ArrayList<>(uicNames.values()),
                "assetType",  assetType,
                "wsEndpoint", "/ws",
                "topic",      "/topic/prices",
                "message",    "Direct UIC streaming started"
        ));
    }

    /**
     * GET /api/stream/status
     */
    @GetMapping("/status")
    @Operation(summary = "Connection status", description = "Returns whether the Saxo WebSocket is currently connected")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("connected", streamingService.isConnected()));
    }
}
