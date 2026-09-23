package com.rrpe.orderservice.exception;

public class PaymentFailedException extends RuntimeException {
    private final Long orderId;

    public PaymentFailedException(Long orderId, String message) {
        super(message);
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}
