package com.rrpe.notificationservice.repository;

import com.rrpe.notificationservice.entity.Notification;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// A minimal in-memory store. CopyOnWriteArrayList is fine here: writes
// are infrequent (one per notification sent) and reads (listing, or
// filtering by order) are far more frequent, which is exactly the
// access pattern that structure is suited for.
@Repository
public class NotificationLog {

    private final List<Notification> notifications = new CopyOnWriteArrayList<>();

    public Notification save(Notification notification) {
        notifications.add(notification);
        return notification;
    }

    public List<Notification> findAll() {
        return List.copyOf(notifications);
    }

    public List<Notification> findByOrderId(String orderId) {
        return notifications.stream()
                .filter(n -> n.getOrderId().equals(orderId))
                .toList();
    }
}
