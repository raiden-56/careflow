package com.careflow.hms.discharge;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.clinical.LabOrder;
import com.careflow.hms.clinical.LabOrderRepository;
import com.careflow.hms.clinical.LabResult;
import com.careflow.hms.clinical.LabResultRepository;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.discharge.DischargeDtos.DeliveryView;
import com.careflow.hms.discharge.DischargeDtos.FollowUpOptions;
import com.careflow.hms.discharge.DischargeDtos.FollowUpOutcomeRequest;
import com.careflow.hms.discharge.DischargeDtos.FollowUpRequest;
import com.careflow.hms.discharge.DischargeDtos.FollowUpView;
import com.careflow.hms.discharge.DischargeDtos.OptionView;
import com.careflow.hms.discharge.DischargeDtos.PendingResultView;
import com.careflow.hms.discharge.DischargeWorkflowService.Names;
import com.careflow.hms.facility.Department;
import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Follow-up and continuity of care: appointments, result reviews, instructions and tasks after discharge. */
@Service
@RequiredArgsConstructor
public class FollowUpService {

    static final Set<FollowUp.Status> ACTIVE = EnumSet.of(FollowUp.Status.PLANNED, FollowUp.Status.SCHEDULED);
    private static final Set<LabOrder.Status> OUTSTANDING_LAB = EnumSet.of(LabOrder.Status.ORDERED,
            LabOrder.Status.COLLECTED, LabOrder.Status.IN_PROGRESS, LabOrder.Status.RESULTED);

    private final FollowUpRepository followUps;
    private final FollowUpDeliveryRepository deliveries;
    private final AdmissionRepository admissions;
    private final PatientRepository patients;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final LabOrderRepository labOrders;
    private final LabResultRepository labResults;
    private final AccessPolicy access;
    private final FollowUpNotifier notifier;
    private final DischargeWorkflowService workflowService;
    private final NotificationService notifications;
    private final TimelineService timeline;
    private final AuditService audit;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<FollowUpView> forAdmission(UUID admissionId) {
        Admission a = access.requireAdmission(admissionId, Scope.CLINICAL);
        return views(followUps.findByAdmissionIdOrderByScheduledAtAsc(a.getId()));
    }

    /** The current clinician's follow-up responsibilities (responsible user or named clinician). */
    @Transactional(readOnly = true)
    public List<FollowUpView> mine(boolean includeClosed) {
        CurrentUser u = TenantContext.requireUser();
        Set<FollowUp.Status> statuses = includeClosed ? EnumSet.allOf(FollowUp.Status.class) : ACTIVE;
        List<FollowUp> list = new ArrayList<>(followUps.findForClinician(u.organizationId(), u.userId(), statuses));
        list.sort(Comparator.comparing((FollowUp f) -> !ACTIVE.contains(f.getStatus()))
                .thenComparing(f -> f.getScheduledAt() == null ? Instant.MAX : f.getScheduledAt()));
        if (includeClosed && list.size() > 200) {
            list = list.subList(0, 200);
        }
        return views(list);
    }

