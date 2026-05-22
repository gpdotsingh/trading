package com.trading.ibcfd.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Risk management settings loaded from application.properties under trading.risk.*
 *
 * Example:
 *   trading.risk.enabled=true
 *   trading.risk.stop-loss-pct=2.0
 *   trading.risk.take-profit-pct=4.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "trading.risk")
public class TradingRiskConfig {

    /** Master switch — set false to disable automatic stop loss on all orders */
    private boolean enabled = true;

    /** Stop loss distance as % of entry price. e.g. 2.0 = 2% */
    private double stopLossPct = 2.0;

    /** Take profit distance as % of entry price. e.g. 4.0 = 4% */
    private double takeProfitPct = 4.0;
}
