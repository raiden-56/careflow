package com.careflow.hms.incident;

import com.careflow.hms.admin.PermissionDirectory;
import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditEventRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.facility.Ward;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.incident.IncidentDtos.HistoryEntry;
import com.careflow.hms.incident.IncidentDtos.IncidentDetail;
import com.careflow.hms.incident.IncidentDtos.IncidentStats;
import com.careflow.hms.incident.IncidentDtos.IncidentSummary;
import com.careflow.hms.incident.IncidentDtos.MedicationEventOption;
import com.careflow.hms.incident.IncidentDtos.PatientOption;
import com.careflow.hms.incident.IncidentDtos.ReportRequest;
import com.careflow.hms.incident.IncidentDtos.ReviewerOption;
import com.careflow.hms.incident.IncidentDtos.TransitionRequest;
import com.careflow.hms.incident.IncidentReport.Status;
import com.careflow.hms.medication.MedicationAdministration;
import com.careflow.hms.medication.MedicationAdministrationRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.org.SequenceService;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Permissions;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Safety incident reporting and review. Reporters see their own reports; holders of INCIDENT_REVIEW
 * see and review all reports of the organization. Everyone else gets 404 for an incident. Every step
 * is audited; incidents linked to a patient add an INTERNAL safety event to the patient's timeline.
 */
@Service
@RequiredArgsConstructor
public class IncidentService {

    private static final Map<String, String> SORTS = Map.of("reportedAt", "reportedAt", "occurredAt", "occurredAt",
            "severity", "severity", "status", "status", "incidentNumber", "incidentNumber");

