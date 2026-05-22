package com.trading.ibcfd.service;

import org.springframework.stereotype.Component;

/**
 * Pure maths for dynamic stop-loss/take-profit calculations.
 * Stateless — safe to share across threads.
 */
@Component
public class StopLossCalculator {

    static final double STOP_LOSS_PCT   = 2.0;
    static final double TAKE_PROFIT_PCT = 4.0;
    static final double TRAIL_RATIO     = 0.60;

    public double stopLossPrice(String action, double entry) {
        return "BUY".equals(action)
                ? entry * (1 - STOP_LOSS_PCT / 100.0)
                : entry * (1 + STOP_LOSS_PCT / 100.0);
    }

    public double takeProfitPrice(String action, double entry) {
        return "BUY".equals(action)
                ? entry * (1 + TAKE_PROFIT_PCT / 100.0)
                : entry * (1 - TAKE_PROFIT_PCT / 100.0);
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

    /** Only update SL if it moves in the profitable direction — never backward. */
    public boolean shouldUpdateStopLoss(String action, double newSl, double currentSl) {
        return "BUY".equals(action) ? newSl > currentSl : newSl < currentSl;
    }
}
