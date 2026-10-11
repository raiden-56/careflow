package com.careflow.hms.patient;

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
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "medical_history")
public class MedicalHistory extends TenantEntity {

    public enum Category { MEDICAL, SURGICAL, FAMILY, SOCIAL, MEDICATION }

    public enum Status { ACTIVE, RESOLVED, ENTERED_IN_ERROR }

    @Column(nullable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(name = "condition_name", nullable = false)
    private String conditionName;

    private String details;
    private LocalDate onsetDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    private UUID recordedBy;

    @Column(nullable = false)
    private Instant recordedAt = Instant.now();

    private String statusReason;
    private UUID statusChangedBy;
    private Instant statusChangedAt;

    @Version
    private Long version;
}
