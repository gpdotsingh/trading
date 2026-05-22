package com.trading.ibcfd.service;

import com.github.alexdlaird.ngrok.NgrokClient;
import com.github.alexdlaird.ngrok.conf.JavaNgrokConfig;
import com.github.alexdlaird.ngrok.protocol.CreateTunnel;
import com.github.alexdlaird.ngrok.protocol.Tunnel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class NgrokService {

    @Value("${ngrok.enabled:true}")
    private boolean enabled;

    @Value("${ngrok.auth-token:}")
    private String authToken;

    @Value("${ngrok.domain:}")
    private String staticDomain;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${trendspider.webhook.secret:}")
    private String webhookSecret;

    private NgrokClient ngrokClient;
    private String publicUrl;

    @EventListener(ApplicationReadyEvent.class)
    public void startTunnel() {
        if (!enabled) {
            log.info("ngrok disabled — set ngrok.enabled=true to activate");
            return;
        }

        try {
            JavaNgrokConfig.Builder configBuilder = new JavaNgrokConfig.Builder();
            if (authToken != null && !authToken.isBlank()) {
                configBuilder.withAuthToken(authToken);
            }

            ngrokClient = new NgrokClient.Builder()
                    .withJavaNgrokConfig(configBuilder.build())
                    .build();

            CreateTunnel.Builder tunnelBuilder = new CreateTunnel.Builder()
                    .withAddr(serverPort);

            // use static domain if configured — URL will never change across restarts
            if (staticDomain != null && !staticDomain.isBlank()) {
                tunnelBuilder.withDomain(staticDomain);
            }

            Tunnel tunnel = ngrokClient.connect(tunnelBuilder.build());

            publicUrl = tunnel.getPublicUrl();

            String webhookUrl = publicUrl + "/api/webhook/trendspider"
                    + (webhookSecret.isBlank() ? "" : "?secret=" + webhookSecret);

            log.info("═══════════════════════════════════════════════════════════");
            log.info("  ngrok tunnel active");
            log.info("  Public URL  : {}", publicUrl);
            log.info("  Signal Hub  : {}/signal-hub.html", publicUrl);
            log.info("  Webhook URL : {}", webhookUrl);
            log.info("  (Paste the webhook URL into TrendSpider alert settings)");
            log.info("═══════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.warn("ngrok failed to start — running locally only: {}", e.getMessage());
            log.warn("Set ngrok.auth-token in application.properties if you have an ngrok account");
        }
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public boolean isActive() {
        return publicUrl != null;
    }

    @PreDestroy
    public void stopTunnel() {
        if (ngrokClient != null) {
            try {
                ngrokClient.kill();
                log.info("ngrok tunnel closed");
            } catch (Exception ignored) {}
        }
    }
}
