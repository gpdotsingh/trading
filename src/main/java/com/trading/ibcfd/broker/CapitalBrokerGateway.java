package com.trading.ibcfd.broker;

import com.trading.ibcfd.config.CapitalComConfig;
import com.trading.ibcfd.config.CapitalComConfig.SymbolMapping;
import com.trading.ibcfd.config.TradingRiskConfig;
import com.trading.ibcfd.service.CapitalComService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CapitalBrokerGateway implements BrokerGateway {

    private final CapitalComService capitalComService;
    private final CapitalComConfig  capitalComConfig;
    private final TradingRiskConfig riskConfig;

    @Override
    public String brokerName() { return "CAPITAL"; }

    @Override
    public boolean isActiveFor(String tradingBroker) {
        return capitalComConfig.isEnabled()
                && (tradingBroker.equalsIgnoreCase("capital")
                    || tradingBroker.equalsIgnoreCase("both"));
    }

    @Override
    public PlaceResult place(String tickerOrSymbol, String action, double requestedQty) throws Exception {
        SymbolMapping m   = resolve(tickerOrSymbol);
        double qty        = requestedQty > 0 ? requestedQty : m.getSize();
        double stopDist   = 0;
        double limitDist  = 0;

        double entryMid = 0;
        if (riskConfig.isEnabled()) {
            entryMid = capitalComService.getCurrentMidPrice(m.getEpic());
            if (entryMid > 0) {
                stopDist  = entryMid * riskConfig.getStopLossPct()   / 100.0;
                limitDist = entryMid * riskConfig.getTakeProfitPct() / 100.0;
                log.info("Capital risk: mid={} SL_dist={} TP_dist={} ({}% / {}%)",
                        entryMid, stopDist, limitDist,
                        riskConfig.getStopLossPct(), riskConfig.getTakeProfitPct());
            }
        }

        String dealRef = capitalComService.openPositionWithSize(tickerOrSymbol, action, qty, stopDist, limitDist);
        log.info("Capital.com order placed: {} {} qty={} dealRef={}", action, tickerOrSymbol, qty, dealRef);
        return new PlaceResult(dealRef, m.getEpic(), "CFD", qty, null, entryMid);
    }

    private SymbolMapping resolve(String tickerOrSymbol) {
        SymbolMapping m = capitalComConfig.getSymbols().get(tickerOrSymbol);
        if (m == null) m = capitalComConfig.getSymbols().get(tickerOrSymbol.toUpperCase());
        if (m == null) m = capitalComConfig.getSymbols().get(tickerOrSymbol.toUpperCase().replace(" ", "_"));
        if (m == null) throw new IllegalArgumentException(
                "No Capital.com mapping for '" + tickerOrSymbol + "'");
        return m;
    }
}
