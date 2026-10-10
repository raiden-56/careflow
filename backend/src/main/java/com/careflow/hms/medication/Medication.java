package com.careflow.hms.medication;

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

/** Medication catalogue entry (managed by pharmacy). */
@Getter
@Setter
@Entity
@Table(name = "medications")
public class Medication extends TenantEntity {

    public enum Form { TABLET, CAPSULE, SYRUP, INJECTION, INFUSION, INHALER, OINTMENT, DROPS, SUPPOSITORY, PATCH, OTHER }

    public enum Route { ORAL, IV, IM, SC, INHALED, TOPICAL, SUBLINGUAL, RECTAL, NASAL, OPHTHALMIC, OTIC }

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String genericName;

    private String brandName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Form form;

    @Column(nullable = false)
    private String strength;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Route defaultRoute;

    private String drugClass;

    /** Used to match documented drug allergies (e.g. "PENICILLIN", "NSAID", "SULFONAMIDE"). */
    private String allergenGroup;

    @Column(nullable = false)
    private boolean isHighAlert;

    @Column(nullable = false)
    private boolean isControlled;

    @Column(nullable = false)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(nullable = false)
    private String dispenseUnit = "UNIT";

    @Column(nullable = false)
    private int reorderLevel;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public String displayName() {
        return genericName + " " + strength + (brandName != null ? " (" + brandName + ")" : "");
    }
}
