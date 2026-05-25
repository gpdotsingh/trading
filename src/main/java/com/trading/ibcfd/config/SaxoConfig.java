package com.trading.ibcfd.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class SaxoConfig {

    @Value("${saxo.token}")
    private String token;

    @Value("${saxo.base-url}")
    private String baseUrl;

    @Value("${saxo.account-key}")
    private String accountKey;

    @Value("${saxo.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${saxo.read-timeout-ms:10000}")
    private int readTimeoutMs;

    public String getToken()      { return token; }
    public String getBaseUrl()    { return baseUrl; }
    public String getAccountKey() { return accountKey; }

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("Authorization", "Bearer " + token);
            request.getHeaders().set("Content-Type", "application/json");
            return execution.execute(request, body);
        });
        return restTemplate;
    }
}
