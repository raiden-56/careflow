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

/** A patient's request concerning their records (copy, correction, sharing restriction...). */
@Getter
@Setter
@Entity
@Table(name = "patient_requests")
public class PatientRequest extends TenantEntity {

    public enum RequestType { RECORD_COPY, CORRECTION, RESTRICT_SHARING, WITHDRAW_CONSENT, ACCESS_REVIEW, OTHER }

    public enum Status { OPEN, IN_REVIEW, COMPLETED, REJECTED }

    @Column(nullable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestType requestType;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private UUID raisedBy;

    @Column(nullable = false)
    private Instant raisedAt = Instant.now();

    private String response;
    private UUID respondedBy;
    private Instant respondedAt;

    @Version
    private Long version;
}
