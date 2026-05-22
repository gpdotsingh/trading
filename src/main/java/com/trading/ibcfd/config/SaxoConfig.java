package com.trading.ibcfd.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class SaxoConfig {

    @Value("${saxo.token}")
    private String token;

    @Value("${saxo.base-url}")
    private String baseUrl;

    @Value("${saxo.account-key}")
    private String accountKey;

    public String getToken()      { return token; }
    public String getBaseUrl()    { return baseUrl; }
    public String getAccountKey() { return accountKey; }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
