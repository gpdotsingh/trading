package com.trading.ibcfd.service;

import com.trading.ibcfd.config.CapitalComConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the Capital.com session lifecycle (CST + X-SECURITY-TOKEN)
 * and provides the authenticated HTTP helpers used by CapitalComService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapitalSessionManager {

    private final CapitalComConfig config;
    private final RestTemplate     restTemplate;

    private final AtomicReference<String> cst           = new AtomicReference<>();
    private final AtomicReference<String> securityToken = new AtomicReference<>();

    public void createSession() {
        Map<String, String> body = new HashMap<>();
        body.put("identifier", config.getIdentifier());
        body.put("password",   config.getPassword());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-CAP-API-KEY", config.getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = exchange("/api/v1/session", HttpMethod.POST, headers, body);
        cst.set(response.getHeaders().getFirst("CST"));
        securityToken.set(response.getHeaders().getFirst("X-SECURITY-TOKEN"));
        log.info("Capital.com session created (demo={})", config.isDemo());
    }

    public void ensureSession() {
        if (cst.get() == null || securityToken.get() == null) createSession();
    }

    public Map<String, Object> get(String path) {
        return exchange(path, HttpMethod.GET, authHeaders(), null).getBody();
    }

    public Map<String, Object> post(String path, Object body) {
        try {
            return exchange(path, HttpMethod.POST, authHeaders(), body).getBody();
        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("Capital.com session expired — refreshing");
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
