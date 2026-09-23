package com.rrpe.inventoryservice.dto;

import java.math.BigDecimal;

public record ProductResponse(
        Long id,
        String sku,
        String name,
        BigDecimal price,
        int stockQuantity,
        int reservedQuantity,
        int availableQuantity
) {}
