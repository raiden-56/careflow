package com.careflow.hms.discharge;

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
@Table(name = "follow_ups")
public class FollowUp extends TenantEntity {

    public enum FollowUpType { APPOINTMENT, RESULT_REVIEW, INSTRUCTION, TASK, TELECONSULT }

    public enum Status { PLANNED, SCHEDULED, COMPLETED, MISSED, CANCELLED }

    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FollowUpType followUpType;

    @Column(nullable = false)
    private String title;

    private String description;
    private Instant scheduledAt;
    private UUID departmentId;
    private UUID clinicianId;
    private UUID responsibleUserId;
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PLANNED;

    private Instant patientNotifiedAt;
    private Instant reminderSentAt;
    private Instant completedAt;
    private String outcomeNotes;
    private UUID outcomeRecordedBy;

    /** RESULT_REVIEW follow-ups: the investigation explicitly handed over for review after discharge. */
    private UUID labOrderId;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Long version;
}
