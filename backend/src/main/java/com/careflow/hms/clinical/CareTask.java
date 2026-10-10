package com.careflow.hms.clinical;

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
@Table(name = "care_tasks")
public class CareTask extends TenantEntity {

    public enum TaskType { LAB_COLLECTION, WOUND_CARE, MOBILISATION, OBSERVATION, EDUCATION, HYGIENE, DISCHARGE_PREP, TRANSFER, OTHER }

    public enum Priority { LOW, NORMAL, HIGH, URGENT }

    public enum Status { OPEN, IN_PROGRESS, DONE, CANCELLED }

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskType taskType;

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    private Instant dueAt;
    private UUID assignedTo;

    @Enumerated(EnumType.STRING)
    private Role assignedRole;

    private String sourceType;
    private UUID sourceId;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private UUID completedBy;
    private Instant completedAt;
    private String completionNotes;

    @Version
    private Long version;
}
