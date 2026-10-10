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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "inventory_batches")
public class InventoryBatch extends TenantEntity {

    public enum Status { ACTIVE, QUARANTINED, EXPIRED, DEPLETED }

    @Column(nullable = false)
    private UUID medicationId;

    @Column(nullable = false)
    private String batchNumber;

    @Column(nullable = false)
    private LocalDate expiryDate;

    @Column(nullable = false)
    private int quantityReceived;

    @Column(nullable = false)
    private int quantityOnHand;

    @Column(nullable = false)
    private BigDecimal unitCost = BigDecimal.ZERO;

    private String supplier;

    @Column(nullable = false)
    private Instant receivedAt = Instant.now();

    private UUID receivedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Version
    private Long version;
}
