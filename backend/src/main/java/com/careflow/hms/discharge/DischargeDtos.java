package com.careflow.hms.discharge;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.admission.AdmissionDtos.PatientBrief;
import com.careflow.hms.billing.Invoice;
import com.careflow.hms.clinical.LabOrder;
import com.careflow.hms.facility.Bed;
import com.careflow.hms.security.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response records for discharge workflows, summaries, follow-ups and the patient portal. */
public final class DischargeDtos {

    private DischargeDtos() {
    }

    // ------------------------------------------------------------------ requests

    public record InitiateRequest(Instant plannedDischargeAt, @Size(max = 1000) String notes) {
    }

    public record CancelRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    public record PlanRequest(Instant plannedDischargeAt) {
    }

    /**
     * Partial step update. Only non-null fields are applied. {@code clearAssignee} removes the assignee.
     * NOT_APPLICABLE needs {@code notApplicableReason}; for required steps also
     * {@code confirmRequiredNotApplicable} (explicit justification).
     */
    public record StepUpdateRequest(
            DischargeStep.Status status,
            DischargeStep.Priority priority,
            UUID assignedTo,
            Boolean clearAssignee,
            @Size(max = 1000) String blockerReason,
            @Size(max = 500) String nextAction,
            @Size(max = 2000) String notes,
            @Size(max = 1000) String notApplicableReason,
            boolean confirmRequiredNotApplicable,
            @NotNull Long version) {
    }

    public enum ClinicalAction { AUTHORIZE, REVOKE }

    public record ClinicalDecisionRequest(@NotNull ClinicalAction decision, @Size(max = 2000) String notes) {
    }

    public record FinancialClearanceRequest(@NotNull DischargeWorkflow.FinancialClearance type,
                                            @Size(max = 1000) String reason) {
    }

    public record CompleteRequest(@NotNull Instant actualDepartureAt, @NotNull Admission.Disposition disposition,
                                  @Size(max = 1000) String notes) {
    }

