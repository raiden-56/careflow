package com.careflow.hms.clinical;

import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.clinical.ClinicalSupport.PatientRef;
import com.careflow.hms.patient.Allergy;
import com.careflow.hms.patient.MedicalHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response records for clinical records, nursing workflows, investigations and dashboards. */
public final class ClinicalDtos {

    private ClinicalDtos() {
    }

    // ------------------------------------------------------------------ allergies & history

    public record AllergyRequest(@NotBlank @Size(max = 200) String allergen, @NotNull Allergy.AllergyType allergyType,
                                 @Size(max = 500) String reaction, @NotNull Allergy.Severity severity) {
    }

    public record StatusChangeRequest(@NotBlank String status, @Size(max = 500) String reason) {
    }

    public record AllergyView(UUID id, UUID patientId, String allergen, Allergy.AllergyType allergyType, String reaction,
                              Allergy.Severity severity, Allergy.Status status, String recordedBy, Instant recordedAt,
                              String statusReason, String statusChangedBy, Instant statusChangedAt, Long version) {
    }

    public record HistoryRequest(@NotNull MedicalHistory.Category category, @NotBlank @Size(max = 300) String conditionName,
                                 @Size(max = 2000) String details, LocalDate onsetDate) {
    }

    public record HistoryView(UUID id, UUID patientId, MedicalHistory.Category category, String conditionName, String details,
                              LocalDate onsetDate, MedicalHistory.Status status, String recordedBy, Instant recordedAt,
                              String statusReason, Long version) {
    }

    // ------------------------------------------------------------------ diagnoses & plans

    public record DiagnosisRequest(@NotBlank @Size(max = 500) String description,
                                   @Pattern(regexp = "^$|^[A-Za-z][0-9][0-9A-Za-z](\\.[0-9A-Za-z]{1,4})?$", message = "must be a valid ICD-10 code, e.g. J18.9") String icdCode,
                                   @NotNull Diagnosis.DiagnosisType diagnosisType, boolean primary,
                                   @Size(max = 1000) String notes) {
    }

    public record ReasonRequest(@NotBlank @Size(min = 3, max = 1000) String reason) {
    }

    public record OptionalNotesRequest(@Size(max = 2000) String notes) {
    }

    public record DiagnosisView(UUID id, UUID admissionId, String icdCode, String description, Diagnosis.DiagnosisType diagnosisType,
                                boolean primary, Diagnosis.Status status, String notes, String recordedBy, Instant recordedAt,
                                String statusReason, Instant statusChangedAt, Long version) {
    }

    public record TreatmentPlanRequest(@NotBlank @Size(max = 8000) String goals, @NotBlank @Size(max = 12000) String planDetails,
                                       @Size(max = 4000) String monitoring, @Size(max = 500) String diet,
                                       @Size(max = 500) String activity, @Size(max = 4000) String patientSummary,
                                       @Size(max = 1000) String revisionReason,
                                       /** The ACTIVE plan this revision is based on (optimistic concurrency); null for the first plan. */
                                       UUID basedOnPlanId) {
    }

    public record ReleaseRequest(@Size(max = 4000) String patientSummary) {
    }

    public record TreatmentPlanView(UUID id, UUID admissionId, int planVersion, String goals, String planDetails, String monitoring,
                                    String diet, String activity, TreatmentPlan.Status status, String patientSummary,
                                    boolean releasedToPatient, Instant releasedAt, String createdBy, Instant createdAt,
                                    UUID supersededById, String revisionReason, Long version) {
    }

    // ------------------------------------------------------------------ notes

    public record NoteRequest(@NotNull ClinicalNote.NoteType noteType, @NotBlank @Size(max = 300) String title,
                              @Size(max = 8000) String subjective, @Size(max = 8000) String objective,
                              @Size(max = 8000) String assessment, @Size(max = 8000) String plan,
                              @NotBlank @Size(max = 20000) String content, @Size(max = 4000) String patientSummary) {
    }