    /** Investigations still awaiting review for the doctor's discharged / discharging patients. */
    @Transactional(readOnly = true)
    public List<PendingResultView> pendingResults() {
        CurrentUser u = TenantContext.requireUser();
        if (u.role() != Role.DOCTOR) {
            throw ApiException.forbidden("Pending result reviews are a clinician worklist.");
        }
        UUID org = u.organizationId();
        Map<UUID, Admission> adm = new LinkedHashMap<>();
        admissions.search(org, EnumSet.of(Admission.Status.DISCHARGE_IN_PROGRESS, Admission.Status.DISCHARGED), null,
                        u.userId(), null, PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "admittedAt")))
                .forEach(a -> adm.put(a.getId(), a));
        Instant horizon = Instant.now().minus(Duration.ofDays(120));
        adm.values().removeIf(a -> a.getDischargedAt() != null && a.getDischargedAt().isBefore(horizon));
        Map<UUID, LabOrder> orders = new LinkedHashMap<>();
        if (!adm.isEmpty()) {
            labOrders.findByAdmissionIdInAndStatusIn(adm.keySet(), OUTSTANDING_LAB).forEach(o -> orders.put(o.getId(), o));
        }
        // Results explicitly handed to me for review after discharge.
        List<FollowUp> mineReviews = followUps.findForClinician(org, u.userId(), ACTIVE).stream()
                .filter(f -> f.getFollowUpType() == FollowUp.FollowUpType.RESULT_REVIEW && f.getLabOrderId() != null).toList();
        labOrders.findAllById(mineReviews.stream().map(FollowUp::getLabOrderId).toList()).forEach(o -> orders.putIfAbsent(o.getId(), o));
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<UUID, Admission> admAll = new HashMap<>(adm);
        admissions.findByIdIn(orders.values().stream().map(LabOrder::getAdmissionId).filter(id -> !admAll.containsKey(id)).toList())
                .forEach(a -> admAll.put(a.getId(), a));
        Map<UUID, FollowUp> handover = followUps.findByLabOrderIdIn(orders.keySet()).stream()
                .filter(f -> f.getFollowUpType() == FollowUp.FollowUpType.RESULT_REVIEW && f.getStatus() != FollowUp.Status.CANCELLED)
                .collect(Collectors.toMap(FollowUp::getLabOrderId, Function.identity(), (x, y) -> ACTIVE.contains(x.getStatus()) ? x : y));
        Set<UUID> abnormal = labResults.findByLabOrderIdIn(orders.keySet()).stream()
                .filter(r -> r.getFlag() != LabResult.Flag.NORMAL && r.getStatus() != LabResult.Status.ENTERED_IN_ERROR)
                .map(LabResult::getLabOrderId).collect(Collectors.toSet());
        Map<UUID, Patient> pts = patients.findByIdIn(admAll.values().stream().map(Admission::getPatientId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Patient::getId, Function.identity()));
        Names names = new Names(users);
        return orders.values().stream()
                .filter(o -> admAll.containsKey(o.getAdmissionId()))
                .filter(o -> OUTSTANDING_LAB.contains(o.getStatus())
                        || handover.containsKey(o.getId()) && ACTIVE.contains(handover.get(o.getId()).getStatus()))
                .map(o -> {
                    Admission a = admAll.get(o.getAdmissionId());
                    Patient p = pts.get(a.getPatientId());
                    FollowUp f = handover.get(o.getId());
                    return new PendingResultView(o.getId(), o.getOrderNumber(), o.getTestName(), o.getCategory(), o.getStatus(),
                            o.getPriority(), o.getOrderedAt(), a.getId(), a.getAdmissionNumber(), a.getPatientId(),
                            p == null ? null : p.fullName(), p == null ? null : p.getMrn(), a.getStatus(), a.getDischargedAt(),
                            abnormal.contains(o.getId()), f == null ? null : f.getId(),
                            f == null ? null : names.of(f.getResponsibleUserId()), f == null ? null : f.getStatus());
                })
                .sorted(Comparator.comparing((PendingResultView v) -> v.status() != LabOrder.Status.RESULTED)
                        .thenComparing(PendingResultView::orderedAt))
                .toList();
    }

    /** Pick-lists for the follow-up editor of an admission. */
    @Transactional(readOnly = true)
    public FollowUpOptions options(UUID admissionId) {
        Admission a = access.requireAdmission(admissionId, Scope.CLINICAL);
        UUID org = a.getOrganizationId();
        List<OptionView> doctors = users.findByOrganizationIdAndRoleAndStatus(org, Role.DOCTOR, User.Status.ACTIVE).stream()
                .sorted(Comparator.comparing(User::getFullName)).map(x -> new OptionView(x.getId(), x.getFullName(), "Doctor")).toList();
        List<OptionView> staff = new ArrayList<>(doctors);
        users.findByOrganizationIdAndRoleAndStatus(org, Role.NURSE, User.Status.ACTIVE).stream()
                .sorted(Comparator.comparing(User::getFullName)).forEach(x -> staff.add(new OptionView(x.getId(), x.getFullName(), "Nurse")));
        List<OptionView> depts = departments.findByOrganizationIdOrderByName(org).stream().filter(Department::isActive)
                .map(d -> new OptionView(d.getId(), d.getName(), d.getSpecialty())).toList();
        List<OptionView> labs = labOrders.findByAdmissionIdAndStatusIn(a.getId(), OUTSTANDING_LAB).stream()
                .map(o -> new OptionView(o.getId(), o.getTestName() + " · " + o.getOrderNumber(), humanize(o.getStatus().name()))).toList();
        return new FollowUpOptions(doctors, staff, depts, labs);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public FollowUpView create(UUID admissionId, FollowUpRequest r) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = access.requireAdmission(admissionId, Scope.CLINICAL);
        if (a.getStatus() == Admission.Status.CANCELLED || a.getStatus() == Admission.Status.PENDING_BED) {
            throw ApiException.rule("ADMISSION_NOT_ELIGIBLE", "Follow-ups are planned for admitted or discharged patients.");
        }
        FollowUp f = new FollowUp();
        f.setAdmissionId(a.getId());
        f.setPatientId(a.getPatientId());
        f.setCreatedBy(u.userId());
        f.setCreatedAt(Instant.now());
        apply(f, a, r, u, true);
        f.setStatus(f.getScheduledAt() == null ? FollowUp.Status.PLANNED : FollowUp.Status.SCHEDULED);
        f = followUps.saveAndFlush(f);
        boolean notifyPatient = FollowUpNotifier.patientFacing(f) && !Boolean.FALSE.equals(r.notifyPatient());
        if (notifyPatient) {
            if (notifier.notify(f, FollowUpDelivery.Event.SCHEDULED).anySent()) {
                f.setPatientNotifiedAt(Instant.now());
            }
        }
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("FOLLOW_UP_PLANNED").category(TimelineEvent.Category.DISCHARGE)
                .title((f.getFollowUpType() == FollowUp.FollowUpType.RESULT_REVIEW ? "Result review planned: " : "Follow-up planned: ") + f.getTitle())
                .detail(f.getScheduledAt() == null ? null : "Scheduled for " + f.getScheduledAt())
                .status(f.getStatus().name())
                .visibility(FollowUpNotifier.patientFacing(f) ? TimelineEvent.Visibility.PATIENT : TimelineEvent.Visibility.CARE_TEAM)
                .entityType("FOLLOW_UP").entityId(f.getId()).build());
        notifyResponsible(f, a, u, "assigned to you");
        audit.record("FOLLOW_UP_CREATED", "FOLLOW_UP", f.getId(), a.getPatientId(), f.getFollowUpType() + ": " + f.getTitle());
        workflowService.syncForAdmission(a.getId());
        return views(List.of(f)).get(0);
    }

    @Transactional
    public FollowUpView update(UUID id, FollowUpRequest r) {
        CurrentUser u = TenantContext.requireUser();
        FollowUp f = require(id);
        Admission a = requireFollowUpAccess(u, f);
        if (!ACTIVE.contains(f.getStatus())) {
            throw ApiException.rule("FOLLOW_UP_CLOSED", "This follow-up is " + f.getStatus().name().toLowerCase() + " and can no longer be edited.");
        }
        if (r.version() == null || !Objects.equals(r.version(), f.getVersion())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This follow-up was changed by someone else. Reload and try again.");
        }
        Instant previous = f.getScheduledAt();
        UUID previousResponsible = f.getResponsibleUserId();
        apply(f, a, r, u, !Objects.equals(previous, r.scheduledAt()));
        f.setStatus(f.getScheduledAt() == null ? FollowUp.Status.PLANNED : FollowUp.Status.SCHEDULED);
        f.setUpdatedAt(Instant.now());
        boolean rescheduled = !Objects.equals(previous, f.getScheduledAt());
        if (rescheduled) {
            f.setReminderSentAt(null);
        }
        followUps.saveAndFlush(f);
        boolean notifyPatient = FollowUpNotifier.patientFacing(f) && !Boolean.FALSE.equals(r.notifyPatient());
        if (rescheduled && notifyPatient) {
            FollowUpDelivery.Event ev = f.getPatientNotifiedAt() != null ? FollowUpDelivery.Event.RESCHEDULED : FollowUpDelivery.Event.SCHEDULED;
            if (notifier.notify(f, ev).anySent()) {
                f.setPatientNotifiedAt(Instant.now());
            }
        }
        if (a != null && rescheduled) {
            timeline.record(TimelineService.Entry.builder()
                    .patientId(f.getPatientId()).admissionId(a.getId())
                    .eventType("FOLLOW_UP_RESCHEDULED").category(TimelineEvent.Category.DISCHARGE)
                    .title("Follow-up rescheduled: " + f.getTitle())
                    .detail(f.getScheduledAt() == null ? "Date to be confirmed" : "Now scheduled for " + f.getScheduledAt())
                    .status(f.getStatus().name())
                    .visibility(FollowUpNotifier.patientFacing(f) ? TimelineEvent.Visibility.PATIENT : TimelineEvent.Visibility.CARE_TEAM)
                    .entityType("FOLLOW_UP").entityId(f.getId()).build());
        }
        if (!Objects.equals(previousResponsible, f.getResponsibleUserId())) {
            notifyResponsible(f, a, u, "assigned to you");
        }
        audit.record("FOLLOW_UP_UPDATED", "FOLLOW_UP", f.getId(), f.getPatientId(), f.getTitle() + (rescheduled ? " (rescheduled)" : ""));
        if (f.getAdmissionId() != null) {
            workflowService.syncForAdmission(f.getAdmissionId());
        }
        return views(List.of(f)).get(0);
    }

    @Transactional
    public FollowUpView recordOutcome(UUID id, FollowUpOutcomeRequest r) {
        CurrentUser u = TenantContext.requireUser();
        FollowUp f = require(id);
        Admission a = requireFollowUpAccess(u, f);
        if (!ACTIVE.contains(f.getStatus())) {
            throw ApiException.rule("FOLLOW_UP_CLOSED", "An outcome has already been recorded for this follow-up.");
        }
        String notes = DischargeWorkflowService.trimToNull(r.outcomeNotes());
        Instant now = Instant.now();
        switch (r.status()) {
            case COMPLETED -> {
            }
            case MISSED -> {
                if (f.getScheduledAt() == null || f.getScheduledAt().isAfter(now)) {
                    throw ApiException.rule("NOT_YET_DUE", "A follow-up can only be marked missed after its scheduled time.");
                }
                if (notes == null) {
                    throw ApiException.badRequest("NOTES_REQUIRED", "Record what was done about the missed follow-up.");
                }
            }
            case CANCELLED -> {
                if (notes == null || notes.length() < 3) {
                    throw ApiException.badRequest("NOTES_REQUIRED", "Document why the follow-up is cancelled.");
                }
            }
            default -> throw ApiException.badRequest("INVALID_OUTCOME", "Outcome must be COMPLETED, MISSED or CANCELLED.");
        }
        f.setStatus(r.status());
        f.setOutcomeNotes(notes);
        f.setOutcomeRecordedBy(u.userId());
        f.setCompletedAt(r.status() == FollowUp.Status.CANCELLED ? null : now);
        f.setUpdatedAt(now);
        followUps.saveAndFlush(f);
        if (r.status() == FollowUp.Status.CANCELLED && FollowUpNotifier.patientFacing(f) && f.getPatientNotifiedAt() != null
                && (f.getScheduledAt() == null || f.getScheduledAt().isAfter(now))) {
            notifier.notify(f, FollowUpDelivery.Event.CANCELLED);
        }
        timeline.record(TimelineService.Entry.builder()
                .patientId(f.getPatientId()).admissionId(f.getAdmissionId())
                .eventType("FOLLOW_UP_" + r.status().name()).category(TimelineEvent.Category.DISCHARGE)
                .title("Follow-up " + r.status().name().toLowerCase() + ": " + f.getTitle())
                .detail(r.status() == FollowUp.Status.CANCELLED ? null : notes)
                .status(r.status().name())
                .visibility(FollowUpNotifier.patientFacing(f) && r.status() != FollowUp.Status.MISSED
                        ? TimelineEvent.Visibility.PATIENT : TimelineEvent.Visibility.CARE_TEAM)
                .entityType("FOLLOW_UP").entityId(f.getId()).build());
        audit.record("FOLLOW_UP_" + r.status().name(), "FOLLOW_UP", f.getId(), f.getPatientId(), notes);
        if (f.getAdmissionId() != null) {
            workflowService.syncForAdmission(f.getAdmissionId());
        }
        return views(List.of(f)).get(0);
    }

    /** Re-sends the patient notification (e.g. after updating contact details). */
    @Transactional
    public FollowUpView resend(UUID id) {
        CurrentUser u = TenantContext.requireUser();
        FollowUp f = require(id);
        requireFollowUpAccess(u, f);
        if (!ACTIVE.contains(f.getStatus())) {
            throw ApiException.rule("FOLLOW_UP_CLOSED", "Only planned or scheduled follow-ups can be re-sent.");
        }
        if (!FollowUpNotifier.patientFacing(f)) {
            throw ApiException.rule("NOT_PATIENT_FACING", "Result reviews are clinician tasks and are not sent to the patient.");
        }
        if (notifier.notify(f, FollowUpDelivery.Event.RESENT).anySent()) {
            f.setPatientNotifiedAt(Instant.now());
        }
        audit.record("FOLLOW_UP_NOTIFICATION_RESENT", "FOLLOW_UP", f.getId(), f.getPatientId(), f.getTitle());
        return views(List.of(f)).get(0);
    }

    /** Background job: reminders for follow-ups starting within the configured window. Returns reminders sent. */
    @Transactional
    public int sendDueReminders(UUID orgId, int hoursBefore) {
        Instant now = Instant.now();
        int sent = 0;
        for (FollowUp f : followUps.findDueForReminder(orgId, now, now.plus(Duration.ofHours(Math.max(hoursBefore, 1))))) {
            if (!FollowUpNotifier.patientFacing(f)) {
                f.setReminderSentAt(now);
                continue;
            }
            FollowUpNotifier.Outcome o = notifier.notify(f, FollowUpDelivery.Event.REMINDER);
            f.setReminderSentAt(now);
            if (o.anySent()) {
                sent++;
            }
        }
        return sent;
    }

    // ------------------------------------------------------------------ helpers

    private FollowUp require(UUID id) {
        return followUps.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Follow-up"));
    }

    /** Named clinician / responsible user, or a clinician with clinical access to the admission. */
    private Admission requireFollowUpAccess(CurrentUser u, FollowUp f) {
        if (!u.has(Permissions.FOLLOWUP_MANAGE)) {
            throw ApiException.forbidden("You do not have permission to manage follow-ups.");
        }
        boolean named = u.userId().equals(f.getResponsibleUserId()) || u.userId().equals(f.getClinicianId());
        if (f.getAdmissionId() == null) {
            if (!named) {
                access.requirePatient(f.getPatientId(), Scope.CLINICAL);
            }
            return null;
        }
        if (named) {
            return admissions.findById(f.getAdmissionId()).orElseThrow();
        }
        return access.requireAdmission(f.getAdmissionId(), Scope.CLINICAL);
    }

    private void apply(FollowUp f, Admission a, FollowUpRequest r, CurrentUser u, boolean validateTime) {
        UUID org = TenantContext.requireOrganizationId();
        Instant now = Instant.now();
        boolean timed = r.type() == FollowUp.FollowUpType.APPOINTMENT || r.type() == FollowUp.FollowUpType.TELECONSULT;
        if (timed && r.scheduledAt() == null) {
            throw ApiException.badRequest("SCHEDULE_REQUIRED", "Appointments and teleconsultations need a date and time.");
        }
        if (validateTime && r.scheduledAt() != null) {
            if (r.scheduledAt().isBefore(now.minus(Duration.ofMinutes(5)))) {
                throw ApiException.badRequest("INVALID_SCHEDULE", "The scheduled time must be in the future.");
            }
            if (r.scheduledAt().isAfter(now.plus(Duration.ofDays(730)))) {
                throw ApiException.badRequest("INVALID_SCHEDULE", "The scheduled time is too far in the future.");
            }
        }
        if (r.departmentId() != null) {
            Department d = departments.findByIdAndOrganizationId(r.departmentId(), org).orElseThrow(() -> ApiException.notFound("Department"));
            if (!d.isActive()) {
                throw ApiException.rule("DEPARTMENT_INACTIVE", d.getName() + " is not active.");
            }
        }
        if (r.clinicianId() != null) {
            User c = users.findByIdAndOrganizationId(r.clinicianId(), org).orElseThrow(() -> ApiException.notFound("Clinician"));
            if (c.getRole() != Role.DOCTOR || c.getStatus() != User.Status.ACTIVE) {
                throw ApiException.rule("INVALID_CLINICIAN", "The clinician must be an active doctor.");
            }
        }
        UUID responsible = r.responsibleUserId();
        if (responsible == null) {
            responsible = r.type() == FollowUp.FollowUpType.RESULT_REVIEW && u.role() != Role.DOCTOR
                    ? a.getAttendingDoctorId() : u.userId();
        }
        User resp = users.findByIdAndOrganizationId(responsible, org).orElseThrow(() -> ApiException.notFound("Responsible staff member"));
        if (resp.getStatus() != User.Status.ACTIVE || resp.getRole() != Role.DOCTOR && resp.getRole() != Role.NURSE) {
            throw ApiException.rule("INVALID_RESPONSIBLE", "The responsible person must be an active doctor or nurse.");
        }
        if (r.type() == FollowUp.FollowUpType.RESULT_REVIEW && resp.getRole() != Role.DOCTOR) {
            throw ApiException.rule("INVALID_RESPONSIBLE", "A result review must be assigned to a doctor.");
        }
        if (r.labOrderId() != null) {
            if (r.type() != FollowUp.FollowUpType.RESULT_REVIEW) {
                throw ApiException.badRequest("LAB_ORDER_ONLY_FOR_RESULT_REVIEW", "Only result-review follow-ups can be linked to an investigation.");
            }
            if (!r.labOrderId().equals(f.getLabOrderId())) {
                LabOrder o = labOrders.findById(r.labOrderId()).filter(x -> x.getAdmissionId().equals(a.getId()))
                        .orElseThrow(() -> ApiException.notFound("Investigation"));
                if (!OUTSTANDING_LAB.contains(o.getStatus())) {
                    throw ApiException.rule("RESULT_ALREADY_HANDLED", o.getTestName() + " is already " + o.getStatus().name().toLowerCase() + ".");
                }
                boolean taken = followUps.findByLabOrderIdIn(List.of(o.getId())).stream()
                        .anyMatch(x -> !x.getId().equals(f.getId()) && x.getStatus() != FollowUp.Status.CANCELLED);
                if (taken) {
                    throw ApiException.conflict("RESULT_ALREADY_HANDED_OVER", o.getTestName() + " has already been handed to a follow-up review.");
                }
            }
        }
        f.setFollowUpType(r.type());
        f.setTitle(r.title().trim());
        f.setDescription(DischargeWorkflowService.trimToNull(r.description()));
        f.setScheduledAt(r.scheduledAt());
        f.setDepartmentId(r.departmentId());
        f.setClinicianId(r.clinicianId());
        f.setResponsibleUserId(resp.getId());
        f.setLocation(DischargeWorkflowService.trimToNull(r.location()));
        f.setLabOrderId(r.type() == FollowUp.FollowUpType.RESULT_REVIEW ? r.labOrderId() : null);
    }

    private void notifyResponsible(FollowUp f, Admission a, CurrentUser u, String verb) {
        if (f.getResponsibleUserId() == null || f.getResponsibleUserId().equals(u.userId())) {
            return;
        }
        User resp = users.findById(f.getResponsibleUserId()).orElse(null);
        Patient p = patients.findById(f.getPatientId()).orElse(null);
        notifications.notifyUsers(List.of(f.getResponsibleUserId()), NotificationRequest.builder()
                .category("FOLLOW_UP").priority(f.getFollowUpType() == FollowUp.FollowUpType.RESULT_REVIEW ? Notification.Priority.HIGH : Notification.Priority.NORMAL)
                .title("Follow-up " + verb + ": " + f.getTitle())
                .message((p == null ? "" : p.fullName() + " - ") + (a == null ? "" : a.getAdmissionNumber()))
                .link(resp != null && resp.getRole() == Role.NURSE && a != null ? "/nurse/discharge/" + a.getId() : "/doctor/follow-ups")
                .entityType("FOLLOW_UP").entityId(f.getId())
                .dedupeKey("FOLLOW_UP_ASSIGNED:" + f.getId() + ":" + f.getResponsibleUserId()).build());
    }

    List<FollowUpView> views(List<FollowUp> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Names names = new Names(users);
        names.preload(list.stream().flatMap(f -> java.util.stream.Stream.of(f.getClinicianId(), f.getResponsibleUserId(),
                f.getCreatedBy(), f.getOutcomeRecordedBy())).toList());
        Map<UUID, Patient> pts = patients.findByIdIn(list.stream().map(FollowUp::getPatientId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Patient::getId, Function.identity()));
        Map<UUID, Admission> adm = admissions.findByIdIn(list.stream().map(FollowUp::getAdmissionId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Admission::getId, Function.identity()));
        Map<UUID, String> deptNames = departments.findByOrganizationIdOrderByName(list.get(0).getOrganizationId()).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        Map<UUID, LabOrder> labs = labOrders.findAllById(list.stream().map(FollowUp::getLabOrderId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(LabOrder::getId, Function.identity()));
        Map<UUID, List<DeliveryView>> dels = new HashMap<>();
        deliveries.findByFollowUpIdInOrderByAttemptedAtDesc(list.stream().map(FollowUp::getId).toList()).forEach(d ->
                dels.computeIfAbsent(d.getFollowUpId(), k -> new ArrayList<>()).add(new DeliveryView(d.getEvent(), d.getChannel().name(),
                        d.getProvider(), mask(d.getDestination()), d.getStatus().name(), d.getErrorMessage(), d.getAttemptedAt())));
        Instant now = Instant.now();
        return list.stream().map(f -> {
            Patient p = pts.get(f.getPatientId());
            Admission a = f.getAdmissionId() == null ? null : adm.get(f.getAdmissionId());
            LabOrder lo = f.getLabOrderId() == null ? null : labs.get(f.getLabOrderId());
            boolean due = ACTIVE.contains(f.getStatus()) && f.getScheduledAt() != null && f.getScheduledAt().isBefore(now);
            return new FollowUpView(f.getId(), f.getAdmissionId(), a == null ? null : a.getAdmissionNumber(), f.getPatientId(),
                    p == null ? null : p.fullName(), p == null ? null : p.getMrn(), f.getFollowUpType(), f.getTitle(),
                    f.getDescription(), f.getScheduledAt(), f.getDepartmentId(), deptNames.get(f.getDepartmentId()),
                    f.getClinicianId(), names.of(f.getClinicianId()), f.getResponsibleUserId(), names.of(f.getResponsibleUserId()),
                    f.getLocation(), f.getLabOrderId(),
                    lo == null ? null : lo.getTestName() + " · " + lo.getOrderNumber() + " (" + humanize(lo.getStatus().name()) + ")",
                    f.getStatus(), f.getPatientNotifiedAt(), f.getReminderSentAt(), f.getCompletedAt(), f.getOutcomeNotes(),
                    names.of(f.getOutcomeRecordedBy()), names.of(f.getCreatedBy()), f.getCreatedAt(), due,
                    dels.getOrDefault(f.getId(), List.of()), f.getVersion());
        }).toList();
    }

    static String mask(String d) {
        if (d == null || d.isBlank()) {
            return null;
        }
        if (d.contains("@")) {
            int at = d.indexOf('@');
            return d.substring(0, Math.min(2, at)) + "***" + d.substring(at);
        }
        String digits = d.replaceAll("\\s", "");
        return digits.length() <= 4 ? "****" : "*".repeat(Math.max(0, digits.length() - 4)) + digits.substring(digits.length() - 4);
    }

    static String humanize(String v) {
        String s = v.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
