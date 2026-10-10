package com.rrpe.orderservice.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    // No explicit connect or read timeout is configured here. Request
    // deadline and cancellation behavior is handled by the proxy layer.
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .additionalInterceptors((request, body, execution) -> {
                    String requestId = RequestPropagationContext.currentRequestId();
                    if (requestId != null) request.getHeaders().set("Request_id", requestId);
                    return execution.execute(request, body);
                })
                .build();
    }
}
