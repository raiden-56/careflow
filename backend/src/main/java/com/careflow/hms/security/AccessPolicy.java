package com.careflow.hms.security;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionCareTeamRepository;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.admission.BedAssignmentRepository;
import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.facility.StaffWardAssignmentRepository;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientAccessGrant;
import com.careflow.hms.patient.PatientAccessGrantRepository;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.user.StaffProfile;
import com.careflow.hms.user.StaffProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Object-level authorization for patient data. Role permissions (@PreAuthorize) say WHAT a user may
 * do; this policy decides WHICH patients/admissions they may do it for.
 *
 * <ul>
 *   <li>Records outside the caller's organization are reported as 404 (no existence leak).</li>
 *   <li>PATIENT: own record and dependants with an active, in-scope caregiver grant.</li>
 *   <li>DOCTOR: clinical access to admissions they attend / are on the care team of, or open
 *       admissions of their own department.</li>
 *   <li>NURSE: clinical access to open admissions located in their assigned wards, or where they are the primary nurse.</li>
 *   <li>PHARMACIST: medication scope only. BILLING: financial scope only.</li>
 *   <li>HOSPITAL_ADMIN: demographics; clinical/financial only if those permissions were explicitly granted.</li>
 *   <li>SUPER_ADMIN: no patient data.</li>
 * </ul>
 * Clinical reads are audited (throttled per user/patient); denials are always audited.
 */
@Service
@RequiredArgsConstructor
public class AccessPolicy {

    public enum Scope { DEMOGRAPHICS, CLINICAL, MEDICATION, FINANCIAL }

    private static final long READ_AUDIT_THROTTLE_MS = 10 * 60 * 1000;

    private final PatientRepository patients;
    private final AdmissionRepository admissions;
    private final AdmissionCareTeamRepository careTeam;
    private final BedAssignmentRepository bedAssignments;
    private final BedRepository beds;
    private final StaffWardAssignmentRepository wardAssignments;
    private final StaffProfileRepository staffProfiles;
    private final PatientAccessGrantRepository grants;
    private final AuditService audit;
    private final Map<String, Long> lastReadAudit = new ConcurrentHashMap<>();

    /** Loads a patient the current user may access in the given scope, or throws 404/403. */
    public Patient requirePatient(UUID patientId, Scope scope) {
        CurrentUser u = TenantContext.requireUser();
        Patient p = patients.findByIdAndOrganizationId(patientId, u.organizationId())
                .orElseThrow(() -> ApiException.notFound("Patient"));
        if (!canAccessPatient(u, p, scope)) {
            deny(u, "PATIENT", p.getId(), p.getId(), scope);
        }
        auditRead(u, "PATIENT", p.getId(), p.getId(), scope);
        return p;
    }

