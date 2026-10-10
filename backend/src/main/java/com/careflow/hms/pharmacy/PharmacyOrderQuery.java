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

/** A pharmacist's clarification request to the prescriber. Never changes the prescription. */
@Getter
@Setter
@Entity
@Table(name = "pharmacy_order_queries")
public class PharmacyOrderQuery extends TenantEntity {

    public enum Status { OPEN, RESOLVED }

    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private UUID raisedBy;

    @Column(nullable = false)
    private Instant raisedAt = Instant.now();

    private UUID resolvedBy;
    private Instant resolvedAt;
    private String resolution;

    @Version
    private Long version;
}
