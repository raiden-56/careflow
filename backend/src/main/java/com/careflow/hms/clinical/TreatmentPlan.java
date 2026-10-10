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

/** Versioned treatment plan: a revision supersedes (never overwrites) the previous ACTIVE plan. */
@Getter
@Setter
@Entity
@Table(name = "treatment_plans")
public class TreatmentPlan extends TenantEntity {

    public enum Status { ACTIVE, SUPERSEDED, COMPLETED }

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private int planVersion;

    @Column(nullable = false)
    private String goals;

    @Column(nullable = false)
    private String planDetails;

    private String monitoring;
    private String diet;
    private String activity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String patientSummary;

    @Column(nullable = false)
    private boolean releasedToPatient;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private UUID supersededById;

    @Version
    private Long version;
}