    /** Loads an admission the current user may access in the given scope, or throws 404/403. */
    public Admission requireAdmission(UUID admissionId, Scope scope) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = admissions.findByIdAndOrganizationId(admissionId, u.organizationId())
                .orElseThrow(() -> ApiException.notFound("Admission"));
        if (!canAccessAdmission(u, a, scope)) {
            deny(u, "ADMISSION", a.getId(), a.getPatientId(), scope);
        }
        auditRead(u, "ADMISSION", a.getId(), a.getPatientId(), scope);
        return a;
    }

    public boolean canAccessPatient(CurrentUser u, Patient p, Scope scope) {
        if (!Objects.equals(p.getOrganizationId(), u.organizationId())) {
            return false;
        }
        return switch (u.role()) {
            case SUPER_ADMIN -> false;
            case PATIENT -> patientPortalAllows(u, p.getId(), scope);
            case HOSPITAL_ADMIN -> switch (scope) {
                case DEMOGRAPHICS -> u.has(Permissions.PATIENT_DEMOGRAPHICS_READ);
                case CLINICAL, MEDICATION -> u.has(Permissions.CLINICAL_READ);
                case FINANCIAL -> u.has(Permissions.BILLING_READ);
            };
            case BILLING -> switch (scope) {
                case DEMOGRAPHICS -> u.has(Permissions.PATIENT_DEMOGRAPHICS_READ);
                case FINANCIAL -> u.has(Permissions.BILLING_READ);
                default -> false;
            };
            case PHARMACIST -> switch (scope) {
                case DEMOGRAPHICS -> u.has(Permissions.PATIENT_DEMOGRAPHICS_READ);
                case MEDICATION -> u.has(Permissions.DISPENSE);
                default -> false;
            };
            case DOCTOR, NURSE -> switch (scope) {
                case DEMOGRAPHICS -> u.has(Permissions.PATIENT_DEMOGRAPHICS_READ);
                case FINANCIAL -> false;
                case CLINICAL, MEDICATION -> u.has(Permissions.CLINICAL_READ)
                        && admissions.findByPatientIdOrderByAdmittedAtDesc(p.getId()).stream()
                        .anyMatch(a -> clinicianAllowed(u, a));
            };
        };
    }

    public boolean canAccessAdmission(CurrentUser u, Admission a, Scope scope) {
        if (!Objects.equals(a.getOrganizationId(), u.organizationId())) {
            return false;
        }
        return switch (u.role()) {
            case DOCTOR, NURSE -> switch (scope) {
                case DEMOGRAPHICS -> u.has(Permissions.PATIENT_DEMOGRAPHICS_READ);
                case FINANCIAL -> false;
                case CLINICAL, MEDICATION -> u.has(Permissions.CLINICAL_READ) && clinicianAllowed(u, a);
            };
            case PATIENT -> patientPortalAllows(u, a.getPatientId(), scope);
            default -> patients.findById(a.getPatientId()).map(p -> canAccessPatient(u, p, scope)).orElse(false);
        };
    }

    /** True when the clinician is responsible for (or currently caring for) this admission. */
    public boolean clinicianAllowed(CurrentUser u, Admission a) {
        if (u.userId().equals(a.getAttendingDoctorId())
                || careTeam.existsByAdmissionIdAndUserIdAndRemovedAtIsNull(a.getId(), u.userId())) {
            return true;
        }
        if (!a.getStatus().isOpen()) {
            return false;
        }
        if (u.role() == Role.DOCTOR) {
            Optional<StaffProfile> sp = staffProfiles.findById(u.userId());
            return sp.isPresent() && sp.get().getDepartmentId() != null
                    && sp.get().getDepartmentId().equals(a.getDepartmentId());
        }
        if (u.role() == Role.NURSE) {
            List<UUID> wardIds = wardAssignments.activeWardIds(u.userId());
            if (wardIds.isEmpty()) {
                return false;
            }
            return bedAssignments.findByAdmissionIdAndReleasedAtIsNull(a.getId())
                    .flatMap(ba -> beds.findById(ba.getBedId()))
                    .map(b -> wardIds.contains(b.getWardId()))
                    .orElse(false);
        }
        return false;
    }

    /** Patient portal: own record or an active caregiver grant whose scope covers the request. */
    public boolean patientPortalAllows(CurrentUser u, UUID patientId, Scope scope) {
        if (!u.has(Permissions.PORTAL_ACCESS)) {
            return false;
        }
        if (patientId.equals(u.patientId())) {
            return true;
        }
        Instant now = Instant.now();
        return grants.findByPatientIdAndGranteeUserIdAndRevokedAtIsNull(patientId, u.userId())
                .filter(g -> g.isActive(now))
                .map(g -> switch (g.getScope()) {
                    case FULL -> true;
                    case BILLING_ONLY -> scope == Scope.FINANCIAL || scope == Scope.DEMOGRAPHICS;
                    case CLINICAL_ONLY -> scope != Scope.FINANCIAL;
                })
                .orElse(false);
    }

    /** Patient ids a portal user may see (self + active grants), for list endpoints. */
    public List<UUID> portalPatientIds(CurrentUser u, Scope scope) {
        Instant now = Instant.now();
        java.util.ArrayList<UUID> ids = new java.util.ArrayList<>();
        if (u.patientId() != null) {
            ids.add(u.patientId());
        }
        for (PatientAccessGrant g : grants.findByGranteeUserIdAndRevokedAtIsNull(u.userId())) {
            if (g.isActive(now) && patientPortalAllows(u, g.getPatientId(), scope)) {
                ids.add(g.getPatientId());
            }
        }
        return ids;
    }

    private void deny(CurrentUser u, String entityType, UUID entityId, UUID patientId, Scope scope) {
        audit.record("ACCESS_DENIED", entityType, entityId, patientId, AuditEvent.Outcome.DENIED,
                u.role() + " denied " + scope + " access", Map.of("scope", scope.name()));
        // Patients probing other records get 404 so they cannot confirm existence.
        if (u.role() == Role.PATIENT) {
            throw ApiException.notFound(entityType.equals("PATIENT") ? "Patient" : "Admission");
        }
        throw ApiException.forbidden("You are not authorized to access this patient's " + scope.name().toLowerCase() + " records.");
    }

    private void auditRead(CurrentUser u, String entityType, UUID entityId, UUID patientId, Scope scope) {
        if (scope == Scope.DEMOGRAPHICS || u.role() == Role.PATIENT && patientId.equals(u.patientId())) {
            return;
        }
        String key = u.userId() + ":" + patientId + ":" + scope;
        long now = System.currentTimeMillis();
        Long last = lastReadAudit.get(key);
        if (last != null && now - last < READ_AUDIT_THROTTLE_MS) {
            return;
        }
        lastReadAudit.put(key, now);
        audit.record("READ_" + scope.name(), entityType, entityId, patientId, AuditEvent.Outcome.SUCCESS,
                "Viewed " + scope.name().toLowerCase() + " records", null);
    }
}
