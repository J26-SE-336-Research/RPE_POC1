package com.rrpe.paymentservice.controller;

import com.rrpe.paymentservice.dto.PaymentRequest;
import com.rrpe.paymentservice.dto.PaymentResponse;
import com.rrpe.paymentservice.dto.RefundRequest;
import com.rrpe.paymentservice.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> process(@Valid @RequestBody PaymentRequest request) {
        return ResponseEntity.ok(paymentService.process(request));
    }

    @PostMapping("/refund")
    public ResponseEntity<PaymentResponse> refund(@Valid @RequestBody RefundRequest request) {
        return ResponseEntity.ok(paymentService.refund(request.paymentId()));
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@PathVariable Long paymentId) {
        return paymentService.getPayment(paymentId);
    }

    @GetMapping("/order/{orderId}")
    public List<PaymentResponse> getPaymentsForOrder(@PathVariable String orderId) {
        return paymentService.getPaymentsForOrder(orderId);
    }
}
