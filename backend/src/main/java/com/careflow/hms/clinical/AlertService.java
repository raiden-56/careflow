package com.careflow.hms.clinical;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.clinical.ClinicalDtos.AlertView;
import com.careflow.hms.clinical.ClinicalDtos.EscalationRequest;
import com.careflow.hms.clinical.ClinicalSupport.PatientRef;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.notification.EscalationRule;
import com.careflow.hms.notification.EscalationRuleRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Persisted clinical alerts (threshold breaches, critical results, allergy overrides) and
 * nurse-to-clinician escalations, with acknowledgement, resolution and time-based re-escalation.
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    public static final Set<ClinicalAlert.Status> UNRESOLVED = EnumSet.of(ClinicalAlert.Status.OPEN, ClinicalAlert.Status.ACKNOWLEDGED);
    private static final int MAX_ESCALATION_LEVEL = 3;

    private final ClinicalAlertRepository alerts;
    private final AdmissionRepository admissions;
    private final UserRepository users;
    private final EscalationRuleRepository escalationRules;
    private final ClinicalSupport support;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    // ------------------------------------------------------------------ raising

    /** Creates an alert and its timeline entry. Notification routing is decided by the caller. */
    @Transactional
    public ClinicalAlert raise(Admission a, ClinicalAlert.AlertType type, ClinicalAlert.Severity severity, String title,
                               String message, String sourceType, UUID sourceId, String parameter, String observedValue,
                               UUID thresholdRuleId, String thresholdText, UUID assignedTo, Instant raisedAt) {
        ClinicalAlert al = new ClinicalAlert();
        al.setAdmissionId(a.getId());
        al.setPatientId(a.getPatientId());
        al.setAlertType(type);
        al.setSeverity(severity);
        al.setTitle(ClinicalSupport.truncate(title, 300));
        al.setMessage(ClinicalSupport.truncate(message, 2000));
        al.setSourceType(sourceType);
        al.setSourceId(sourceId);
        al.setParameter(parameter);
        al.setObservedValue(observedValue);
        al.setThresholdRuleId(thresholdRuleId);
        al.setThresholdText(thresholdText);
        al.setAssignedTo(assignedTo);
        al.setRaisedBy(TenantContext.actorId());
        al.setRaisedAt(raisedAt == null ? Instant.now() : raisedAt);
        al = alerts.saveAndFlush(al);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("ALERT_" + type.name()).category(categoryFor(type))
                .title(al.getTitle()).detail(ClinicalSupport.truncate(message, 500)).status(severity.name())
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("CLINICAL_ALERT").entityId(al.getId())
                .occurredAt(al.getRaisedAt()).build());
        return al;
    }

    static TimelineEvent.Category categoryFor(ClinicalAlert.AlertType type) {
        return switch (type) {
            case VITAL_THRESHOLD -> TimelineEvent.Category.OBSERVATION;
            case LAB_CRITICAL -> TimelineEvent.Category.INVESTIGATION;
            case MEDICATION, ALLERGY -> TimelineEvent.Category.MEDICATION;
            case ESCALATION, OTHER -> TimelineEvent.Category.CLINICAL;
        };
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<AlertView> forAdmission(UUID admissionId) {
        support.requireClinical(admissionId);
        return views(alerts.findByAdmissionIdOrderByRaisedAtDesc(admissionId));
    }

    @Transactional(readOnly = true)
    public PageResponse<AlertView> mine(Collection<ClinicalAlert.Status> statuses, ClinicalAlert.Severity severity,
                                        ClinicalAlert.AlertType type, int page, int size) {
        List<UUID> ids = support.myAdmissionIds();
        if (ids.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0, 0);
        }
        Collection<ClinicalAlert.Status> st = statuses == null || statuses.isEmpty() ? EnumSet.allOf(ClinicalAlert.Status.class) : statuses;
        List<ClinicalAlert> list = alerts.findByAdmissionIdInAndStatusInOrderByRaisedAtDesc(ids, st).stream()
                .filter(a -> severity == null || a.getSeverity() == severity)
                .filter(a -> type == null || a.getAlertType() == type)
                .sorted((x, y) -> {
                    int s = Integer.compare(rank(y), rank(x));
                    return s != 0 ? s : y.getRaisedAt().compareTo(x.getRaisedAt());
                })
                .toList();
        PageResponse<ClinicalAlert> p = PageResponse.ofList(list, page, Math.min(Math.max(size, 1), 100));
        return new PageResponse<>(views(p.content()), p.page(), p.size(), p.totalElements(), p.totalPages());
    }

    /** Unresolved first, critical before warning. */
    private static int rank(ClinicalAlert a) {
        int unresolved = a.getStatus() == ClinicalAlert.Status.RESOLVED ? 0 : 10;
        int open = a.getStatus() == ClinicalAlert.Status.OPEN ? 5 : 0;
        return unresolved + open + a.getSeverity().ordinal();
    }

    public List<AlertView> views(List<ClinicalAlert> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, PatientRef> refs = support.refs(list.stream().map(ClinicalAlert::getAdmissionId).distinct().toList());
        Map<UUID, String> names = support.userNames(list.stream()
                .flatMap(a -> Stream.of(a.getAssignedTo(), a.getRaisedBy(), a.getAcknowledgedBy(), a.getResolvedBy())).toList());
        return list.stream().map(a -> new AlertView(a.getId(), a.getAdmissionId(), refs.get(a.getAdmissionId()), a.getAlertType(),
                a.getSeverity(), a.getTitle(), a.getMessage(), a.getSourceType(), a.getSourceId(), a.getParameter(),
                a.getObservedValue(), a.getThresholdRuleId(), a.getThresholdText(), a.getStatus(), a.getAssignedTo(),
                names.get(a.getAssignedTo()), a.getRaisedBy() == null ? "System" : names.get(a.getRaisedBy()), a.getRaisedAt(),
                names.get(a.getAcknowledgedBy()), a.getAcknowledgedAt(), names.get(a.getResolvedBy()), a.getResolvedAt(),
                a.getResolutionNotes(), a.getEscalationLevel(), a.getLastEscalatedAt(), a.getVersion())).toList();
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public AlertView acknowledge(UUID alertId) {
        ClinicalAlert al = support.requireOrg(alerts.findById(alertId), "Alert");
        Admission a = support.requireClinical(al.getAdmissionId());
        if (al.getStatus() != ClinicalAlert.Status.OPEN) {
            throw ApiException.rule("ALERT_NOT_OPEN", "This alert has already been " + al.getStatus().name().toLowerCase() + ".");
        }
        al.setStatus(ClinicalAlert.Status.ACKNOWLEDGED);
        al.setAcknowledgedBy(TenantContext.actorId());
        al.setAcknowledgedAt(Instant.now());
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("ALERT_ACKNOWLEDGED").category(categoryFor(al.getAlertType()))
                .title("Alert acknowledged: " + al.getTitle()).status("ACKNOWLEDGED")
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("CLINICAL_ALERT").entityId(al.getId()).build());
        audit.record("ALERT_ACKNOWLEDGED", "CLINICAL_ALERT", al.getId(), a.getPatientId(), al.getTitle());
        alerts.flush();
        return views(List.of(al)).get(0);
    }

    @Transactional
    public AlertView resolve(UUID alertId, String notes) {
        ClinicalAlert al = support.requireOrg(alerts.findById(alertId), "Alert");
        Admission a = support.requireClinical(al.getAdmissionId());
        if (al.getStatus() == ClinicalAlert.Status.RESOLVED) {
            throw ApiException.rule("ALERT_RESOLVED", "This alert is already resolved.");
        }
        doResolve(al, notes);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("ALERT_RESOLVED").category(categoryFor(al.getAlertType()))
                .title("Alert resolved: " + al.getTitle()).detail(notes).status("RESOLVED")
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("CLINICAL_ALERT").entityId(al.getId()).build());
        audit.record("ALERT_RESOLVED", "CLINICAL_ALERT", al.getId(), a.getPatientId(), notes);
        alerts.flush();
        return views(List.of(al)).get(0);
    }

    /** Resolves without access checks (system-initiated, e.g. the source record was reviewed/corrected). */
    void doResolve(ClinicalAlert al, String notes) {
        if (al.getAcknowledgedAt() == null) {
            al.setAcknowledgedAt(Instant.now());
            al.setAcknowledgedBy(TenantContext.actorId());
        }
        al.setStatus(ClinicalAlert.Status.RESOLVED);
        al.setResolvedBy(TenantContext.actorId());
        al.setResolvedAt(Instant.now());
        al.setResolutionNotes(ClinicalSupport.truncate(notes, 2000));
    }

    /** Nurse-to-clinician escalation: an ESCALATION alert assigned to the attending doctor plus an urgent notification. */
    @Transactional
    public AlertView escalate(UUID admissionId, EscalationRequest r) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = support.requireOpenClinical(admissionId);
        UUID target = a.getAttendingDoctorId();
        if (r.assignToUserId() != null) {
            User doc = users.findByIdAndOrganizationId(r.assignToUserId(), u.organizationId())
                    .filter(x -> x.getRole() == Role.DOCTOR && x.getStatus() == User.Status.ACTIVE)
                    .orElseThrow(() -> ApiException.rule("INVALID_ESCALATION_TARGET", "Escalations must be addressed to an active doctor."));
            target = doc.getId();
        }
        ClinicalAlert.Severity severity = switch (r.urgency()) {
            case EMERGENCY -> ClinicalAlert.Severity.CRITICAL;
            case URGENT -> ClinicalAlert.Severity.WARNING;
            case ROUTINE -> ClinicalAlert.Severity.INFO;
        };
        String doctorName = support.userName(target);
        ClinicalAlert al = raise(a, ClinicalAlert.AlertType.ESCALATION, severity,
                "Escalation (" + r.urgency().name().toLowerCase() + "): " + r.concern().trim(),
                "Raised by " + u.fullName() + ". Observations: " + r.observations().trim(),
                "ESCALATION", null, null, null, null, null, target, null);
        PatientRef ref = support.ref(a.getId());
        notifications.notifyUsers(List.of(target), NotificationRequest.builder()
                .category("REVIEW_REQUEST")
                .priority(severity == ClinicalAlert.Severity.CRITICAL ? Notification.Priority.CRITICAL
                        : severity == ClinicalAlert.Severity.WARNING ? Notification.Priority.HIGH : Notification.Priority.NORMAL)
                .title((r.urgency() == ClinicalDtos.Urgency.EMERGENCY ? "EMERGENCY review requested: " : "Review requested: ")
                        + (ref == null ? a.getAdmissionNumber() : ref.patientName()))
                .message(r.concern().trim() + (ref != null && ref.location() != null ? " · " + ref.location() : ""))
                .link("/doctor/patients/" + a.getId()).entityType("CLINICAL_ALERT").entityId(al.getId())
                .requiresAck(severity == ClinicalAlert.Severity.CRITICAL)
                .dedupeKey("ESCALATION:" + al.getId()).build());
        audit.record("ESCALATION_RAISED", "CLINICAL_ALERT", al.getId(), a.getPatientId(),
                r.urgency() + " to " + doctorName + ": " + r.concern());
        return views(List.of(al)).get(0);
    }

    // ------------------------------------------------------------------ job

    /**
     * Re-notifies the care team for CRITICAL alerts still unacknowledged after the ALERT_UNACKNOWLEDGED
     * rule threshold. Each pass raises the escalation level by one (dedupe per alert + level).
     */
    @Transactional
    public int escalateUnacknowledged(UUID orgId) {
        List<EscalationRule> rules = escalationRules.findByOrganizationIdAndEventTypeAndActiveTrueOrderByEscalationLevel(orgId,
                EscalationRule.EventType.ALERT_UNACKNOWLEDGED);
        if (rules.isEmpty()) {
            return 0;
        }
        int minThreshold = rules.stream().mapToInt(EscalationRule::getThresholdMinutes).min().orElse(15);
        Instant now = Instant.now();
        int count = 0;
        for (ClinicalAlert al : alerts.findUnacknowledgedCritical(orgId, now.minus(Duration.ofMinutes(minThreshold)))) {
            if (al.getEscalationLevel() >= MAX_ESCALATION_LEVEL) {
                continue;
            }
            int nextLevel = al.getEscalationLevel() + 1;
            EscalationRule rule = rules.stream().filter(r -> r.getEscalationLevel() == nextLevel).findFirst()
                    .orElse(rules.get(rules.size() - 1));
            Instant since = al.getLastEscalatedAt() != null ? al.getLastEscalatedAt() : al.getRaisedAt();
            if (since.plus(Duration.ofMinutes(rule.getThresholdMinutes())).isAfter(now)) {
                continue;
            }
            Admission a = admissions.findById(al.getAdmissionId()).orElse(null);
            if (a == null || !a.getStatus().isOpen()) {
                continue;
            }
            al.setEscalationLevel(nextLevel);
            al.setLastEscalatedAt(now);
            long minutes = Duration.between(al.getRaisedAt(), now).toMinutes();
            NotificationRequest.NotificationRequestBuilder req = NotificationRequest.builder()
                    .category("OBSERVATION_ALERT").priority(Notification.Priority.CRITICAL).requiresAck(true)
                    .title("Unacknowledged critical alert (level " + nextLevel + ")")
                    .message(al.getTitle() + " · open for " + minutes + " min · " + a.getAdmissionNumber())
                    .entityType("CLINICAL_ALERT").entityId(al.getId())
                    .dedupeKey("ALERT_UNACK:" + al.getId() + ":L" + nextLevel);
            List<UUID> doctors = new ArrayList<>();
            doctors.add(a.getAttendingDoctorId());
            if (al.getAssignedTo() != null) {
                doctors.add(al.getAssignedTo());
            }
            if (rule.getEscalateToRole() == Role.NURSE) {
                notifications.notifyWardNurses(a.getId(), req.link("/nurse/patients/" + a.getId()).build());
            } else {
                notifications.notifyUsers(doctors, req.link("/doctor/patients/" + a.getId()).build());
                notifications.notifyCareTeam(a.getId(), Role.DOCTOR, req.link("/doctor/patients/" + a.getId()).build());
            }
            timeline.record(TimelineService.Entry.builder()
                    .patientId(a.getPatientId()).admissionId(a.getId())
                    .eventType("ALERT_ESCALATED").category(categoryFor(al.getAlertType()))
                    .title("Critical alert escalated (level " + nextLevel + ")").detail(al.getTitle())
                    .status("CRITICAL").visibility(TimelineEvent.Visibility.CARE_TEAM)
                    .entityType("CLINICAL_ALERT").entityId(al.getId()).build());
            audit.record("ALERT_ESCALATED", "CLINICAL_ALERT", al.getId(), a.getPatientId(), "Level " + nextLevel);
            count++;
        }
        return count;
    }
}
