package com.rrpe.paymentservice.dto;

import jakarta.validation.constraints.NotNull;

public record RefundRequest(
        @NotNull Long paymentId
) {}
