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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "service_tariffs")
public class ServiceTariff extends TenantEntity {

    public enum Category { ROOM, PROCEDURE, LAB, PHARMACY, CONSULTATION, NURSING, IMAGING, CONSUMABLE, OTHER }

    public enum BillingUnit { PER_UNIT, PER_DAY, PER_VISIT, PER_HOUR }

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillingUnit billingUnit = BillingUnit.PER_UNIT;

    /** For ROOM tariffs: the {@code Room.RoomType} this daily rate applies to. */
    private String roomType;

    private UUID departmentId;
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private LocalDate effectiveFrom = LocalDate.now();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
