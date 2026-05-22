package com.trading.ibcfd.controller;

import com.trading.ibcfd.model.InstrumentDetails;
import com.trading.ibcfd.service.InstrumentCatalogService;
import com.trading.ibcfd.service.InstrumentLookup;
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

@RestController
@RequestMapping("/api/instruments")
@RequiredArgsConstructor
@Tag(name = "Instruments", description = "Saxo instrument details including MinimumTradeSize from /ref/v1/instruments/details")
public class InstrumentController {

    private final InstrumentLookup        instrumentService;
    private final InstrumentCatalogService catalogService;

    /**
     * GET /api/instruments/browse?keywords=oil&assetTypes=CfdOnFutures&top=20&skip=0
     *
     * Full Saxo instrument catalog search with all available filter parameters.
     * Supports pagination via $skip / $top.
     */
    @GetMapping("/browse")
    @Operation(
        summary = "Browse instruments",
        description = "Full Saxo /ref/v1/instruments catalog search with all filter parameters. " +
                      "Supports pagination. Use this to discover any instrument available on the sim."
    )
    public ResponseEntity<Map<String, Object>> browse(
            @Parameter(description = "Offset for pagination")
            @RequestParam(required = false) Integer skip,
            @Parameter(description = "Max results (default 20)")
            @RequestParam(defaultValue = "20") int top,
            @Parameter(description = "CfdOnIndex | FxSpot | CfdOnFutures | CfdOnStock | CfdOnEtf | CfdOnCommodity")
            @RequestParam(required = false) String assetTypes,
            @Parameter(description = "Free-text keyword search")
            @RequestParam(required = false) String keywords,
            @Parameter(description = "Comma-separated UICs to look up directly")
            @RequestParam(required = false) String uics,
            @Parameter(description = "Exchange ID filter, e.g. XLON, XNAS")
            @RequestParam(required = false) String exchangeId,
            @Parameter(description = "Include non-tradable instruments")
            @RequestParam(required = false) Boolean includeNonTradable,
            @Parameter(description = "Tag filter")
            @RequestParam(required = false) String tags,
            @Parameter(description = "Instrument class filter")
            @RequestParam(required = false, name = "class") String clazz,
            @Parameter(description = "Filter instruments that can participate in multi-leg orders")
            @RequestParam(required = false) Boolean canParticipateInMultiLegOrder) {

        return ResponseEntity.ok(catalogService.browse(
                skip, top, assetTypes, keywords, uics,
                exchangeId, includeNonTradable, tags, clazz, canParticipateInMultiLegOrder));
    }

    /**
     * GET /api/instruments/search?keyword=oil&assetType=CfdOnFutures&top=10
     *
     * Returns Symbol + Description + UIC for every Saxo match.
     * Use this to discover the EXACT keyword to put in application.properties presets.
     *
     * Examples:
     *   GET /api/instruments/search?keyword=oil&assetType=CfdOnFutures
     *   GET /api/instruments/search?keyword=gold&assetType=FxSpot
     *   GET /api/instruments/search?keyword=germany&assetType=CfdOnIndex
     */
    @GetMapping("/search")
    @Operation(
        summary = "Search instruments",
        description = "Keyword search against Saxo /ref/v1/instruments. " +
                      "Use this to find the exact Symbol or Description to put in the preset config."
    )
    public ResponseEntity<List<Map<String, Object>>> search(
            @Parameter(description = "Free-text keyword, e.g. 'oil', 'gold', 'germany'")
            @RequestParam String keyword,
            @Parameter(description = "CfdOnIndex | FxSpot | CfdOnFutures | CfdOnStock | CfdOnEtf")
            @RequestParam(defaultValue = "CfdOnStock") String assetType,
            @Parameter(description = "Max results (default 10)")
            @RequestParam(defaultValue = "10") int top) {

        return ResponseEntity.ok(catalogService.search(keyword, assetType, top));
    }

    /**
     * GET /api/instruments/details?symbol=Germany+40&assetType=CfdOnIndex
     *
     * Returns full instrument details including MinimumTradeSize for one symbol.
     */
    @GetMapping("/details")
    @Operation(
        summary = "Get instrument details",
        description = "Fetches MinimumTradeSize, LotSize, currency and description from Saxo /ref/v1/instruments/details"
    )
    public ResponseEntity<InstrumentDetails> getDetails(
            @Parameter(description = "Symbol or keyword, e.g. 'Germany 40', 'XAUUSD', 'AAPL'")
            @RequestParam String symbol,
            @Parameter(description = "CfdOnIndex | FxSpot | CfdOnFutures | CfdOnStock | CfdOnEtf")
            @RequestParam(defaultValue = "CfdOnStock") String assetType) {

        return ResponseEntity.ok(instrumentService.getDetails(symbol, assetType));
    }

    /**
     * GET /api/instruments/details/bulk?symbols=Germany+40,Netherlands+25&assetType=CfdOnIndex
     *
     * Returns MinimumTradeSize for multiple symbols in one call.
     * Used by the Python analytics layer on startup so it can set
     * quantity_per_trade automatically from the Saxo API instead of properties.
     */
    @GetMapping("/details/bulk")
    @Operation(
        summary = "Get bulk instrument details",
        description = "Returns MinimumTradeSize for multiple symbols. Used by analytics/main.py on startup."
    )
    public ResponseEntity<Map<String, InstrumentDetails>> getBulkDetails(
            @Parameter(description = "Comma-separated symbols")
            @RequestParam String symbols,
            @Parameter(description = "CfdOnIndex | FxSpot | CfdOnFutures | CfdOnStock | CfdOnEtf")
            @RequestParam(defaultValue = "CfdOnStock") String assetType) {

        List<String> symbolList = Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        Map<String, InstrumentDetails> result = new LinkedHashMap<>();
        for (String symbol : symbolList) {
            try {
                result.put(symbol, instrumentService.getDetails(symbol, assetType));
            } catch (Exception ex) {
                // Return partial results — don't fail the whole batch for one bad symbol
                result.put(symbol, buildErrorDetails(symbol, assetType, ex.getMessage()));
            }
        }
        return ResponseEntity.ok(result);
    }

    // ── helper ───────────────────────────────────────────────────────────────────

    private InstrumentDetails buildErrorDetails(String symbol, String assetType, String error) {
        return InstrumentDetails.builder()
                .symbol(symbol)
                .assetType(assetType)
                .uic(-1)
                .description("ERROR: " + error)
                .currencyCode("N/A")
                .minimumTradeSize(-1)
                .lotSize(-1)
                .maximumTradeSize(-1)
                .build();
    }
}
