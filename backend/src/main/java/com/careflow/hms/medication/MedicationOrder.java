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
import java.util.UUID;

/**
 * An authorized prescription. Only prescribers (PRESCRIBE permission) create, hold or discontinue
 * orders. Modifying a dose creates a new order that {@code replacesOrderId} the old one.
 */
@Getter
@Setter
@Entity
@Table(name = "medication_orders")
public class MedicationOrder extends TenantEntity {

    public enum Frequency {
        ONCE, STAT, OD, BD, TDS, QID, Q4H, Q6H, Q8H, Q12H, HS, PRN, WEEKLY;

        /** Default administration times (hospital local time) used when none are specified. */
        public String defaultTimes() {
            return switch (this) {
                case OD -> "09:00";
                case BD -> "09:00,21:00";
                case TDS -> "08:00,14:00,20:00";
                case QID -> "06:00,12:00,18:00,22:00";
                case Q4H -> "02:00,06:00,10:00,14:00,18:00,22:00";
                case Q6H -> "00:00,06:00,12:00,18:00";
                case Q8H -> "06:00,14:00,22:00";
                case Q12H -> "08:00,20:00";
                case HS -> "22:00";
                case WEEKLY -> "09:00";
                case ONCE, STAT, PRN -> "";
            };
        }

        public boolean isScheduled() {
            return this != PRN && this != STAT && this != ONCE;
        }
    }

    public enum Status { ACTIVE, ON_HOLD, DISCONTINUED, COMPLETED }

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private UUID medicationId;

    @Column(nullable = false)
    private String dose;

    @Column(nullable = false)
    private String doseUnit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Medication.Route route;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Frequency frequency;

    /** Comma-separated HH:mm times in hospital local time. */
    private String scheduleTimes;

    @Column(nullable = false)
    private Instant startAt;

    private Instant endAt;
    private Integer durationDays;
    private String indication;
    private String instructions;
    private String patientInstructions;

    @Column(nullable = false)
    private boolean isDischargeMedication;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    private String allergyOverrideReason;

    @Column(nullable = false)
    private UUID prescribedBy;

    @Column(nullable = false)
    private Instant prescribedAt = Instant.now();

    private UUID verifiedByPharmacist;
    private Instant verifiedAt;
    private UUID discontinuedBy;
    private Instant discontinuedAt;
    private String discontinueReason;
    private UUID replacesOrderId;

    @Column(nullable = false)
    private boolean releasedToPatient;

    private String holdReason;
    private UUID heldBy;
    private Instant heldAt;

    @Version
    private Long version;
}
