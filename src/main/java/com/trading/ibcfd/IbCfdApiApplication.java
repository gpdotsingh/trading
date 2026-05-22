package com.trading.ibcfd;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IbCfdApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(IbCfdApiApplication.class, args);
    }
}
