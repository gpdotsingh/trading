package com.trading.ibcfd.service;

import com.trading.ibcfd.config.CapitalComConfig;
import com.trading.ibcfd.config.SaxoConfig;
import com.trading.ibcfd.config.TrendSpiderSymbolConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches live market status (open/closed) and trading hours for an instrument
 * from both Saxo and Capital.com.
 *
 * Saxo:  GET /trade/v1/infoprices  → Quote.MarketState
 *        GET /ref/v1/instruments/details → TradingConditions (hours via ExchangeId)
 * Cap:   GET /api/v1/markets/{epic} → marketStatus + openingHours
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketStatusService {

    private final SaxoConfig               saxoConfig;
    private final RestTemplate             restTemplate;
    private final InstrumentLookup         instrumentLookup;
    private final TrendSpiderSymbolConfig  symbolConfig;
    private final CapitalComConfig         capitalComConfig;
    private final CapitalSessionManager    capitalSession;

    public Map<String, Object> getStatus(String ticker) {
        String t = ticker.toUpperCase();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticker", t);

        TrendSpiderSymbolConfig.SymbolMapping m = symbolConfig.getSymbols().get(t);
        if (m != null) {
            result.put("saxo", fetchSaxoStatus(m.getSaxoSymbol(), m.getAssetType()));
        }

        if (capitalComConfig.isEnabled()) {
            CapitalComConfig.SymbolMapping cm = capitalComConfig.getSymbols().get(t);
            if (cm != null) result.put("capital", fetchCapitalStatus(cm.getEpic()));
        }

        return result;
    }

    /** Status for every ticker in the symbol config. */
    public Map<String, Object> getAllStatuses() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String ticker : symbolConfig.getSymbols().keySet()) {
            try { out.put(ticker, getStatus(ticker)); }
            catch (Exception e) { out.put(ticker, Map.of("error", e.getMessage())); }
        }
        return out;
    }

    // ── Saxo ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchSaxoStatus(String symbol, String assetType) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            int uic = instrumentLookup.findUic(symbol, assetType);

            // 1. Live market state via info prices
            String priceUrl = saxoConfig.getBaseUrl()
                    + "/trade/v1/infoprices/?AssetType=" + assetType
                    + "&Uic=" + uic + "&FieldGroups=Quote";
            Map<String, Object> priceResp = restTemplate.getForObject(priceUrl, Map.class);
            if (priceResp != null && priceResp.get("Quote") instanceof Map<?, ?> quoteRaw) {
                Map<String, Object> quote = (Map<String, Object>) quoteRaw;
                String state = String.valueOf(quote.getOrDefault("MarketState", "Unknown"));
                out.put("marketState", state);
                out.put("tradeable", "Open".equalsIgnoreCase(state));
            }

            // 2. Trading conditions (hours + exchange)
            String detailUrl = saxoConfig.getBaseUrl()
                    + "/ref/v1/instruments/details?Uics=" + uic
                    + "&AssetTypes=" + assetType + "&FieldGroups=TradingConditions";
            Map<String, Object> detailResp = restTemplate.getForObject(detailUrl, Map.class);
            if (detailResp != null && detailResp.get("Data") instanceof List<?> data && !data.isEmpty()) {
                Map<String, Object> item = (Map<String, Object>) data.get(0);
                out.put("exchangeId",   item.getOrDefault("ExchangeId",   "—"));
                out.put("currencyCode", item.getOrDefault("CurrencyCode", "—"));
                out.put("description",  item.getOrDefault("Description",  symbol));
                if (item.get("TradingConditions") instanceof Map<?, ?> tc) {
                    extractTradingSessions(out, (Map<String, Object>) tc);
                }
            }
        } catch (Exception e) {
            log.debug("Saxo status fetch failed for {}: {}", symbol, e.getMessage());
            out.put("error", e.getMessage());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void extractTradingSessions(Map<String, Object> out, Map<String, Object> tc) {
        if (tc.get("TradingSessions") instanceof List<?> sessions) {
            out.put("tradingSessions", sessions);
        }
        if (tc.containsKey("MinimumTradeSize")) out.put("minimumTradeSize", tc.get("MinimumTradeSize"));
    }

    // ── Capital.com ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchCapitalStatus(String epic) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            capitalSession.ensureSession();
            Map<String, Object> resp = capitalSession.get("/api/v1/markets/" + epic);
            if (resp == null) { out.put("error", "No response"); return out; }

            Object instrument = resp.get("instrument");
            Object snapshot   = resp.get("snapshot");
            Object hours      = resp.get("dealingRules");

            if (instrument instanceof Map<?, ?>) {
                Map<String, Object> ins = (Map<String, Object>) instrument;
                out.put("name",       ins.getOrDefault("name",       epic));
                out.put("expiry",     ins.getOrDefault("expiry",     "—"));
                out.put("lotSize",    ins.getOrDefault("lotSize",    "—"));
                out.put("currencies", ins.getOrDefault("currencies", List.of()));
            }
            if (snapshot instanceof Map<?, ?>) {
                Map<String, Object> snap = (Map<String, Object>) snapshot;
                String status = String.valueOf(snap.getOrDefault("marketStatus", "UNKNOWN"));
                out.put("marketStatus", status);
                out.put("tradeable",    "TRADEABLE".equalsIgnoreCase(status));
                out.put("bid",          snap.get("bid"));
                out.put("offer",        snap.get("offer"));
                out.put("updateTime",   snap.get("updateTime"));
            }
            if (hours instanceof Map<?, ?>) {
                out.put("dealingRules", hours);
            }

            // openingHours is a separate top-level field
            if (resp.get("openingHours") instanceof Map<?, ?> oh) {
                out.put("openingHours", buildOpeningHours((Map<String, Object>) oh));
            }
        } catch (Exception e) {
            log.debug("Capital status fetch failed for epic {}: {}", epic, e.getMessage());
            out.put("error", e.getMessage());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildOpeningHours(Map<String, Object> oh) {
        Map<String, Object> hours = new LinkedHashMap<>();
        String[] days = {"mon","tue","wed","thu","fri","sat","sun"};
        for (String day : days) {
            if (oh.get(day) instanceof List<?> sessions) hours.put(day, sessions);
        }
        if (oh.get("zone") != null) hours.put("zone", oh.get("zone"));
        return hours;
    }
}
