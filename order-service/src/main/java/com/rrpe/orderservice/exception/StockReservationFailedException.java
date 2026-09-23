package com.rrpe.orderservice.exception;

public class StockReservationFailedException extends RuntimeException {
    private final Long orderId;

    public StockReservationFailedException(Long orderId, String message) {
        super(message);
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}
