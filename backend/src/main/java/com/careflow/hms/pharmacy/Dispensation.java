package com.careflow.hms.pharmacy;

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

/** Pharmacy issue of stock against a prescription. Never marks a dose as administered. */
@Getter
@Setter
@Entity
@Table(name = "dispensations")
public class Dispensation extends TenantEntity {

    public enum Status { DISPENSED, PARTIALLY_RETURNED, RETURNED }

    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private UUID medicationId;

    @Column(nullable = false)
    private UUID batchId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int returnedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DISPENSED;

    @Column(nullable = false)
    private UUID dispensedBy;

    @Column(nullable = false)
    private Instant dispensedAt = Instant.now();

    private String notes;
    private UUID chargeLineId;

    @Version
    private Long version;
}
