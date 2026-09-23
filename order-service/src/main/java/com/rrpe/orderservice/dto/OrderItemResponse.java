package com.rrpe.orderservice.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        String sku,
        int quantity,
        BigDecimal unitPrice
) {}
