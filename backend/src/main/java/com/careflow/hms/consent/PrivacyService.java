package com.careflow.hms.consent;

import com.careflow.hms.admin.PermissionDirectory;
import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditEventRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.consent.PrivacyDtos.AccessEntry;
import com.careflow.hms.consent.PrivacyDtos.ConsentRequest;
import com.careflow.hms.consent.PrivacyDtos.ConsentView;
import com.careflow.hms.consent.PrivacyDtos.NewRequest;
import com.careflow.hms.consent.PrivacyDtos.PrivacyStats;
import com.careflow.hms.consent.PrivacyDtos.RequestView;
import com.careflow.hms.consent.PrivacyDtos.RespondRequest;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientAccessGrant;
import com.careflow.hms.patient.PatientAccessGrantRepository;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Permissions;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Consent records, patient record requests and access transparency. Staff endpoints need CONSENT_MANAGE /
 * PATIENT_REQUEST_MANAGE; portal endpoints go through {@link AccessPolicy} and only return the patient's own
 * (or an authorized dependant's) information.
 */
@Service
@RequiredArgsConstructor
public class PrivacyService {

    /** Consent types a patient may withdraw directly in the portal. Treatment consents go through the care team. */
    public static final Set<ConsentRecord.ConsentType> PORTAL_WITHDRAWABLE = EnumSet.of(ConsentRecord.ConsentType.DATA_SHARING,
            ConsentRecord.ConsentType.COMMUNICATION, ConsentRecord.ConsentType.RESEARCH, ConsentRecord.ConsentType.INSURANCE_SHARING);

    private static final Map<String, String> CONSENT_SORTS = Map.of("grantedAt", "grantedAt", "type", "consentType", "status", "status");
    private static final Map<String, String> REQUEST_SORTS = Map.of("raisedAt", "raisedAt", "status", "status", "type", "requestType");

    private final ConsentRecordRepository consents;
    private final PatientRequestRepository requests;
    private final PatientRepository patients;
    private final AdmissionRepository admissions;
    private final PatientAccessGrantRepository grants;
    private final UserRepository users;
    private final AccessPolicy accessPolicy;
    private final AuditService audit;
    private final AuditEventRepository auditEvents;
    private final NotificationService notifications;
    private final PermissionDirectory permissionDirectory;
    private final JdbcTemplate jdbc;

    // ================================================================== staff: consents

    @Transactional(readOnly = true)
    public PageResponse<ConsentView> searchConsents(String patientMrn, ConsentRecord.Status status, ConsentRecord.ConsentType type,
                                                    int page, int size, String sort) {
        UUID org = TenantContext.requireOrganizationId();
        UUID patientId = resolveMrn(org, patientMrn);
        // EXPIRED is also derived: a granted consent past its expiry is reported as expired.
        var result = consents.search(org, patientId, status == null ? "ALL" : status.name(), Instant.now(), type,
                Paging.of(page, size, sort, CONSENT_SORTS, Sort.by(Sort.Direction.DESC, "grantedAt")));
        Cache c = new Cache();
        return PageResponse.of(result, x -> view(x, c, true));
    }

    @Transactional
    public ConsentView recordConsent(ConsentRequest r) {
        CurrentUser u = TenantContext.requireUser();
        Patient p = accessPolicy.requirePatient(r.patientId(), AccessPolicy.Scope.DEMOGRAPHICS);
        if (r.admissionId() != null) {
            Admission a = admissions.findByIdAndOrganizationId(r.admissionId(), u.organizationId())
                    .orElseThrow(() -> ApiException.notFound("Admission"));
            if (!a.getPatientId().equals(p.getId())) {
                throw ApiException.badRequest("ADMISSION_MISMATCH", "The admission does not belong to this patient.");
            }
        }
        if (r.captureMethod() == ConsentRecord.CaptureMethod.PORTAL) {
            throw ApiException.badRequest("INVALID_METHOD", "Portal consent is captured by the patient in the portal.");
        }
        Instant granted = r.grantedAt() == null ? Instant.now() : r.grantedAt();
        if (granted.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("INVALID_DATE", "The consent date cannot be in the future.");
        }
        if (r.expiresAt() != null && !r.expiresAt().isAfter(granted)) {
            throw ApiException.badRequest("INVALID_EXPIRY", "The expiry must be after the consent date.");
        }
        if ((r.consentType() == ConsentRecord.ConsentType.DATA_SHARING || r.consentType() == ConsentRecord.ConsentType.INSURANCE_SHARING
                || r.consentType() == ConsentRecord.ConsentType.CAREGIVER_ACCESS) && (r.sharedWith() == null || r.sharedWith().isBlank())) {
            throw ApiException.badRequest("SHARED_WITH_REQUIRED", "State who the information is shared with.");
        }
        ConsentRecord c = new ConsentRecord();
        c.setPatientId(p.getId());
        c.setAdmissionId(r.admissionId());
        c.setConsentType(r.consentType());
        c.setPurpose(r.purpose().trim());
        c.setSharedWith(blank(r.sharedWith()));
        c.setStatus("DECLINED".equals(r.decision()) ? ConsentRecord.Status.DECLINED : ConsentRecord.Status.GRANTED);
        c.setGrantedAt(granted);
        c.setExpiresAt(r.expiresAt());
        c.setCapturedBy(u.userId());
        c.setCaptureMethod(r.captureMethod());
        c.setNotes(blank(r.notes()));
        c = consents.saveAndFlush(c);
        audit.record("CONSENT_RECORDED", "CONSENT", c.getId(), p.getId(), AuditEvent.Outcome.SUCCESS,
                c.getConsentType() + " " + c.getStatus() + " (" + c.getCaptureMethod() + ")", null);
        return view(c, new Cache(), true);
    }

    @Transactional
    public ConsentView withdrawByStaff(UUID id, String reason) {
        CurrentUser u = TenantContext.requireUser();
        ConsentRecord c = consents.findByIdAndOrganizationId(id, u.organizationId()).orElseThrow(() -> ApiException.notFound("Consent"));
        accessPolicy.requirePatient(c.getPatientId(), AccessPolicy.Scope.DEMOGRAPHICS);
        withdraw(c, reason);
        audit.record("CONSENT_WITHDRAWN", "CONSENT", c.getId(), c.getPatientId(), AuditEvent.Outcome.SUCCESS,
                c.getConsentType() + " withdrawn by staff: " + reason.trim(), null);
        return view(c, new Cache(), true);
    }

    private void withdraw(ConsentRecord c, String reason) {
        if (c.getStatus() != ConsentRecord.Status.GRANTED) {
            throw ApiException.rule("NOT_ACTIVE", "Only a granted consent can be withdrawn.");
        }
        c.setStatus(ConsentRecord.Status.WITHDRAWN);
        c.setWithdrawnAt(Instant.now());
        c.setWithdrawalReason(reason.trim());
        consents.saveAndFlush(c);
    }

    // ================================================================== staff: patient requests

    @Transactional(readOnly = true)
    public PageResponse<RequestView> searchRequests(PatientRequest.Status status, PatientRequest.RequestType type, String patientMrn,
                                                    int page, int size, String sort) {
        UUID org = TenantContext.requireOrganizationId();
        Set<PatientRequest.Status> statuses = status == null ? EnumSet.allOf(PatientRequest.Status.class) : EnumSet.of(status);
        var result = requests.search(org, statuses, type, resolveMrn(org, patientMrn),
                Paging.of(page, size, sort, REQUEST_SORTS, Sort.by(Sort.Direction.DESC, "raisedAt")));
        Cache c = new Cache();
        return PageResponse.of(result, r -> view(r, c, true));
    }

    @Transactional
    public RequestView respond(UUID id, RespondRequest body) {
        CurrentUser u = TenantContext.requireUser();
        PatientRequest r = requests.findByIdAndOrganizationId(id, u.organizationId()).orElseThrow(() -> ApiException.notFound("Request"));
        if (!Objects.equals(r.getVersion(), body.version())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This request was updated by someone else. Reload and try again.");
        }
        if (r.getStatus() == PatientRequest.Status.COMPLETED || r.getStatus() == PatientRequest.Status.REJECTED) {
            throw ApiException.rule("REQUEST_CLOSED", "This request is already closed.");
        }
        if (body.status() == PatientRequest.Status.OPEN) {
            throw ApiException.badRequest("INVALID_STATUS", "Choose in review, completed or rejected.");
        }
        PatientRequest.Status from = r.getStatus();
        r.setStatus(body.status());
        r.setResponse(body.response().trim());
        r.setRespondedBy(u.userId());
        r.setRespondedAt(Instant.now());
        requests.saveAndFlush(r);
        audit.record("PATIENT_REQUEST_" + body.status().name(), "PATIENT_REQUEST", r.getId(), r.getPatientId(),
                AuditEvent.Outcome.SUCCESS, r.getRequestType() + " " + from + " -> " + body.status(), null);
        notifications.notifyPatient(r.getPatientId(), NotificationRequest.builder()
                .category("PRIVACY")
                .priority(Notification.Priority.NORMAL)
                .title("Update on your request: " + r.getSubject())
                .message(switch (body.status()) {
                    case COMPLETED -> "Your request has been completed.";
                    case REJECTED -> "Your request could not be fulfilled. See the response for details.";
                    default -> "Your request is being reviewed by the hospital.";
                })
                .link("/patient/privacy")
                .entityType("PATIENT_REQUEST").entityId(r.getId())
                .dedupeKey("PATIENT_REQUEST:" + r.getId() + ":" + body.status())
                .build());
        return view(r, new Cache(), true);
    }

    @Transactional(readOnly = true)
    public PrivacyStats stats() {
        UUID org = TenantContext.requireOrganizationId();
        Map<String, Object> c = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status = 'GRANTED' AND (expires_at IS NULL OR expires_at > now())) AS granted,
                       count(*) FILTER (WHERE status = 'WITHDRAWN') AS withdrawn
                FROM consent_records WHERE organization_id = ?
                """, org);
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status = 'OPEN') AS open,
                       count(*) FILTER (WHERE status = 'IN_REVIEW') AS in_review,
                       count(*) FILTER (WHERE status IN ('COMPLETED','REJECTED') AND responded_at >= ?) AS closed
                FROM patient_requests WHERE organization_id = ?
                """, Timestamp.from(Instant.now().minus(Duration.ofDays(30))), org);
        return new PrivacyStats(num(c, "granted"), num(c, "withdrawn"), num(r, "open"), num(r, "in_review"), num(r, "closed"));
    }

    // ================================================================== portal

    @Transactional(readOnly = true)
    public List<ConsentView> portalConsents(UUID patientId) {
        accessPolicy.requirePatient(patientId, AccessPolicy.Scope.DEMOGRAPHICS);
        Cache c = new Cache();
        return consents.findByPatientIdOrderByGrantedAtDesc(patientId).stream().map(x -> view(x, c, false)).toList();
    }

    @Transactional
    public ConsentView portalWithdraw(UUID patientId, UUID consentId, String reason) {
        CurrentUser u = TenantContext.requireUser();
        accessPolicy.requirePatient(patientId, AccessPolicy.Scope.DEMOGRAPHICS);
        requireSelfOrFullGrant(u, patientId);
        ConsentRecord c = consents.findByIdAndOrganizationId(consentId, u.organizationId())
                .filter(x -> x.getPatientId().equals(patientId))
                .orElseThrow(() -> ApiException.notFound("Consent"));
        if (!PORTAL_WITHDRAWABLE.contains(c.getConsentType())) {
            throw ApiException.rule("NOT_PORTAL_WITHDRAWABLE",
                    "This consent must be withdrawn with your care team. Submit a 'Withdraw consent' request instead.");
        }
        withdraw(c, reason);
        audit.record("CONSENT_WITHDRAWN", "CONSENT", c.getId(), patientId, AuditEvent.Outcome.SUCCESS,
                c.getConsentType() + " withdrawn in the patient portal", null);
        notifyPrivacyTeam(u, "Consent withdrawn in the portal",
                humanize(c.getConsentType().name()) + " consent was withdrawn by " + u.fullName() + ".", "CONSENT_WITHDRAWN:" + c.getId());
        return view(c, new Cache(), false);
    }

    @Transactional(readOnly = true)
    public List<RequestView> portalRequests(UUID patientId) {
        accessPolicy.requirePatient(patientId, AccessPolicy.Scope.DEMOGRAPHICS);
        Cache c = new Cache();
        return requests.findByPatientIdOrderByRaisedAtDesc(patientId).stream().map(r -> view(r, c, false)).toList();
    }

    @Transactional
    public RequestView portalSubmit(UUID patientId, NewRequest body) {
        CurrentUser u = TenantContext.requireUser();
        accessPolicy.requirePatient(patientId, AccessPolicy.Scope.DEMOGRAPHICS);
        requireSelfOrFullGrant(u, patientId);
        long open = requests.findByPatientIdOrderByRaisedAtDesc(patientId).stream()
                .filter(r -> r.getStatus() == PatientRequest.Status.OPEN || r.getStatus() == PatientRequest.Status.IN_REVIEW).count();
        if (open >= 10) {
            throw ApiException.rule("TOO_MANY_OPEN_REQUESTS", "You already have 10 open requests. Please wait for a response.");
        }
        PatientRequest r = new PatientRequest();
        r.setPatientId(patientId);
        r.setRequestType(body.requestType());
        r.setSubject(body.subject().trim());
        r.setDescription(body.description().trim());
        r.setRaisedBy(u.userId());
        r.setRaisedAt(Instant.now());
        r = requests.saveAndFlush(r);
        audit.record("PATIENT_REQUEST_SUBMITTED", "PATIENT_REQUEST", r.getId(), patientId, AuditEvent.Outcome.SUCCESS,
                r.getRequestType() + ": " + r.getSubject(), null);
        notifyPrivacyTeam(u, "New patient request: " + humanize(r.getRequestType().name()),
                r.getSubject(), "PATIENT_REQUEST_NEW:" + r.getId());
        return view(r, new Cache(), false);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessEntry> accessLog(UUID patientId, int page, int size) {
        accessPolicy.requirePatient(patientId, AccessPolicy.Scope.CLINICAL);
        var result = auditEvents.findAccessForPatient(patientId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50), Sort.by(Sort.Direction.DESC, "occurredAt")));
        return PageResponse.of(result, e -> new AccessEntry(e.getOccurredAt(),
                e.getActorName() == null || "SYSTEM".equals(e.getActorRole()) ? "Hospital system" : e.getActorName(),
                roleLabel(e.getActorRole()), activity(e.getAction())));
    }

    static String roleLabel(String role) {
        if (role == null) {
            return "Staff";
        }
        return switch (role) {
            case "DOCTOR" -> "Doctor";
            case "NURSE" -> "Nurse";
            case "HOSPITAL_ADMIN" -> "Hospital administration";
            case "BILLING" -> "Billing team";
            case "PHARMACIST" -> "Pharmacist";
            case "SYSTEM" -> "Automated process";
            default -> "Staff";
        };
    }

    static String activity(String action) {
        if (action == null) {
            return "Accessed your record";
        }
        if (action.startsWith("TRANSFER_")) {
            return "Managed a ward transfer for you";
        }
        if (action.startsWith("INCIDENT_")) {
            return "Recorded a patient-safety review entry";
        }
        if (action.startsWith("PATIENT_REQUEST_")) {
            return "Handled one of your record requests";
        }
        return switch (action) {
            case "READ_CLINICAL" -> "Viewed your clinical record";
            case "READ_MEDICATION" -> "Viewed your medication record";
            case "READ_FINANCIAL" -> "Viewed your billing record";
            case "PATIENT_REGISTERED" -> "Registered you as a patient";
            case "PATIENT_UPDATED" -> "Updated your personal details";
            case "PORTAL_ACCOUNT_CREATED" -> "Set up your portal account";
            case "CAREGIVER_GRANTED", "CAREGIVER_REVOKED" -> "Changed who can view your records";
            case "ADMISSION_CREATED" -> "Admitted you to hospital";
            case "ADMISSION_UPDATED", "EXPECTED_DISCHARGE_UPDATED" -> "Updated your admission details";
            case "ADMISSION_CANCELLED" -> "Cancelled an admission";
            case "BED_ASSIGNED" -> "Assigned your bed";
            case "CARE_TEAM_ADDED", "CARE_TEAM_REMOVED" -> "Updated your care team";
            case "CONSENT_RECORDED" -> "Recorded your consent";
            case "CONSENT_WITHDRAWN" -> "Recorded a consent withdrawal";
            case "DOCUMENT_UPLOADED" -> "Added a document to your record";
            case "DOCUMENT_RELEASED" -> "Shared a document with you";
            case "DOCUMENT_ARCHIVED" -> "Archived a document in your record";
            case "DOCUMENT_DOWNLOADED" -> "Opened a document from your record";
            default -> {
                String s = action.toLowerCase().replace('_', ' ');
                yield "Updated your record (" + s + ")";
            }
        };
    }

    // ================================================================== helpers

    private void requireSelfOrFullGrant(CurrentUser u, UUID patientId) {
        if (patientId.equals(u.patientId())) {
            return;
        }
        boolean full = grants.findByPatientIdAndGranteeUserIdAndRevokedAtIsNull(patientId, u.userId())
                .filter(g -> g.isActive(Instant.now()) && g.getScope() == PatientAccessGrant.Scope.FULL).isPresent();
        if (!full) {
            throw ApiException.forbidden("Only the patient or a caregiver with full access can make privacy changes.");
        }
    }

    private void notifyPrivacyTeam(CurrentUser u, String title, String message, String dedupe) {
        List<UUID> staff = permissionDirectory.activeUsersWith(u.organizationId(), Permissions.PATIENT_REQUEST_MANAGE);
        notifications.notifyUsers(staff, NotificationRequest.builder()
                .category("PRIVACY").priority(Notification.Priority.NORMAL)
                .title(title).message(message).link("/admin/privacy")
                .dedupeKey(dedupe).build());
    }

    private UUID resolveMrn(UUID org, String mrn) {
        if (mrn == null || mrn.isBlank()) {
            return null;
        }
        List<UUID> ids = jdbc.queryForList("SELECT id FROM patients WHERE organization_id = ? AND upper(mrn) = upper(?)",
                UUID.class, org, mrn.trim());
        return ids.isEmpty() ? new UUID(0, 0) : ids.get(0);
    }

    private class Cache {
        final Map<UUID, Patient> patientCache = new HashMap<>();
        final Map<UUID, String> userNames = new HashMap<>();
        final Map<UUID, String> admissionNumbers = new HashMap<>();

        Patient patient(UUID id) {
            return patientCache.computeIfAbsent(id, k -> patients.findById(k).orElse(null));
        }

        String user(UUID id) {
            return id == null ? null : userNames.computeIfAbsent(id, k -> users.findById(k).map(User::getFullName).orElse(null));
        }

        String admission(UUID id) {
            return id == null ? null : admissionNumbers.computeIfAbsent(id, k -> admissions.findById(k).map(Admission::getAdmissionNumber).orElse(null));
        }
    }

    private ConsentView view(ConsentRecord c, Cache cache, boolean staff) {
        Patient p = cache.patient(c.getPatientId());
        ConsentRecord.Status effective = c.getStatus() == ConsentRecord.Status.GRANTED && c.getExpiresAt() != null
                && c.getExpiresAt().isBefore(Instant.now()) ? ConsentRecord.Status.EXPIRED : c.getStatus();
        return new ConsentView(c.getId(), c.getPatientId(), p == null ? null : p.fullName(), p == null ? null : p.getMrn(),
                c.getAdmissionId(), cache.admission(c.getAdmissionId()), c.getConsentType(), c.getPurpose(), c.getSharedWith(),
                c.getStatus(), effective, c.getGrantedAt(), c.getExpiresAt(), c.getWithdrawnAt(), c.getWithdrawalReason(),
                cache.user(c.getCapturedBy()), c.getCaptureMethod(), staff ? c.getNotes() : null,
                effective == ConsentRecord.Status.GRANTED && PORTAL_WITHDRAWABLE.contains(c.getConsentType()), c.getVersion());
    }

    private RequestView view(PatientRequest r, Cache cache, boolean staff) {
        Patient p = cache.patient(r.getPatientId());
        return new RequestView(r.getId(), r.getPatientId(), p == null ? null : p.fullName(), p == null ? null : p.getMrn(),
                r.getRequestType(), r.getSubject(), r.getDescription(), r.getStatus(), cache.user(r.getRaisedBy()), r.getRaisedAt(),
                r.getResponse(), staff ? cache.user(r.getRespondedBy()) : (r.getRespondedBy() == null ? null : "Hospital privacy team"),
                r.getRespondedAt(), r.getVersion());
    }

    private static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String humanize(String v) {
        String s = v.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
