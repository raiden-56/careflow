package com.careflow.hms.medication;

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
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Medication administration record (MAR) entry: one scheduled (or PRN/STAT) dose.
 * "Due" and "overdue" are derived from SCHEDULED + scheduledAt; exceptions (DELAYED,
 * REFUSED, MISSED, HELD) require a reason and stay traceable via follow-up fields.
 */
@Getter
@Setter
@Entity
@Table(name = "medication_administrations")
public class MedicationAdministration extends TenantEntity {

    public enum Status {
        SCHEDULED, ADMINISTERED, DELAYED, REFUSED, MISSED, HELD, CANCELLED;

        public static final Set<Status> EXCEPTIONS = EnumSet.of(DELAYED, REFUSED, MISSED, HELD);

        public boolean isException() {
            return EXCEPTIONS.contains(this);
        }
    }

    public enum FollowUpStatus { NONE, OPEN, IN_PROGRESS, RESOLVED }

    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.SCHEDULED;

    private Instant administeredAt;
    private String doseGiven;
    private String routeGiven;
    private String site;
    private String reason;
    private String notes;
    private UUID recordedBy;
    private Instant recordedAt;
    private UUID witnessUserId;
    private Instant overdueFlaggedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FollowUpStatus followUpStatus = FollowUpStatus.NONE;

    private String followUpNotes;
    private UUID followUpBy;
    private Instant followUpAt;
    private String responsibleTeam;

    @Column(nullable = false)
    private int overdueEscalationLevel;

    @Version
    private Long version;
}
