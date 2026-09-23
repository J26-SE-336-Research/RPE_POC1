package com.rrpe.inventoryservice.dto;

public record StockReservationResponse(
        String sku,
        int reservedQuantity,
        int remainingAvailable
) {}