    public record TemplateRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotNull DischargeStepTemplate.Category category,
            @NotNull Role responsibleRole,
            boolean required,
            @Size(max = 40) String autoCheck,
            boolean active,
            int stepOrder,
            @Size(max = 40) String code,
            Long version) {
    }

    // ------------------------------------------------------------------ workflow views

    public record CheckView(boolean met, String explanation) {
    }

    public record StepView(UUID id, String code, int order, String name, String description,
                           DischargeStepTemplate.Category category, Role responsibleRole, boolean required,
                           String autoCheck, DischargeStep.Status status, DischargeStep.Priority priority,
                           UUID assignedToId, String assignedToName, String blockerReason, String nextAction,
                           String notes, String notApplicableReason, String updatedByName, Instant updatedAt,
                           String completedByName, Instant completedAt, boolean systemCompleted, boolean terminal,
                           CheckView check, boolean canUpdate, List<DischargeStep.Status> allowedStatuses,
                           boolean canMarkNotApplicable, Long version) {
    }

    public record BlockerView(UUID stepId, String code, String title, DischargeStepTemplate.Category category,
                              Role ownerRole, DischargeStep.Priority priority, String explanation, String nextAction,
                              boolean automatic, DischargeStep.Status stepStatus, Instant since) {
    }

    public record ClinicalPanel(DischargeWorkflow.ClinicalDecision decision, String decidedByName, Instant decidedAt,
                                String notes, boolean notesVisible) {
    }

    public record FinancialPanel(DischargeWorkflow.FinancialClearance clearance, String clearedByName,
                                 Instant clearedAt, String exceptionReason, BigDecimal balanceAtClearance,
                                 UUID invoiceId, String invoiceNumber, Invoice.Status invoiceStatus,
                                 BigDecimal currentBalance, String paymentStatus, boolean amountsVisible,
                                 boolean requiredForDischarge, boolean exceptionAllowed) {
    }

    public record Progress(int completed, int total, int percent) {
    }

    public record Readiness(boolean canComplete, List<String> outstanding) {
    }

    public record WorkflowView(UUID id, UUID admissionId, DischargeWorkflow.Status status, Instant plannedDischargeAt,
                               String initiatedByName, Instant initiatedAt, String stage, String stageLabel,
                               long ageHours, Progress progress, List<StepView> steps, List<BlockerView> blockers,
                               ClinicalPanel clinical, FinancialPanel financial, String disposition,
                               Instant actualDepartureAt, String completedByName, Instant completedAt,
                               String cancelledReason, Readiness readiness, Long version) {
    }

    public record WorkflowHistoryItem(UUID id, DischargeWorkflow.Status status, Instant initiatedAt,
                                      String initiatedByName, Instant completedAt, String cancelledReason) {
    }

    public record Actions(boolean canInitiate, boolean canCancel, boolean canEditPlan, boolean canAuthorize,
                          boolean canClearFinancially, boolean canComplete, boolean canWriteSummary,
                          boolean canManageFollowUps, String initiateBlockedReason) {
    }

    public record BedTurnover(UUID bedId, String bedLabel, Bed.Status status, Instant statusChangedAt,
                              boolean released) {
    }

    public record SummaryStatusView(UUID id, DischargeSummary.Status status, boolean released,
                                    String finalizedByName, Instant finalizedAt) {
    }

    public record DischargeDetail(AdmissionSummary admission, WorkflowView workflow, List<WorkflowHistoryItem> history,
                                  Actions actions, BedTurnover bed, SummaryStatusView summary, int followUpCount,
                                  boolean clinicalAccess, boolean financialAccess) {
    }

    public record WorkflowListItem(UUID workflowId, UUID admissionId, String admissionNumber, PatientBrief patient,
                                   String wardName, String bedLabel, String departmentName, String attendingDoctorName,
                                   DischargeWorkflow.Status status, String stage, String stageLabel, long ageHours,
                                   Instant initiatedAt, Instant plannedDischargeAt, int blockerCount, int blockedSteps,
                                   int urgentBlockers, Progress progress, DischargeWorkflow.ClinicalDecision clinicalDecision,
                                   DischargeWorkflow.FinancialClearance financialClearance, List<String> topBlockers,
                                   Instant completedAt, String disposition) {
    }

    public record WorklistItem(AdmissionSummary admission, WorkflowListItem workflow) {
    }

    public record Worklist(List<WorklistItem> active, List<WorkflowListItem> recentlyCompleted) {
    }

    public record StageDelay(String stage, String label, int steps, double avgOpenHours, double medianOpenHours,
                             int currentlyOpen, int currentlyBlocked) {
    }

    public record StepDelay(String code, String name, String stage, int count, double avgOpenHours,
                            double medianOpenHours, int currentlyOpen, int currentlyBlocked) {
    }

    public record DelayReport(Instant from, Instant to, int workflows, int completed, double avgHoursToDischarge,
                              double medianHoursToDischarge, List<StageDelay> byStage, List<StepDelay> bySteps) {
    }

    public record FinancialQueueItem(WorkflowListItem workflow, UUID invoiceId, String invoiceNumber,
                                     Invoice.Status invoiceStatus, BigDecimal balance, String paymentStatus,
                                     DischargeWorkflow.FinancialClearance clearance, String clearedByName,
                                     Instant clearedAt, String exceptionReason, BigDecimal balanceAtClearance) {
    }

    public record TemplateView(UUID id, String code, int stepOrder, String name, String description,
                               DischargeStepTemplate.Category category, Role responsibleRole, boolean required,
                               String autoCheck, boolean active, Long version) {
    }

    // ------------------------------------------------------------------ summaries

    public record SummaryRequest(
            String presentingComplaint,
            String diagnosesText,
            String treatmentSummary,
            String proceduresText,
            String relevantResults,
            String conditionAtDischarge,
            String dischargeMedications,
            String dischargeInstructions,
            String followUpPlan,
            String warningSigns,
            @Size(max = 1000) String amendmentReason,
            @NotNull Long version) {
    }

    public record AmendmentView(String reason, String amendedByName, Instant amendedAt) {
    }

    public record SummaryView(UUID id, UUID admissionId, DischargeSummary.Status status, String presentingComplaint,
                              String diagnosesText, String treatmentSummary, String proceduresText,
                              String relevantResults, String conditionAtDischarge, String dischargeMedications,
                              String dischargeInstructions, String followUpPlan, String warningSigns,
                              String preparedByName, Instant preparedAt, String finalizedByName, Instant finalizedAt,
                              boolean released, String releasedByName, Instant releasedAt, String updatedByName,
                              Instant updatedAt, List<AmendmentView> amendments, Long version) {
    }

    public record SummaryEnvelope(SummaryView summary, boolean canWrite, boolean canRelease) {
    }

    // ------------------------------------------------------------------ follow-ups

    public record FollowUpRequest(
            @NotNull FollowUp.FollowUpType type,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 2000) String description,
            Instant scheduledAt,
            UUID departmentId,
            UUID clinicianId,
            UUID responsibleUserId,
            @Size(max = 200) String location,
            UUID labOrderId,
            Boolean notifyPatient,
            Long version) {
    }

    public record FollowUpOutcomeRequest(@NotNull FollowUp.Status status, @Size(max = 2000) String outcomeNotes) {
    }

    public record DeliveryView(FollowUpDelivery.Event event, String channel, String provider, String destination,
                               String status, String error, Instant attemptedAt) {
    }

    public record FollowUpView(UUID id, UUID admissionId, String admissionNumber, UUID patientId, String patientName,
                               String patientMrn, FollowUp.FollowUpType type, String title, String description,
                               Instant scheduledAt, UUID departmentId, String departmentName, UUID clinicianId,
                               String clinicianName, UUID responsibleUserId, String responsibleName, String location,
                               UUID labOrderId, String labOrderLabel, FollowUp.Status status,
                               Instant patientNotifiedAt, Instant reminderSentAt, Instant completedAt,
                               String outcomeNotes, String outcomeRecordedByName, String createdByName,
                               Instant createdAt, boolean outcomeDue, List<DeliveryView> deliveries, Long version) {
    }

    public record PendingResultView(UUID labOrderId, String orderNumber, String testName, LabOrder.Category category,
                                    LabOrder.Status status, LabOrder.Priority priority, Instant orderedAt,
                                    UUID admissionId, String admissionNumber, UUID patientId, String patientName,
                                    String patientMrn, Admission.Status admissionStatus, Instant dischargedAt,
                                    boolean abnormal, UUID followUpId, String followUpResponsibleName,
                                    FollowUp.Status followUpStatus) {
    }

    public record OptionView(UUID id, String label, String detail) {
    }

    public record FollowUpOptions(List<OptionView> clinicians, List<OptionView> staff, List<OptionView> departments,
                                  List<OptionView> labOrders) {
    }

    // ------------------------------------------------------------------ patient portal

    public record PortalStep(String title, String status, String statusLabel, Instant completedAt) {
    }

    public record PortalMedication(String name, String dose, String route, String frequency, Integer durationDays,
                                   String instructions) {
    }

    public record PortalSummary(UUID admissionId, String status, Instant finalizedAt, String finalizedByName,
                                String diagnosesText, String treatmentSummary, String proceduresText,
                                String relevantResults, String conditionAtDischarge, String dischargeMedications,
                                String dischargeInstructions, String followUpPlan, String warningSigns) {
    }

    public record PortalAcknowledgement(boolean available, boolean acknowledged, Instant acknowledgedAt,
                                        String acknowledgedByName, String unavailableReason) {
    }

    public record PortalDischarge(UUID admissionId, String admissionNumber, Admission.Status admissionStatus,
                                  Instant admittedAt, Instant dischargedAt, String departmentName, String doctorName,
                                  String workflowStatus, String headline, Instant plannedDischargeAt,
                                  Instant initiatedAt, Instant completedAt, Progress progress,
                                  boolean doctorApproved, Instant doctorApprovedAt, List<PortalStep> steps,
                                  PortalSummary summary, List<PortalMedication> medications, String warningSigns,
                                  PortalAcknowledgement acknowledgement, int upcomingFollowUps) {
    }

    public record PortalDischargeEnvelope(PortalDischarge discharge) {
    }

    public record PortalFollowUp(UUID id, FollowUp.FollowUpType type, String title, String instructions,
                                 Instant scheduledAt, String departmentName, String clinicianName, String location,
                                 FollowUp.Status status, Instant patientNotifiedAt, Instant completedAt) {
    }

    public record PortalFollowUps(List<PortalFollowUp> upcoming, List<PortalFollowUp> past) {
    }

    public record AcknowledgeRequest(boolean confirmed, @Size(max = 1000) String comment) {
    }
}
