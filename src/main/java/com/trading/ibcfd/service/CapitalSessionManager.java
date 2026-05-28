package com.trading.ibcfd.service;

import com.trading.ibcfd.config.CapitalComConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the Capital.com session lifecycle (CST + X-SECURITY-TOKEN).
 * Sessions expire after ~10 minutes; we proactively refresh after 9 minutes.
 * A 429 rate-limit on the auth endpoint is handled with a 10-second cooldown.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapitalSessionManager {

    /** Capital.com demo sessions last ~10 minutes; refresh proactively after 9. */
    private static final long SESSION_TTL_MS   = 9 * 60 * 1000L;
    /** Minimum gap between createSession() calls to avoid hammering the auth endpoint. */
    private static final long AUTH_COOLDOWN_MS = 10_000L;

    private final CapitalComConfig config;
    private final RestTemplate     restTemplate;

    private final AtomicReference<String> cst           = new AtomicReference<>();
    private final AtomicReference<String> securityToken = new AtomicReference<>();
    private final AtomicLong sessionCreatedAt  = new AtomicLong(0);
    private final AtomicLong lastAuthAttemptAt = new AtomicLong(0);

    public synchronized void createSession() {
        long now = System.currentTimeMillis();
        long sinceLastAttempt = now - lastAuthAttemptAt.get();
        if (sinceLastAttempt < AUTH_COOLDOWN_MS) {
            log.warn("Auth cooldown active — skipping createSession() ({}ms since last attempt)", sinceLastAttempt);
            return;
        }
        lastAuthAttemptAt.set(now);

        Map<String, String> body = new HashMap<>();
        body.put("identifier", config.getIdentifier());
        body.put("password",   config.getPassword());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-CAP-API-KEY", config.getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = exchange("/api/v1/session", HttpMethod.POST, headers, body);
        cst.set(response.getHeaders().getFirst("CST"));
        securityToken.set(response.getHeaders().getFirst("X-SECURITY-TOKEN"));
        sessionCreatedAt.set(System.currentTimeMillis());
        log.info("Capital.com session created (demo={})", config.isDemo());
    }

    public String getCst()           { return cst.get(); }
    public String getSecurityToken() { return securityToken.get(); }

    public void ensureSession() {
        boolean missing  = cst.get() == null || securityToken.get() == null;
        boolean expired  = (System.currentTimeMillis() - sessionCreatedAt.get()) > SESSION_TTL_MS;
        if (missing || expired) createSession();
    }

    public Map<String, Object> get(String path) {
        try {
            return exchange(path, HttpMethod.GET, authHeaders(), null).getBody();
        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("Capital.com session expired (GET) — refreshing");
            invalidateSession();
            createSession();
            return exchange(path, HttpMethod.GET, authHeaders(), null).getBody();
        }
    }

    public Map<String, Object> post(String path, Object body) {
        try {
            return exchange(path, HttpMethod.POST, authHeaders(), body).getBody();
        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("Capital.com session expired (POST) — refreshing");
            invalidateSession();
            createSession();
            return exchange(path, HttpMethod.POST, authHeaders(), body).getBody();
        }
    }

    public void delete(String path) {
        restTemplate.exchange(config.getBaseUrl() + path, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), Void.class);
    }

    public void put(String path, Object body) {
        restTemplate.exchange(config.getBaseUrl() + path, HttpMethod.PUT,
                new HttpEntity<>(body, authHeaders()), Void.class);
    }

    private void invalidateSession() {
        cst.set(null);
        securityToken.set(null);
        sessionCreatedAt.set(0);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("CST",              cst.get());
        h.set("X-SECURITY-TOKEN", securityToken.get());
        h.set("X-CAP-API-KEY",    config.getApiKey());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> exchange(
            String path, HttpMethod method, HttpHeaders headers, Object body) {
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) restTemplate.exchange(
                config.getBaseUrl() + path, method,
                body != null ? new HttpEntity<>(body, headers) : new HttpEntity<>(headers),
                Map.class);
    }
}
