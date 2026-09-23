package com.rrpe.paymentservice.service;

import com.rrpe.paymentservice.dto.PaymentRequest;
import com.rrpe.paymentservice.dto.PaymentResponse;
import com.rrpe.paymentservice.entity.Payment;
import com.rrpe.paymentservice.entity.PaymentStatus;
import com.rrpe.paymentservice.exception.PaymentNotFoundException;
import com.rrpe.paymentservice.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final double simulatedFailureRate;

    public PaymentService(
            PaymentRepository paymentRepository,
            @Value("${payment.simulated-failure-rate:0.15}") double simulatedFailureRate
    ) {
        this.paymentRepository = paymentRepository;
        this.simulatedFailureRate = simulatedFailureRate;
    }

    // Processes a payment attempt. Every attempt is recorded, whether
    // it is approved or declined, so the payment history for an order
    // is a complete audit trail rather than only the successful case.
    @Transactional
    public PaymentResponse process(PaymentRequest request) {
        String failureReason = decideOutcome(request);
        PaymentStatus status = (failureReason == null) ? PaymentStatus.APPROVED : PaymentStatus.DECLINED;

        Payment payment = new Payment(request.orderId(), request.amount(), status, failureReason);
        payment = paymentRepository.save(payment);

        return toResponse(payment);
    }

    @Transactional
    public PaymentResponse refund(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        payment.setStatus(PaymentStatus.REFUNDED);
        payment = paymentRepository.save(payment);
        return toResponse(payment);
    }

    public PaymentResponse getPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return toResponse(payment);
    }

    public List<PaymentResponse> getPaymentsForOrder(String orderId) {
        return paymentRepository.findByOrderId(orderId).stream()
                .map(this::toResponse)
                .toList();
    }

    // Returns null for an approved payment, or a human-readable
    // decline reason otherwise. A deterministic forceFail flag is
    // checked before the random simulated failure rate, so callers
    // testing retry/cancellation logic get predictable outcomes.
    private String decideOutcome(PaymentRequest request) {
        if (request.forceFail()) {
            return "Forced failure requested by caller (test scenario)";
        }
        if (ThreadLocalRandom.current().nextDouble() < simulatedFailureRate) {
            return "Simulated gateway decline";
        }
        return null;
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(
                p.getId(), p.getOrderId(), p.getAmount(), p.getStatus(),
                p.getFailureReason(), p.getCreatedAt());
    }
}
