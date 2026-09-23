package com.rrpe.paymentservice.dto;

import com.rrpe.paymentservice.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        Long paymentId,
        String orderId,
        BigDecimal amount,
        PaymentStatus status,
        String failureReason,
        Instant createdAt
) {}
