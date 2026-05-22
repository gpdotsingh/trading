package com.trading.ibcfd.controller;

import com.trading.ibcfd.model.MarketPrice;
import com.trading.ibcfd.service.MarketDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
@Tag(name = "Market Data", description = "Get live CFD bid/ask prices from Saxo Bank simulation")
public class MarketDataController {

    private final MarketDataService marketDataService;

    /**
     * GET /api/market/price/{symbol}?assetType=CfdOnStock
     *
     * Examples:
     *   GET /api/market/price/AAPL
     *   GET /api/market/price/SPX500?assetType=CfdOnIndex
     */
    @GetMapping("/price/{symbol}")
    @Operation(summary = "Get live price", description = "Returns bid, ask and mid for a single CFD instrument")
    public ResponseEntity<MarketPrice> getPrice(
            @Parameter(description = "Symbol e.g. AAPL, SPX500, EURUSD") @PathVariable String symbol,
            @Parameter(description = "CfdOnStock | CfdOnIndex | CfdOnEtf | FxSpot")
            @RequestParam(defaultValue = "CfdOnStock") String assetType) {
        return ResponseEntity.ok(marketDataService.getMarketPrice(symbol, assetType));
    }

    /**
     * GET /api/market/prices?symbols=AAPL,TSLA,MSFT&assetType=CfdOnStock
     *
     * Used by the live dashboard to fetch all prices in a single call.
     * UICs are cached after the first call — subsequent calls are fast.
     */
    @GetMapping("/prices")
    @Operation(
        summary = "Get bulk prices",
        description = "Fetches live prices for multiple symbols in one call. Used by the dashboard."
    )
    public ResponseEntity<Map<String, MarketPrice>> getBulkPrices(
            @Parameter(description = "Comma-separated symbols, e.g. AAPL,TSLA,MSFT")
            @RequestParam String symbols,
            @Parameter(description = "CfdOnStock | CfdOnIndex | CfdOnEtf | FxSpot")
            @RequestParam(defaultValue = "CfdOnStock") String assetType) {
        return ResponseEntity.ok(marketDataService.getBulkPrices(symbols, assetType));
    }
}
