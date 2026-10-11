package com.careflow.hms.discharge;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.discharge.DischargeDtos.AcknowledgeRequest;
import com.careflow.hms.discharge.DischargeDtos.PortalAcknowledgement;
import com.careflow.hms.discharge.DischargeDtos.PortalDischarge;
import com.careflow.hms.discharge.DischargeDtos.PortalDischargeEnvelope;
import com.careflow.hms.discharge.DischargeDtos.PortalFollowUp;
import com.careflow.hms.discharge.DischargeDtos.PortalFollowUps;
import com.careflow.hms.discharge.DischargeDtos.PortalMedication;
import com.careflow.hms.discharge.DischargeDtos.PortalStep;
import com.careflow.hms.discharge.DischargeDtos.PortalSummary;
import com.careflow.hms.discharge.DischargeDtos.Progress;
import com.careflow.hms.discharge.DischargeWorkflowService.Names;
import com.careflow.hms.facility.Department;
import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.medication.Medication;
import com.careflow.hms.medication.MedicationOrder;
import com.careflow.hms.medication.MedicationOrderRepository;
import com.careflow.hms.medication.MedicationRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.patient.PatientAccessGrantRepository;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.AccessPolicy.Scope;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Permissions;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Patient portal view of discharge and follow-up. Only patient-appropriate content is returned: plain-language
 * step progress (no internal notes, assignees or blocker details), the summary and take-home medicines once
 * released by the doctor, and patient-facing follow-ups.
 */
@Service
@RequiredArgsConstructor
public class DischargePortalService {

    private static final Map<String, String> FRIENDLY = Map.ofEntries(
            Map.entry("CLINICAL_DECISION", "Your doctor approves your discharge"),
            Map.entry("CLINICAL_REVIEWS", "Final checks by your care team"),
            Map.entry("RESULTS_REVIEWED", "Your test results are reviewed"),
            Map.entry("MED_RECONCILIATION", "Your medicines are checked by the pharmacy"),
            Map.entry("DISCHARGE_PRESCRIPTIONS", "Take-home medicines are prescribed"),
            Map.entry("NURSING_EDUCATION", "Your nurse explains your care at home"),
            Map.entry("FOLLOW_UP", "Follow-up appointments are arranged"),
            Map.entry("DOCUMENTATION", "Your discharge summary is prepared"),
            Map.entry("FINAL_BILL", "Your final bill is prepared"),
            Map.entry("FINANCIAL_CLEARANCE", "Billing is completed"),
            Map.entry("PATIENT_ACK", "You confirm you understand your instructions"),
            Map.entry("DEPARTURE", "You leave the hospital"));

    private final DischargeWorkflowRepository workflows;
    private final DischargeStepRepository steps;
    private final DischargeSummaryRepository summaries;
    private final FollowUpRepository followUps;
    private final AdmissionRepository admissions;
    private final MedicationOrderRepository medicationOrders;
    private final MedicationRepository medications;
    private final DepartmentRepository departments;
    private final UserRepository users;
    private final PatientAccessGrantRepository grants;
    private final AccessPolicy access;
    private final DischargeWorkflowService workflowService;
    private final NotificationService notifications;
    private final TimelineService timeline;
    private final AuditService audit;

    private record Focus(Admission admission, DischargeWorkflow workflow) {
    }

    @Transactional(readOnly = true)
    public PortalDischargeEnvelope discharge(UUID patientId) {
        requirePortal(patientId);
        return new PortalDischargeEnvelope(focus(patientId).map(this::build).orElse(null));
    }

    @Transactional(readOnly = true)
    public PortalFollowUps followUps(UUID patientId) {
        requirePortal(patientId);
        List<FollowUp> list = followUps.findByPatientIdOrderByScheduledAtAsc(patientId).stream()
                .filter(FollowUpNotifier::patientFacing).toList();
        Names names = new Names(users);
        Map<UUID, String> depts = list.isEmpty() ? Map.of() : departments.findByOrganizationIdOrderByName(list.get(0).getOrganizationId())
                .stream().collect(Collectors.toMap(Department::getId, Department::getName));
        Function<FollowUp, PortalFollowUp> map = f -> new PortalFollowUp(f.getId(), f.getFollowUpType(), f.getTitle(),
                f.getDescription(), f.getScheduledAt(), depts.get(f.getDepartmentId()), names.of(f.getClinicianId()),
                f.getLocation(), f.getStatus(), f.getPatientNotifiedAt(), f.getCompletedAt());
        List<PortalFollowUp> upcoming = list.stream().filter(f -> FollowUpService.ACTIVE.contains(f.getStatus()))
                .sorted(Comparator.comparing(f -> f.getScheduledAt() == null ? Instant.MAX : f.getScheduledAt()))
                .map(map).toList();
        List<PortalFollowUp> past = list.stream().filter(f -> !FollowUpService.ACTIVE.contains(f.getStatus()))
                .sorted(Comparator.comparing((FollowUp f) -> f.getScheduledAt() == null ? f.getCreatedAt() : f.getScheduledAt()).reversed())
                .map(map).toList();
        return new PortalFollowUps(upcoming, past);
    }

