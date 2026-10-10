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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Discount / write-off / credit note / refund / debit note. Requires approval; only an APPROVED
 * adjustment produces a ledger entry. The approver must differ from the requester.
 */
@Getter
@Setter
@Entity
@Table(name = "financial_adjustments")
public class FinancialAdjustment extends TenantEntity {

    public enum Type {
        DISCOUNT, WRITE_OFF, CREDIT_NOTE, REFUND, DEBIT_NOTE;

        /** +1 when the adjustment increases the amount owed, -1 when it decreases it. */
        public int balanceSign() {
            return switch (this) {
                case REFUND, DEBIT_NOTE -> 1;
                case DISCOUNT, WRITE_OFF, CREDIT_NOTE -> -1;
            };
        }
    }

    public enum Status { PENDING, APPROVED, REJECTED }

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false, updatable = false)
    private String adjustmentNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type adjustmentType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String reason;

    private UUID relatedPaymentId;
    private String refundMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private UUID requestedBy;

    @Column(nullable = false)
    private Instant requestedAt = Instant.now();

    private UUID decidedBy;
    private Instant decidedAt;
    private String decisionNotes;

    @Version
    private Long version;
}
