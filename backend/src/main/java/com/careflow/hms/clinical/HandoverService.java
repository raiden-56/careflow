package com.careflow.hms.clinical;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.clinical.ClinicalDtos.HandoverCreateRequest;
import com.careflow.hms.clinical.ClinicalDtos.HandoverItemUpdate;
import com.careflow.hms.clinical.ClinicalDtos.HandoverItemView;
import com.careflow.hms.clinical.ClinicalDtos.HandoverUpdateRequest;
import com.careflow.hms.clinical.ClinicalDtos.HandoverView;
import com.careflow.hms.clinical.ClinicalDtos.NurseOption;
import com.careflow.hms.clinical.ClinicalSupport.PatientRef;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.facility.StaffWardAssignmentRepository;
import com.careflow.hms.facility.Ward;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.medication.MarService;
import com.careflow.hms.medication.MedicationDtos.DoseView;
import com.careflow.hms.notification.EscalationRule;
import com.careflow.hms.notification.EscalationRuleRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.org.SettingsService;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Structured nursing shift handover per ward: items are pre-filled from live records (condition,
 * observations, medications due, outstanding tests/tasks, escalations), edited by the outgoing
 * nurse, submitted to a receiving nurse and acknowledged.
 */
@Service
@RequiredArgsConstructor
public class HandoverService {

