package com.careflow.hms.discharge;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionCareTeamRepository;
import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.admission.AdmissionService;
import com.careflow.hms.admission.BedAllocationService;
import com.careflow.hms.admission.BedAssignment;
import com.careflow.hms.admission.BedAssignmentRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.Invoice;
import com.careflow.hms.billing.InvoiceRepository;
import com.careflow.hms.billing.LedgerService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.Money;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.discharge.DischargeChecks.Evaluation;
import com.careflow.hms.discharge.DischargeChecks.Result;
import com.careflow.hms.discharge.DischargeChecks.Snapshot;
import com.careflow.hms.discharge.DischargeDtos.Actions;
import com.careflow.hms.discharge.DischargeDtos.BedTurnover;
import com.careflow.hms.discharge.DischargeDtos.CheckView;
import com.careflow.hms.discharge.DischargeDtos.ClinicalDecisionRequest;
import com.careflow.hms.discharge.DischargeDtos.ClinicalPanel;
import com.careflow.hms.discharge.DischargeDtos.CompleteRequest;
import com.careflow.hms.discharge.DischargeDtos.DischargeDetail;
import com.careflow.hms.discharge.DischargeDtos.FinancialClearanceRequest;
import com.careflow.hms.discharge.DischargeDtos.FinancialPanel;
import com.careflow.hms.discharge.DischargeDtos.InitiateRequest;
import com.careflow.hms.discharge.DischargeDtos.StepUpdateRequest;
import com.careflow.hms.discharge.DischargeDtos.StepView;
import com.careflow.hms.discharge.DischargeDtos.SummaryStatusView;
import com.careflow.hms.discharge.DischargeDtos.WorkflowHistoryItem;
import com.careflow.hms.discharge.DischargeDtos.WorkflowListItem;
import com.careflow.hms.discharge.DischargeDtos.WorkflowView;
import com.careflow.hms.facility.Bed;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.medication.MedicationAdministration;
import com.careflow.hms.medication.MedicationAdministrationRepository;
import com.careflow.hms.medication.MedicationOrder;
import com.careflow.hms.medication.MedicationOrderRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.notification.NotificationTemplateRepository;
import com.careflow.hms.org.SettingsService;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.AccessPolicy.Scope;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Permissions;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Coordinated discharge workflow. Clinical authorization (an explicit decision by the treating doctor) and
 * financial clearance (an administrative step) are separate records that never imply each other; software
 * only evaluates hospital-configured checks against recorded data and never makes a clinical decision.
 */
@Service
@RequiredArgsConstructor
public class DischargeWorkflowService {

    static final Set<Admission.Disposition> EXCEPTIONAL_DISPOSITIONS =
            EnumSet.of(Admission.Disposition.LAMA, Admission.Disposition.ABSCONDED, Admission.Disposition.DECEASED);

    private final DischargeWorkflowRepository workflows;
    private final DischargeStepRepository steps;
    private final DischargeStepTemplateRepository templates;
    private final DischargeSummaryRepository summaries;
    private final FollowUpRepository followUps;
    private final AdmissionRepository admissions;
    private final AdmissionCareTeamRepository careTeam;
    private final BedAssignmentRepository bedAssignments;
    private final BedRepository beds;
    private final PatientRepository patients;
    private final UserRepository users;
    private final InvoiceRepository invoices;
    private final MedicationAdministrationRepository administrations;
    private final MedicationOrderRepository medicationOrders;
    private final NotificationTemplateRepository notificationTemplates;
    private final AccessPolicy access;
    private final DischargeChecks checks;
    private final BedAllocationService bedAllocation;
    private final AdmissionService admissionService;
    private final SettingsService settings;
    private final LedgerService ledger;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    // ================================================================== access helpers

    /** The object scope a staff role uses to view discharge information (null = no staff access). */
    static Scope viewScope(CurrentUser u) {
        return switch (u.role()) {
            case DOCTOR, NURSE -> Scope.CLINICAL;
            case BILLING -> Scope.FINANCIAL;
            case PHARMACIST -> Scope.MEDICATION;
            case HOSPITAL_ADMIN -> Scope.DEMOGRAPHICS;
            default -> null;
        };
    }

    /** Loads an admission the caller may view in discharge screens, or throws 404/403. */
    public Admission requireView(UUID admissionId) {
        CurrentUser u = TenantContext.requireUser();
        Scope s = viewScope(u);
        if (s == null || !u.has(Permissions.DISCHARGE_VIEW)) {
            throw ApiException.forbidden("You do not have access to discharge workflows.");
        }
        return access.requireAdmission(admissionId, s);
    }

    public boolean canView(CurrentUser u, Admission a) {
        Scope s = viewScope(u);
        return s != null && u.has(Permissions.DISCHARGE_VIEW) && access.canAccessAdmission(u, a, s);
    }

    private static boolean mayCoordinate(CurrentUser u) {
        return (u.role() == Role.DOCTOR || u.role() == Role.NURSE || u.role() == Role.HOSPITAL_ADMIN)
                && (u.has(Permissions.DISCHARGE_STEP_UPDATE) || u.has(Permissions.DISCHARGE_CLINICAL_AUTHORIZE));
    }

    private void requireCoordinator(CurrentUser u, Admission a) {
        if (!mayCoordinate(u)) {
            throw ApiException.forbidden("Only the care team or discharge coordination can start or change a discharge plan.");
        }
        access.requireAdmission(a.getId(), viewScope(u));
    }

