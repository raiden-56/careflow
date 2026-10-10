package com.careflow.hms.timeline;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Immutable
@Table(name = "timeline_events")
public class TimelineEvent extends TenantEntity {

    public enum Category { ADMISSION, CLINICAL, MEDICATION, OBSERVATION, INVESTIGATION, NURSING, BILLING, DISCHARGE, TRANSFER, SAFETY }

    /**
     * PATIENT: released to the patient portal and visible to all authorized staff.
     * CARE_TEAM: clinical staff only. BILLING: billing staff (and patient-facing billing summaries).
     * INTERNAL: operational/administrative only.
     */
    public enum Visibility { PATIENT, CARE_TEAM, BILLING, INTERNAL }

    @Column(nullable = false)
    private UUID patientId;

    private UUID admissionId;

    @Column(nullable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false)
    private String title;

    private String detail;
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Visibility visibility;

    private String entityType;
    private UUID entityId;
    private UUID actorUserId;
    private String actorName;
    private String actorRole;

    @Column(nullable = false)
    private Instant occurredAt = Instant.now();
}
