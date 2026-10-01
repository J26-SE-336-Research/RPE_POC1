package com.rrpe.notificationservice.controller;

import com.rrpe.notificationservice.dto.NotificationRequest;
import com.rrpe.notificationservice.dto.NotificationResponse;
import com.rrpe.notificationservice.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@RestController
@RequestMapping("/notifications")
public class NotificationController {
    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/send")
    public ResponseEntity<NotificationResponse> send(
            @RequestHeader(name = "cancellation_Triggered", defaultValue = "false") boolean cancellationTriggered,
            @RequestHeader(name = "Request_id", required = false) String requestId,
            @Valid @RequestBody NotificationRequest request) {
        if (cancellationTriggered) {
            log.warn("notification-service skipped optional notification for Request_id={} due to cancellation",
                    requestId);
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(notificationService.send(request));
    }

    @GetMapping
    public List<NotificationResponse> listAll() {
        return notificationService.listAll();
    }

    @GetMapping("/order/{orderId}")
    public List<NotificationResponse> listForOrder(@PathVariable String orderId) {
        return notificationService.listForOrder(orderId);
    }
}
