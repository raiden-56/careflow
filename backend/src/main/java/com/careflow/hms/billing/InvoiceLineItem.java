package com.careflow.hms.billing;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A posted charge. Amounts are never edited; a correction is a REVERSAL line (negative amounts)
 * that references the original, which is then marked REVERSED.
 */
@Getter
@Setter
@Entity
@Table(name = "invoice_line_items")
public class InvoiceLineItem extends TenantEntity {

    public enum SourceType { BED_DAY, LAB_ORDER, PROCEDURE, DISPENSATION, CONSULTATION, MANUAL, REVERSAL }

    public enum Status { POSTED, REVERSED, REVERSAL }

    @Column(nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(updatable = false)
    private UUID tariffId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ServiceTariff.Category category;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(nullable = false, updatable = false)
    private LocalDate serviceDate;

    @Column(nullable = false, updatable = false)
    private BigDecimal quantity;

    @Column(nullable = false, updatable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false, updatable = false)
    private BigDecimal taxRate;

    @Column(nullable = false, updatable = false)
    private BigDecimal netAmount;

    @Column(nullable = false, updatable = false)
    private BigDecimal taxAmount;

    @Column(nullable = false, updatable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SourceType sourceType;

    @Column(updatable = false)
    private UUID sourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.POSTED;

    @Column(updatable = false)
    private UUID reversesLineId;

    @Column(updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false)
    private UUID postedBy;

    @Column(nullable = false, updatable = false)
    private Instant postedAt = Instant.now();
}