    private DischargeWorkflow lockWorkflow(UUID id) {
        return workflows.lockById(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Discharge workflow"));
    }

    private Admission lockAdmission(UUID id) {
        return admissions.lockById(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Admission"));
    }

    private static boolean isOpen(DischargeWorkflow w) {
        return w.getStatus() == DischargeWorkflow.Status.IN_PROGRESS || w.getStatus() == DischargeWorkflow.Status.READY;
    }

    private static void requireOpen(DischargeWorkflow w) {
        if (!isOpen(w)) {
            throw ApiException.rule("WORKFLOW_CLOSED", "This discharge workflow is " + w.getStatus().name().toLowerCase() + ".");
        }
    }

    private boolean requireClearance() {
        return settings.getBool(SettingsService.DISCHARGE_REQUIRE_FINANCIAL_CLEARANCE);
    }

    // ================================================================== commands

    @Transactional
    public DischargeDetail initiate(UUID admissionId, InitiateRequest r) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = lockAdmission(admissionId);
        requireCoordinator(u, a);
        switch (a.getStatus()) {
            case ADMITTED -> {
            }
            case PENDING_BED -> throw ApiException.rule("ADMISSION_NOT_ELIGIBLE", "The patient is still awaiting a bed; assign a bed first.");
            case DISCHARGE_IN_PROGRESS -> throw ApiException.conflict("DISCHARGE_IN_PROGRESS", "A discharge workflow is already in progress for this admission.");
            default -> throw ApiException.rule("ADMISSION_NOT_ELIGIBLE", "This admission is " + a.getStatus().name().toLowerCase().replace('_', ' ') + ".");
        }
        if (bedAssignments.findByAdmissionIdAndReleasedAtIsNull(a.getId()).isEmpty()) {
            throw ApiException.rule("NO_BED", "The patient has no active bed assignment.");
        }
        if (workflows.findOpenByAdmission(a.getId()).isPresent()) {
            throw ApiException.conflict("DISCHARGE_IN_PROGRESS", "A discharge workflow is already in progress for this admission.");
        }
        Instant planned = r == null ? null : r.plannedDischargeAt();
        validatePlanned(planned);
        List<DischargeStepTemplate> active = templates.findByOrganizationIdAndActiveTrueOrderByStepOrder(a.getOrganizationId());
        if (active.isEmpty()) {
            throw ApiException.rule("NO_TEMPLATES", "No active discharge steps are configured for this hospital.");
        }
        DischargeWorkflow w = new DischargeWorkflow();
        w.setAdmissionId(a.getId());
        w.setStatus(DischargeWorkflow.Status.IN_PROGRESS);
        w.setPlannedDischargeAt(planned);
        w.setInitiatedBy(u.userId());
        w.setInitiatedAt(Instant.now());
        w = workflows.saveAndFlush(w);
        for (DischargeStepTemplate t : active) {
            DischargeStep s = new DischargeStep();
            s.setWorkflowId(w.getId());
            s.setTemplateCode(t.getCode());
            s.setStepOrder(t.getStepOrder());
            s.setName(t.getName());
            s.setDescription(t.getDescription());
            s.setCategory(t.getCategory());
            s.setResponsibleRole(t.getResponsibleRole());
            s.setRequired(t.isRequired());
            s.setAutoCheck(t.getAutoCheck());
            s.setStatus(DischargeStep.Status.NOT_STARTED);
            s.setUpdatedAt(w.getInitiatedAt());
            steps.save(s);
        }
        a.transitionTo(Admission.Status.DISCHARGE_IN_PROGRESS);
        if (planned != null) {
            a.setExpectedDischargeDate(TimeUtil.localDate(planned));
        }
        Patient p = patients.findById(a.getPatientId()).orElseThrow();
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("DISCHARGE_INITIATED").category(TimelineEvent.Category.DISCHARGE)
                .title("Discharge planning started")
                .detail(planned == null ? "Your care team has started preparing your discharge."
                        : "Planned discharge: " + TimeUtil.localDate(planned))
                .status(w.getStatus().name()).visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("DISCHARGE_WORKFLOW").entityId(w.getId()).build());
        String msg = p.fullName() + " (" + a.getAdmissionNumber() + ")"
                + (planned == null ? "" : " - planned " + TimeUtil.localDate(planned));
        String key = "DISCHARGE_STARTED:" + w.getId();
        notifications.notifyCareTeam(a.getId(), Role.DOCTOR, req("DISCHARGE", Notification.Priority.NORMAL,
                "Discharge planning started", msg, "/doctor/discharge/" + a.getId(), w.getId(), key));
        notifications.notifyWardNurses(a.getId(), req("DISCHARGE", Notification.Priority.NORMAL,
                "Discharge planning started", msg + ". Nursing education and departure steps are on the checklist.",
                "/nurse/discharge/" + a.getId(), w.getId(), key));
        notifications.notifyRole(Role.BILLING, req("DISCHARGE", Notification.Priority.NORMAL,
                "Discharge in progress: prepare final bill", msg, "/billing/discharge", w.getId(), key));
        notifications.notifyRole(Role.PHARMACIST, req("DISCHARGE", Notification.Priority.NORMAL,
                "Discharge in progress: medication reconciliation", msg, "/pharmacy/prescriptions", w.getId(), key));
        audit.record("DISCHARGE_INITIATED", "DISCHARGE_WORKFLOW", w.getId(), a.getPatientId(),
                "Discharge workflow started for " + a.getAdmissionNumber()
                        + (r != null && r.notes() != null && !r.notes().isBlank() ? ": " + r.notes().trim() : ""));
        sync(w, a, steps.findByWorkflowIdOrderByStepOrder(w.getId()));
        return detail(a, w);
    }

    @Transactional
    public DischargeDetail cancel(UUID workflowId, String reason) {
        CurrentUser u = TenantContext.requireUser();
        DischargeWorkflow w0 = workflows.findByIdAndOrganizationId(workflowId, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Discharge workflow"));
        Admission a = lockAdmission(w0.getAdmissionId());
        DischargeWorkflow w = lockWorkflow(workflowId);
        requireCoordinator(u, a);
        requireOpen(w);
        w.setStatus(DischargeWorkflow.Status.CANCELLED);
        w.setCancelledReason(reason.trim());
        if (a.getStatus() == Admission.Status.DISCHARGE_IN_PROGRESS) {
            a.transitionTo(Admission.Status.ADMITTED);
        }
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("DISCHARGE_CANCELLED").category(TimelineEvent.Category.DISCHARGE)
                .title("Discharge postponed")
                .detail("Your care team will continue your treatment and discuss a new discharge plan with you.")
                .status("CANCELLED").visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("DISCHARGE_WORKFLOW").entityId(w.getId()).build());
        Patient p = patients.findById(a.getPatientId()).orElseThrow();
        String key = "DISCHARGE_CANCELLED:" + w.getId();
        String msg = p.fullName() + " (" + a.getAdmissionNumber() + "): " + truncate(reason.trim(), 160);
        notifications.notifyCareTeam(a.getId(), Role.DOCTOR, req("DISCHARGE", Notification.Priority.NORMAL,
                "Discharge cancelled", msg, "/doctor/discharge/" + a.getId(), w.getId(), key));
        notifications.notifyWardNurses(a.getId(), req("DISCHARGE", Notification.Priority.NORMAL,
                "Discharge cancelled", msg, "/nurse/discharge/" + a.getId(), w.getId(), key));
        notifications.notifyRole(Role.BILLING, req("DISCHARGE", Notification.Priority.LOW,
                "Discharge cancelled", p.fullName() + " (" + a.getAdmissionNumber() + ") remains admitted.",
                "/billing/discharge", w.getId(), key));
        audit.record("DISCHARGE_CANCELLED", "DISCHARGE_WORKFLOW", w.getId(), a.getPatientId(), reason.trim());
        return detail(a, w);
    }

