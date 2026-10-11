package com.careflow.hms.discharge;

import com.careflow.hms.common.TenantEntity;
import com.careflow.hms.notification.NotificationDelivery;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One attempt to tell the patient about a follow-up on one channel (append-only log). */
@Getter
@Setter
@Entity
@Table(name = "follow_up_deliveries")
public class FollowUpDelivery extends TenantEntity {

    public enum Event { SCHEDULED, RESCHEDULED, REMINDER, CANCELLED, RESENT }

    @Column(nullable = false)
    private UUID followUpId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationDelivery.Channel channel;

    @Column(nullable = false)
    private String provider;

    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationDelivery.Status status;

    private String errorMessage;

    private UUID notificationId;

    @Column(nullable = false)
    private String dedupeKey;

    @Column(nullable = false)
    private Instant attemptedAt = Instant.now();
}
