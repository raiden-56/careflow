package com.careflow.hms.document;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Secure patient documents: whitelisted types verified by magic bytes, size limit, sanitized names,
 * random storage keys, SHA-256 integrity hashes, release to the portal, archive (no hard delete) and
 * audited downloads through short-lived signed links.
 */
@Service
@RequiredArgsConstructor
public class DocumentService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;

    /** Allowed content types and their canonical file extensions. */
    static final Map<String, String> ALLOWED = Map.of("application/pdf", "pdf", "image/png", "png", "image/jpeg", "jpg");

    private final PatientDocumentRepository documents;
    private final AdmissionRepository admissions;
    private final UserRepository users;
    private final AccessPolicy accessPolicy;
    private final DocumentStorage storage;
    private final DownloadTokenService tokens;
    private final AuditService audit;
    private final TimelineService timeline;
    private final NotificationService notifications;

    public record DocumentView(UUID id, UUID patientId, UUID admissionId, String admissionNumber,
                               PatientDocument.DocumentType documentType, String title, String fileName, String contentType,
                               long sizeBytes, String sha256, boolean releasedToPatient, PatientDocument.Status status,
                               String uploadedByName, Instant uploadedAt, Long version) {
    }

    public record DownloadLink(String url, Instant expiresAt) {
    }

    public record FileContent(String fileName, String contentType, byte[] bytes) {
    }

    // ------------------------------------------------------------------ upload

    @Transactional
    public DocumentView upload(UUID patientId, UUID admissionId, PatientDocument.DocumentType type, String title,
                               String originalFileName, String declaredContentType, byte[] content) {
        CurrentUser u = TenantContext.requireUser();
        Patient p = accessPolicy.requirePatient(patientId, AccessPolicy.Scope.CLINICAL);
        if (admissionId != null) {
            Admission a = admissions.findByIdAndOrganizationId(admissionId, u.organizationId())
                    .orElseThrow(() -> ApiException.notFound("Admission"));
            if (!a.getPatientId().equals(p.getId())) {
                throw ApiException.badRequest("ADMISSION_MISMATCH", "The admission does not belong to this patient.");
            }
        }
        if (title == null || title.isBlank() || title.trim().length() > 300) {
            throw ApiException.badRequest("TITLE_REQUIRED", "Enter a title of up to 300 characters.");
        }
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("EMPTY_FILE", "The file is empty.");
        }
        if (content.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Files must be 10 MB or smaller.");
        }
        String declared = normalizeType(declaredContentType);
        String detected = detectType(content);
        if (declared == null || !ALLOWED.containsKey(declared) || detected == null || !detected.equals(declared)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                    "Only PDF, PNG and JPEG files are accepted, and the file content must match its type.");
        }
        String ext = ALLOWED.get(detected);
        String fileName = sanitizeFileName(originalFileName, ext);
        String key = storage.store(u.organizationId(), ext, content);
        removeFileOnRollback(key);

        PatientDocument d = new PatientDocument();
        d.setPatientId(p.getId());
        d.setAdmissionId(admissionId);
        d.setDocumentType(type);
        d.setTitle(title.trim());
        d.setFileName(fileName);
        d.setContentType(detected);
        d.setSizeBytes(content.length);
        d.setSha256(sha256(content));
        d.setStorageKey(key);
        d.setUploadedBy(u.userId());
        d.setUploadedAt(Instant.now());
        d = documents.saveAndFlush(d);
        audit.record("DOCUMENT_UPLOADED", "DOCUMENT", d.getId(), p.getId(), AuditEvent.Outcome.SUCCESS,
                type + ": " + d.getTitle(), Map.of("sizeBytes", d.getSizeBytes(), "contentType", d.getContentType(), "sha256", d.getSha256()));
        timeline.record(TimelineService.Entry.builder()
                .patientId(p.getId()).admissionId(admissionId)
                .eventType("DOCUMENT_UPLOADED").category(TimelineEvent.Category.CLINICAL)
                .title("Document added: " + d.getTitle())
                .visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("DOCUMENT").entityId(d.getId())
                .build());
        return view(d);
    }

    // ------------------------------------------------------------------ staff views & actions

    @Transactional(readOnly = true)
    public List<DocumentView> list(UUID patientId, UUID admissionId, boolean includeArchived) {
        if (patientId == null && admissionId == null) {
            throw ApiException.badRequest("FILTER_REQUIRED", "Specify a patient or an admission.");
        }
        if (admissionId != null) {
            Admission a = accessPolicy.requireAdmission(admissionId, AccessPolicy.Scope.CLINICAL);
            if (patientId != null && !patientId.equals(a.getPatientId())) {
                throw ApiException.badRequest("ADMISSION_MISMATCH", "The admission does not belong to this patient.");
            }
            return documents.findByAdmissionIdAndStatusOrderByUploadedAtDesc(admissionId, PatientDocument.Status.ACTIVE).stream()
                    .map(this::view).toList();
        }
        accessPolicy.requirePatient(patientId, AccessPolicy.Scope.CLINICAL);
        List<PatientDocument> active = documents.findByPatientIdAndStatusOrderByUploadedAtDesc(patientId, PatientDocument.Status.ACTIVE);
        if (!includeArchived) {
            return active.stream().map(this::view).toList();
        }
        List<PatientDocument> all = new java.util.ArrayList<>(active);
        all.addAll(documents.findByPatientIdAndStatusOrderByUploadedAtDesc(patientId, PatientDocument.Status.ARCHIVED));
        return all.stream().map(this::view).toList();
    }

    @Transactional
    public DocumentView release(UUID id) {
        PatientDocument d = requireStaffAccess(id);
        if (d.getStatus() != PatientDocument.Status.ACTIVE) {
            throw ApiException.rule("DOCUMENT_ARCHIVED", "Archived documents cannot be released.");
        }
        if (d.isReleasedToPatient()) {
            return view(d);
        }
        d.setReleasedToPatient(true);
        documents.saveAndFlush(d);
        audit.record("DOCUMENT_RELEASED", "DOCUMENT", d.getId(), d.getPatientId(), AuditEvent.Outcome.SUCCESS,
                "Released to patient portal: " + d.getTitle(), null);
        timeline.record(TimelineService.Entry.builder()
                .patientId(d.getPatientId()).admissionId(d.getAdmissionId())
                .eventType("DOCUMENT_RELEASED").category(TimelineEvent.Category.CLINICAL)
                .title("New document available: " + d.getTitle())
                .visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("DOCUMENT").entityId(d.getId())
                .build());
        notifications.notifyPatient(d.getPatientId(), NotificationRequest.builder()
                .category("DOCUMENT").priority(Notification.Priority.NORMAL)
                .title("A document is available in your portal")
                .message(d.getTitle() + " has been shared with you.")
                .link("/patient/privacy")
                .entityType("DOCUMENT").entityId(d.getId())
                .dedupeKey("DOCUMENT_RELEASED:" + d.getId())
                .build());
        return view(d);
    }

    @Transactional
    public DocumentView archive(UUID id, String reason) {
        PatientDocument d = requireStaffAccess(id);
        if (d.getStatus() == PatientDocument.Status.ARCHIVED) {
            return view(d);
        }
        d.setStatus(PatientDocument.Status.ARCHIVED);
        d.setReleasedToPatient(false);
        documents.saveAndFlush(d);
        audit.record("DOCUMENT_ARCHIVED", "DOCUMENT", d.getId(), d.getPatientId(), AuditEvent.Outcome.SUCCESS,
                d.getTitle() + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()), null);
        return view(d);
    }

    // ------------------------------------------------------------------ portal

    @Transactional(readOnly = true)
    public List<DocumentView> portalDocuments(UUID patientId) {
        accessPolicy.requirePatient(patientId, AccessPolicy.Scope.CLINICAL);
        return documents.findByPatientIdAndStatusOrderByUploadedAtDesc(patientId, PatientDocument.Status.ACTIVE).stream()
                .filter(PatientDocument::isReleasedToPatient)
                .map(this::view).toList();
    }

    // ------------------------------------------------------------------ download links

    @Transactional(readOnly = true)
    public DownloadLink downloadLink(UUID id) {
        CurrentUser u = TenantContext.requireUser();
        PatientDocument d = documents.findByIdAndOrganizationId(id, u.organizationId())
                .orElseThrow(() -> ApiException.notFound("Document"));
        boolean patient = u.role() == Role.PATIENT;
        if (patient) {
            // Portal users only ever see released, active documents; anything else does not exist for them.
            if (!d.isReleasedToPatient() || d.getStatus() != PatientDocument.Status.ACTIVE) {
                throw ApiException.notFound("Document");
            }
        }
        accessPolicy.requirePatient(d.getPatientId(), AccessPolicy.Scope.CLINICAL);
        DownloadTokenService.Issued t = tokens.issue(d.getId(), u.organizationId(), u.userId(), patient);
        return new DownloadLink("/api/documents/download/" + t.token(), t.expiresAt());
    }

    /** Resolves a signed link (no session required). Every attempt is audited. */
    @Transactional(readOnly = true)
    public FileContent download(String token) {
        DownloadTokenService.Verification v = tokens.verify(token);
        if (v instanceof DownloadTokenService.Invalid) {
            audit.recordAs(null, null, "Anonymous", "UNKNOWN", "DOCUMENT_DOWNLOAD_REJECTED", "DOCUMENT", null, null,
                    AuditEvent.Outcome.DENIED, "Invalid or tampered download link", null);
            throw ApiException.forbidden("This download link is invalid.");
        }
        if (v instanceof DownloadTokenService.Expired e) {
            User u = users.findById(e.claims().userId()).orElse(null);
            audit.recordAs(e.claims().organizationId(), e.claims().userId(), u == null ? null : u.getFullName(),
                    u == null ? null : u.getRole().name(), "DOCUMENT_DOWNLOAD_REJECTED", "DOCUMENT", e.claims().documentId(), null,
                    AuditEvent.Outcome.DENIED, "Expired download link", null);
            throw new ApiException(HttpStatus.GONE, "LINK_EXPIRED", "This download link has expired. Request a new one.");
        }
        DownloadTokenService.Claims c = ((DownloadTokenService.Valid) v).claims();
        Optional<PatientDocument> found = documents.findByIdAndOrganizationId(c.documentId(), c.organizationId());
        User user = users.findById(c.userId()).filter(x -> x.getOrganizationId().equals(c.organizationId())
                && x.getStatus() == User.Status.ACTIVE).orElse(null);
        if (found.isEmpty() || user == null
                || (c.patientScope() && (!found.get().isReleasedToPatient() || found.get().getStatus() != PatientDocument.Status.ACTIVE))) {
            audit.recordAs(c.organizationId(), c.userId(), user == null ? null : user.getFullName(),
                    user == null ? null : user.getRole().name(), "DOCUMENT_DOWNLOAD_REJECTED", "DOCUMENT", c.documentId(),
                    found.map(PatientDocument::getPatientId).orElse(null), AuditEvent.Outcome.DENIED,
                    "Document no longer available to this user", null);
            throw ApiException.notFound("Document");
        }
        PatientDocument d = found.get();
        byte[] bytes = storage.read(d.getStorageKey());
        audit.recordAs(c.organizationId(), user.getId(), user.getFullName(), user.getRole().name(), "DOCUMENT_DOWNLOADED",
                "DOCUMENT", d.getId(), d.getPatientId(), AuditEvent.Outcome.SUCCESS, "Downloaded " + d.getTitle(),
                Map.of("fileName", d.getFileName(), "sha256", d.getSha256()));
        return new FileContent(d.getFileName(), d.getContentType(), bytes);
    }

    // ------------------------------------------------------------------ helpers

    private PatientDocument requireStaffAccess(UUID id) {
        CurrentUser u = TenantContext.requireUser();
        PatientDocument d = documents.findByIdAndOrganizationId(id, u.organizationId()).orElseThrow(() -> ApiException.notFound("Document"));
        accessPolicy.requirePatient(d.getPatientId(), AccessPolicy.Scope.CLINICAL);
        return d;
    }

    static String normalizeType(String t) {
        if (t == null) {
            return null;
        }
        String s = t.toLowerCase().split(";")[0].trim();
        return switch (s) {
            case "image/jpg", "image/pjpeg" -> "image/jpeg";
            case "application/x-pdf" -> "application/pdf";
            default -> s;
        };
    }

    /** Identifies the file type from its leading bytes (never trusts the client-supplied type alone). */
    static String detectType(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return "application/pdf";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }

    /** Keeps a safe display name: no paths, no control characters, a whitelist of characters and the right extension. */
    static String sanitizeFileName(String original, String ext) {
        String name = original == null ? "" : original;
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").replaceAll("[^A-Za-z0-9._() -]", "_").replaceAll("_+", "_").trim();
        name = name.replaceAll("^[.\\s]+", "");
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (base.isBlank()) {
            base = "document";
        }
        if (base.length() > 100) {
            base = base.substring(0, 100);
        }
        return base + "." + ext;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void removeFileOnRollback(String key) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        storage.deleteQuietly(key);
                    }
                }
            });
        }
    }

    private DocumentView view(PatientDocument d) {
        String admissionNumber = d.getAdmissionId() == null ? null
                : admissions.findById(d.getAdmissionId()).map(Admission::getAdmissionNumber).orElse(null);
        String by = users.findById(d.getUploadedBy()).map(User::getFullName).orElse(null);
        return new DocumentView(d.getId(), d.getPatientId(), d.getAdmissionId(), admissionNumber, d.getDocumentType(), d.getTitle(),
                d.getFileName(), d.getContentType(), d.getSizeBytes(), d.getSha256(), d.isReleasedToPatient(), d.getStatus(), by,
                d.getUploadedAt(), d.getVersion());
    }
}
