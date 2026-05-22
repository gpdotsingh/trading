package com.trading.ibcfd.service;

import com.trading.ibcfd.config.CapitalComConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Capital.com CFD trading operations.
 * Session lifecycle is delegated to CapitalSessionManager.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapitalComService {

    private final CapitalComConfig      config;
    private final CapitalSessionManager session;

    public void createSession() { session.createSession(); }

    public String openPosition(String ticker, String direction) {
        session.ensureSession();
        CapitalComConfig.SymbolMapping mapping = resolveMapping(ticker);
        if (mapping == null) throw new IllegalArgumentException(
                "No Capital.com epic mapped for ticker '" + ticker
                + "' — add capital.symbols." + ticker + ".epic in application.properties");

        Map<String, Object> body = Map.of(
                "epic",           mapping.getEpic(),
                "direction",      direction.toUpperCase(),
                "size",           mapping.getSize(),
                "guaranteedStop", false);

        log.info("Capital.com OPEN {} {} (epic={} size={})",
                direction, ticker, mapping.getEpic(), mapping.getSize());
        Map<String, Object> response = session.post("/api/v1/positions", body);
        String dealRef = response != null ? str(response.get("dealReference")) : "";
        log.info("Capital.com position opened: dealReference={}", dealRef);
        return dealRef;
    }

    public String openPositionWithSize(String ticker, String direction, double size) {
        return openPositionWithSize(ticker, direction, size, 0, 0);
    }

    public String openPositionWithSize(String ticker, String direction, double size,
                                       double stopDistance, double limitDistance) {
        session.ensureSession();
        CapitalComConfig.SymbolMapping mapping = resolveMapping(ticker);
        if (mapping == null) throw new IllegalArgumentException(
                "No Capital.com epic mapped for ticker '" + ticker + "'");

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("epic",           mapping.getEpic());
        body.put("direction",      direction.toUpperCase());
        body.put("size",           size);
        body.put("guaranteedStop", false);
        if (stopDistance  > 0) body.put("stopDistance",  stopDistance);
        if (limitDistance > 0) body.put("limitDistance", limitDistance);

        log.info("Capital.com OPEN {} {} (epic={} size={} SL_dist={} TP_dist={})",
                direction, ticker, mapping.getEpic(), size, stopDistance, limitDistance);
        Map<String, Object> response = session.post("/api/v1/positions", body);
        String dealRef = response != null ? str(response.get("dealReference")) : "";
        log.info("Capital.com position opened: dealReference={}", dealRef);
        return dealRef;
    }

    /** Returns current bid/offer for an epic — used for stop loss calculation. */
    @SuppressWarnings("unchecked")
    public double getCurrentMidPrice(String epic) {
        session.ensureSession();
        Map<String, Object> resp = session.get("/api/v1/markets/" + epic);
        if (resp == null) return 0;
        Object snapshot = resp.get("snapshot");
        if (snapshot instanceof Map<?, ?>) {
            Map<String, Object> snap = (Map<String, Object>) snapshot;
            Object bid   = snap.get("bid");
            Object offer = snap.get("offer");
            if (bid instanceof Number b && offer instanceof Number o)
                return (b.doubleValue() + o.doubleValue()) / 2.0;
        }
        return 0;
    }

    public String openPositionWithRisk(String ticker, String direction,
                                       double stopDistance, double limitDistance) {
        session.ensureSession();
        CapitalComConfig.SymbolMapping mapping = resolveMapping(ticker);
        if (mapping == null) throw new IllegalArgumentException("No Capital.com epic for ticker: " + ticker);

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("epic",           mapping.getEpic());
        body.put("direction",      direction.toUpperCase());
        body.put("size",           mapping.getSize());
        body.put("guaranteedStop", false);
        if (stopDistance  > 0) body.put("stopDistance",  stopDistance);
        if (limitDistance > 0) body.put("limitDistance", limitDistance);

        log.info("Capital.com OPEN {} {} epic={} SL_dist={} TP_dist={}",
                direction, ticker, mapping.getEpic(), stopDistance, limitDistance);
        Map<String, Object> response = session.post("/api/v1/positions", body);
        String dealRef = response != null ? str(response.get("dealReference")) : "";
        log.info("Capital.com position opened with risk: dealReference={}", dealRef);
        return dealRef;
    }

    public void closePosition(String dealId) {
        session.ensureSession();
        log.info("Capital.com CLOSE position dealId={}", dealId);
        session.delete("/api/v1/positions/" + dealId);
        log.info("Capital.com position closed: {}", dealId);
    }

    public void updatePosition(String dealId, double stopLevel, double limitLevel) {
        session.ensureSession();
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        if (stopLevel  > 0) body.put("stopLevel",  stopLevel);
        if (limitLevel > 0) body.put("limitLevel", limitLevel);
        if (body.isEmpty()) return;
        log.info("Capital.com UPDATE position {} SL={} TP={}", dealId, stopLevel, limitLevel);
        session.put("/api/v1/positions/" + dealId, body);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getOpenPositions() {
        session.ensureSession();
        Map<String, Object> body = session.get("/api/v1/positions");
        if (body == null) return List.of();
        Object positions = body.get("positions");
        return positions instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    public Map<String, Object> getAccountDetails() {
        session.ensureSession();
        Map<String, Object> body = session.get("/api/v1/accounts");
        return body != null ? body : Map.of();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getActivityHistory(int lastPeriod) {
        session.ensureSession();
        Map<String, Object> body = session.get("/api/v1/history/activity?lastPeriod=" + lastPeriod);
        if (body == null) return List.of();
        Object activities = body.get("activities");
        return activities instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getWorkingOrders() {
        session.ensureSession();
        Map<String, Object> body = session.get("/api/v1/workingorders");
        if (body == null) return List.of();
        Object orders = body.get("workingOrders");
        return orders instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchInstruments(String keyword) {
        session.ensureSession();
        Map<String, Object> body = session.get("/api/v1/markets?searchTerm=" + keyword);
        if (body == null) return List.of();
        Object markets = body.get("markets");
        return markets instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private CapitalComConfig.SymbolMapping resolveMapping(String ticker) {
        if (ticker == null) return null;
        CapitalComConfig.SymbolMapping m = config.getSymbols().get(ticker.toUpperCase());
        if (m == null) m = config.getSymbols().get(ticker);
        return m;
    }

    private String str(Object val) { return val == null ? "" : val.toString(); }
}
