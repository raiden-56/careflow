package com.careflow.hms.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Delivery attempt of a notification on one channel. */
@Getter
@Setter
@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    public enum Channel { IN_APP, EMAIL, SMS, PUSH }

    public enum Status { PENDING, SENT, DELIVERED, FAILED, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID notificationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(nullable = false)
    private String provider;

    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private Instant attemptedAt = Instant.now();

    private String errorMessage;
}
