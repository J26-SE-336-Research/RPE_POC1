package com.rrpe.orderservice.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    // Fixed, generous timeouts for now. This is intentionally naive —
    // a real deployment of this proof-of-concept replaces a flat
    // per-call timeout with a remaining-deadline budget passed hop to
    // hop, which is exactly the gap an interceptor layer is meant to
    // fill without order-service's own code needing to change.
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }
}