    @Transactional
    public DischargeDetail updatePlan(UUID workflowId, Instant planned) {
        CurrentUser u = TenantContext.requireUser();
        DischargeWorkflow w = lockWorkflow(workflowId);
        Admission a = admissions.findById(w.getAdmissionId()).orElseThrow();
        requireCoordinator(u, a);
        requireOpen(w);
        validatePlanned(planned);
        w.setPlannedDischargeAt(planned);
        if (planned != null) {
            a.setExpectedDischargeDate(TimeUtil.localDate(planned));
        }
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("DISCHARGE_PLAN_UPDATED").category(TimelineEvent.Category.DISCHARGE)
                .title(planned == null ? "Planned discharge time cleared" : "Planned discharge: " + TimeUtil.localDate(planned))
                .visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("DISCHARGE_WORKFLOW").entityId(w.getId()).build());
        audit.record("DISCHARGE_PLAN_UPDATED", "DISCHARGE_WORKFLOW", w.getId(), a.getPatientId(), String.valueOf(planned));
        sync(w, a, steps.findByWorkflowIdOrderByStepOrder(w.getId()));
        return detail(a, w);
    }

    @Transactional
    public DischargeDetail updateStep(UUID workflowId, UUID stepId, StepUpdateRequest r) {
        CurrentUser u = TenantContext.requireUser();
        DischargeWorkflow w = lockWorkflow(workflowId);
        Admission a = admissions.findById(w.getAdmissionId()).orElseThrow();
        DischargeStep step = steps.findByIdAndWorkflowId(stepId, w.getId())
                .orElseThrow(() -> ApiException.notFound("Discharge step"));
        requireStepActor(u, a, step);
        requireOpen(w);
        if (!Objects.equals(r.version(), step.getVersion())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This step was changed by someone else. Reload and try again.");
        }
        if (DischargeChecks.isTerminal(step)) {
            throw ApiException.rule("TERMINAL_STEP", "'" + step.getName() + "' is recorded automatically when the discharge is completed.");
        }
        List<DischargeStep> all = steps.findByWorkflowIdOrderByStepOrder(w.getId());
        sync(w, a, all);
        Instant now = Instant.now();
        DischargeStep.Status before = step.getStatus();
        DischargeStep.Status target = r.status();
        if (target != null && target != before) {
            Snapshot snap = checks.load(w, a);
            switch (target) {
                case COMPLETED -> {
                    requireDoctorForClinical(u, step, "completed");
                    if (step.getAutoCheck() != null) {
                        Result res = checks.evaluate(step.getAutoCheck(), snap, true);
                        if (!res.met()) {
                            throw ApiException.rule("AUTO_CHECK_UNMET", "'" + step.getName() + "' cannot be completed yet: " + res.explanation());
                        }
                    }
                    step.setStatus(DischargeStep.Status.COMPLETED);
                    step.setCompletedBy(u.userId());
                    step.setCompletedAt(now);
                    step.setBlockerReason(null);
                    step.setNotApplicableReason(null);
                }
                case NOT_APPLICABLE -> {
                    String reason = trimToNull(r.notApplicableReason());
                    if (reason == null || reason.length() < 5) {
                        throw ApiException.badRequest("NA_REASON_REQUIRED", "Document why this step is not applicable (at least 5 characters).");
                    }
                    requireDoctorForClinical(u, step, "marked not applicable");
                    if (step.getAutoCheck() != null) {
                        if (!DischargeChecks.CLINICIAN_MAY_MARK_NA.contains(step.getAutoCheck())) {
                            throw ApiException.rule("NA_NOT_ALLOWED", "'" + step.getName()
                                    + "' is checked automatically from recorded data and cannot be marked not applicable.");
                        }
                        if (u.role() != Role.DOCTOR) {
                            throw ApiException.forbidden("Only a doctor can record that '" + step.getName() + "' is not applicable.");
                        }
                    }
                    if (step.isRequired() && !r.confirmRequiredNotApplicable()) {
                        throw ApiException.rule("JUSTIFICATION_REQUIRED", "'" + step.getName()
                                + "' is a required step. Confirm the documented justification to mark it not applicable.");
                    }
                    step.setStatus(DischargeStep.Status.NOT_APPLICABLE);
                    step.setNotApplicableReason(reason);
                    step.setCompletedBy(null);
                    step.setCompletedAt(null);
                    step.setBlockerReason(null);
                }
                case BLOCKED -> {
                    String reason = trimToNull(r.blockerReason());
                    if (reason == null || reason.length() < 3) {
                        throw ApiException.badRequest("BLOCKER_REASON_REQUIRED", "Describe what is blocking this step.");
                    }
                    step.setStatus(DischargeStep.Status.BLOCKED);
                    step.setBlockerReason(reason);
                    step.setCompletedBy(null);
                    step.setCompletedAt(null);
                }
                case IN_PROGRESS, NOT_STARTED -> {
                    if (before == DischargeStep.Status.COMPLETED && step.getAutoCheck() != null
                            && checks.evaluate(step.getAutoCheck(), snap, true).met()) {
                        throw ApiException.rule("AUTO_CHECK_MET", "'" + step.getName()
                                + "' is satisfied by recorded data. Change the underlying record instead.");
                    }
                    step.setStatus(target);
                    step.setCompletedBy(null);
                    step.setCompletedAt(null);
                    step.setBlockerReason(null);
                    step.setNotApplicableReason(null);
                }
            }
        } else if (r.blockerReason() != null && step.getStatus() == DischargeStep.Status.BLOCKED) {
            String reason = trimToNull(r.blockerReason());
            if (reason == null) {
                throw ApiException.badRequest("BLOCKER_REASON_REQUIRED", "Describe what is blocking this step.");
            }
            step.setBlockerReason(reason);
        }
        UUID previousAssignee = step.getAssignedTo();
        if (r.priority() != null) {
            step.setPriority(r.priority());
        }
        if (Boolean.TRUE.equals(r.clearAssignee())) {
            step.setAssignedTo(null);
        } else if (r.assignedTo() != null) {
            User assignee = users.findByIdAndOrganizationId(r.assignedTo(), a.getOrganizationId())
                    .filter(x -> x.getStatus() == User.Status.ACTIVE)
                    .orElseThrow(() -> ApiException.notFound("Staff member"));
            if (assignee.getRole() != step.getResponsibleRole()) {
                throw ApiException.rule("INVALID_ASSIGNEE", "This step must be assigned to a " + roleLabel(step.getResponsibleRole()) + ".");
            }
            step.setAssignedTo(assignee.getId());
        }
        if (r.nextAction() != null) {
            step.setNextAction(trimToNull(r.nextAction()));
        }
        if (r.notes() != null) {
            step.setNotes(trimToNull(r.notes()));
        }
        step.setUpdatedBy(u.userId());
        step.setUpdatedAt(now);
        steps.saveAndFlush(step);

        Patient p = patients.findById(a.getPatientId()).orElseThrow();
        if (step.getStatus() != before) {
            boolean patientAck = "PATIENT_ACK".equals(step.getTemplateCode()) && step.getStatus() == DischargeStep.Status.COMPLETED;
            timeline.record(TimelineService.Entry.builder()
                    .patientId(a.getPatientId()).admissionId(a.getId())
                    .eventType(patientAck ? "DISCHARGE_INSTRUCTIONS_ACKNOWLEDGED" : "DISCHARGE_STEP_UPDATED")
                    .category(TimelineEvent.Category.DISCHARGE)
                    .title(patientAck ? "Discharge instructions acknowledged" : step.getName() + ": " + statusLabel(step.getStatus()))
                    .detail(step.getStatus() == DischargeStep.Status.BLOCKED ? step.getBlockerReason()
                            : step.getStatus() == DischargeStep.Status.NOT_APPLICABLE ? step.getNotApplicableReason() : step.getNotes())
                    .status(step.getStatus().name())
                    .visibility(patientAck ? TimelineEvent.Visibility.PATIENT : TimelineEvent.Visibility.CARE_TEAM)
                    .entityType("DISCHARGE_STEP").entityId(step.getId()).build());
            if (step.getStatus() == DischargeStep.Status.BLOCKED) {
                String key = "DISCHARGE_STEP_BLOCKED:" + step.getId() + ":" + now.getEpochSecond();
                String msg = p.fullName() + " (" + a.getAdmissionNumber() + ") - " + step.getName() + ": " + truncate(step.getBlockerReason(), 160);
                notifications.notifyCareTeam(a.getId(), Role.DOCTOR, req("DISCHARGE", Notification.Priority.HIGH,
                        "Discharge step blocked", msg, "/doctor/discharge/" + a.getId(), w.getId(), key));
                notifications.notifyWardNurses(a.getId(), req("DISCHARGE", Notification.Priority.HIGH,
                        "Discharge step blocked", msg, "/nurse/discharge/" + a.getId(), w.getId(), key));
            }
        }
        if (step.getAssignedTo() != null && !step.getAssignedTo().equals(previousAssignee)
                && !step.getAssignedTo().equals(u.userId())) {
            notifications.notifyUsers(List.of(step.getAssignedTo()), req("ASSIGNMENT", Notification.Priority.NORMAL,
                    "Discharge step assigned to you", step.getName() + " - " + p.fullName() + " (" + a.getAdmissionNumber() + ")",
                    linkFor(step.getResponsibleRole(), a.getId()), w.getId(),
                    "DISCHARGE_STEP_ASSIGNED:" + step.getId() + ":" + step.getAssignedTo() + ":" + now.getEpochSecond()));
        }
        audit.record("DISCHARGE_STEP_UPDATED", "DISCHARGE_STEP", step.getId(), a.getPatientId(),
                step.getTemplateCode() + ": " + before + " -> " + step.getStatus());
        sync(w, a, all);
        return detail(a, w);
    }

    @Transactional
    public DischargeDetail clinicalDecision(UUID workflowId, ClinicalDecisionRequest r) {
        CurrentUser u = TenantContext.requireUser();
        DischargeWorkflow w = lockWorkflow(workflowId);
        Admission a = admissions.findById(w.getAdmissionId()).orElseThrow();
        if (u.role() != Role.DOCTOR || !u.has(Permissions.DISCHARGE_CLINICAL_AUTHORIZE)) {
            throw ApiException.forbidden("Only the treating doctor can record the clinical discharge decision.");
        }
        access.requireAdmission(a.getId(), Scope.CLINICAL);
        if (!isTreatingDoctor(u, a)) {
            throw ApiException.forbidden("Only the attending doctor or a doctor on the patient's care team can record the discharge decision.");
        }
        requireOpen(w);
        String notes = trimToNull(r.notes());
        Patient p = patients.findById(a.getPatientId()).orElseThrow();
        Instant now = Instant.now();
        if (r.decision() == DischargeDtos.ClinicalAction.AUTHORIZE) {
            if (notes == null || notes.length() < 3) {
                throw ApiException.badRequest("CLINICAL_NOTES_REQUIRED", "Record the clinical basis for the discharge decision.");
            }
            if (w.getClinicalDecision() == DischargeWorkflow.ClinicalDecision.AUTHORIZED) {
                throw ApiException.conflict("ALREADY_AUTHORIZED", "Discharge has already been clinically authorized.");
            }
            w.setClinicalDecision(DischargeWorkflow.ClinicalDecision.AUTHORIZED);
            w.setClinicalAuthorizedBy(u.userId());
            w.setClinicalAuthorizedAt(now);
            w.setClinicalNotes(notes);
            timeline.record(TimelineService.Entry.builder()
                    .patientId(a.getPatientId()).admissionId(a.getId())
                    .eventType("DISCHARGE_CLINICALLY_AUTHORIZED").category(TimelineEvent.Category.DISCHARGE)
                    .title("Your doctor has approved your discharge")
                    .detail("The team is completing the remaining discharge steps.")
                    .status("AUTHORIZED").visibility(TimelineEvent.Visibility.PATIENT)
                    .entityType("DISCHARGE_WORKFLOW").entityId(w.getId()).build());
            String key = "DISCHARGE_AUTHORIZED:" + w.getId() + ":" + now.getEpochSecond();
            String msg = p.fullName() + " (" + a.getAdmissionNumber() + ") authorized for discharge by " + u.fullName() + ".";
            notifications.notifyWardNurses(a.getId(), req("DISCHARGE", Notification.Priority.HIGH,
                    "Discharge authorized", msg + " Complete nursing education and departure steps.",
                    "/nurse/discharge/" + a.getId(), w.getId(), key));
            notifications.notifyRole(Role.BILLING, req("DISCHARGE", Notification.Priority.NORMAL,
                    "Discharge authorized: final bill", msg + " Finalize the invoice and record clearance.",
                    "/billing/discharge", w.getId(), key));
            notifications.notifyPatient(a.getPatientId(), req("DISCHARGE", Notification.Priority.NORMAL,
                    "Your doctor has approved your discharge",
                    "The care team is completing the remaining steps. You can follow progress in the portal.",
                    "/patient/discharge", w.getId(), key));
            audit.record("DISCHARGE_CLINICALLY_AUTHORIZED", "DISCHARGE_WORKFLOW", w.getId(), a.getPatientId(),
                    "Clinical discharge authorization recorded");
        } else {
            if (w.getClinicalDecision() != DischargeWorkflow.ClinicalDecision.AUTHORIZED) {
                throw ApiException.rule("NOT_AUTHORIZED", "There is no clinical authorization to withdraw.");
            }
            if (notes == null || notes.length() < 3) {
                throw ApiException.badRequest("REVOKE_REASON_REQUIRED", "Document why the discharge authorization is withdrawn.");
            }
            w.setClinicalDecision(DischargeWorkflow.ClinicalDecision.REVOKED);
            w.setClinicalAuthorizedBy(u.userId());
            w.setClinicalAuthorizedAt(now);
            w.setClinicalNotes(notes);
            timeline.record(TimelineService.Entry.builder()
                    .patientId(a.getPatientId()).admissionId(a.getId())
                    .eventType("DISCHARGE_AUTHORIZATION_WITHDRAWN").category(TimelineEvent.Category.DISCHARGE)
                    .title("Discharge on hold")
                    .detail("Your doctor will explain the next steps of your care.")
                    .status("REVOKED").visibility(TimelineEvent.Visibility.PATIENT)
                    .entityType("DISCHARGE_WORKFLOW").entityId(w.getId()).build());
            String key = "DISCHARGE_REVOKED:" + w.getId() + ":" + now.getEpochSecond();
            String msg = p.fullName() + " (" + a.getAdmissionNumber() + "): discharge authorization withdrawn by " + u.fullName() + ".";
            notifications.notifyWardNurses(a.getId(), req("DISCHARGE", Notification.Priority.HIGH,
                    "Discharge on hold", msg, "/nurse/discharge/" + a.getId(), w.getId(), key));
            notifications.notifyRole(Role.BILLING, req("DISCHARGE", Notification.Priority.NORMAL,
                    "Discharge on hold", msg, "/billing/discharge", w.getId(), key));
            notifications.notifyPatient(a.getPatientId(), req("DISCHARGE", Notification.Priority.NORMAL,
                    "Discharge on hold", "Your doctor will explain the next steps of your care.",
                    "/patient/discharge", w.getId(), key));
            audit.record("DISCHARGE_AUTHORIZATION_REVOKED", "DISCHARGE_WORKFLOW", w.getId(), a.getPatientId(), notes);
        }
        workflows.saveAndFlush(w);
        sync(w, a, steps.findByWorkflowIdOrderByStepOrder(w.getId()));
        return detail(a, w);
    }

    @Transactional
    public DischargeDetail financialClearance(UUID workflowId, FinancialClearanceRequest r) {
        CurrentUser u = TenantContext.requireUser();
        DischargeWorkflow w = lockWorkflow(workflowId);
        Admission a = admissions.findById(w.getAdmissionId()).orElseThrow();
        if (!u.has(Permissions.DISCHARGE_FINANCIAL_CLEAR)) {
            throw ApiException.forbidden("Financial clearance is recorded by billing staff.");
        }
        access.requireAdmission(a.getId(), Scope.FINANCIAL);
        requireOpen(w);
        if (w.getFinancialClearance() == DischargeWorkflow.FinancialClearance.CLEARED) {
            throw ApiException.conflict("ALREADY_CLEARED", "Financial clearance has already been recorded.");
        }
        Optional<Invoice> active = invoices.findActiveByAdmission(a.getId());
        Invoice invoice = active.flatMap(i -> invoices.lockById(i.getId(), a.getOrganizationId())).orElse(null);
        BigDecimal balance = invoice == null ? Money.ZERO : ledger.balance(invoice.getId());
        String reason = trimToNull(r.reason());
        Patient p = patients.findById(a.getPatientId()).orElseThrow();
        Instant now = Instant.now();
        if (r.type() == DischargeWorkflow.FinancialClearance.CLEARED) {
            if (balance.signum() > 0) {
                throw ApiException.rule("BALANCE_OUTSTANDING", "An outstanding balance of INR " + balance.toPlainString()
                        + " remains on " + invoice.getInvoiceNumber() + ". Record the payment, or an authorized exception if hospital policy allows.");
            }
        } else {
            if (reason == null || reason.length() < 10) {
                throw ApiException.badRequest("EXCEPTION_REASON_REQUIRED",
                        "An authorized financial exception requires a documented reason (at least 10 characters).");
            }
            if (!settings.getBool(SettingsService.DISCHARGE_ALLOW_FINANCIAL_EXCEPTION)) {
                throw ApiException.rule("EXCEPTION_NOT_ALLOWED", "Hospital policy does not allow financial exceptions at discharge.");
            }
            if (balance.signum() <= 0) {
                throw ApiException.rule("NO_OUTSTANDING_BALANCE", "The balance is settled; record financial clearance instead of an exception.");
            }
        }
        w.setFinancialClearance(r.type());
        w.setFinancialClearedBy(u.userId());
        w.setFinancialClearedAt(now);
        w.setFinancialExceptionReason(r.type() == DischargeWorkflow.FinancialClearance.EXCEPTION ? reason : null);
        w.setBalanceAtClearance(balance);
        workflows.saveAndFlush(w);
        boolean exception = r.type() == DischargeWorkflow.FinancialClearance.EXCEPTION;
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType(exception ? "DISCHARGE_FINANCIAL_EXCEPTION" : "DISCHARGE_FINANCIALLY_CLEARED")
                .category(TimelineEvent.Category.BILLING)
                .title(exception ? "Authorized financial exception recorded for discharge" : "Financial clearance recorded for discharge")
                .detail(exception ? "Balance at clearance INR " + balance.toPlainString() + ": " + reason
                        : "Balance at clearance INR " + balance.toPlainString())
                .status(r.type().name()).visibility(TimelineEvent.Visibility.BILLING)
                .entityType("DISCHARGE_WORKFLOW").entityId(w.getId()).build());
        String key = "DISCHARGE_FINANCE:" + w.getId() + ":" + r.type();
        notifications.notifyWardNurses(a.getId(), req("DISCHARGE", Notification.Priority.NORMAL,
                exception ? "Financial exception recorded" : "Financial clearance recorded",
                p.fullName() + " (" + a.getAdmissionNumber() + ") - billing step complete.",
                "/nurse/discharge/" + a.getId(), w.getId(), key));
        if (exception) {
            notifications.notifyRole(Role.HOSPITAL_ADMIN, req("DISCHARGE", Notification.Priority.HIGH,
                    "Authorized financial exception at discharge",
                    p.fullName() + " (" + a.getAdmissionNumber() + "): INR " + balance.toPlainString() + " outstanding. "
                            + truncate(reason, 160) + " - recorded by " + u.fullName(),
                    "/admin/discharges/" + a.getId(), w.getId(), key));
        }
        audit.record(exception ? "DISCHARGE_FINANCIAL_EXCEPTION" : "DISCHARGE_FINANCIALLY_CLEARED", "DISCHARGE_WORKFLOW",
                w.getId(), a.getPatientId(), "Balance " + balance.toPlainString() + (exception ? "; " + reason : ""));
        sync(w, a, steps.findByWorkflowIdOrderByStepOrder(w.getId()));
        return detail(a, w);
    }

    @Transactional
    public DischargeDetail complete(UUID workflowId, CompleteRequest r) {
        CurrentUser u = TenantContext.requireUser();
        DischargeWorkflow w0 = workflows.findByIdAndOrganizationId(workflowId, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Discharge workflow"));
        // Lock order: admission, then workflow (same as cancel) to stay deadlock-free.
        Admission a = lockAdmission(w0.getAdmissionId());
        DischargeWorkflow w = lockWorkflow(workflowId);
        if (!u.has(Permissions.DISCHARGE_COMPLETE) || (u.role() != Role.NURSE && u.role() != Role.HOSPITAL_ADMIN)) {
            throw ApiException.forbidden("Departure is recorded by nursing or discharge coordination staff.");
        }
        access.requireAdmission(a.getId(), viewScope(u));
        requireOpen(w);
        if (a.getStatus() != Admission.Status.DISCHARGE_IN_PROGRESS) {
            throw ApiException.rule("ADMISSION_NOT_DISCHARGING", "The admission is not in discharge.");
        }
        Instant now = Instant.now();
        Instant departure = r.actualDepartureAt();
        if (departure.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("INVALID_DEPARTURE_TIME", "The departure time cannot be in the future.");
        }
        if (departure.isBefore(a.getAdmittedAt())) {
            throw ApiException.badRequest("INVALID_DEPARTURE_TIME", "The departure time cannot be before admission.");
        }
        List<DischargeStep> all = steps.findByWorkflowIdOrderByStepOrder(w.getId());
        sync(w, a, all);
        boolean exceptional = EXCEPTIONAL_DISPOSITIONS.contains(r.disposition());
        String notes = trimToNull(r.notes());
        if (!exceptional) {
            if (w.getClinicalDecision() != DischargeWorkflow.ClinicalDecision.AUTHORIZED) {
                throw ApiException.rule("CLINICAL_AUTHORIZATION_REQUIRED",
                        "Discharge cannot be completed until the treating doctor records the clinical discharge authorization.");
            }
            if (requireClearance() && w.getFinancialClearance() == null) {
                throw ApiException.rule("FINANCIAL_CLEARANCE_REQUIRED",
                        "Hospital policy requires financial clearance or an authorized exception before departure.");
            }
            List<String> open = all.stream()
                    .filter(s -> s.isRequired() && !s.getStatus().isDone() && !DischargeChecks.isTerminal(s))
                    .map(DischargeStep::getName).toList();
            if (!open.isEmpty()) {
                throw ApiException.rule("STEPS_INCOMPLETE", "Complete or resolve these required steps first: " + String.join("; ", open) + ".");
            }
        } else {
            if (notes == null || notes.length() < 10) {
                throw ApiException.badRequest("NOTES_REQUIRED", "Document the circumstances of a "
                        + r.disposition().name().toLowerCase() + " discharge (at least 10 characters).");
            }
            for (DischargeStep s : all) {
                if (!s.getStatus().isDone() && !DischargeChecks.isTerminal(s)) {
                    s.setStatus(DischargeStep.Status.NOT_APPLICABLE);
                    s.setNotApplicableReason("Discharge recorded as " + dispositionLabel(r.disposition()) + ": " + notes);
                    s.setUpdatedBy(u.userId());
                    s.setUpdatedAt(now);
                }
            }
        }

        // Admission -> DISCHARGED, bed released into turnover, future doses cancelled.
        a.transitionTo(Admission.Status.DISCHARGED);
        a.setDischargedAt(departure);
        a.setDischargeDisposition(r.disposition());
        Bed.Status turnover = parseTurnover(settings.get(SettingsService.BED_TURNOVER_STATUS));
        String bedLabel = bedAssignments.findByAdmissionIdAndReleasedAtIsNull(a.getId())
                .map(ba -> admissionService.describeBed(ba.getBedId())).orElse(null);
        Bed released = bedAllocation.release(a, BedAssignment.ReleaseReason.DISCHARGE, turnover);
        Instant cutoff = departure.isBefore(now) ? departure : now;
        int cancelledDoses = 0;
        for (MedicationAdministration m : administrations.findByAdmissionIdAndStatusAndScheduledAtAfter(
                a.getId(), MedicationAdministration.Status.SCHEDULED, cutoff)) {
            m.setStatus(MedicationAdministration.Status.CANCELLED);
            m.setReason("Patient discharged");
            m.setRecordedBy(u.userId());
            m.setRecordedAt(now);
            cancelledDoses++;
        }
        int completedOrders = 0;
        for (MedicationOrder o : medicationOrders.findByAdmissionIdAndStatusIn(a.getId(),
                EnumSet.of(MedicationOrder.Status.ACTIVE, MedicationOrder.Status.ON_HOLD))) {
            if (!o.isDischargeMedication()) {
                o.setStatus(MedicationOrder.Status.COMPLETED);
                if (o.getEndAt() == null || o.getEndAt().isAfter(departure)) {
                    o.setEndAt(departure);
                }
                completedOrders++;
            }
        }

        w.setStatus(DischargeWorkflow.Status.COMPLETED);
        w.setActualDepartureAt(departure);
        w.setDischargeDisposition(r.disposition().name());
        w.setCompletedBy(u.userId());
        w.setCompletedAt(now);
        for (DischargeStep s : all) {
            if (DischargeChecks.isTerminal(s)) {
                s.setStatus(DischargeStep.Status.COMPLETED);
                s.setCompletedBy(u.userId());
                s.setCompletedAt(DischargeChecks.DEPARTED.equals(s.getAutoCheck()) ? departure : now);
                s.setUpdatedBy(u.userId());
                s.setUpdatedAt(now);
                if (DischargeChecks.BED_RELEASED.equals(s.getAutoCheck())) {
                    s.setNotes(released == null ? "No bed was assigned at departure."
                            : (bedLabel == null ? "Bed" : bedLabel) + " moved to " + turnover.name().toLowerCase() + ".");
                }
            }
        }
        workflows.saveAndFlush(w);

        Patient p = patients.findById(a.getPatientId()).orElseThrow();
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("PATIENT_DISCHARGED").category(TimelineEvent.Category.DISCHARGE)
                .title(r.disposition() == Admission.Disposition.HOME ? "Discharged home" : "Discharged (" + dispositionLabel(r.disposition()) + ")")
                .detail("Departure recorded at " + departure)
                .status("DISCHARGED").visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("DISCHARGE_WORKFLOW").entityId(w.getId()).occurredAt(departure).build());
        if (released != null) {
            timeline.record(TimelineService.Entry.builder()
                    .patientId(a.getPatientId()).admissionId(a.getId())
                    .eventType("BED_RELEASED").category(TimelineEvent.Category.ADMISSION)
                    .title("Bed released for " + turnover.name().toLowerCase())
                    .detail(bedLabel).status(turnover.name()).visibility(TimelineEvent.Visibility.INTERNAL)
                    .entityType("BED").entityId(released.getId()).build());
        }
        String key = "DISCHARGED:" + a.getId();
        if (r.disposition() != Admission.Disposition.DECEASED) {
            String title = notificationTemplates.findByOrganizationIdAndCodeAndChannelAndActiveTrue(
                    a.getOrganizationId(), "DISCHARGE_COMPLETED", "IN_APP").map(t -> t.getSubject()).orElse("You have been discharged");
            String body = notificationTemplates.findByOrganizationIdAndCodeAndChannelAndActiveTrue(
                            a.getOrganizationId(), "DISCHARGE_COMPLETED", "IN_APP").map(t -> t.getBody())
                    .orElse("Your discharge summary and instructions are available in the portal.");
            notifications.notifyPatient(a.getPatientId(), req("DISCHARGE", Notification.Priority.NORMAL, title, body,
                    "/patient/discharge", w.getId(), key));
        }
        notifications.notifyRole(Role.HOSPITAL_ADMIN, req("DISCHARGE", Notification.Priority.NORMAL,
                "Discharge completed: " + p.fullName(),
                a.getAdmissionNumber() + " (" + dispositionLabel(r.disposition()) + ")"
                        + (bedLabel == null ? "" : " - " + bedLabel + " is now " + turnover.name().toLowerCase()),
                "/admin/discharges/" + a.getId(), w.getId(), key));
        notifications.notifyCareTeam(a.getId(), Role.DOCTOR, req("DISCHARGE", Notification.Priority.LOW,
                "Patient discharged: " + p.fullName(), a.getAdmissionNumber() + " - review follow-up responsibilities.",
                "/doctor/discharge/" + a.getId(), w.getId(), key));
        audit.record("DISCHARGE_COMPLETED", "DISCHARGE_WORKFLOW", w.getId(), a.getPatientId(),
                a.getAdmissionNumber() + " discharged (" + r.disposition() + "); " + cancelledDoses + " scheduled doses cancelled, "
                        + completedOrders + " inpatient orders completed" + (notes == null ? "" : "; " + notes));
        return detail(a, w);
    }

    // ================================================================== automatic checks

    /** Re-evaluates the automatic checks of the admission's open workflow (no-op when none). */
    @Transactional
    public void syncForAdmission(UUID admissionId) {
        workflows.findOpenByAdmission(admissionId).ifPresent(w0 -> {
            DischargeWorkflow w = lockWorkflow(w0.getId());
            Admission a = admissions.findById(w.getAdmissionId()).orElseThrow();
            sync(w, a, steps.findByWorkflowIdOrderByStepOrder(w.getId()));
        });
    }

    /** Locks and re-evaluates one workflow (used by read paths and background jobs). */
    @Transactional
    public void syncWorkflow(UUID workflowId) {
        DischargeWorkflow w = lockWorkflow(workflowId);
        if (isOpen(w)) {
            Admission a = admissions.findById(w.getAdmissionId()).orElseThrow();
            sync(w, a, steps.findByWorkflowIdOrderByStepOrder(w.getId()));
        }
    }

    /**
     * Applies automatic checks: satisfied checks complete their step (attributed to whoever recorded the
     * underlying fact), unmet checks re-open a previously completed step, and the workflow moves between
     * IN_PROGRESS and READY. Caller must hold the workflow lock.
     */
    void sync(DischargeWorkflow w, Admission a, List<DischargeStep> list) {
        if (!isOpen(w)) {
            return;
        }
        boolean requireClearance = requireClearance();
        Snapshot snap = checks.load(w, a);
        Instant now = Instant.now();
        for (DischargeStep s : list) {
            String key = s.getAutoCheck();
            if (key == null || DischargeChecks.TERMINAL.contains(key)) {
                continue;
            }
            if (DischargeChecks.FINANCIAL_CLEARED.equals(key) && !requireClearance && w.getFinancialClearance() == null) {
                if (!s.getStatus().isDone()) {
                    s.setStatus(DischargeStep.Status.NOT_APPLICABLE);
                    s.setNotApplicableReason("Not required: hospital policy does not require financial clearance before discharge.");
                    s.setUpdatedBy(null);
                    s.setUpdatedAt(now);
                }
                continue;
            }
            Result res = checks.evaluate(key, snap, true);
            if (res.met()) {
                if (s.getStatus() != DischargeStep.Status.COMPLETED) {
                    Attribution at = attribution(key, w, snap);
                    s.setStatus(DischargeStep.Status.COMPLETED);
                    s.setCompletedBy(at.userId());
                    s.setCompletedAt(at.at() == null ? now : at.at());
                    s.setBlockerReason(null);
                    s.setNotApplicableReason(null);
                    s.setUpdatedBy(null);
                    s.setUpdatedAt(now);
                }
            } else {
                boolean systemNa = s.getStatus() == DischargeStep.Status.NOT_APPLICABLE && s.getUpdatedBy() == null;
                if (s.getStatus() == DischargeStep.Status.COMPLETED || systemNa) {
                    s.setStatus(DischargeStep.Status.IN_PROGRESS);
                    s.setCompletedBy(null);
                    s.setCompletedAt(null);
                    s.setNotApplicableReason(null);
                    s.setNextAction(res.nextAction());
                    s.setUpdatedBy(null);
                    s.setUpdatedAt(now);
                }
            }
        }
        Evaluation ev = checks.evaluateAll(w, list, snap, true, requireClearance);
        DischargeWorkflow.Status target = ev.readiness().canComplete() ? DischargeWorkflow.Status.READY : DischargeWorkflow.Status.IN_PROGRESS;
        if (w.getStatus() != target) {
            w.setStatus(target);
            if (target == DischargeWorkflow.Status.READY) {
                Patient p = patients.findById(a.getPatientId()).orElseThrow();
                String key = "DISCHARGE_READY:" + w.getId() + ":" + now.getEpochSecond() / 3600;
                notifications.notifyWardNurses(a.getId(), req("DISCHARGE", Notification.Priority.HIGH,
                        "Ready for departure: " + p.fullName(), a.getAdmissionNumber()
                                + " - all discharge steps are complete. Record the departure when the patient leaves.",
                        "/nurse/discharge/" + a.getId(), w.getId(), key));
            }
        }
    }

    private record Attribution(UUID userId, Instant at) {
    }

    private Attribution attribution(String key, DischargeWorkflow w, Snapshot snap) {
        return switch (key) {
            case DischargeChecks.CLINICAL_AUTHORIZED -> new Attribution(w.getClinicalAuthorizedBy(), w.getClinicalAuthorizedAt());
            case DischargeChecks.FINANCIAL_CLEARED -> new Attribution(w.getFinancialClearedBy(), w.getFinancialClearedAt());
            case DischargeChecks.SUMMARY_FINAL -> snap.summary()
                    .map(s -> new Attribution(s.getFinalizedBy(), s.getFinalizedAt())).orElse(new Attribution(null, null));
            case DischargeChecks.INVOICE_FINALIZED -> snap.invoice()
                    .map(i -> new Attribution(i.getFinalizedBy(), i.getFinalizedAt())).orElse(new Attribution(null, null));
            default -> new Attribution(null, null);
        };
    }

    // ================================================================== views

    @Transactional
    public DischargeDetail detailForAdmission(UUID admissionId) {
        Admission a = requireView(admissionId);
        workflows.findOpenByAdmission(a.getId()).ifPresent(w -> syncWorkflow(w.getId()));
        return detail(a, null);
    }

    @Transactional
    public DischargeDetail detailForWorkflow(UUID workflowId) {
        DischargeWorkflow w = workflows.findByIdAndOrganizationId(workflowId, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Discharge workflow"));
        Admission a = requireView(w.getAdmissionId());
        if (isOpen(w)) {
            syncWorkflow(w.getId());
        }
        return detail(a, w);
    }

    DischargeDetail detail(Admission a, DischargeWorkflow preferred) {
        CurrentUser u = TenantContext.requireUser();
        boolean clinical = access.canAccessAdmission(u, a, Scope.CLINICAL);
        boolean financial = access.canAccessAdmission(u, a, Scope.FINANCIAL);
        AdmissionSummary adm = admissionService.summaries(List.of(a), clinical).get(0);
        List<DischargeWorkflow> all = workflows.findByAdmissionIdOrderByInitiatedAtDesc(a.getId());
        DischargeWorkflow w = preferred != null ? preferred
                : all.stream().filter(DischargeWorkflowService::isOpen).findFirst().orElse(all.isEmpty() ? null : all.get(0));
        Names names = new Names(users);
        WorkflowView view = w == null ? null : workflowView(u, a, w, clinical, financial, names);
        DischargeWorkflow current = w;
        List<WorkflowHistoryItem> history = all.stream().filter(x -> current == null || !x.getId().equals(current.getId()))
                .map(x -> new WorkflowHistoryItem(x.getId(), x.getStatus(), x.getInitiatedAt(), names.of(x.getInitiatedBy()),
                        x.getCompletedAt(), x.getCancelledReason())).toList();
        boolean wfOpen = w != null && isOpen(w);
        boolean coordinator = mayCoordinate(u) && canView(u, a);
        String initiateBlocked = null;
        boolean hasBed = bedAssignments.findByAdmissionIdAndReleasedAtIsNull(a.getId()).isPresent();
        if (!coordinator) {
            initiateBlocked = "Discharge planning is started by the care team or discharge coordination.";
        } else if (a.getStatus() == Admission.Status.PENDING_BED) {
            initiateBlocked = "The patient is still awaiting a bed.";
        } else if (a.getStatus() == Admission.Status.DISCHARGED) {
            initiateBlocked = "The patient has been discharged.";
        } else if (a.getStatus() == Admission.Status.CANCELLED) {
            initiateBlocked = "The admission was cancelled.";
        } else if (wfOpen) {
            initiateBlocked = "A discharge workflow is already in progress.";
        } else if (!hasBed) {
            initiateBlocked = "The patient has no active bed assignment.";
        }
        boolean canAuthorize = wfOpen && u.role() == Role.DOCTOR && u.has(Permissions.DISCHARGE_CLINICAL_AUTHORIZE)
                && clinical && isTreatingDoctor(u, a);
        boolean canClear = wfOpen && u.has(Permissions.DISCHARGE_FINANCIAL_CLEAR) && financial
                && w.getFinancialClearance() != DischargeWorkflow.FinancialClearance.CLEARED;
        boolean canComplete = wfOpen && u.has(Permissions.DISCHARGE_COMPLETE)
                && (u.role() == Role.NURSE && clinical || u.role() == Role.HOSPITAL_ADMIN && canView(u, a));
        boolean canSummary = u.has(Permissions.DISCHARGE_SUMMARY_WRITE) && u.role() == Role.DOCTOR && clinical;
        boolean canFollow = u.has(Permissions.FOLLOWUP_MANAGE) && clinical;
        Actions actions = new Actions(initiateBlocked == null, wfOpen && coordinator, wfOpen && coordinator, canAuthorize,
                canClear, canComplete, canSummary, canFollow, initiateBlocked);
        SummaryStatusView summary = summaries.findByAdmissionId(a.getId())
                .map(s -> new SummaryStatusView(s.getId(), s.getStatus(), s.isReleasedToPatient(), names.of(s.getFinalizedBy()), s.getFinalizedAt()))
                .orElse(null);
        int fu = (int) followUps.findByAdmissionIdOrderByScheduledAtAsc(a.getId()).stream()
                .filter(f -> f.getStatus() != FollowUp.Status.CANCELLED).count();
        return new DischargeDetail(adm, view, history, actions, bedTurnover(a), summary, fu, clinical, financial);
    }

    private BedTurnover bedTurnover(Admission a) {
        List<BedAssignment> stays = bedAssignments.findByAdmissionIdOrderByAssignedAtAsc(a.getId());
        if (stays.isEmpty()) {
            return null;
        }
        BedAssignment last = stays.get(stays.size() - 1);
        return beds.findById(last.getBedId()).map(b -> new BedTurnover(b.getId(), admissionService.describeBed(b.getId()),
                b.getStatus(), b.getStatusChangedAt(), last.getReleasedAt() != null)).orElse(null);
    }

    WorkflowView workflowView(CurrentUser u, Admission a, DischargeWorkflow w, boolean clinical, boolean financial, Names names) {
        List<DischargeStep> list = steps.findByWorkflowIdOrderByStepOrder(w.getId());
        Snapshot snap = checks.load(w, a);
        boolean requireClearance = requireClearance();
        Evaluation ev = checks.evaluateAll(w, list, snap, clinical, requireClearance);
        boolean open = isOpen(w);
        boolean scopeOk = canView(u, a);
        names.preload(list.stream().flatMap(s -> java.util.stream.Stream.of(s.getAssignedTo(), s.getUpdatedBy(), s.getCompletedBy())).toList());
        List<StepView> stepViews = list.stream().map(s -> {
            Result r = ev.results().get(s.getId());
            boolean canUpdate = open && scopeOk && u.has(Permissions.DISCHARGE_STEP_UPDATE)
                    && (u.role() == s.getResponsibleRole() || u.role() == Role.HOSPITAL_ADMIN)
                    && !DischargeChecks.isTerminal(s);
            boolean system = s.getAutoCheck() != null && s.getStatus() == DischargeStep.Status.COMPLETED && s.getUpdatedBy() == null;
            List<DischargeStep.Status> allowed = canUpdate ? allowedStatuses(u, s, r) : List.of();
            return new StepView(s.getId(), s.getTemplateCode(), s.getStepOrder(), s.getName(), s.getDescription(),
                    s.getCategory(), s.getResponsibleRole(), s.isRequired(), s.getAutoCheck(), s.getStatus(), s.getPriority(),
                    s.getAssignedTo(), names.of(s.getAssignedTo()), s.getBlockerReason(), s.getNextAction(), s.getNotes(),
                    s.getNotApplicableReason(), s.getUpdatedBy() != null ? names.of(s.getUpdatedBy())
                            : s.getStatus() == DischargeStep.Status.NOT_STARTED ? null : "System (automatic check)",
                    s.getUpdatedAt(), s.getCompletedBy() == null ? (s.getStatus() == DischargeStep.Status.COMPLETED ? "System" : null)
                    : names.of(s.getCompletedBy()), s.getCompletedAt(), system, DischargeChecks.isTerminal(s),
                    r == null ? null : new CheckView(r.met(), r.explanation()), canUpdate, allowed,
                    allowed.contains(DischargeStep.Status.NOT_APPLICABLE), s.getVersion());
        }).toList();
        ClinicalPanel cp = new ClinicalPanel(w.getClinicalDecision(), names.of(w.getClinicalAuthorizedBy()),
                w.getClinicalAuthorizedAt(), clinical ? w.getClinicalNotes() : null, clinical);
        Invoice inv = snap.invoice().orElse(null);
        BigDecimal current = null;
        String payStatus = null;
        if (financial && inv != null) {
            LedgerService.BalanceSummary bs = ledger.summarize(inv.getId());
            current = bs.balance();
            payStatus = bs.paymentStatus().name();
        }
        FinancialPanel fp = new FinancialPanel(w.getFinancialClearance(), names.of(w.getFinancialClearedBy()),
                w.getFinancialClearedAt(), financial || u.role() == Role.HOSPITAL_ADMIN ? w.getFinancialExceptionReason() : null,
                financial ? w.getBalanceAtClearance() : null, inv == null ? null : inv.getId(),
                inv == null ? null : inv.getInvoiceNumber(), inv == null ? null : inv.getStatus(), current, payStatus,
                financial, requireClearance, settings.getBool(SettingsService.DISCHARGE_ALLOW_FINANCIAL_EXCEPTION));
        long ageHours = Duration.between(w.getInitiatedAt(), w.getCompletedAt() != null ? w.getCompletedAt() : Instant.now()).toHours();
        return new WorkflowView(w.getId(), w.getAdmissionId(), w.getStatus(), w.getPlannedDischargeAt(),
                names.of(w.getInitiatedBy()), w.getInitiatedAt(), ev.stage(), ev.stageLabel(), ageHours, ev.progress(),
                stepViews, ev.blockers(), cp, fp, w.getDischargeDisposition(), w.getActualDepartureAt(),
                names.of(w.getCompletedBy()), w.getCompletedAt(), w.getCancelledReason(), ev.readiness(), w.getVersion());
    }

    /** Compact board row for a workflow (caller has already checked access). */
    public WorkflowListItem listItem(CurrentUser u, DischargeWorkflow w, Admission a, AdmissionSummary adm, Names names) {
        boolean clinical = access.canAccessAdmission(u, a, Scope.CLINICAL);
        List<DischargeStep> list = steps.findByWorkflowIdOrderByStepOrder(w.getId());
        Evaluation ev = checks.evaluateAll(w, list, checks.load(w, a), clinical, requireClearance());
        long ageHours = Duration.between(w.getInitiatedAt(), w.getCompletedAt() != null ? w.getCompletedAt() : Instant.now()).toHours();
        int blocked = (int) list.stream().filter(s -> s.getStatus() == DischargeStep.Status.BLOCKED).count();
        int urgent = (int) ev.blockers().stream().filter(b -> b.priority() == DischargeStep.Priority.URGENT
                || b.priority() == DischargeStep.Priority.HIGH || b.stepStatus() == DischargeStep.Status.BLOCKED).count();
        String ward = adm.location() != null ? adm.location().wardName() : null;
        String bed = adm.location() != null ? "Rm " + adm.location().roomNumber() + " · Bed " + adm.location().bedNumber() : null;
        return new WorkflowListItem(w.getId(), a.getId(), a.getAdmissionNumber(), adm.patient(), ward, bed,
                adm.departmentName(), adm.attendingDoctorName(), w.getStatus(), ev.stage(), ev.stageLabel(), ageHours,
                w.getInitiatedAt(), w.getPlannedDischargeAt(), ev.blockers().size(), blocked, urgent, ev.progress(),
                w.getClinicalDecision(), w.getFinancialClearance(),
                ev.blockers().stream().limit(3).map(DischargeDtos.BlockerView::title).toList(),
                w.getCompletedAt(), w.getDischargeDisposition());
    }

    // ================================================================== helpers

    private void requireStepActor(CurrentUser u, Admission a, DischargeStep s) {
        if (!u.has(Permissions.DISCHARGE_STEP_UPDATE)) {
            throw ApiException.forbidden("You do not have permission to update discharge steps.");
        }
        Scope scope = viewScope(u);
        if (scope == null) {
            throw ApiException.forbidden("You do not have permission to update discharge steps.");
        }
        access.requireAdmission(a.getId(), scope);
        if (u.role() != s.getResponsibleRole() && u.role() != Role.HOSPITAL_ADMIN) {
            throw ApiException.forbidden("'" + s.getName() + "' is owned by the " + roleLabel(s.getResponsibleRole()) + " team.");
        }
    }

    /** Status transitions the user may apply to a step (mirrors the rules enforced in {@link #updateStep}). */
    private static List<DischargeStep.Status> allowedStatuses(CurrentUser u, DischargeStep s, Result check) {
        List<DischargeStep.Status> out = new ArrayList<>();
        boolean clinicalOnlyDoctor = s.getCategory() == DischargeStepTemplate.Category.CLINICAL && u.role() != Role.DOCTOR;
        boolean auto = s.getAutoCheck() != null;
        boolean met = check != null && check.met();
        for (DischargeStep.Status target : DischargeStep.Status.values()) {
            if (target == s.getStatus()) {
                continue;
            }
            switch (target) {
                case COMPLETED -> {
                    if (!clinicalOnlyDoctor && (!auto || met)) {
                        out.add(target);
                    }
                }
                case NOT_APPLICABLE -> {
                    if (!clinicalOnlyDoctor && (!auto || DischargeChecks.CLINICIAN_MAY_MARK_NA.contains(s.getAutoCheck()) && u.role() == Role.DOCTOR)) {
                        out.add(target);
                    }
                }
                case IN_PROGRESS, NOT_STARTED -> {
                    if (!(s.getStatus() == DischargeStep.Status.COMPLETED && auto && met)) {
                        out.add(target);
                    }
                }
                case BLOCKED -> {
                    if (!(s.getStatus() == DischargeStep.Status.COMPLETED && auto && met)) {
                        out.add(target);
                    }
                }
            }
        }
        return out;
    }

    private static void requireDoctorForClinical(CurrentUser u, DischargeStep s, String verb) {
        if (s.getCategory() == DischargeStepTemplate.Category.CLINICAL && u.role() != Role.DOCTOR) {
            throw ApiException.forbidden("Clinical steps can only be " + verb + " by a doctor.");
        }
    }

    boolean isTreatingDoctor(CurrentUser u, Admission a) {
        return u.role() == Role.DOCTOR && (u.userId().equals(a.getAttendingDoctorId())
                || careTeam.existsByAdmissionIdAndUserIdAndRemovedAtIsNull(a.getId(), u.userId()));
    }

    private static void validatePlanned(Instant planned) {
        if (planned != null && planned.isBefore(Instant.now().minus(Duration.ofHours(1)))) {
            throw ApiException.badRequest("INVALID_PLANNED_TIME", "The planned discharge time cannot be in the past.");
        }
        if (planned != null && planned.isAfter(Instant.now().plus(Duration.ofDays(90)))) {
            throw ApiException.badRequest("INVALID_PLANNED_TIME", "The planned discharge time is too far in the future.");
        }
    }

    static Bed.Status parseTurnover(String v) {
        try {
            Bed.Status s = Bed.Status.valueOf(v == null ? "CLEANING" : v.trim().toUpperCase());
            return s == Bed.Status.MAINTENANCE ? s : Bed.Status.CLEANING;
        } catch (IllegalArgumentException e) {
            return Bed.Status.CLEANING;
        }
    }

    static String linkFor(Role role, UUID admissionId) {
        return switch (role) {
            case DOCTOR -> "/doctor/discharge/" + admissionId;
            case NURSE -> "/nurse/discharge/" + admissionId;
            case BILLING -> "/billing/discharge";
            case PHARMACIST -> "/pharmacy/prescriptions";
            case PATIENT -> "/patient/discharge";
            default -> "/admin/discharges/" + admissionId;
        };
    }

    static String roleLabel(Role r) {
        return switch (r) {
            case DOCTOR -> "doctor";
            case NURSE -> "nursing";
            case BILLING -> "billing";
            case PHARMACIST -> "pharmacy";
            case HOSPITAL_ADMIN -> "administration";
            default -> r.name().toLowerCase();
        };
    }

    static String dispositionLabel(Admission.Disposition d) {
        return switch (d) {
            case HOME -> "home";
            case TRANSFERRED -> "transferred to another facility";
            case LAMA -> "left against medical advice";
            case DECEASED -> "deceased";
            case ABSCONDED -> "absconded";
            case OTHER -> "other";
        };
    }

    static String statusLabel(DischargeStep.Status s) {
        return switch (s) {
            case NOT_STARTED -> "not started";
            case IN_PROGRESS -> "in progress";
            case BLOCKED -> "blocked";
            case COMPLETED -> "completed";
            case NOT_APPLICABLE -> "not applicable";
        };
    }

    static NotificationRequest req(String category, Notification.Priority priority, String title, String message,
                                   String link, UUID workflowId, String dedupeKey) {
        return NotificationRequest.builder().category(category).priority(priority).title(title)
                .message(truncate(message, 1900)).link(link).entityType("DISCHARGE_WORKFLOW").entityId(workflowId)
                .dedupeKey(dedupeKey).build();
    }

    static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    /** Small per-request user-name cache. */
    public static final class Names {
        private final UserRepository users;
        private final Map<UUID, String> cache = new HashMap<>();

        public Names(UserRepository users) {
            this.users = users;
        }

        public void preload(Collection<UUID> ids) {
            Set<UUID> missing = new HashSet<>();
            for (UUID id : ids) {
                if (id != null && !cache.containsKey(id)) {
                    missing.add(id);
                }
            }
            if (!missing.isEmpty()) {
                users.findAllById(missing).forEach(x -> cache.put(x.getId(), x.getFullName()));
            }
        }

        public String of(UUID id) {
            if (id == null) {
                return null;
            }
            return cache.computeIfAbsent(id, k -> users.findById(k).map(User::getFullName).orElse("Unknown user"));
        }
    }
}
