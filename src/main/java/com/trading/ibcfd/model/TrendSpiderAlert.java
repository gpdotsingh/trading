package com.trading.ibcfd.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Payload sent by TrendSpider when an alert fires.
 *
 * Configure your TrendSpider alert webhook body as:
 * {
 *   "ticker":    "{{ticker}}",
 *   "price":     {{price}},
 *   "action":    "BUY",
 *   "alertName": "{{alert_name}}",
 *   "interval":  "{{interval}}",
 *   "message":   "{{message}}",
 *   "timestamp": "{{time}}"
 * }
 *
 * Any extra fields TrendSpider sends are captured in `extras`.
 */
@Data
@NoArgsConstructor
public class TrendSpiderAlert {

    private String ticker;      // symbol, e.g. "GER40" or "XAUUSD"
    private Double price;       // price at alert time
    private String action;      // "BUY", "SELL", or custom label
    private String alertName;   // name of the alert in TrendSpider
    private String interval;    // chart timeframe, e.g. "1h", "4h", "1d"
    private String message;     // human-readable description
    private String timestamp;   // ISO-8601 or TrendSpider's format

    // ── Indicator values sent from TrendSpider chart ──────────────────────────
    private Double mfi;         // Money Flow Index — add {{MFI(14)}} in TrendSpider alert body
    private Double rsi;         // RSI — add {{RSI(14)}} in TrendSpider alert body
    private Double volume;      // Volume — add {{volume}} in TrendSpider alert body

    // absorbs any unrecognised fields without throwing
    private final Map<String, Object> extras = new HashMap<>();

    @JsonAnySetter
    public void setExtra(String key, Object value) {
        extras.put(key, value);
    }
}
