package com.rrpe.paymentservice.exception;

// Thrown, not just returned as a DECLINED status, when the caller
// needs to treat a decline as an exceptional outcome (for example,
// to trigger a compensating stock release in the calling service).
public class PaymentDeclinedException extends RuntimeException {
    public PaymentDeclinedException(String reason) {
        super("Payment declined: " + reason);
    }
}
