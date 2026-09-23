package com.rrpe.orderservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

@Component
public class PaymentClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public PaymentClient(
            RestTemplate restTemplate,
            @Value("${clients.payment-service.base-url}") String baseUrl
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public record PaymentRequest(String orderId, BigDecimal amount, boolean forceFail) {}

    public record PaymentResponse(Long paymentId, String orderId, BigDecimal amount,
                                   String status, String failureReason) {}

    public PaymentResponse process(String orderId, BigDecimal amount, boolean forceFail) {
        try {
            return restTemplate.postForObject(
                    baseUrl + "/payments/process",
                    new PaymentRequest(orderId, amount, forceFail),
                    PaymentResponse.class);
        } catch (HttpClientErrorException ex) {
            // payment-service never actually returns 4xx for a normal
            // decline (a decline is a 200 with status=DECLINED) — this
            // branch exists for genuine client errors, e.g. bad request.
            throw ex;
        }
    }
}
