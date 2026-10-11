package com.careflow.hms.billing;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One (partial) settlement received against an approved insurance / third-party claim. Each row is
 * matched by exactly one INSURANCE_SETTLEMENT ledger entry. Append-only (database trigger).
 */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "claim_settlements")
public class ClaimSettlement extends TenantEntity {

    @Column(nullable = false)
    private UUID claimId;

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String settlementReference;

    private String notes;

    @Column(nullable = false)
    private Instant settledAt;

    @Column(nullable = false)
    private UUID recordedBy;

    @Column(nullable = false)
    private Instant recordedAt;
}
