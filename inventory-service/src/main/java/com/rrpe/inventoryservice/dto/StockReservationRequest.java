package com.rrpe.inventoryservice.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record StockReservationRequest(
        @NotBlank String sku,
        @Min(1) int quantity
) {}
