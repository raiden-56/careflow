package com.careflow.hms.billing;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Insurance / third-party claim. Only settled amounts reduce the balance (via the ledger). */
@Getter
@Setter
@Entity
@Table(name = "insurance_claims")
public class InsuranceClaim extends TenantEntity {

    public enum PayerType { INSURER, TPA, CORPORATE, GOVERNMENT }

    public enum Status { DRAFT, SUBMITTED, UNDER_REVIEW, APPROVED, PARTIALLY_APPROVED, REJECTED, SETTLED }

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false, updatable = false)
    private String claimNumber;

    @Column(nullable = false)
    private String payerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayerType payerType = PayerType.INSURER;

    @Column(nullable = false)
    private String policyNumber;

    private String preAuthNumber;

    @Column(nullable = false)
    private BigDecimal claimedAmount;

    private BigDecimal approvedAmount;

    @Column(nullable = false)
    private BigDecimal settledAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    private Instant submittedAt;
    private Instant decidedAt;
    private Instant settledAt;
    private String rejectionReason;
    private String notes;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
