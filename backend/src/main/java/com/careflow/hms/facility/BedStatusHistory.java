package com.careflow.hms.facility;

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

@Getter
@Setter
@Entity
@Table(name = "bed_status_history")
public class BedStatusHistory extends TenantEntity {

    @Column(nullable = false)
    private UUID bedId;

    @Enumerated(EnumType.STRING)
    private Bed.Status fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Bed.Status toStatus;

    private String reason;
    private UUID changedBy;

    @Column(nullable = false)
    private Instant changedAt;
}
