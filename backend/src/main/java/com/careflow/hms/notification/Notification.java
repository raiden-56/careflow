package com.careflow.hms.notification;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "notifications")
public class Notification extends TenantEntity {

    public enum Priority { LOW, NORMAL, HIGH, CRITICAL }

    @Column(nullable = false)
    private UUID recipientUserId;

    /** e.g. ASSIGNMENT, MEDICATION_DUE, MEDICATION_OVERDUE, OBSERVATION_ALERT, LAB_RESULT, REVIEW_REQUEST, BED, BILLING, PAYMENT, DISCHARGE, FOLLOW_UP, HANDOVER, SYSTEM */
    @Column(nullable = false)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.NORMAL;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String message;

    /** Frontend route to the related record, e.g. /nurse/patients/{admissionId}. */
    private String link;

    private String entityType;
    private UUID entityId;

    /** Prevents duplicate notifications for the same event and recipient. */
    private String dedupeKey;

    @Column(nullable = false)
    private boolean requiresAck;

    private Instant readAt;
    private Instant acknowledgedAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
