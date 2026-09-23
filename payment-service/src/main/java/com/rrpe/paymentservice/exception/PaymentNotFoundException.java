package com.rrpe.paymentservice.exception;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(Long paymentId) {
        super("No payment found with id " + paymentId);
    }
}
