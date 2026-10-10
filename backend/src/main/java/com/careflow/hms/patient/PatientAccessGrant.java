package com.careflow.hms.patient;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Explicit authorization for a caregiver/guardian login to view a patient's portal records. */
@Getter
@Setter
@Entity
@Table(name = "patient_access_grants")
public class PatientAccessGrant extends TenantEntity {

    public enum Scope { FULL, BILLING_ONLY, CLINICAL_ONLY }

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private UUID granteeUserId;

    @Column(nullable = false)
    private String relationship;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Scope scope = Scope.FULL;

    private UUID grantedBy;

    @Column(nullable = false)
    private Instant grantedAt = Instant.now();

    private Instant expiresAt;
    private Instant revokedAt;
    private UUID revokedBy;
    private String notes;

    public boolean isActive(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
