package com.rrpe.paymentservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotBlank String orderId,
        @DecimalMin(value = "0.01") BigDecimal amount,
        // Lets a caller deterministically force a decline for testing
        // retry and cancellation behaviour, instead of relying only on
        // the random simulated failure rate.
        boolean forceFail
) {}
