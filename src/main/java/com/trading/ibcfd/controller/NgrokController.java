package com.trading.ibcfd.controller;

import com.trading.ibcfd.service.NgrokService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ngrok")
@RequiredArgsConstructor
public class NgrokController {

    private final NgrokService ngrokService;

    @Value("${trendspider.webhook.secret:}")
    private String webhookSecret;

    @GetMapping("/url")
    public ResponseEntity<Map<String, String>> getNgrokUrl() {
        String url = ngrokService.getPublicUrl();
        if (url == null) url = "";

        String webhookUrl = url.isBlank() ? "" :
                url + "/api/webhook/trendspider" +
                (webhookSecret.isBlank() ? "" : "?secret=" + webhookSecret);

        return ResponseEntity.ok(Map.of(
                "url",        url,
                "webhookUrl", webhookUrl,
                "active",     String.valueOf(ngrokService.isActive())
        ));
    }
}
