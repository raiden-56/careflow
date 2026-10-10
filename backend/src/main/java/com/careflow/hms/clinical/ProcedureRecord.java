package com.careflow.hms.clinical;

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

@Getter
@Setter
@Entity
@Table(name = "procedures")
public class ProcedureRecord extends TenantEntity {

    public enum Status { PLANNED, COMPLETED, CANCELLED }

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    private UUID tariffId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private Instant scheduledAt;
    private Instant performedAt;
    private UUID performedBy;
    private String anaesthesia;
    private String findings;
    private String complications;
    private String notes;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private boolean chargePosted;

    @Version
    private Long version;
}
