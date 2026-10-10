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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "admissions")
public class Admission extends TenantEntity {

    public enum Status {
        PENDING_BED, ADMITTED, DISCHARGE_IN_PROGRESS, DISCHARGED, CANCELLED;

        private static final Map<Status, Set<Status>> ALLOWED = Map.of(
                PENDING_BED, EnumSet.of(ADMITTED, CANCELLED),
                ADMITTED, EnumSet.of(DISCHARGE_IN_PROGRESS),
                DISCHARGE_IN_PROGRESS, EnumSet.of(ADMITTED, DISCHARGED),
                DISCHARGED, EnumSet.noneOf(Status.class),
                CANCELLED, EnumSet.noneOf(Status.class));

        public boolean canMoveTo(Status target) {
            return ALLOWED.get(this).contains(target);
        }

        public boolean isOpen() {
            return this == PENDING_BED || this == ADMITTED || this == DISCHARGE_IN_PROGRESS;
        }
    }

    public enum AdmissionType { ELECTIVE, EMERGENCY, TRANSFER_IN, DAYCARE }

    public enum Disposition { HOME, TRANSFERRED, LAMA, DECEASED, ABSCONDED, OTHER }

    @Column(nullable = false, updatable = false)
    private UUID patientId;

    @Column(nullable = false, updatable = false)
    private String admissionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdmissionType admissionType;

    @Column(nullable = false)
    private Instant admittedAt;

    @Column(nullable = false)
    private String admissionReason;

    @Column(nullable = false)
    private UUID departmentId;

    @Column(nullable = false)
    private UUID attendingDoctorId;

    private String referringDoctor;
    private String bedRequirements;

    @Column(nullable = false)
    private boolean requiresIsolation;

    private LocalDate expectedDischargeDate;
    private Instant dischargedAt;

    @Enumerated(EnumType.STRING)
    private Disposition dischargeDisposition;

    private String cancellationReason;
    private UUID createdBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    /** Enforces the admission state machine. */
    public void transitionTo(Status target) {
        if (!status.canMoveTo(target)) {
            throw com.careflow.hms.common.ApiException.rule("INVALID_ADMISSION_TRANSITION",
                    "Admission cannot move from " + status + " to " + target);
        }
        this.status = target;
    }
}
