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
import java.util.UUID;

/** A received payment. Idempotent by (organization, idempotencyKey). Reversed, never deleted. */
@Getter
@Setter
@Entity
@Table(name = "payments")
public class Payment extends TenantEntity {

    public enum Method { CASH, CARD, UPI, NET_BANKING, CHEQUE, DEMAND_DRAFT, ONLINE }

    public enum Status { COMPLETED, REVERSED }

    @Column(nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(nullable = false, updatable = false)
    private UUID patientId;

    @Column(nullable = false, updatable = false)
    private String receiptNumber;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Method method;

    @Column(updatable = false)
    private String methodReference;

    @Column(updatable = false)
    private String payerName;

    @Column(nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.COMPLETED;

    @Column(updatable = false)
    private String notes;

    @Column(nullable = false, updatable = false)
    private UUID receivedBy;

    @Column(nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    private UUID reversedBy;
    private Instant reversedAt;
    private String reversalReason;

    /** True when the payment was explicitly accepted beyond the outstanding balance (advance deposit). */
    @Column(name = "is_advance", nullable = false, updatable = false)
    private boolean advance;
}
