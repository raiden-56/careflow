package com.careflow.hms.facility;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Assigns a staff member (typically a nurse) to a ward; drives nurse patient lists and access. */
@Getter
@Setter
@Entity
@Table(name = "staff_ward_assignments")
public class StaffWardAssignment extends TenantEntity {

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID wardId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private Instant assignedAt = Instant.now();

    private UUID assignedBy;
    private Instant endedAt;
}
