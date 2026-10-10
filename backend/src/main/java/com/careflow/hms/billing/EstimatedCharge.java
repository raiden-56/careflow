package com.careflow.hms.billing;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Informational estimate of a future charge. Never included in the authoritative balance. */
@Getter
@Setter
@Entity
@Table(name = "estimated_charges")
public class EstimatedCharge extends TenantEntity {

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private BigDecimal estimatedAmount;

    private String basis;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Long version;
}
