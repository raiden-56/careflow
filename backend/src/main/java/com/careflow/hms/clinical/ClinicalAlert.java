package com.careflow.hms.clinical;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Threshold alerts and nurse-to-clinician escalations. Persisted so they never depend on a browser popup. */
@Getter
@Setter
@Entity
@Table(name = "clinical_alerts")
public class ClinicalAlert extends TenantEntity {

    public enum AlertType { VITAL_THRESHOLD, ESCALATION, MEDICATION, ALLERGY, LAB_CRITICAL, OTHER }

    public enum Severity { INFO, WARNING, CRITICAL }

    public enum Status { OPEN, ACKNOWLEDGED, RESOLVED }

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertType alertType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String message;

    private String sourceType;
    private UUID sourceId;
    private String parameter;
    private String observedValue;
    private UUID thresholdRuleId;
    private String thresholdText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    private UUID assignedTo;
    private UUID raisedBy;

    @Column(nullable = false)
    private Instant raisedAt = Instant.now();

    private UUID acknowledgedBy;
    private Instant acknowledgedAt;
    private UUID resolvedBy;
    private Instant resolvedAt;
    private String resolutionNotes;

    @Column(nullable = false)
    private int escalationLevel;

    private Instant lastEscalatedAt;

    @Version
    private Long version;
}
