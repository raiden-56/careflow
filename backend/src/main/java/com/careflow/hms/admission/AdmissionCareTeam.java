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

@Getter
@Setter
@Entity
@Table(name = "admission_care_team")
public class AdmissionCareTeam extends TenantEntity {

    public enum CareRole { ATTENDING, CONSULTANT, RESIDENT, PRIMARY_NURSE }

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CareRole careRole;

    private UUID addedBy;

    @Column(nullable = false)
    private Instant addedAt = Instant.now();

    private Instant removedAt;
}
