package com.rrpe.orderservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class NotificationClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public NotificationClient(
            RestTemplate restTemplate,
            @Value("${clients.notification-service.base-url}") String baseUrl
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public record NotificationRequest(String orderId, String recipient, String channel, String message) {}

    public void sendOrderConfirmation(String orderId, String customerId) {
        NotificationRequest request = new NotificationRequest(
                orderId,
                customerId,
                "EMAIL",
                "Your order " + orderId + " has been confirmed."
        );
        restTemplate.postForObject(baseUrl + "/notifications/send", request, Object.class);
    }
}
