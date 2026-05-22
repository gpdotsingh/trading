package com.trading.ibcfd.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Saxo Bank CFD Trading API")
                        .description("REST API for CFD trading via Saxo Bank simulation — supports stocks, indices, ETFs and forex")
                        .version("1.0.0")
                        .contact(new Contact().name("Trading API")));
    }
}
