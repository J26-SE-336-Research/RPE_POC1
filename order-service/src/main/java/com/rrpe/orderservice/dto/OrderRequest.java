package com.rrpe.orderservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record OrderRequest(
        @NotBlank String customerId,
        @NotEmpty @Valid List<OrderItemRequest> items,
        // Lets a caller deterministically force the payment step to
        // fail, so the compensating stock-release path can be
        // demonstrated on demand rather than waiting on the random
        // simulated failure rate in payment-service.
        boolean forcePaymentFail
) {}
