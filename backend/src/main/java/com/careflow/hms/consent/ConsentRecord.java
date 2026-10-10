package com.careflow.hms.consent;

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
@Table(name = "consent_records")
public class ConsentRecord extends TenantEntity {

    public enum ConsentType { TREATMENT, PROCEDURE, DATA_SHARING, CAREGIVER_ACCESS, COMMUNICATION, RESEARCH, INSURANCE_SHARING }

    public enum Status { GRANTED, WITHDRAWN, EXPIRED, DECLINED }

    public enum CaptureMethod { WRITTEN, VERBAL, ELECTRONIC, PORTAL }

    @Column(nullable = false)
    private UUID patientId;

    private UUID admissionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentType consentType;

    @Column(nullable = false)
    private String purpose;

    private String sharedWith;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.GRANTED;

    @Column(nullable = false)
    private Instant grantedAt = Instant.now();

    private Instant expiresAt;
    private Instant withdrawnAt;
    private String withdrawalReason;
    private UUID capturedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaptureMethod captureMethod = CaptureMethod.WRITTEN;

    private String notes;

    @Version
    private Long version;
}
