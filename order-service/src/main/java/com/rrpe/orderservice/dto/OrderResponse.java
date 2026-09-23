package com.rrpe.orderservice.dto;

import com.rrpe.orderservice.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        String failureReason,
        Long paymentId,
        List<OrderItemResponse> items,
        Instant createdAt
) {}
