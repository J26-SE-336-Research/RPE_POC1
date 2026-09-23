package com.rrpe.notificationservice.dto;

import jakarta.validation.constraints.NotBlank;

public record NotificationRequest(
        @NotBlank String orderId,
        @NotBlank String recipient,
        @NotBlank String channel,
        @NotBlank String message
) {}
