package com.rrpe.notificationservice.entity;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

// Held in memory only (see repository) rather than persisted, since
// a delivery log does not need to survive a container restart for
// this proof-of-concept. A real deployment would swap this for a
// durable store or a message queue without changing the API.
public class Notification {

    private static final AtomicLong SEQUENCE = new AtomicLong(1);

    private final Long id;
    private final String orderId;
    private final String recipient;
    private final String channel;
    private final String message;
    private final Instant sentAt;

    public Notification(String orderId, String recipient, String channel, String message) {
        this.id = SEQUENCE.getAndIncrement();
        this.orderId = orderId;
        this.recipient = recipient;
        this.channel = channel;
        this.message = message;
        this.sentAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getRecipient() { return recipient; }
    public String getChannel() { return channel; }
    public String getMessage() { return message; }
    public Instant getSentAt() { return sentAt; }
}
