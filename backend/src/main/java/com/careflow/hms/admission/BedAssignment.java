package com.careflow.hms.admission;

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

/**
 * One bed occupancy interval for an admission. Partial unique indexes guarantee a bed and an
 * admission each have at most one open (unreleased) assignment.
 */
@Getter
@Setter
@Entity
@Table(name = "bed_assignments")
public class BedAssignment extends TenantEntity {

    public enum ReleaseReason { TRANSFER, DISCHARGE, CANCELLED, CORRECTION }

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID bedId;

    @Column(nullable = false)
    private Instant assignedAt;

    private Instant releasedAt;
    private UUID assignedBy;
    private UUID releasedBy;

    @Enumerated(EnumType.STRING)
    private ReleaseReason releaseReason;

    private UUID transferId;
    private String notes;
}