    public record AmendNoteRequest(@NotBlank @Size(max = 300) String title, @Size(max = 8000) String subjective,
                                   @Size(max = 8000) String objective, @Size(max = 8000) String assessment,
                                   @Size(max = 8000) String plan, @NotBlank @Size(max = 20000) String content,
                                   @NotBlank @Size(min = 3, max = 1000) String reason) {
    }

    public record NoteView(UUID id, UUID admissionId, ClinicalNote.NoteType noteType, String title, String subjective,
                           String objective, String assessment, String plan, String content, String patientSummary,
                           boolean releasedToPatient, Instant releasedAt, ClinicalNote.Status status, UUID amendsNoteId,
                           String correctionReason, UUID authorId, String authorName, Instant authoredAt, Long version) {
    }

    // ------------------------------------------------------------------ vitals

    public record VitalsRequest(Instant recordedAt,
                                @DecimalMin(value = "25.0", message = "must be between 25 and 45 °C") @DecimalMax(value = "45.0", message = "must be between 25 and 45 °C") BigDecimal temperatureC,
                                @Min(value = 0, message = "must be between 0 and 300") @Max(value = 300, message = "must be between 0 and 300") Integer pulseBpm,
                                @Min(value = 0, message = "must be between 0 and 80") @Max(value = 80, message = "must be between 0 and 80") Integer respiratoryRate,
                                @Min(value = 0, message = "must be between 0 and 300") @Max(value = 300, message = "must be between 0 and 300") Integer systolicBp,
                                @Min(value = 0, message = "must be between 0 and 200") @Max(value = 200, message = "must be between 0 and 200") Integer diastolicBp,
                                @Min(value = 0, message = "must be between 0 and 100") @Max(value = 100, message = "must be between 0 and 100") Integer spo2,
                                boolean onOxygen,
                                @Min(value = 0, message = "must be between 0 and 10") @Max(value = 10, message = "must be between 0 and 10") Integer painScore,
                                @DecimalMin(value = "0", message = "must be between 0 and 1000") @DecimalMax(value = "1000", message = "must be between 0 and 1000") BigDecimal bloodGlucose,
                                @DecimalMin(value = "0", message = "must be between 0 and 500") @DecimalMax(value = "500", message = "must be between 0 and 500") BigDecimal weightKg,
                                VitalSign.Consciousness consciousness,
                                @Size(max = 1000) String notes) {
    }

    public record VitalView(UUID id, UUID admissionId, Instant recordedAt, String recordedBy, BigDecimal temperatureC, Integer pulseBpm,
                            Integer respiratoryRate, Integer systolicBp, Integer diastolicBp, Integer spo2, boolean onOxygen,
                            Integer painScore, BigDecimal bloodGlucose, BigDecimal weightKg, VitalSign.Consciousness consciousness,
                            String notes, boolean enteredInError, String errorReason, List<String> breaches) {
    }

    public record VitalsRecorded(VitalView vital, List<AlertView> alertsRaised) {
    }

    public record TrendPoint(Instant t, BigDecimal value, UUID vitalId, String breach) {
    }

    public record ThresholdBand(String severity, BigDecimal min, BigDecimal max, String description) {
    }

    public record Crossing(Instant t, BigDecimal value, String fromLevel, String toLevel, String direction, String threshold) {
    }

    public record SeriesStats(BigDecimal min, BigDecimal max, BigDecimal mean, BigDecimal first, BigDecimal latest, int count) {
    }

    public record TrendSeries(String parameter, String label, String unit, List<TrendPoint> points, List<ThresholdBand> bands,
                              List<Crossing> crossings, SeriesStats stats) {
    }

    public record Gap(Instant from, Instant to, double hours, boolean ongoing) {
    }

