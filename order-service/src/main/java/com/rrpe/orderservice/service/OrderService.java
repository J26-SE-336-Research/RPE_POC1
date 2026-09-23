package com.rrpe.orderservice.service;

import com.rrpe.orderservice.client.InventoryClient;
import com.rrpe.orderservice.client.NotificationClient;
import com.rrpe.orderservice.client.PaymentClient;
import com.rrpe.orderservice.dto.*;
import com.rrpe.orderservice.entity.Order;
import com.rrpe.orderservice.entity.OrderItem;
import com.rrpe.orderservice.entity.OrderStatus;
import com.rrpe.orderservice.exception.OrderNotFoundException;
import com.rrpe.orderservice.exception.PaymentFailedException;
import com.rrpe.orderservice.exception.StockReservationFailedException;
import com.rrpe.orderservice.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final NotificationClient notificationClient;

    public OrderService(
            OrderRepository orderRepository,
            InventoryClient inventoryClient,
            PaymentClient paymentClient,
            NotificationClient notificationClient
    ) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.notificationClient = notificationClient;
    }

    // Orchestrates order creation as a saga: reserve stock for every
    // item, charge payment for the total, then either confirm the
    // reservations (success) or release them (failure). Each step
    // that can fail has a corresponding compensating action for
    // everything that already succeeded before it, so a partial
    // failure never leaves stock silently held against an order that
    // will never complete.
    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        Order order = new Order(request.customerId());
        order = orderRepository.save(order);
        String orderId = order.getId().toString();

        List<ReservedLine> reserved = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (OrderItemRequest itemRequest : request.items()) {
            try {
                InventoryClient.ProductInfo product = inventoryClient.getProduct(itemRequest.sku());
                inventoryClient.reserve(itemRequest.sku(), itemRequest.quantity());

                reserved.add(new ReservedLine(itemRequest.sku(), itemRequest.quantity(), product.price()));
                total = total.add(product.price().multiply(BigDecimal.valueOf(itemRequest.quantity())));

                order.addItem(new OrderItem(itemRequest.sku(), itemRequest.quantity(), product.price()));

            } catch (RuntimeException ex) {
                log.warn("Stock reservation failed for order {} on sku {}: {}",
                        orderId, itemRequest.sku(), ex.getMessage());
                releaseAll(reserved);
                order.setStatus(OrderStatus.FAILED);
                order.setFailureReason("Stock reservation failed: " + ex.getMessage());
                orderRepository.save(order);
                throw new StockReservationFailedException(order.getId(), order.getFailureReason());
            }
        }

        order.setTotalAmount(total);

        PaymentClient.PaymentResponse payment;
        try {
            payment = paymentClient.process(orderId, total, request.forcePaymentFail());
        } catch (RuntimeException ex) {
            log.warn("Payment call failed for order {}: {}", orderId, ex.getMessage());
            releaseAll(reserved);
            order.setStatus(OrderStatus.FAILED);
            order.setFailureReason("Payment request failed: " + ex.getMessage());
            orderRepository.save(order);
            throw new PaymentFailedException(order.getId(), order.getFailureReason());
        }

        if (!"APPROVED".equals(payment.status())) {
            log.warn("Payment declined for order {}: {}", orderId, payment.failureReason());
            releaseAll(reserved);
            order.setStatus(OrderStatus.FAILED);
            order.setFailureReason("Payment declined: " + payment.failureReason());
            orderRepository.save(order);
            throw new PaymentFailedException(order.getId(), order.getFailureReason());
        }

        // Payment approved: convert every reservation into a
        // permanent stock deduction.
        for (ReservedLine line : reserved) {
            inventoryClient.confirm(line.sku(), line.quantity());
        }

        order.setStatus(OrderStatus.CONFIRMED);
        order.setPaymentId(payment.paymentId());
        order = orderRepository.save(order);

        // Best-effort: a failed notification should not undo an
        // already-confirmed, already-paid order. This is the one step
        // in the chain that is deliberately not part of the saga.
        try {
            notificationClient.sendOrderConfirmation(orderId, request.customerId());
        } catch (RuntimeException ex) {
            log.warn("Order {} confirmed, but notification failed: {}", orderId, ex.getMessage());
        }

        return toResponse(order);
    }

    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return toResponse(order);
    }

    public List<OrderResponse> listOrders() {
        return orderRepository.findAll().stream().map(this::toResponse).toList();
    }

    private void releaseAll(List<ReservedLine> reserved) {
        for (ReservedLine line : reserved) {
            try {
                inventoryClient.release(line.sku(), line.quantity());
            } catch (RuntimeException ex) {
                // Best-effort compensation: log and continue releasing
                // the rest rather than letting one failed release stop
                // the others from being attempted.
                log.error("Failed to release reserved stock for sku {}: {}", line.sku(), ex.getMessage());
            }
        }
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(i -> new OrderItemResponse(i.getSku(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        return new OrderResponse(
                order.getId(), order.getCustomerId(), order.getStatus(), order.getTotalAmount(),
                order.getFailureReason(), order.getPaymentId(), items, order.getCreatedAt());
    }

    private record ReservedLine(String sku, int quantity, BigDecimal unitPrice) {}
}
