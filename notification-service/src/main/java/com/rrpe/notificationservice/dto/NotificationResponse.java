package com.rrpe.notificationservice.dto;

import java.time.Instant;

public record NotificationResponse(
        Long id,
        String orderId,
        String recipient,
        String channel,
        String message,
        Instant sentAt
) {}
