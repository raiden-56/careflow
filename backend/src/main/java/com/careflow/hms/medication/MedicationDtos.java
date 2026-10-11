package com.careflow.hms.medication;

import com.careflow.hms.clinical.ClinicalSupport.PatientRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response records for medication orders (prescriptions) and the MAR. */
public final class MedicationDtos {

    private MedicationDtos() {
    }

    public record MedicationOption(UUID id, String code, String displayName, String genericName, String brandName, String form,
                                   String strength, Medication.Route defaultRoute, String drugClass, String allergenGroup,
                                   boolean highAlert, boolean controlled, List<String> allergyConflicts) {
    }

    public record OrderRequest(@NotNull UUID medicationId,
                               @NotBlank @Size(max = 50) String dose,
                               @NotBlank @Size(max = 20) String doseUnit,
                               @NotNull Medication.Route route,
                               @NotNull MedicationOrder.Frequency frequency,
                               @Pattern(regexp = "^$|^([01]\\d|2[0-3]):[0-5]\\d(,([01]\\d|2[0-3]):[0-5]\\d)*$",
                                       message = "must be comma-separated HH:mm times, e.g. 08:00,20:00") String scheduleTimes,
                               Instant startAt,
                               @Min(value = 1, message = "must be at least 1 day") @Max(value = 180, message = "must be at most 180 days") Integer durationDays,
                               Instant endAt,
                               @Size(max = 500) String indication,
                               @Size(max = 1000) String instructions,
                               @Size(max = 1000) String patientInstructions,
                               boolean dischargeMedication,
                               @Size(max = 1000) String allergyOverrideReason) {
    }

    public record ModifyRequest(@NotNull @Valid OrderRequest order, @NotBlank @Size(min = 3, max = 1000) String reason) {
    }

    public record ReleaseOrderRequest(@Size(max = 1000) String patientInstructions) {
    }

    public record OrderView(UUID id, UUID admissionId, PatientRef patient, UUID medicationId, String medicationName,
                            String genericName, String strength, String form, boolean highAlert, boolean controlled,
                            String dose, String doseUnit, Medication.Route route, MedicationOrder.Frequency frequency,
                            String scheduleTimes, Instant startAt, Instant endAt, Integer durationDays, String indication,
                            String instructions, String patientInstructions, boolean dischargeMedication,
                            MedicationOrder.Status status, String allergyOverrideReason, UUID prescribedById,
                            String prescribedBy, Instant prescribedAt, String verifiedBy, Instant verifiedAt,
                            String discontinuedBy, Instant discontinuedAt, String discontinueReason, String holdReason,
                            Instant heldAt, UUID replacesOrderId, boolean releasedToPatient, Instant nextDoseAt,
                            Instant lastAdministeredAt, Long version) {
    }

    public record DoseView(UUID id, UUID orderId, UUID admissionId, PatientRef patient, String medicationName, String strength,
                           String dose, String doseUnit, Medication.Route route, MedicationOrder.Frequency frequency,
                           String instructions, boolean highAlert, boolean controlled, MedicationOrder.Status orderStatus,
                           Instant scheduledAt, MedicationAdministration.Status status, String displayStatus,
                           Instant administeredAt, String doseGiven, String routeGiven, String site, String reason,
                           String notes, String recordedBy, Instant recordedAt, String witness, Instant overdueFlaggedAt,
                           MedicationAdministration.FollowUpStatus followUpStatus, String followUpNotes, String followUpBy,
                           Instant followUpAt, String responsibleTeam, int minutesLate, Long version) {
    }

    public record RoundGroup(Instant slot, List<DoseView> doses) {
    }

    public record RoundsView(Instant generatedAt, int dueWindowMinutes, int graceMinutes, boolean nurseMayHoldDose, int due,
                             int overdue, int upcoming, List<RoundGroup> groups, List<OrderView> prnOrders) {
    }

    public record AdministerRequest(Instant administeredAt, @Size(max = 50) String doseGiven, Medication.Route routeGiven,
                                    @Size(max = 100) String site, @Size(max = 1000) String notes, UUID witnessUserId,
                                    Long version) {
    }

    public record ExceptionRequest(@NotNull MedicationAdministration.Status status,
                                   @NotBlank(message = "A reason is required for this exception") @Size(min = 3, max = 1000) String reason,
                                   @Size(max = 1000) String notes, Long version) {
    }

    public record PrnDoseRequest(Instant administeredAt, @NotBlank @Size(max = 50) String doseGiven, Medication.Route routeGiven,
                                 @Size(max = 100) String site,
                                 @NotBlank(message = "Document the indication for this dose") @Size(min = 3, max = 1000) String reason,
                                 @Size(max = 1000) String notes, UUID witnessUserId) {
    }

    public record FollowUpRequest(@NotNull MedicationAdministration.FollowUpStatus status, @Size(max = 2000) String notes) {
    }
}