    public record TrendsView(UUID admissionId, Instant from, Instant to, int expectedIntervalHours, List<TrendSeries> series,
                             List<Gap> gaps, int recordings, String disclaimer) {
    }

    public record CompareRow(String parameter, String label, String unit, SeriesStats a, SeriesStats b, BigDecimal meanChange,
                             String observedDirection) {
    }

    public record CompareView(UUID admissionId, Instant aFrom, Instant aTo, Instant bFrom, Instant bTo, List<CompareRow> rows,
                              String disclaimer) {
    }

    // ------------------------------------------------------------------ alerts

    public record AlertView(UUID id, UUID admissionId, PatientRef patient, ClinicalAlert.AlertType alertType,
                            ClinicalAlert.Severity severity, String title, String message, String sourceType, UUID sourceId,
                            String parameter, String observedValue, UUID thresholdRuleId, String thresholdText,
                            ClinicalAlert.Status status, UUID assignedTo, String assignedToName, String raisedBy, Instant raisedAt,
                            String acknowledgedBy, Instant acknowledgedAt, String resolvedBy, Instant resolvedAt,
                            String resolutionNotes, int escalationLevel, Instant lastEscalatedAt, Long version) {
    }

    public record EscalationRequest(@NotBlank @Size(max = 300) String concern, @NotBlank @Size(max = 1500) String observations,
                                    @NotNull Urgency urgency, UUID assignToUserId) {
    }

    public enum Urgency { ROUTINE, URGENT, EMERGENCY }

    public record ResolveAlertRequest(@NotBlank @Size(min = 3, max = 2000) String notes) {
    }

    // ------------------------------------------------------------------ investigations

    public record CatalogueItem(UUID tariffId, String code, String name, String category, BigDecimal unitPrice, String description) {
    }

    public record LabOrderRequest(@NotNull UUID tariffId, LabOrder.Priority priority, @Size(max = 1000) String clinicalInfo,
                                  @Size(max = 100) String specimen, Instant dueAt) {
    }

    public record CollectRequest(Instant collectedAt, @Size(max = 100) String specimen, @Size(max = 500) String notes) {
    }

    public record ResultComponent(@NotBlank @Size(max = 200) String component, @NotBlank @Size(max = 500) String valueText,
                                  BigDecimal valueNumeric, @Size(max = 50) String unit, @Size(max = 100) String referenceRange,
                                  @NotNull LabResult.Flag flag, @Size(max = 2000) String interpretation) {
    }

    public record ResultEntryRequest(@NotEmpty @Size(max = 40) List<@Valid ResultComponent> components,
                                     LabResult.Status status, Instant resultedAt) {
    }

    public record LisResultPayload(@NotBlank @Size(max = 32) String orderNumber,
                                   @NotEmpty @Size(max = 40) List<@Valid ResultComponent> components,
                                   Instant performedAt, @Size(max = 100) String analyzer) {
    }

    public record ReviewRequest(@Size(max = 2000) String comment) {
    }

    public record LabResultView(UUID id, String component, String valueText, BigDecimal valueNumeric, String unit,
                                String referenceRange, LabResult.Flag flag, String interpretation, String resultedBy,
                                Instant resultedAt, LabResult.Source source, String reviewedBy, Instant reviewedAt,
                                String reviewComment, boolean releasedToPatient, Instant releasedAt, LabResult.Status status) {
    }

    public record LabOrderView(UUID id, UUID admissionId, PatientRef patient, String orderNumber, UUID tariffId, String testCode,
                               String testName, LabOrder.Category category, String specimen, LabOrder.Priority priority,
                               LabOrder.Status status, String clinicalInfo, UUID orderedById, String orderedBy, Instant orderedAt,
                               Instant dueAt, String collectedBy, Instant collectedAt, String cancelledBy, Instant cancelledAt,
                               String cancelReason, boolean chargePosted, boolean hasCritical, boolean hasAbnormal,
                               boolean released, List<LabResultView> results, Long version) {
    }