    /** Allowed review transitions. */
    static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.REPORTED, EnumSet.of(Status.UNDER_REVIEW),
            Status.UNDER_REVIEW, EnumSet.of(Status.ACTION_REQUIRED, Status.CLOSED),
            Status.ACTION_REQUIRED, EnumSet.of(Status.UNDER_REVIEW, Status.CLOSED),
            Status.CLOSED, EnumSet.of(Status.UNDER_REVIEW));

    private static final Set<IncidentReport.Severity> NOTIFY_SEVERITIES = EnumSet.of(IncidentReport.Severity.SEVERE,
            IncidentReport.Severity.DEATH);

    private final IncidentReportRepository incidents;
    private final PatientRepository patients;
    private final AdmissionRepository admissions;
    private final WardRepository wards;
    private final UserRepository users;
    private final MedicationAdministrationRepository medicationEvents;
    private final SequenceService sequences;
    private final AccessPolicy accessPolicy;
    private final AuditService audit;
    private final AuditEventRepository auditEvents;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final PermissionDirectory permissionDirectory;
    private final JdbcTemplate jdbc;

    // ------------------------------------------------------------------ reporting

    @Transactional
    public IncidentDetail report(ReportRequest r) {
        CurrentUser u = TenantContext.requireUser();
        UUID org = u.organizationId();
        if (r.occurredAt().isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("OCCURRED_IN_FUTURE", "The occurrence time cannot be in the future.");
        }
        if (r.occurredAt().isBefore(Instant.now().minus(Duration.ofDays(365)))) {
            throw ApiException.badRequest("OCCURRED_TOO_LONG_AGO", "Incidents older than a year should be reported to the quality team directly.");
        }
        if (r.wardId() != null) {
            wards.findByIdAndOrganizationId(r.wardId(), org).orElseThrow(() -> ApiException.notFound("Ward"));
        }
        UUID patientId = r.patientId();
        Admission admission = null;
        if (r.admissionId() != null) {
            admission = accessPolicy.requireAdmission(r.admissionId(), AccessPolicy.Scope.DEMOGRAPHICS);
            if (patientId != null && !patientId.equals(admission.getPatientId())) {
                throw ApiException.badRequest("ADMISSION_MISMATCH", "The admission does not belong to the selected patient.");
            }
            patientId = admission.getPatientId();
        }
        if (patientId != null) {
            accessPolicy.requirePatient(patientId, AccessPolicy.Scope.DEMOGRAPHICS);
        }
        if (r.medicationAdministrationId() != null) {
            if (admission == null) {
                throw ApiException.badRequest("ADMISSION_REQUIRED", "Select the admission the medication event belongs to.");
            }
            MedicationAdministration m = medicationEvents.findById(r.medicationAdministrationId())
                    .filter(x -> x.getOrganizationId().equals(org))
                    .orElseThrow(() -> ApiException.notFound("Medication event"));
            if (!m.getAdmissionId().equals(admission.getId())) {
                throw ApiException.badRequest("MEDICATION_EVENT_MISMATCH", "The medication event does not belong to this admission.");
            }
        }
        IncidentReport i = new IncidentReport();
        i.setIncidentNumber(sequences.incidentNumber());
        i.setIncidentType(r.incidentType());
        i.setSeverity(r.severity());
        i.setOccurredAt(r.occurredAt());
        i.setLocation(blank(r.location()));
        i.setWardId(r.wardId());
        i.setPatientId(patientId);
        i.setAdmissionId(admission == null ? null : admission.getId());
        i.setMedicationAdministrationId(r.medicationAdministrationId());
        i.setDescription(r.description().trim());
        i.setImmediateActions(blank(r.immediateActions()));
        i.setReportedBy(u.userId());
        i.setReportedAt(Instant.now());
        i = incidents.saveAndFlush(i);

        audit.record("INCIDENT_REPORTED", "INCIDENT", i.getId(), patientId, AuditEvent.Outcome.SUCCESS,
                i.getIncidentNumber() + " " + i.getIncidentType() + " (" + i.getSeverity() + ")",
                Map.of("type", i.getIncidentType().name(), "severity", i.getSeverity().name()));
        if (patientId != null) {
            timeline.record(TimelineService.Entry.builder()
                    .patientId(patientId)
                    .admissionId(i.getAdmissionId())
                    .eventType("INCIDENT_REPORTED")
                    .category(TimelineEvent.Category.SAFETY)
                    .title("Safety incident " + i.getIncidentNumber() + " reported")
                    .detail(humanize(i.getIncidentType().name()) + " - severity " + humanize(i.getSeverity().name()))
                    .status(i.getStatus().name())
                    .visibility(TimelineEvent.Visibility.INTERNAL)
                    .entityType("INCIDENT")
                    .entityId(i.getId())
                    .occurredAt(i.getOccurredAt())
                    .build());
        }
        if (NOTIFY_SEVERITIES.contains(i.getSeverity())) {
            List<UUID> reviewers = permissionDirectory.activeUsersWith(org, Permissions.INCIDENT_REVIEW);
            notifications.notifyUsers(reviewers.stream().filter(id -> !id.equals(u.userId())).toList(), NotificationRequest.builder()
                    .category("SAFETY")
                    .priority(Notification.Priority.CRITICAL)
                    .title("Severe incident reported: " + i.getIncidentNumber())
                    .message(humanize(i.getIncidentType().name()) + " (" + humanize(i.getSeverity().name()) + ") reported by "
                            + u.fullName() + ". Immediate review required.")
                    .link("/admin/incidents?id=" + i.getId())
                    .entityType("INCIDENT")
                    .entityId(i.getId())
                    .dedupeKey("INCIDENT_REPORTED:" + i.getId())
                    .requiresAck(true)
                    .build());
        }
        return detail(i, u);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PageResponse<IncidentSummary> mine(Status status, int page, int size, String sort) {
        CurrentUser u = TenantContext.requireUser();
        return search(u, u.userId(), status, null, null, null, null, page, size, sort);
    }

    @Transactional(readOnly = true)
    public PageResponse<IncidentSummary> all(Status status, IncidentReport.Severity severity, IncidentReport.IncidentType type,
                                             UUID reviewerId, String q, int page, int size, String sort) {
        return search(TenantContext.requireUser(), null, status, severity, type, reviewerId, q, page, size, sort);
    }

    private PageResponse<IncidentSummary> search(CurrentUser u, UUID reportedBy, Status status, IncidentReport.Severity severity,
                                                 IncidentReport.IncidentType type, UUID reviewerId, String q, int page,
                                                 int size, String sort) {
        Page<IncidentReport> result = incidents.search(u.organizationId(), reportedBy,
                status == null ? EnumSet.allOf(Status.class) : EnumSet.of(status), severity, type, reviewerId,
                Paging.likePattern(q), Paging.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "reportedAt")));
        Names names = new Names();
        boolean demographics = u.has(Permissions.PATIENT_DEMOGRAPHICS_READ);
        return PageResponse.of(result, i -> {
            Patient p = demographics && i.getPatientId() != null ? names.patient(i.getPatientId()) : null;
            return new IncidentSummary(i.getId(), i.getIncidentNumber(), i.getIncidentType(), i.getSeverity(), i.getStatus(),
                    i.getOccurredAt(), i.getReportedAt(), i.getLocation(), names.ward(i.getWardId()),
                    p == null ? null : p.fullName(), p == null ? null : p.getMrn(), i.getPatientId() != null,
                    names.user(i.getReportedBy()), names.user(i.getReviewerId()), u.userId().equals(i.getReportedBy()));
        });
    }

    @Transactional(readOnly = true)
    public IncidentDetail get(UUID id) {
        CurrentUser u = TenantContext.requireUser();
        return detail(requireVisible(id, u), u);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID id) {
        CurrentUser u = TenantContext.requireUser();
        IncidentReport i = requireVisible(id, u);
        return auditEvents.findEntityHistory(u.organizationId(), "INCIDENT", i.getId()).stream()
                .map(e -> new HistoryEntry(e.getOccurredAt(), e.getActorName(), e.getActorRole(), e.getAction(), e.getSummary(),
                        e.getDetails()))
                .toList();
    }

    @Transactional(readOnly = true)
    public IncidentStats stats() {
        UUID org = TenantContext.requireOrganizationId();
        Map<String, Object> m = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status = 'REPORTED') AS reported,
                       count(*) FILTER (WHERE status = 'UNDER_REVIEW') AS under_review,
                       count(*) FILTER (WHERE status = 'ACTION_REQUIRED') AS action_required,
                       count(*) FILTER (WHERE status = 'CLOSED') AS closed,
                       count(*) FILTER (WHERE status <> 'CLOSED' AND severity IN ('SEVERE','DEATH')) AS severe_open,
                       count(*) FILTER (WHERE reported_at >= ?) AS last30
                FROM incident_reports WHERE organization_id = ?
                """, Timestamp.from(Instant.now().minus(Duration.ofDays(30))), org);
        return new IncidentStats(n(m, "reported"), n(m, "under_review"), n(m, "action_required"), n(m, "closed"),
                n(m, "severe_open"), n(m, "last30"));
    }

    // ------------------------------------------------------------------ review workflow

    @Transactional
    public IncidentDetail assign(UUID id, UUID reviewerId) {
        CurrentUser u = TenantContext.requireUser();
        IncidentReport i = incidents.findByIdAndOrganizationId(id, u.organizationId()).orElseThrow(() -> ApiException.notFound("Incident"));
        User reviewer = users.findByIdAndOrganizationId(reviewerId, u.organizationId()).orElseThrow(() -> ApiException.notFound("Reviewer"));
        if (!permissionDirectory.userHas(u.organizationId(), reviewerId, Permissions.INCIDENT_REVIEW)) {
            throw ApiException.rule("NOT_A_REVIEWER", reviewer.getFullName() + " is not authorized to review incidents.");
        }
        if (i.getStatus() == Status.CLOSED) {
            throw ApiException.rule("INCIDENT_CLOSED", "Reopen the incident before assigning a reviewer.");
        }
        UUID previous = i.getReviewerId();
        i.setReviewerId(reviewerId);
        incidents.saveAndFlush(i);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reviewer", reviewer.getFullName());
        if (previous != null) {
            details.put("previousReviewer", users.findById(previous).map(User::getFullName).orElse(null));
        }
        audit.record("INCIDENT_REVIEWER_ASSIGNED", "INCIDENT", i.getId(), i.getPatientId(), AuditEvent.Outcome.SUCCESS,
                i.getIncidentNumber() + " assigned to " + reviewer.getFullName(), details);
        if (!reviewerId.equals(u.userId())) {
            notifications.notifyUsers(List.of(reviewerId), NotificationRequest.builder()
                    .category("SAFETY").priority(Notification.Priority.HIGH)
                    .title("Incident " + i.getIncidentNumber() + " assigned to you")
                    .message(humanize(i.getIncidentType().name()) + " (" + humanize(i.getSeverity().name()) + ") needs your review.")
                    .link("/admin/incidents?id=" + i.getId()).entityType("INCIDENT").entityId(i.getId())
                    .dedupeKey("INCIDENT_ASSIGNED:" + i.getId() + ":" + reviewerId + ":" + Instant.now().getEpochSecond())
                    .build());
        }
        return detail(i, u);
    }

    @Transactional
    public IncidentDetail transition(UUID id, TransitionRequest r) {
        CurrentUser u = TenantContext.requireUser();
        IncidentReport i = incidents.findByIdAndOrganizationId(id, u.organizationId()).orElseThrow(() -> ApiException.notFound("Incident"));
        if (!Objects.equals(i.getVersion(), r.version())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This incident was updated by someone else. Reload and try again.");
        }
        Status from = i.getStatus();
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(r.status())) {
            throw ApiException.rule("INVALID_TRANSITION", "An incident cannot move from " + humanize(from.name()) + " to "
                    + humanize(r.status().name()) + ".");
        }
        String notes = blank(r.reviewNotes());
        String actions = blank(r.correctiveActions());
        if (r.status() == Status.ACTION_REQUIRED && actions == null && i.getCorrectiveActions() == null) {
            throw ApiException.badRequest("ACTIONS_REQUIRED", "Describe the corrective actions that are required.");
        }
        if (r.status() == Status.CLOSED) {
            if (notes == null && i.getReviewNotes() == null) {
                throw ApiException.badRequest("REVIEW_NOTES_REQUIRED", "Record the review findings before closing the incident.");
            }
            if (actions == null && i.getCorrectiveActions() == null) {
                throw ApiException.badRequest("ACTIONS_REQUIRED", "Record the corrective actions taken before closing the incident.");
            }
        }
        if (from == Status.CLOSED && notes == null) {
            throw ApiException.badRequest("REOPEN_REASON_REQUIRED", "Explain why the incident is being reopened.");
        }
        if (i.getReviewerId() == null) {
            i.setReviewerId(u.userId());
        }
        if (notes != null) {
            i.setReviewNotes(notes);
        }
        if (actions != null) {
            i.setCorrectiveActions(actions);
        }
        i.setStatus(r.status());
        i.setClosedAt(r.status() == Status.CLOSED ? Instant.now() : null);
        incidents.saveAndFlush(i);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", from.name());
        details.put("to", r.status().name());
        if (notes != null) {
            details.put("reviewNotes", notes);
        }
        if (actions != null) {
            details.put("correctiveActions", actions);
        }
        audit.record("INCIDENT_STATUS_CHANGED", "INCIDENT", i.getId(), i.getPatientId(), AuditEvent.Outcome.SUCCESS,
                i.getIncidentNumber() + " " + from + " -> " + r.status(), details);
        if (i.getPatientId() != null) {
            timeline.record(TimelineService.Entry.builder()
                    .patientId(i.getPatientId()).admissionId(i.getAdmissionId())
                    .eventType("INCIDENT_" + r.status().name())
                    .category(TimelineEvent.Category.SAFETY)
                    .title("Safety incident " + i.getIncidentNumber() + ": " + humanize(r.status().name()).toLowerCase())
                    .status(r.status().name())
                    .visibility(TimelineEvent.Visibility.INTERNAL)
                    .entityType("INCIDENT").entityId(i.getId())
                    .build());
        }
        if (!i.getReportedBy().equals(u.userId())) {
            notifications.notifyUsers(List.of(i.getReportedBy()), NotificationRequest.builder()
                    .category("SAFETY").priority(Notification.Priority.NORMAL)
                    .title("Your incident report " + i.getIncidentNumber() + " is " + humanize(r.status().name()).toLowerCase())
                    .message(r.status() == Status.CLOSED ? "The review is complete. Thank you for reporting." : "The safety team has updated your report.")
                    .link("/incidents?id=" + i.getId()).entityType("INCIDENT").entityId(i.getId())
                    .dedupeKey("INCIDENT_STATUS:" + i.getId() + ":" + r.status() + ":" + i.getVersion())
                    .build());
        }
        return detail(i, u);
    }

    @Transactional(readOnly = true)
    public List<ReviewerOption> reviewers() {
        UUID org = TenantContext.requireOrganizationId();
        List<UUID> ids = permissionDirectory.activeUsersWith(org, Permissions.INCIDENT_REVIEW);
        return users.findByOrganizationIdAndIdIn(org, ids).stream()
                .map(x -> new ReviewerOption(x.getId(), x.getFullName(), x.getRole().name()))
                .sorted((a, b) -> a.fullName().compareToIgnoreCase(b.fullName()))
                .toList();
    }

    // ------------------------------------------------------------------ pickers for the report form

    /** Open admissions matching a name, MRN or admission number (demographics only). */
    @Transactional(readOnly = true)
    public List<PatientOption> patientLookup(String q) {
        CurrentUser u = TenantContext.requireUser();
        if (!u.has(Permissions.PATIENT_DEMOGRAPHICS_READ)) {
            throw ApiException.forbidden("Your role cannot look up patients. Describe the patient in the report instead.");
        }
        String like = Paging.likePattern(q);
        if (like == null || q.trim().length() < 2) {
            return List.of();
        }
        return jdbc.query("""
                SELECT p.id AS pid, p.first_name || ' ' || p.last_name AS name, p.mrn, a.id AS aid, a.admission_number,
                       w.name AS ward, r.room_number, b.bed_number
                FROM admissions a JOIN patients p ON p.id = a.patient_id
                LEFT JOIN bed_assignments ba ON ba.admission_id = a.id AND ba.released_at IS NULL
                LEFT JOIN beds b ON b.id = ba.bed_id LEFT JOIN rooms r ON r.id = b.room_id LEFT JOIN wards w ON w.id = b.ward_id
                WHERE a.organization_id = ? AND a.status IN ('PENDING_BED','ADMITTED','DISCHARGE_IN_PROGRESS')
                  AND (lower(p.first_name || ' ' || p.last_name) LIKE ? OR lower(p.mrn) LIKE ? OR lower(a.admission_number) LIKE ?)
                ORDER BY p.first_name, p.last_name LIMIT 15
                """, (rs, n) -> new PatientOption(rs.getObject("pid", UUID.class), rs.getString("name"), rs.getString("mrn"),
                rs.getObject("aid", UUID.class), rs.getString("admission_number"),
                rs.getString("ward") == null ? "Awaiting bed" : rs.getString("ward") + " · " + rs.getString("room_number") + "-" + rs.getString("bed_number")),
                u.organizationId(), like, like, like);
    }

    /** Recent medication events of an admission (requires medication access to that admission). */
    @Transactional(readOnly = true)
    public List<MedicationEventOption> medicationEvents(UUID admissionId) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = admissions.findByIdAndOrganizationId(admissionId, u.organizationId())
                .orElseThrow(() -> ApiException.notFound("Admission"));
        // A picker, not a record view: staff without medication access to this admission simply get no options.
        if (!accessPolicy.canAccessAdmission(u, a, AccessPolicy.Scope.MEDICATION)) {
            return List.of();
        }
        return jdbc.query("""
                SELECT m.id, m.scheduled_at, m.status, o.dose || ' ' || o.dose_unit AS dose,
                       md.generic_name || ' ' || md.strength AS med
                FROM medication_administrations m
                JOIN medication_orders o ON o.id = m.order_id
                JOIN medications md ON md.id = o.medication_id
                WHERE m.admission_id = ? AND m.scheduled_at >= ? AND m.scheduled_at <= ?
                ORDER BY m.scheduled_at DESC LIMIT 50
                """, (rs, n) -> new MedicationEventOption(rs.getObject("id", UUID.class), rs.getTimestamp("scheduled_at").toInstant(),
                rs.getString("med"), rs.getString("status"), rs.getString("dose")),
                a.getId(), Timestamp.from(Instant.now().minus(Duration.ofDays(7))), Timestamp.from(Instant.now().plus(Duration.ofHours(2))));
    }

    // ------------------------------------------------------------------ helpers

    private IncidentReport requireVisible(UUID id, CurrentUser u) {
        IncidentReport i = incidents.findByIdAndOrganizationId(id, u.organizationId()).orElseThrow(() -> ApiException.notFound("Incident"));
        if (!u.has(Permissions.INCIDENT_REVIEW) && !u.userId().equals(i.getReportedBy())) {
            audit.record("ACCESS_DENIED", "INCIDENT", i.getId(), null, AuditEvent.Outcome.DENIED,
                    u.role() + " attempted to open an incident report they did not file", null);
            throw ApiException.notFound("Incident");
        }
        return i;
    }

    private IncidentDetail detail(IncidentReport i, CurrentUser u) {
        Names names = new Names();
        boolean demographics = u.has(Permissions.PATIENT_DEMOGRAPHICS_READ);
        Patient p = demographics && i.getPatientId() != null ? names.patient(i.getPatientId()) : null;
        String admissionNumber = i.getAdmissionId() == null || !demographics ? null
                : admissions.findById(i.getAdmissionId()).map(Admission::getAdmissionNumber).orElse(null);
        String medEvent = null;
        if (i.getMedicationAdministrationId() != null) {
            List<String> ev = jdbc.query("""
                    SELECT md.generic_name || ' ' || md.strength || ' - ' || m.status || ' (scheduled ' ||
                           to_char(m.scheduled_at AT TIME ZONE ?, 'DD Mon HH24:MI') || ')' AS label
                    FROM medication_administrations m JOIN medication_orders o ON o.id = m.order_id
                    JOIN medications md ON md.id = o.medication_id WHERE m.id = ?
                    """, (rs, n) -> rs.getString("label"), u.zone().getId(), i.getMedicationAdministrationId());
            medEvent = ev.isEmpty() ? null : ev.get(0);
        }
        User reporter = users.findById(i.getReportedBy()).orElse(null);
        boolean canReview = u.has(Permissions.INCIDENT_REVIEW);
        return new IncidentDetail(i.getId(), i.getIncidentNumber(), i.getIncidentType(), i.getSeverity(), i.getStatus(),
                i.getOccurredAt(), i.getReportedAt(), i.getLocation(), i.getWardId(), names.ward(i.getWardId()),
                demographics ? i.getPatientId() : null, p == null ? (i.getPatientId() != null ? "Linked patient" : null) : p.fullName(),
                p == null ? null : p.getMrn(), demographics ? i.getAdmissionId() : null, admissionNumber,
                i.getMedicationAdministrationId(), medEvent, i.getDescription(), i.getImmediateActions(), i.getReportedBy(),
                reporter == null ? null : reporter.getFullName(), reporter == null ? null : reporter.getRole().name(),
                i.getReviewerId(), names.user(i.getReviewerId()), i.getReviewNotes(), i.getCorrectiveActions(), i.getClosedAt(),
                canReview, canReview ? List.copyOf(TRANSITIONS.getOrDefault(i.getStatus(), Set.of())) : List.of(), i.getVersion());
    }

    private class Names {
        private final Map<UUID, String> userNames = new HashMap<>();
        private final Map<UUID, String> wardNames = new HashMap<>();
        private final Map<UUID, Patient> patientCache = new HashMap<>();

        String user(UUID id) {
            return id == null ? null : userNames.computeIfAbsent(id, k -> users.findById(k).map(User::getFullName).orElse(null));
        }

        String ward(UUID id) {
            return id == null ? null : wardNames.computeIfAbsent(id, k -> wards.findById(k).map(Ward::getName).orElse(null));
        }

        Patient patient(UUID id) {
            return patientCache.computeIfAbsent(id, k -> patients.findById(k).orElse(null));
        }
    }

    private static long n(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String humanize(String v) {
        String s = v.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
