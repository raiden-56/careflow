package com.careflow.hms.admission;

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
@Table(name = "transfers")
public class Transfer extends TenantEntity {

    public enum Status { REQUESTED, APPROVED, COMPLETED, CANCELLED, REJECTED }

    public enum Priority { ROUTINE, URGENT }

    @Column(nullable = false)
    private UUID admissionId;

    private UUID fromBedId;
    private UUID toBedId;
    private UUID toWardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.ROUTINE;

    @Column(nullable = false)
    private UUID requestedBy;

    @Column(nullable = false)
    private Instant requestedAt = Instant.now();

    private UUID decidedBy;
    private Instant decidedAt;
    private String decisionNotes;
    private UUID completedBy;
    private Instant completedAt;

    @Version
    private Long version;
}