    @Transactional
    public PortalDischargeEnvelope acknowledge(UUID patientId, AcknowledgeRequest r) {
        CurrentUser u = requirePortal(patientId);
        if (!r.confirmed()) {
            throw ApiException.badRequest("CONFIRMATION_REQUIRED", "Please confirm that you have read and understood your discharge instructions.");
        }
        Focus f = focus(patientId).orElseThrow(() -> ApiException.rule("NO_DISCHARGE", "There is no discharge to acknowledge."));
        if (f.workflow() == null || f.workflow().getStatus() == DischargeWorkflow.Status.CANCELLED) {
            throw ApiException.rule("NO_DISCHARGE", "There is no discharge to acknowledge.");
        }
        DischargeWorkflow w = workflows.lockById(f.workflow().getId(), f.admission().getOrganizationId()).orElseThrow();
        DischargeStep step = ackStep(w.getId()).orElseThrow(() ->
                ApiException.rule("ACK_NOT_CONFIGURED", "This hospital does not record patient acknowledgement in the portal."));
        boolean released = summaries.findByAdmissionId(f.admission().getId()).map(DischargeSummary::isReleasedToPatient).orElse(false);
        if (!released) {
            throw ApiException.rule("INSTRUCTIONS_NOT_RELEASED", "Your discharge instructions have not been shared with you yet.");
        }
        if (step.getStatus() == DischargeStep.Status.COMPLETED) {
            throw ApiException.conflict("ALREADY_ACKNOWLEDGED", "Your acknowledgement has already been recorded.");
        }
        String who = u.fullName() + (patientId.equals(u.patientId()) ? " (patient)"
                : grants.findByPatientIdAndGranteeUserIdAndRevokedAtIsNull(patientId, u.userId())
                .map(g -> " (" + g.getRelationship().toLowerCase() + ")").orElse(" (caregiver)"));
        String comment = DischargeWorkflowService.trimToNull(r.comment());
        Instant now = Instant.now();
        step.setStatus(DischargeStep.Status.COMPLETED);
        step.setCompletedBy(u.userId());
        step.setCompletedAt(now);
        step.setUpdatedBy(u.userId());
        step.setUpdatedAt(now);
        step.setBlockerReason(null);
        step.setNotes("Acknowledged in the patient portal by " + who + (comment == null ? "." : ". Comment: " + comment));
        steps.saveAndFlush(step);
        Admission a = f.admission();
        timeline.record(TimelineService.Entry.builder()
                .patientId(patientId).admissionId(a.getId())
                .eventType("DISCHARGE_INSTRUCTIONS_ACKNOWLEDGED").category(TimelineEvent.Category.DISCHARGE)
                .title("Discharge instructions acknowledged").detail("Recorded in the patient portal by " + who)
                .status("COMPLETED").visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("DISCHARGE_STEP").entityId(step.getId()).build());
        var req = DischargeWorkflowService.req("DISCHARGE", Notification.Priority.NORMAL,
                "Patient acknowledged discharge instructions",
                a.getAdmissionNumber() + ": acknowledged by " + who + (comment == null ? "" : " - \"" + DischargeWorkflowService.truncate(comment, 200) + "\""),
                null, w.getId(), "DISCHARGE_ACK:" + step.getId());
        if (a.getStatus().isOpen()) {
            notifications.notifyWardNurses(a.getId(), withLink(req, "/nurse/discharge/" + a.getId()));
        }
        notifications.notifyCareTeam(a.getId(), Role.DOCTOR, withLink(req, "/doctor/discharge/" + a.getId()));
        audit.record("DISCHARGE_INSTRUCTIONS_ACKNOWLEDGED", "DISCHARGE_STEP", step.getId(), patientId, "Acknowledged by " + who);
        if (w.getStatus() == DischargeWorkflow.Status.IN_PROGRESS || w.getStatus() == DischargeWorkflow.Status.READY) {
            workflowService.syncWorkflow(w.getId());
        }
        return new PortalDischargeEnvelope(build(new Focus(a, w)));
    }

    // ------------------------------------------------------------------ helpers

