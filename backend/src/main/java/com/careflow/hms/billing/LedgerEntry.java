package com.careflow.hms.billing;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only financial ledger (enforced by a database trigger). The invoice balance is
 * SUM(amount): positive entries increase what is owed, negative entries reduce it.
 */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry extends TenantEntity {

    public enum EntryType {
        CHARGE, CHARGE_REVERSAL, PAYMENT, PAYMENT_REVERSAL, DISCOUNT, WRITE_OFF, CREDIT_NOTE, DEBIT_NOTE,
        REFUND, INSURANCE_SETTLEMENT
    }

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryType entryType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String referenceType;

    @Column(nullable = false)
    private UUID referenceId;

    private String referenceNumber;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
