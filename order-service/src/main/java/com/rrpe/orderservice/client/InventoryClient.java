package com.rrpe.orderservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

// Thin wrapper around calls to inventory-service. Kept deliberately
// simple — no retry, circuit breaker, or per-call timeout policy —
// because request deadline and cancellation behavior is handled by
// the proxy layer rather than order-service client code.
@Component
public class InventoryClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public InventoryClient(
            RestTemplate restTemplate,
            @Value("${clients.inventory-service.base-url}") String baseUrl
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public record ProductInfo(String sku, String name, BigDecimal price, int availableQuantity) {}

    public record StockRequest(String sku, int quantity) {}

    public record StockResponse(String sku, int reservedQuantity, int remainingAvailable) {}

    public ProductInfo getProduct(String sku) {
        return restTemplate.getForObject(baseUrl + "/inventory/products/" + sku, ProductInfo.class);
    }

    public StockResponse reserve(String sku, int quantity) {
        return restTemplate.postForObject(
                baseUrl + "/inventory/reserve", new StockRequest(sku, quantity), StockResponse.class);
    }

    public StockResponse release(String sku, int quantity) {
        return restTemplate.postForObject(
                baseUrl + "/inventory/release", new StockRequest(sku, quantity), StockResponse.class);
    }

    public StockResponse confirm(String sku, int quantity) {
        return restTemplate.postForObject(
                baseUrl + "/inventory/confirm", new StockRequest(sku, quantity), StockResponse.class);
    }
}
