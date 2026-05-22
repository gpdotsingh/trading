package com.trading.ibcfd.service;

import com.trading.ibcfd.config.TradingRiskConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Pure maths for stop-loss / take-profit calculations.
 * Percentages are read from TradingRiskConfig (application.properties).
 * Stateless — safe to share across threads.
 */
@Component
@RequiredArgsConstructor
public class StopLossCalculator {

    private final TradingRiskConfig riskConfig;

    static final double TRAIL_RATIO = 0.60;

    public double stopLossPrice(String action, double entry) {
        double pct = riskConfig.getStopLossPct();
        return "BUY".equals(action)
                ? entry * (1 - pct / 100.0)
                : entry * (1 + pct / 100.0);
    }

    public double takeProfitPrice(String action, double entry) {
        double pct = riskConfig.getTakeProfitPct();
        return "BUY".equals(action)
                ? entry * (1 + pct / 100.0)
                : entry * (1 - pct / 100.0);
    }

    public double trailingStopPrice(String action, double entry, double lockedPct) {
        return "BUY".equals(action)
                ? entry * (1 + lockedPct / 100.0)
                : entry * (1 - lockedPct / 100.0);
    }

    public double marginPct(String action, double entry, double current) {
        return "BUY".equals(action)
                ? (current - entry) / entry * 100.0
                : (entry - current) / entry * 100.0;
    }

    public boolean takeProfitHit(String action, double current, double tp) {
        return "BUY".equals(action) ? current >= tp : current <= tp;
    }

    public boolean stopLossHit(String action, double current, double sl) {
        return "BUY".equals(action) ? current <= sl : current >= sl;
    }

    public boolean shouldUpdateStopLoss(String action, double newSl, double currentSl) {
        return "BUY".equals(action) ? newSl > currentSl : newSl < currentSl;
    }
}
