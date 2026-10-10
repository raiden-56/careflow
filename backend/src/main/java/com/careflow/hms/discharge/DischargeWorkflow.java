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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Coordinated discharge. Clinical authorization (a clinician's decision) and financial clearance
 * (an administrative step) are recorded separately and never infer each other.
 */
@Getter
@Setter
@Entity
@Table(name = "discharge_workflows")
public class DischargeWorkflow extends TenantEntity {

    public enum Status { IN_PROGRESS, READY, COMPLETED, CANCELLED }

    public enum ClinicalDecision { AUTHORIZED, REVOKED }

    public enum FinancialClearance { CLEARED, EXCEPTION }

    @Column(nullable = false)
    private UUID admissionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.IN_PROGRESS;

    private Instant plannedDischargeAt;

    @Column(nullable = false)
    private UUID initiatedBy;

    @Column(nullable = false)
    private Instant initiatedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    private ClinicalDecision clinicalDecision;

    private UUID clinicalAuthorizedBy;
    private Instant clinicalAuthorizedAt;
    private String clinicalNotes;

    @Enumerated(EnumType.STRING)
    private FinancialClearance financialClearance;

    private UUID financialClearedBy;
    private Instant financialClearedAt;
    private String financialExceptionReason;
    private BigDecimal balanceAtClearance;
    private String dischargeDisposition;
    private Instant actualDepartureAt;
    private UUID completedBy;
    private Instant completedAt;
    private String cancelledReason;

    @Version
    private Long version;
}
