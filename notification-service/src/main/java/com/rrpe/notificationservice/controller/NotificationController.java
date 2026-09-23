package com.rrpe.notificationservice.controller;

import com.rrpe.notificationservice.dto.NotificationRequest;
import com.rrpe.notificationservice.dto.NotificationResponse;
import com.rrpe.notificationservice.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/send")
    public ResponseEntity<NotificationResponse> send(@Valid @RequestBody NotificationRequest request) {
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
