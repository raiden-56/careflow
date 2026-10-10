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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Invoice header. Payment status (UNPAID / PARTIALLY_PAID / PAID / CREDIT) is never stored: it is
 * derived from the authoritative ledger by {@link LedgerService}.
 */
@Getter
@Setter
@Entity
@Table(name = "invoices")
public class Invoice extends TenantEntity {

    public enum Status { OPEN, FINALIZED, CANCELLED }

    public enum PayerType { SELF, INSURANCE, CORPORATE, GOVERNMENT }

    @Column(nullable = false)
    private UUID patientId;

    private UUID admissionId;

    @Column(nullable = false, updatable = false)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayerType payerType = PayerType.SELF;

    private String notes;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private UUID finalizedBy;
    private Instant finalizedAt;
    private UUID reviewedBy;
    private Instant reviewedAt;
    private UUID cancelledBy;
    private Instant cancelledAt;
    private String cancelReason;

    /** Last hospital-local date for which room (bed-day) charges have been posted. */
    private LocalDate roomChargesPostedUntil;

    @Version
    private Long version;
}
