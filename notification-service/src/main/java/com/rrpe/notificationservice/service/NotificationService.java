package com.rrpe.notificationservice.service;

import com.rrpe.notificationservice.dto.NotificationRequest;
import com.rrpe.notificationservice.dto.NotificationResponse;
import com.rrpe.notificationservice.entity.Notification;
import com.rrpe.notificationservice.repository.NotificationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationLog notificationLog;

    public NotificationService(NotificationLog notificationLog) {
        this.notificationLog = notificationLog;
    }

    // Simulates dispatch: logs the notification and stores it, rather
    // than integrating a real email/SMS provider, since delivery
    // itself is not what this proof-of-concept is testing.
    public NotificationResponse send(NotificationRequest request) {
        Notification notification = new Notification(
                request.orderId(), request.recipient(), request.channel(), request.message());
        notificationLog.save(notification);

        log.info("Notification sent | order={} channel={} recipient={} message=\"{}\"",
                request.orderId(), request.channel(), request.recipient(), request.message());

        return toResponse(notification);
    }

    public List<NotificationResponse> listAll() {
        return notificationLog.findAll().stream().map(this::toResponse).toList();
    }

    public List<NotificationResponse> listForOrder(String orderId) {
        return notificationLog.findByOrderId(orderId).stream().map(this::toResponse).toList();
    }

    private NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(
                n.getId(), n.getOrderId(), n.getRecipient(), n.getChannel(), n.getMessage(), n.getSentAt());
    }
}
