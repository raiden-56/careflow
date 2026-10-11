package com.careflow.hms.discharge;

import com.careflow.hms.common.TenantEntity;
import com.careflow.hms.security.Role;
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
@Table(name = "discharge_steps")
public class DischargeStep extends TenantEntity {

    public enum Status {
        NOT_STARTED, IN_PROGRESS, BLOCKED, COMPLETED, NOT_APPLICABLE;

        public boolean isDone() {
            return this == COMPLETED || this == NOT_APPLICABLE;
        }
    }

    public enum Priority { LOW, NORMAL, HIGH, URGENT }

    @Column(nullable = false)
    private UUID workflowId;

    @Column(nullable = false)
    private String templateCode;

    @Column(nullable = false)
    private int stepOrder;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DischargeStepTemplate.Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role responsibleRole;

    @Column(nullable = false)
    private boolean required = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.NORMAL;

    /** Snapshot of the template description at initiation. */
    private String description;

    /** Snapshot of the template's automatic check key (see DischargeChecks), or null for manual steps. */
    private String autoCheck;

    private UUID assignedTo;
    private String blockerReason;
    private String notApplicableReason;
    private String nextAction;
    private String notes;
    private UUID updatedBy;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    private UUID completedBy;
    private Instant completedAt;

    @Version
    private Long version;
}