    // ------------------------------------------------------------------ procedures

    public record ProcedureRequest(@NotBlank @Size(max = 300) String name, UUID tariffId, Instant scheduledAt,
                                   @Size(max = 4000) String notes) {
    }

    public record CompleteProcedureRequest(Instant performedAt, UUID performedById, @Size(max = 100) String anaesthesia,
                                           @Size(max = 8000) String findings, @Size(max = 4000) String complications,
                                           @Size(max = 4000) String notes) {
    }

    public record ProcedureView(UUID id, UUID admissionId, UUID tariffId, String tariffName, String name,
                                ProcedureRecord.Status status, Instant scheduledAt, Instant performedAt, String performedBy,
                                String anaesthesia, String findings, String complications, String notes, String createdBy,
                                Instant createdAt, boolean chargePosted, Long version) {
    }

    // ------------------------------------------------------------------ care tasks

    public record TaskRequest(@NotNull CareTask.TaskType taskType, @NotBlank @Size(max = 300) String title,
                              @Size(max = 2000) String description, CareTask.Priority priority, Instant dueAt,
                              UUID assignedTo) {
    }

    public record TaskStatusRequest(@NotNull CareTask.Status status, @Size(max = 2000) String notes) {
    }

    public record AssignTaskRequest(UUID userId) {
    }

    public record TaskView(UUID id, UUID admissionId, PatientRef patient, CareTask.TaskType taskType, String title,
                           String description, CareTask.Priority priority, CareTask.Status status, Instant dueAt,
                           boolean overdue, UUID assignedTo, String assignedToName, String assignedRole, String sourceType,
                           UUID sourceId, String createdBy, Instant createdAt, String completedBy, Instant completedAt,
                           String completionNotes, Long version) {
    }

    // ------------------------------------------------------------------ handover

    public record HandoverCreateRequest(@NotNull UUID wardId, @NotNull LocalDate shiftDate, @NotNull Handover.Shift shift) {
    }

    public record HandoverItemUpdate(@NotNull UUID id, @Size(max = 4000) String conditionSummary,
                                     @Size(max = 4000) String importantObservations, @Size(max = 4000) String pendingMedications,
                                     @Size(max = 4000) String pendingTasks, @Size(max = 4000) String outstandingItems,
                                     @Size(max = 4000) String escalations, HandoverItem.Priority priority) {
    }

    public record HandoverUpdateRequest(@Size(max = 4000) String generalNotes, UUID toUserId,
                                        List<@Valid HandoverItemUpdate> items, @NotNull Long version) {
    }

    public record HandoverSubmitRequest(@NotNull UUID toUserId) {
    }

    public record AckRequest(@Size(max = 1000) String comment) {
    }

    public record HandoverItemView(UUID id, UUID admissionId, PatientRef patient, String conditionSummary,
                                   String importantObservations, String pendingMedications, String pendingTasks,
                                   String outstandingItems, String escalations, HandoverItem.Priority priority) {
    }

    public record HandoverView(UUID id, UUID wardId, String wardName, LocalDate shiftDate, Handover.Shift shift,
                               UUID fromUserId, String fromUserName, UUID toUserId, String toUserName, String generalNotes,
                               Handover.Status status, boolean requiresAck, Instant submittedAt, String acknowledgedBy,
                               Instant acknowledgedAt, String ackComment, Instant createdAt, int itemCount,
                               int criticalCount, List<HandoverItemView> items, Long version) {
    }

    public record NurseOption(UUID id, String fullName) {
    }

    // ------------------------------------------------------------------ drafts

    public record DraftRequest(@NotNull AiDraft.DraftType draftType) {
    }

    public record AcceptDraftRequest(@Size(max = 300) String title, @Size(max = 20000) String editedContent) {
    }

