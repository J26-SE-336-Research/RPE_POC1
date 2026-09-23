package com.rrpe.orderservice.dto;

import java.time.Instant;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        Long orderId
) {
    public static ErrorResponse of(int status, String error, String message, Long orderId) {
        return new ErrorResponse(Instant.now(), status, error, message, orderId);
    }
}
