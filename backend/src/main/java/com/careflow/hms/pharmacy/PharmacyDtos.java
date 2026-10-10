package com.careflow.hms.pharmacy;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.admission.AdmissionDtos.PatientBrief;
import com.careflow.hms.medication.Medication;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PharmacyDtos {

    private PharmacyDtos() {
    }

    /** Stock availability of a catalogue item, from usable stock (ACTIVE, unexpired batches). */
    public enum Availability { IN_STOCK, LOW_STOCK, OUT_OF_STOCK }

    // ------------------------------------------------------------------ catalogue

    public record MedicationView(
            UUID id, String code, String genericName, String brandName, Medication.Form form, String strength,
            Medication.Route defaultRoute, String drugClass, String allergenGroup,
            @JsonProperty("isHighAlert") boolean isHighAlert,
            @JsonProperty("isControlled") boolean isControlled,
            BigDecimal unitPrice, String dispenseUnit, int reorderLevel, boolean active,
            long stockOnHand, Availability availability, String displayName, Long version) {
    }

    public record MedicationRequest(
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{1,31}$",
                    message = "Use 2-32 letters, digits or hyphens") String code,
            @NotBlank @Size(max = 200) String genericName,
            @Size(max = 200) String brandName,
            @NotNull Medication.Form form,
            @NotBlank @Size(max = 50) String strength,
            @NotNull Medication.Route defaultRoute,
            @Size(max = 100) String drugClass,
            @Size(max = 100) String allergenGroup,
            @JsonProperty("isHighAlert") boolean isHighAlert,
            @JsonProperty("isControlled") boolean isControlled,
            @NotNull @DecimalMin(value = "0.00", message = "Price cannot be negative")
            @Digits(integer = 10, fraction = 2, message = "Use at most 2 decimal places") BigDecimal unitPrice,
            @NotBlank @Size(max = 20) String dispenseUnit,
            @NotNull @Min(0) @Max(1_000_000) Integer reorderLevel,
            /** Required on update (optimistic locking). */
            Long version) {
    }

    public record ReasonRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    // ------------------------------------------------------------------ inventory

    public record ReceiveStockRequest(
            @NotNull UUID medicationId,
            @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9./_-]{0,49}$",
                    message = "Use letters, digits and . / _ - only") String batchNumber,
            @NotNull LocalDate expiryDate,
            @NotNull @Min(1) @Max(1_000_000) Integer quantity,
            @NotNull @DecimalMin(value = "0.00", message = "Cost cannot be negative")
            @Digits(integer = 10, fraction = 2, message = "Use at most 2 decimal places") BigDecimal unitCost,
            @Size(max = 200) String supplier) {
    }

    public enum AdjustDirection { IN, OUT }

    public enum AdjustReason { COUNT_CORRECTION, DAMAGE, OTHER }

    public record AdjustRequest(
            @NotNull AdjustDirection direction,
            @NotNull @Min(1) @Max(1_000_000) Integer quantity,
            @NotNull AdjustReason reasonCode,
            @NotBlank @Size(min = 3, max = 450) String reason) {
    }

    public record BatchView(
            UUID id, UUID medicationId, String medicationCode, String medicationName, String dispenseUnit,
            String batchNumber, LocalDate expiryDate, long daysToExpiry, boolean expired,
            int quantityReceived, int quantityOnHand, BigDecimal unitCost, BigDecimal stockValue, String supplier,
            Instant receivedAt, String receivedByName, InventoryBatch.Status status, boolean usable, Long version) {
    }

    public record BatchDetail(BatchView batch, List<MovementView> movements) {
    }

    public record MovementView(
            UUID id, UUID batchId, String batchNumber, UUID medicationId, String medicationName,
            StockMovement.MovementType type, int quantity, int balanceAfter, String referenceType, UUID referenceId,
            String reason, String performedByName, Instant performedAt) {
    }

    public record StockLevel(
            UUID medicationId, String code, String name, Medication.Form form, String strength, String dispenseUnit,
            boolean active,
            @JsonProperty("isHighAlert") boolean isHighAlert,
            @JsonProperty("isControlled") boolean isControlled,
            int reorderLevel, long usable, long quarantined, long expiredOnHand, long nearExpiry, int batchCount,
            LocalDate nearestExpiry, BigDecimal stockValue, BigDecimal unitPrice, Availability availability) {
    }

    public record AlertsView(int nearExpiryDays, List<StockLevel> lowStock, List<BatchView> nearExpiry,
                             List<BatchView> expiredOnHand, List<BatchView> quarantined) {
    }

    public record DayCount(LocalDate date, int dispensations, long units) {
    }

    public record PharmacyDashboard(
            int queueSize, int awaitingVerification, int openQueries, int readyToDispense,
            int dispensedToday, long unitsDispensedToday,
            int lowStockCount, int outOfStockCount, int nearExpiryCount, int expiredOnHandCount, int quarantinedCount,
            BigDecimal stockValue, int nearExpiryDays, List<DayCount> dispensedLast7Days) {
    }

    // ------------------------------------------------------------------ prescription queue

    public enum SupplyStatus { AWAITING_VERIFICATION, QUERIED, READY_TO_DISPENSE, SUPPLIED, COURSE_ENDED }

    public enum QueueFilter { OUTSTANDING, AWAITING_VERIFICATION, QUERIED, READY_TO_DISPENSE, ALL }

    /** severity: error | warning | info. */
    public record Warning(String type, String severity, String message) {
    }

    public record OrderQueryView(UUID id, UUID orderId, String message, PharmacyOrderQuery.Status status,
                                 String raisedByName, Instant raisedAt, String resolvedByName, Instant resolvedAt,
                                 String resolution) {
    }

    public record QueueItem(
            UUID orderId, UUID admissionId, String admissionNumber, Admission.Status admissionStatus,
            PatientBrief patient, String location,
            UUID medicationId, String medicationCode, String medicationName, Medication.Form form, String strength,
            String dispenseUnit,
            @JsonProperty("isHighAlert") boolean isHighAlert,
            @JsonProperty("isControlled") boolean isControlled,
            BigDecimal unitPrice,
            String dose, String doseUnit, Medication.Route route, String frequency, String scheduleTimes,
            Instant startAt, Instant endAt, String indication, String instructions, String allergyOverrideReason,
            boolean dischargeMedication, UUID prescribedById, String prescribedByName, Instant prescribedAt,
            boolean verified, String verifiedByName, Instant verifiedAt, OrderQueryView openQuery,
            List<String> allergies, List<Warning> warnings, long stockOnHand, Availability availability,
            Instant lastDispensedAt, int netDispensedQuantity, int netDispensedInCycle,
            Integer suggestedQuantity, String suggestedQuantityBasis, SupplyStatus supplyStatus, Long orderVersion) {
    }

    public record BatchOption(UUID batchId, String batchNumber, LocalDate expiryDate, long daysToExpiry,
                              int quantityOnHand, boolean suggested) {
    }

    public record OrderDetail(QueueItem item, AdmissionSummary admission, List<String> otherActiveMedications,
                              String interactionNote, int supplyCycleHours, List<BatchOption> batches,
                              List<DispensationView> dispensations, List<OrderQueryView> queries) {
    }

    public record VerifyRequest(boolean allergyAcknowledged, @Size(max = 500) String note) {
    }

    public record QueryRequest(@NotBlank @Size(min = 5, max = 1000) String message) {
    }

    public record ResolveQueryRequest(@NotBlank @Size(min = 3, max = 1000) String resolution) {
    }

    // ------------------------------------------------------------------ dispensing

    public record DispenseRequest(
            @NotNull UUID orderId,
            /** Optional: when omitted the FEFO batch (earliest usable expiry with enough stock) is used. */
            UUID batchId,
            @NotNull @Min(1) @Max(1000) Integer quantity,
            @Size(max = 1000) String notes,
            /** Required for high-alert / controlled medications and documented allergy matches. */
            boolean safetyChecksConfirmed) {
    }

    public record ReturnRequest(
            @NotNull @Min(1) @Max(1000) Integer quantity,
            @NotBlank @Size(min = 3, max = 450) String reason,
            /** Damaged/contaminated returns are not restocked. */
            boolean damaged) {
    }

    public record DispensationView(
            UUID id, UUID orderId, UUID admissionId, String admissionNumber, UUID patientId, String patientName,
            String patientMrn, UUID medicationId, String medicationCode, String medicationName, String dispenseUnit,
            UUID batchId, String batchNumber, LocalDate batchExpiry, int quantity, int returnedQuantity,
            int netQuantity, Dispensation.Status status, String dispensedByName, Instant dispensedAt, String notes,
            UUID chargeLineId, BigDecimal unitPrice, BigDecimal chargedAmount) {
    }

    public record ChargeLineView(UUID id, String description, LocalDate serviceDate, BigDecimal quantity,
                                 BigDecimal unitPrice, BigDecimal totalAmount, String status, Instant postedAt,
                                 String reason) {
    }

    public record DispensationDetail(DispensationView dispensation, List<MovementView> movements,
                                     List<ChargeLineView> charges) {
    }
}
