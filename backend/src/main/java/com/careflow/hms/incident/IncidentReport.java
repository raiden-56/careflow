package com.careflow.hms.incident;

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

@Getter
@Setter
@Entity
@Table(name = "incident_reports")
public class IncidentReport extends TenantEntity {

    public enum IncidentType { MEDICATION_ERROR, NEAR_MISS, FALL, PRESSURE_INJURY, EQUIPMENT, INFECTION, PATIENT_IDENTIFICATION, BEHAVIOURAL, OTHER }

    public enum Severity { NO_HARM, MINOR, MODERATE, SEVERE, DEATH }

    public enum Status { REPORTED, UNDER_REVIEW, ACTION_REQUIRED, CLOSED }

    @Column(nullable = false, updatable = false)
    private String incidentNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentType incidentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(nullable = false)
    private Instant occurredAt;

    private String location;
    private UUID wardId;
    private UUID patientId;
    private UUID admissionId;
    private UUID medicationAdministrationId;

    @Column(nullable = false)
    private String description;

    private String immediateActions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.REPORTED;

    @Column(nullable = false)
    private UUID reportedBy;

    @Column(nullable = false)
    private Instant reportedAt = Instant.now();

    private UUID reviewerId;
    private String reviewNotes;
    private String correctiveActions;
    private Instant closedAt;

    @Version
    private Long version;
}