    public record DraftView(UUID id, UUID admissionId, AiDraft.DraftType draftType, String generator, String content,
                            List<Map<String, Object>> sourceRefs, AiDraft.Status status, String createdBy, Instant createdAt,
                            String reviewedBy, Instant reviewedAt, UUID publishedNoteId, String disclaimer) {
    }

    // ------------------------------------------------------------------ summary

    public record ClinicalSummary(AdmissionSummary admission, String admissionReason, List<AllergyView> allergies,
                                  List<HistoryView> history, List<DiagnosisView> diagnoses, TreatmentPlanView activePlan,
                                  List<com.careflow.hms.medication.MedicationDtos.OrderView> activeMedications, VitalView latestVitals,
                                  boolean observationOverdue, Double hoursSinceLastVitals, int expectedIntervalHours,
                                  List<LabOrderView> pendingInvestigations, List<AlertView> openAlerts,
                                  List<TaskView> openTasks, List<NoteView> recentNotes) {
    }

    // ------------------------------------------------------------------ dashboards & reports

    public record PatientRow(PatientRef patient, int openAlerts, int criticalAlerts, boolean observationOverdue,
                             Instant lastVitalsAt, int dueDoses, int overdueDoses, int openTasks, int pendingResults,
                             boolean expectedDischargeToday, LocalDate expectedDischargeDate, String latestNews,
                             Instant admittedAt, long lengthOfStayDays, String primaryDiagnosis) {
    }

    public record DoctorDashboard(int myPatients, int criticalAlerts, int openAlerts, int resultsToReview,
                                  int pendingInvestigations, int expectedDischargesToday, int newAdmissions24h,
                                  int activeOrders, int documentationGaps, List<AlertView> criticalAlertList,
                                  List<LabOrderView> resultsAwaitingReview, List<PatientRow> patients) {
    }

    public record NurseDashboard(int myPatients, int dosesDue, int dosesOverdue, int openExceptions, int tasksDue,
                                 int tasksOverdue, int openAlerts, int observationsOverdue, int pendingCollections,
                                 int handoversToAcknowledge, int expectedDischargesToday, List<PatientRow> patients,
                                 List<TaskView> upcomingTasks, List<AlertView> alerts) {
    }

    public record DocumentationRow(PatientRef patient, boolean admissionAssessment, Instant lastProgressNoteAt,
                                   Double hoursSinceProgressNote, boolean activePlan, boolean primaryDiagnosis,
                                   Instant lastVitalsAt, boolean vitalsOverdue, int completenessPercent, List<String> gaps) {
    }

    public record DocumentationReport(int admissions, int fullyDocumented, int averageCompleteness,
                                      List<DocumentationRow> rows) {
    }

    public record OutstandingRow(LabOrderView order, double ageHours, boolean overdue, String reason) {
    }

    public record OutstandingReport(int total, int awaitingCollection, int awaitingResult, int awaitingReview,
                                    int overdue, List<OutstandingRow> rows) {
    }

    public record CountRow(String key, long count) {
    }

    public record AlertHistoryReport(int days, long total, long critical, long resolved, Double medianMinutesToAcknowledge,
                                     List<CountRow> byType, List<CountRow> bySeverity, List<CountRow> byDay,
                                     List<AlertView> recent) {
    }

    // ------------------------------------------------------------------ portal

    public record PortalNote(UUID id, String noteType, String title, String patientSummary, String authorName,
                             Instant authoredAt, Instant releasedAt, String admissionNumber) {
    }

    public record PortalResult(UUID orderId, String testName, String category, Instant orderedAt, Instant resultedAt,
                               String reviewComment, String admissionNumber, List<LabResultView> components) {
    }

    public record PortalPlan(UUID id, int planVersion, String patientSummary, String status, Instant createdAt,
                             Instant releasedAt, String createdBy, String admissionNumber) {
    }

    public record PortalRecords(UUID patientId, List<PortalNote> notes, List<PortalResult> results, List<PortalPlan> plans) {
    }
}