    private final HandoverRepository handovers;
    private final HandoverItemRepository items;
    private final AdmissionRepository admissions;
    private final StaffWardAssignmentRepository wardAssignments;
    private final WardRepository wards;
    private final UserRepository users;
    private final EscalationRuleRepository escalationRules;
    private final ClinicalNoteRepository notes;
    private final DiagnosisRepository diagnoses;
    private final ClinicalAlertRepository alerts;
    private final CareTaskRepository tasks;
    private final LabOrderRepository labOrders;
    private final ProcedureRecordRepository procedures;
    private final VitalsService vitals;
    private final MarService mar;
    private final ClinicalSupport support;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final AuditService audit;

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<HandoverView> list(UUID wardId, boolean toMe, int page, int size) {
        CurrentUser u = TenantContext.requireUser();
        List<UUID> myWards = wardAssignments.activeWardIds(u.userId());
        List<UUID> scope = wardId != null ? List.of(requireWardAccess(wardId)) : myWards;
        if (scope.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0, 0);
        }
        Page<Handover> p = handovers.findByWardIdIn(scope, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50),
                Sort.by(Sort.Direction.DESC, "shiftDate").and(Sort.by(Sort.Direction.DESC, "createdAt"))));
        List<Handover> content = p.getContent().stream()
                .filter(h -> !toMe || u.userId().equals(h.getToUserId()))
                .filter(h -> h.getStatus() != Handover.Status.DRAFT || u.userId().equals(h.getFromUserId()))
                .toList();
        return new PageResponse<>(content.stream().map(h -> view(h, false)).toList(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    @Transactional(readOnly = true)
    public HandoverView get(UUID id) {
        Handover h = require(id);
        requireWardAccess(h.getWardId());
        if (h.getStatus() == Handover.Status.DRAFT && !h.getFromUserId().equals(TenantContext.actorId())) {
            throw ApiException.notFound("Handover");
        }
        return view(h, true);
    }

    @Transactional(readOnly = true)
    public List<NurseOption> receivingNurses(UUID wardId) {
        requireWardAccess(wardId);
        UUID me = TenantContext.actorId();
        List<UUID> ids = wardAssignments.activeUserIdsForWard(wardId).stream().filter(id -> !id.equals(me)).toList();
        return users.findByOrganizationIdAndIdIn(TenantContext.requireOrganizationId(), ids).stream()
                .filter(x -> x.getStatus() == User.Status.ACTIVE && x.getRole() == Role.NURSE)
                .sorted(Comparator.comparing(User::getFullName))
                .map(x -> new NurseOption(x.getId(), x.getFullName())).toList();
    }

    public long awaitingMyAck() {
        return handovers.findByToUserIdAndStatus(TenantContext.actorId(), Handover.Status.SUBMITTED).size();
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public HandoverView create(HandoverCreateRequest r) {
        CurrentUser u = TenantContext.requireUser();
        requireWardAccess(r.wardId());
        if (r.shiftDate().isBefore(TimeUtil.today().minusDays(1)) || r.shiftDate().isAfter(TimeUtil.today().plusDays(1))) {
            throw ApiException.badRequest("INVALID_SHIFT_DATE", "Handovers are created for yesterday, today or tomorrow.");
        }
        boolean exists = handovers.findTop30ByWardIdInOrderByShiftDateDescCreatedAtDesc(List.of(r.wardId())).stream()
                .anyMatch(h -> h.getShiftDate().equals(r.shiftDate()) && h.getShift() == r.shift()
                        && h.getFromUserId().equals(u.userId()) && h.getStatus() == Handover.Status.DRAFT);
        if (exists) {
            throw ApiException.conflict("HANDOVER_EXISTS", "You already have a draft handover for this ward and shift.");
        }
        Handover h = new Handover();
        h.setWardId(r.wardId());
        h.setShiftDate(r.shiftDate());
        h.setShift(r.shift());
        h.setFromUserId(u.userId());
        h.setStatus(Handover.Status.DRAFT);
        h.setRequiresAck(Boolean.parseBoolean(settings.get(u.organizationId(), SettingsService.HANDOVER_REQUIRE_ACK)));
        h.setCreatedAt(Instant.now());
        h = handovers.saveAndFlush(h);
        List<Admission> patients = admissions.findOpenInWards(u.organizationId(), List.of(r.wardId()));
        for (Admission a : patients) {
            items.save(prefill(h, a));
        }
        items.flush();
        audit.record("HANDOVER_CREATED", "HANDOVER", h.getId(), null, patients.size() + " patient(s)");
        return view(h, true);
    }

    /** Builds a handover item from live records for one patient. Statements are factual extracts only. */
    HandoverItem prefill(Handover h, Admission a) {
        Instant now = Instant.now();
        java.time.ZoneId zone = TimeUtil.zone();
        HandoverItem it = new HandoverItem();
        it.setHandoverId(h.getId());
        it.setAdmissionId(a.getId());
        it.setPatientId(a.getPatientId());

        List<String> condition = new ArrayList<>();
        diagnoses.findByAdmissionIdOrderByRecordedAtDesc(a.getId()).stream()
                .filter(d -> d.getStatus() == Diagnosis.Status.ACTIVE).sorted(Comparator.comparing(d -> !d.isPrimary()))
                .limit(2).forEach(d -> condition.add((d.isPrimary() ? "Dx: " : "Also: ") + d.getDescription()));
        notes.findByAdmissionIdOrderByAuthoredAtDesc(a.getId()).stream()
                .filter(n -> n.getStatus() == ClinicalNote.Status.FINAL).findFirst()
                .ifPresent(n -> condition.add("Latest note (" + HM.withZone(zone).format(n.getAuthoredAt()) + ", "
                        + ClinicalRecordService.humanType(n.getNoteType()).toLowerCase() + "): "
                        + ClinicalSupport.truncate(n.getAssessment() != null ? n.getAssessment() : n.getContent(), 220)));
        it.setConditionSummary(condition.isEmpty() ? null : String.join("\n", condition));

        List<String> obs = new ArrayList<>();
        vitals.latest(a.getId()).ifPresent(v -> obs.add("Last obs " + HM.withZone(zone).format(v.getRecordedAt()) + ": " + VitalsService.describe(v)));
        Double since = vitals.hoursSinceLast(a.getId());
        int interval = settings.getInt(a.getOrganizationId(), SettingsService.VITALS_EXPECTED_INTERVAL_HOURS);
        if (since == null || since > interval) {
            obs.add("Observations overdue (" + (since == null ? "none recorded" : since + " h since last") + ")");
        }
        List<ClinicalAlert> open = alerts.findByAdmissionIdInAndStatusInOrderByRaisedAtDesc(List.of(a.getId()), AlertService.UNRESOLVED);
        open.stream().filter(al -> al.getAlertType() == ClinicalAlert.AlertType.VITAL_THRESHOLD).limit(4)
                .forEach(al -> obs.add(al.getSeverity().name().toLowerCase() + ": " + al.getTitle() + " (" + al.getStatus().name().toLowerCase() + ")"));
        it.setImportantObservations(obs.isEmpty() ? null : String.join("\n", obs));

        List<DoseView> doses = mar.pendingDoses(a.getId(), now.minus(Duration.ofHours(12)), now.plus(Duration.ofHours(8)));
        it.setPendingMedications(doses.isEmpty() ? null : doses.stream().limit(10)
                .map(d -> HM.withZone(zone).format(d.scheduledAt()) + " " + d.medicationName() + " " + Objects.toString(d.dose(), "")
                        + " " + Objects.toString(d.doseUnit(), "") + " " + (d.route() == null ? "" : d.route().name())
                        + ("OVERDUE".equals(d.displayStatus()) ? " - OVERDUE" : "DELAYED".equals(d.displayStatus()) ? " - delayed" : "")
                        + (d.highAlert() ? " [high-alert]" : ""))
                .collect(Collectors.joining("\n")));

        Instant nextShiftEnd = now.plus(Duration.ofHours(12));
        List<String> taskLines = tasks.findByAdmissionIdAndStatusIn(a.getId(), CareTaskService.ACTIVE).stream()
                .filter(t -> t.getDueAt() == null || t.getDueAt().isBefore(nextShiftEnd))
                .sorted(Comparator.comparing(t -> t.getDueAt() == null ? Instant.MAX : t.getDueAt()))
                .limit(8)
                .map(t -> (t.getDueAt() == null ? "" : HM.withZone(zone).format(t.getDueAt()) + " ") + t.getTitle()
                        + (t.getDueAt() != null && t.getDueAt().isBefore(now) ? " - OVERDUE" : ""))
                .toList();
        it.setPendingTasks(taskLines.isEmpty() ? null : String.join("\n", taskLines));

        List<String> outstanding = new ArrayList<>();
        labOrders.findByAdmissionIdAndStatusIn(a.getId(), EnumSet.of(LabOrder.Status.ORDERED, LabOrder.Status.COLLECTED,
                        LabOrder.Status.IN_PROGRESS, LabOrder.Status.RESULTED))
                .forEach(o -> outstanding.add(o.getTestName() + " - " + o.getStatus().name().toLowerCase().replace('_', ' ')
                        + (o.getPriority() != LabOrder.Priority.ROUTINE ? " (" + o.getPriority().name().toLowerCase() + ")" : "")));
        procedures.findByAdmissionIdOrderByCreatedAtDesc(a.getId()).stream()
                .filter(p -> p.getStatus() == ProcedureRecord.Status.PLANNED)
                .forEach(p -> outstanding.add("Procedure planned: " + p.getName()));
        if (a.getExpectedDischargeDate() != null && !a.getExpectedDischargeDate().isAfter(TimeUtil.today().plusDays(1))) {
            outstanding.add("Expected discharge " + a.getExpectedDischargeDate());
        }
        it.setOutstandingItems(outstanding.isEmpty() ? null : String.join("\n", outstanding));

        List<String> esc = open.stream().filter(al -> al.getAlertType() != ClinicalAlert.AlertType.VITAL_THRESHOLD)
                .limit(5).map(al -> al.getTitle() + " (" + al.getStatus().name().toLowerCase() + ")").toList();
        it.setEscalations(esc.isEmpty() ? null : String.join("\n", esc));

        boolean critical = open.stream().anyMatch(al -> al.getSeverity() == ClinicalAlert.Severity.CRITICAL);
        boolean watch = !open.isEmpty() || doses.stream().anyMatch(d -> "OVERDUE".equals(d.displayStatus()))
                || since == null || since > interval;
        it.setPriority(critical ? HandoverItem.Priority.CRITICAL : watch ? HandoverItem.Priority.WATCH : HandoverItem.Priority.NORMAL);
        return it;
    }

    @Transactional
    public HandoverView update(UUID id, HandoverUpdateRequest r) {
        Handover h = requireOwnDraft(id);
        if (!r.version().equals(h.getVersion())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This handover was changed elsewhere. Reload and try again.");
        }
        h.setGeneralNotes(ClinicalSupport.blankToNull(r.generalNotes()));
        if (r.toUserId() != null) {
            h.setToUserId(requireReceiver(h, r.toUserId()));
        }
        if (r.items() != null) {
            Map<UUID, HandoverItem> byId = items.findByHandoverId(h.getId()).stream()
                    .collect(Collectors.toMap(HandoverItem::getId, x -> x));
            for (HandoverItemUpdate u : r.items()) {
                HandoverItem it = byId.get(u.id());
                if (it == null) {
                    throw ApiException.notFound("Handover item");
                }
                it.setConditionSummary(ClinicalSupport.blankToNull(u.conditionSummary()));
                it.setImportantObservations(ClinicalSupport.blankToNull(u.importantObservations()));
                it.setPendingMedications(ClinicalSupport.blankToNull(u.pendingMedications()));
                it.setPendingTasks(ClinicalSupport.blankToNull(u.pendingTasks()));
                it.setOutstandingItems(ClinicalSupport.blankToNull(u.outstandingItems()));
                it.setEscalations(ClinicalSupport.blankToNull(u.escalations()));
                if (u.priority() != null) {
                    it.setPriority(u.priority());
                }
            }
        }
        handovers.saveAndFlush(h);
        return view(h, true);
    }

    /** Re-builds one patient's item from current records (discarding edits to that item). */
    @Transactional
    public HandoverView refreshItem(UUID id, UUID itemId) {
        Handover h = requireOwnDraft(id);
        HandoverItem existing = items.findById(itemId).filter(i -> i.getHandoverId().equals(h.getId()))
                .orElseThrow(() -> ApiException.notFound("Handover item"));
        Admission a = admissions.findById(existing.getAdmissionId()).orElseThrow();
        HandoverItem fresh = prefill(h, a);
        existing.setConditionSummary(fresh.getConditionSummary());
        existing.setImportantObservations(fresh.getImportantObservations());
        existing.setPendingMedications(fresh.getPendingMedications());
        existing.setPendingTasks(fresh.getPendingTasks());
        existing.setOutstandingItems(fresh.getOutstandingItems());
        existing.setEscalations(fresh.getEscalations());
        existing.setPriority(fresh.getPriority());
        items.flush();
        return view(h, true);
    }

    @Transactional
    public HandoverView submit(UUID id, UUID toUserId) {
        Handover h = requireOwnDraft(id);
        h.setToUserId(requireReceiver(h, toUserId));
        h.setStatus(Handover.Status.SUBMITTED);
        h.setSubmittedAt(Instant.now());
        String wardName = wards.findById(h.getWardId()).map(Ward::getName).orElse("ward");
        notifications.notifyUsers(List.of(h.getToUserId()), NotificationRequest.builder()
                .category("HANDOVER").priority(Notification.Priority.HIGH).requiresAck(h.isRequiresAck())
                .title("Shift handover for you: " + wardName)
                .message(h.getShift().name().toLowerCase() + " shift " + h.getShiftDate() + " from " + support.userName(h.getFromUserId()))
                .link("/nurse/handover?id=" + h.getId()).entityType("HANDOVER").entityId(h.getId())
                .dedupeKey("HANDOVER_SUBMITTED:" + h.getId()).build());
        audit.record("HANDOVER_SUBMITTED", "HANDOVER", h.getId(), null, "To " + support.userName(h.getToUserId()));
        handovers.flush();
        return view(h, true);
    }

    @Transactional
    public HandoverView acknowledge(UUID id, String comment) {
        Handover h = require(id);
        requireWardAccess(h.getWardId());
        UUID me = TenantContext.actorId();
        if (h.getStatus() != Handover.Status.SUBMITTED) {
            throw ApiException.rule("HANDOVER_NOT_SUBMITTED", h.getStatus() == Handover.Status.ACKNOWLEDGED
                    ? "This handover has already been acknowledged." : "This handover has not been submitted yet.");
        }
        if (!me.equals(h.getToUserId())) {
            throw ApiException.forbidden("Only the receiving nurse can acknowledge this handover.");
        }
        h.setStatus(Handover.Status.ACKNOWLEDGED);
        h.setAcknowledgedBy(me);
        h.setAcknowledgedAt(Instant.now());
        h.setAckComment(ClinicalSupport.blankToNull(comment));
        notifications.notifyUsers(List.of(h.getFromUserId()), NotificationRequest.builder()
                .category("HANDOVER").title("Handover acknowledged")
                .message(support.userName(me) + " acknowledged your " + h.getShift().name().toLowerCase() + " handover")
                .link("/nurse/handover?id=" + h.getId()).entityType("HANDOVER").entityId(h.getId())
                .dedupeKey("HANDOVER_ACK:" + h.getId()).build());
        audit.record("HANDOVER_ACKNOWLEDGED", "HANDOVER", h.getId(), null, comment);
        handovers.flush();
        return view(h, true);
    }

    // ------------------------------------------------------------------ job

    /** Notifies ward nurses about submitted handovers still unacknowledged after the rule threshold. */
    @Transactional
    public int escalateUnacknowledged(UUID orgId) {
        List<EscalationRule> rules = escalationRules.findByOrganizationIdAndEventTypeAndActiveTrueOrderByEscalationLevel(orgId,
                EscalationRule.EventType.HANDOVER_UNACKNOWLEDGED);
        if (rules.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int n = 0;
        for (Handover h : handovers.findByOrganizationIdAndStatus(orgId, Handover.Status.SUBMITTED)) {
            if (!h.isRequiresAck() || h.getSubmittedAt() == null) {
                continue;
            }
            long minutes = Duration.between(h.getSubmittedAt(), now).toMinutes();
            String wardName = wards.findById(h.getWardId()).map(Ward::getName).orElse("ward");
            for (EscalationRule rule : rules) {
                if (minutes < rule.getThresholdMinutes()) {
                    continue;
                }
                NotificationRequest req = NotificationRequest.builder()
                        .category("HANDOVER").priority(Notification.Priority.HIGH)
                        .title("Handover not yet acknowledged: " + wardName)
                        .message(h.getShift().name().toLowerCase() + " shift " + h.getShiftDate() + " · submitted " + minutes
                                + " min ago to " + support.userName(h.getToUserId()))
                        .link("/nurse/handover?id=" + h.getId()).entityType("HANDOVER").entityId(h.getId())
                        .dedupeKey("HANDOVER_UNACK:" + h.getId() + ":L" + rule.getEscalationLevel()).build();
                int sent = rule.getEscalateToRole() == Role.NURSE
                        ? notifications.notifyUsers(wardAssignments.activeUserIdsForWard(h.getWardId()), req)
                        : notifications.notifyRole(rule.getEscalateToRole(), req);
                n += sent;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ helpers

    private Handover require(UUID id) {
        return support.requireOrg(handovers.findById(id), "Handover");
    }

    private Handover requireOwnDraft(UUID id) {
        Handover h = require(id);
        requireWardAccess(h.getWardId());
        if (!h.getFromUserId().equals(TenantContext.actorId())) {
            throw ApiException.forbidden("Only the nurse handing over can edit or submit this handover.");
        }
        if (h.getStatus() != Handover.Status.DRAFT) {
            throw ApiException.rule("HANDOVER_SUBMITTED", "This handover has already been submitted.");
        }
        return h;
    }

    private UUID requireWardAccess(UUID wardId) {
        CurrentUser u = TenantContext.requireUser();
        wards.findByIdAndOrganizationId(wardId, u.organizationId()).orElseThrow(() -> ApiException.notFound("Ward"));
        if (!wardAssignments.activeWardIds(u.userId()).contains(wardId)) {
            throw ApiException.forbidden("You are not assigned to this ward.");
        }
        return wardId;
    }

    private UUID requireReceiver(Handover h, UUID toUserId) {
        if (toUserId.equals(h.getFromUserId())) {
            throw ApiException.rule("INVALID_RECEIVER", "Hand over to a different nurse.");
        }
        User to = users.findByIdAndOrganizationId(toUserId, h.getOrganizationId())
                .filter(x -> x.getStatus() == User.Status.ACTIVE && x.getRole() == Role.NURSE)
                .orElseThrow(() -> ApiException.rule("INVALID_RECEIVER", "The receiving staff member must be an active nurse."));
        if (!wardAssignments.activeWardIds(to.getId()).contains(h.getWardId())) {
            throw ApiException.rule("INVALID_RECEIVER", to.getFullName() + " is not assigned to this ward.");
        }
        return to.getId();
    }

    HandoverView view(Handover h, boolean withItems) {
        List<HandoverItem> its = items.findByHandoverId(h.getId());
        Map<UUID, PatientRef> refs = support.refs(its.stream().map(HandoverItem::getAdmissionId).distinct().toList());
        Map<UUID, String> names = support.userNames(Stream.of(h.getFromUserId(), h.getToUserId(), h.getAcknowledgedBy()).toList());
        List<HandoverItemView> iv = withItems ? its.stream()
                .sorted(Comparator.comparing((HandoverItem i) -> -i.getPriority().ordinal())
                        .thenComparing(i -> refs.get(i.getAdmissionId()) == null ? "" : Objects.toString(refs.get(i.getAdmissionId()).location(), "")))
                .map(i -> new HandoverItemView(i.getId(), i.getAdmissionId(), refs.get(i.getAdmissionId()), i.getConditionSummary(),
                        i.getImportantObservations(), i.getPendingMedications(), i.getPendingTasks(), i.getOutstandingItems(),
                        i.getEscalations(), i.getPriority())).toList() : List.of();
        String wardName = wards.findById(h.getWardId()).map(Ward::getName).orElse(null);
        return new HandoverView(h.getId(), h.getWardId(), wardName, h.getShiftDate(), h.getShift(), h.getFromUserId(),
                names.get(h.getFromUserId()), h.getToUserId(), names.get(h.getToUserId()), h.getGeneralNotes(), h.getStatus(),
                h.isRequiresAck(), h.getSubmittedAt(), names.get(h.getAcknowledgedBy()), h.getAcknowledgedAt(), h.getAckComment(),
                h.getCreatedAt(), its.size(), (int) its.stream().filter(i -> i.getPriority() == HandoverItem.Priority.CRITICAL).count(),
                iv, h.getVersion());
    }
}