    private CurrentUser requirePortal(UUID patientId) {
        CurrentUser u = TenantContext.requireUser();
        if (u.role() != Role.PATIENT || !u.has(Permissions.PORTAL_ACCESS)) {
            throw ApiException.forbidden("The patient portal is for patients and authorized caregivers.");
        }
        access.requirePatient(patientId, Scope.CLINICAL);
        return u;
    }

    /** Current open workflow, else the latest completed one, else the latest discharge with a released summary. */
    private Optional<Focus> focus(UUID patientId) {
        List<Admission> list = admissions.findByPatientIdOrderByAdmittedAtDesc(patientId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, Admission> byId = list.stream().collect(Collectors.toMap(Admission::getId, Function.identity()));
        List<DischargeWorkflow> wfs = workflows.findByAdmissionIdInOrderByInitiatedAtDesc(byId.keySet());
        Optional<DischargeWorkflow> open = wfs.stream().filter(w -> w.getStatus() == DischargeWorkflow.Status.IN_PROGRESS
                || w.getStatus() == DischargeWorkflow.Status.READY).findFirst();
        if (open.isPresent()) {
            return Optional.of(new Focus(byId.get(open.get().getAdmissionId()), open.get()));
        }
        Optional<DischargeWorkflow> done = wfs.stream().filter(w -> w.getStatus() == DischargeWorkflow.Status.COMPLETED)
                .max(Comparator.comparing(w -> w.getCompletedAt() == null ? w.getInitiatedAt() : w.getCompletedAt()));
        Optional<Admission> releasedOnly = list.stream().filter(a -> a.getStatus() == Admission.Status.DISCHARGED)
                .filter(a -> summaries.findByAdmissionId(a.getId()).map(DischargeSummary::isReleasedToPatient).orElse(false))
                .findFirst();
        if (done.isPresent()) {
            Admission da = byId.get(done.get().getAdmissionId());
            if (releasedOnly.isPresent() && releasedOnly.get().getAdmittedAt().isAfter(da.getAdmittedAt())) {
                return Optional.of(new Focus(releasedOnly.get(), null));
            }
            return Optional.of(new Focus(da, done.get()));
        }
        return releasedOnly.map(a -> new Focus(a, null));
    }

    private Optional<DischargeStep> ackStep(UUID workflowId) {
        List<DischargeStep> list = steps.findByWorkflowIdOrderByStepOrder(workflowId);
        return list.stream().filter(s -> "PATIENT_ACK".equals(s.getTemplateCode())).findFirst()
                .or(() -> list.stream().filter(s -> s.getCategory() == DischargeStepTemplate.Category.PATIENT && s.getAutoCheck() == null).findFirst());
    }

    private PortalDischarge build(Focus f) {
        Admission a = f.admission();
        DischargeWorkflow w = f.workflow();
        Names names = new Names(users);
        Optional<DischargeSummary> sum = summaries.findByAdmissionId(a.getId()).filter(DischargeSummary::isReleasedToPatient);
        List<PortalStep> portalSteps = List.of();
        Progress progress = null;
        PortalAcknowledgement ack = new PortalAcknowledgement(false, false, null, null, "No discharge checklist is recorded for this stay.");
        if (w != null) {
            List<DischargeStep> list = steps.findByWorkflowIdOrderByStepOrder(w.getId()).stream()
                    .filter(s -> !DischargeChecks.BED_RELEASED.equals(s.getAutoCheck())).toList();
            portalSteps = list.stream().filter(s -> s.getStatus() != DischargeStep.Status.NOT_APPLICABLE)
                    .map(s -> new PortalStep(FRIENDLY.getOrDefault(s.getTemplateCode(), s.getName()),
                            s.getStatus() == DischargeStep.Status.COMPLETED ? "DONE"
                                    : s.getStatus() == DischargeStep.Status.NOT_STARTED ? "WAITING" : "IN_PROGRESS",
                            s.getStatus() == DischargeStep.Status.COMPLETED ? "Done"
                                    : s.getStatus() == DischargeStep.Status.NOT_STARTED ? "Not started yet" : "In progress",
                            s.getStatus() == DischargeStep.Status.COMPLETED ? s.getCompletedAt() : null))
                    .toList();
            int total = portalSteps.size();
            int done = (int) portalSteps.stream().filter(s -> s.status().equals("DONE")).count();
            progress = new Progress(done, total, total == 0 ? 0 : (int) Math.round(done * 100.0 / total));
            Optional<DischargeStep> ackStep = ackStep(w.getId());
            if (ackStep.isEmpty()) {
                ack = new PortalAcknowledgement(false, false, null, null, "Acknowledgement is not part of this hospital's checklist.");
            } else if (ackStep.get().getStatus() == DischargeStep.Status.COMPLETED) {
                ack = new PortalAcknowledgement(false, true, ackStep.get().getCompletedAt(), names.of(ackStep.get().getCompletedBy()), null);
            } else if (w.getStatus() == DischargeWorkflow.Status.CANCELLED) {
                ack = new PortalAcknowledgement(false, false, null, null, "Your discharge has been postponed.");
            } else if (sum.isEmpty()) {
                ack = new PortalAcknowledgement(false, false, null, null,
                        "You can confirm your instructions once your doctor shares your discharge summary.");
            } else {
                ack = new PortalAcknowledgement(true, false, null, null, null);
            }
        }
        String headline;
        String wfStatus = w == null ? "COMPLETED" : w.getStatus().name();
        if (w == null || w.getStatus() == DischargeWorkflow.Status.COMPLETED) {
            headline = "You have been discharged from hospital";
        } else if (w.getStatus() == DischargeWorkflow.Status.READY) {
            headline = "Everything is ready for you to go home. Your nurse will confirm your departure.";
        } else if (w.getClinicalDecision() == DischargeWorkflow.ClinicalDecision.AUTHORIZED) {
            headline = "Your doctor has approved your discharge. The team is completing the remaining steps.";
        } else {
            headline = "Your care team is preparing your discharge.";
        }
        boolean showMeds = sum.isPresent();
        List<MedicationOrder> orders = medicationOrders.findByAdmissionIdOrderByPrescribedAtDesc(a.getId()).stream()
                .filter(MedicationOrder::isDischargeMedication)
                .filter(o -> o.getStatus() == MedicationOrder.Status.ACTIVE || o.getStatus() == MedicationOrder.Status.COMPLETED)
                .filter(o -> showMeds || o.isReleasedToPatient()).toList();
        Map<UUID, Medication> meds = medications.findByIdIn(orders.stream().map(MedicationOrder::getMedicationId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Medication::getId, Function.identity()));
        List<PortalMedication> pm = orders.stream().map(o -> {
            Medication m = meds.get(o.getMedicationId());
            return new PortalMedication(m == null ? "Medicine" : m.getGenericName() + " " + m.getStrength(),
                    o.getDose() + " " + o.getDoseUnit(), o.getRoute().name().toLowerCase(),
                    DischargeSummaryService.frequencyText(o.getFrequency()), o.getDurationDays(), o.getPatientInstructions());
        }).toList();
        PortalSummary ps = sum.map(s -> new PortalSummary(a.getId(), s.getStatus().name(), s.getFinalizedAt(), names.of(s.getFinalizedBy()),
                s.getDiagnosesText(), s.getTreatmentSummary(), s.getProceduresText(), s.getRelevantResults(),
                s.getConditionAtDischarge(), s.getDischargeMedications(), s.getDischargeInstructions(), s.getFollowUpPlan(),
                s.getWarningSigns())).orElse(null);
        int upcoming = (int) followUps.findByPatientIdOrderByScheduledAtAsc(a.getPatientId()).stream()
                .filter(FollowUpNotifier::patientFacing).filter(x -> FollowUpService.ACTIVE.contains(x.getStatus())).count();
        String dept = departments.findById(a.getDepartmentId()).map(Department::getName).orElse(null);
        return new PortalDischarge(a.getId(), a.getAdmissionNumber(), a.getStatus(), a.getAdmittedAt(), a.getDischargedAt(),
                dept, names.of(a.getAttendingDoctorId()), wfStatus, headline, w == null ? null : w.getPlannedDischargeAt(),
                w == null ? null : w.getInitiatedAt(), w == null ? a.getDischargedAt() : w.getCompletedAt(), progress,
                w != null && w.getClinicalDecision() == DischargeWorkflow.ClinicalDecision.AUTHORIZED,
                w != null && w.getClinicalDecision() == DischargeWorkflow.ClinicalDecision.AUTHORIZED ? w.getClinicalAuthorizedAt() : null,
                portalSteps, ps, pm, ps == null ? null : ps.warningSigns(), ack, upcoming);
    }

    private static com.careflow.hms.notification.NotificationRequest withLink(com.careflow.hms.notification.NotificationRequest r, String link) {
        return com.careflow.hms.notification.NotificationRequest.builder().category(r.category()).priority(r.priority())
                .title(r.title()).message(r.message()).link(link).entityType(r.entityType()).entityId(r.entityId())
                .dedupeKey(r.dedupeKey()).build();
    }
}
