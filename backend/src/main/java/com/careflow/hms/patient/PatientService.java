package com.careflow.hms.patient;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.admission.BedAssignmentRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.auth.PasswordResetToken;
import com.careflow.hms.auth.PasswordResetTokenRepository;
import com.careflow.hms.auth.TokenHasher;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.config.CareFlowProperties;
import com.careflow.hms.facility.Bed;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.facility.RoomRepository;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.notification.ExternalChannelGateway;
import com.careflow.hms.notification.NotificationDelivery;
import com.careflow.hms.org.SequenceService;
import com.careflow.hms.patient.PatientDtos.CaregiverGrantRequest;
import com.careflow.hms.patient.PatientDtos.CaregiverGrantView;
import com.careflow.hms.patient.PatientDtos.CurrentLocation;
import com.careflow.hms.patient.PatientDtos.DuplicateCandidate;
import com.careflow.hms.patient.PatientDtos.PatientDetail;
import com.careflow.hms.patient.PatientDtos.PatientRequest;
import com.careflow.hms.patient.PatientDtos.PatientSummary;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Permissions;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PatientService {

    private static final Map<String, String> SORTS = Map.of(
            "name", "lastName", "mrn", "mrn", "createdAt", "createdAt", "dateOfBirth", "dateOfBirth");

    private final PatientRepository patients;
    private final PatientAccessGrantRepository grants;
    private final AdmissionRepository admissions;
    private final BedAssignmentRepository bedAssignments;
    private final BedRepository beds;
    private final RoomRepository rooms;
    private final WardRepository wards;
    private final UserRepository users;
    private final PasswordResetTokenRepository resetTokens;
    private final SequenceService sequences;
    private final AccessPolicy access;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;
    private final ExternalChannelGateway mailGateway;
    private final CareFlowProperties props;

    @Transactional(readOnly = true)
    public PageResponse<PatientSummary> search(String q, Patient.Status status, int page, int size, String sort) {
        CurrentUser u = TenantContext.requireUser();
        var pageable = Paging.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = patients.search(u.organizationId(), status, Paging.likePattern(q), pageable);
        return PageResponse.of(result, this::toSummary);
    }

    @Transactional(readOnly = true)
    public PatientDetail get(UUID id) {
        Patient p = access.requirePatient(id, AccessPolicy.Scope.DEMOGRAPHICS);
        return toDetail(p);
    }

    @Transactional(readOnly = true)
    public List<DuplicateCandidate> findDuplicates(PatientRequest r) {
        CurrentUser u = TenantContext.requireUser();
        String q = Paging.likePattern(r.firstName() + " " + r.lastName());
        return patients.search(u.organizationId(), null, q, org.springframework.data.domain.PageRequest.of(0, 20))
                .getContent().stream()
                .filter(p -> p.getDateOfBirth().equals(r.dateOfBirth())
                        || (r.phone() != null && r.phone().equals(p.getPhone())))
                .map(p -> new DuplicateCandidate(p.getId(), p.getMrn(), p.fullName(), p.getDateOfBirth(), p.getPhone()))
                .toList();
    }

    @Transactional
    public PatientDetail register(PatientRequest r) {
        if (r.dateOfBirth().isAfter(LocalDate.now())) {
            throw ApiException.badRequest("INVALID_DOB", "Date of birth cannot be in the future.");
        }
        if (!r.confirmNotDuplicate()) {
            List<DuplicateCandidate> dups = findDuplicates(r);
            if (!dups.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "POSSIBLE_DUPLICATE",
                        "A patient with the same name and date of birth or phone already exists ("
                                + dups.get(0).mrn() + "). Confirm to register anyway.");
            }
        }
        Patient p = new Patient();
        apply(p, r);
        p.setMrn(sequences.mrn());
        p.setCreatedBy(TenantContext.actorId());
        p = patients.saveAndFlush(p);
        audit.record("PATIENT_REGISTERED", "PATIENT", p.getId(), p.getId(), "Registered " + p.getMrn());
        return toDetail(p);
    }

    @Transactional
    public PatientDetail update(UUID id, PatientRequest r) {
        Patient p = access.requirePatient(id, AccessPolicy.Scope.DEMOGRAPHICS);
        if (r.version() == null || !r.version().equals(p.getVersion())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This record was changed by someone else. Reload and try again.");
        }
        apply(p, r);
        audit.record("PATIENT_UPDATED", "PATIENT", p.getId(), p.getId(), "Demographics updated");
        return toDetail(patients.saveAndFlush(p));
    }

    /** Creates a portal login for the patient and sends a set-password invitation. */
    @Transactional
    public PatientDetail createPortalAccount(UUID id, String email) {
        Patient p = access.requirePatient(id, AccessPolicy.Scope.DEMOGRAPHICS);
        if (p.getUserId() != null) {
            throw ApiException.conflict("PORTAL_EXISTS", "This patient already has a portal account.");
        }
        User user = createPortalUser(email, p.fullName(), p.getPhone());
        p.setUserId(user.getId());
        audit.record("PORTAL_ACCOUNT_CREATED", "PATIENT", p.getId(), p.getId(), "Portal invitation sent");
        return toDetail(p);
    }

    @Transactional(readOnly = true)
    public List<CaregiverGrantView> listCaregivers(UUID patientId) {
        Patient p = requireGrantManagement(patientId);
        return grants.findByPatientIdOrderByGrantedAtDesc(p.getId()).stream().map(this::toGrantView).toList();
    }

    /**
     * Grants a caregiver access. Patients may grant access to an existing portal user; registration
     * staff may additionally create the caregiver's portal account.
     */
    @Transactional
    public CaregiverGrantView addCaregiver(UUID patientId, CaregiverGrantRequest r) {
        CurrentUser u = TenantContext.requireUser();
        Patient p = requireGrantManagement(patientId);
        User grantee = users.findByEmailIgnoreCase(r.email().trim()).orElse(null);
        if (grantee == null) {
            if (u.role() == Role.PATIENT) {
                throw ApiException.rule("CAREGIVER_NOT_REGISTERED",
                        "No portal account exists for that email. Ask the hospital registration desk to register the caregiver.");
            }
            if (r.fullName() == null || r.fullName().isBlank()) {
                throw ApiException.badRequest("NAME_REQUIRED", "Caregiver name is required to create a portal account.");
            }
            grantee = createPortalUser(r.email(), r.fullName(), r.phone());
        }
        if (!grantee.getOrganizationId().equals(u.organizationId()) || grantee.getRole() != Role.PATIENT) {
            throw ApiException.rule("INVALID_CAREGIVER", "Caregiver access can only be granted to a patient-portal account of this hospital.");
        }
        if (grantee.getId().equals(p.getUserId())) {
            throw ApiException.rule("INVALID_CAREGIVER", "A patient cannot be their own caregiver.");
        }
        PatientAccessGrant g = new PatientAccessGrant();
        g.setPatientId(p.getId());
        g.setGranteeUserId(grantee.getId());
        g.setRelationship(r.relationship());
        g.setScope(r.scope());
        g.setExpiresAt(r.expiresAt());
        g.setNotes(r.notes());
        g.setGrantedBy(u.userId());
        g.setGrantedAt(Instant.now());
        g = grants.saveAndFlush(g);
        audit.record("CAREGIVER_GRANTED", "PATIENT", p.getId(), p.getId(),
                "Granted " + r.scope() + " access to " + grantee.getFullName());
        return toGrantView(g);
    }

    @Transactional
    public CaregiverGrantView revokeCaregiver(UUID patientId, UUID grantId) {
        CurrentUser u = TenantContext.requireUser();
        Patient p = requireGrantManagement(patientId);
        PatientAccessGrant g = grants.findById(grantId)
                .filter(x -> x.getPatientId().equals(p.getId()))
                .orElseThrow(() -> ApiException.notFound("Caregiver access"));
        if (g.getRevokedAt() == null) {
            g.setRevokedAt(Instant.now());
            g.setRevokedBy(u.userId());
            audit.record("CAREGIVER_REVOKED", "PATIENT", p.getId(), p.getId(), "Caregiver access revoked");
        }
        return toGrantView(g);
    }

    /** Only the patient themself (not a caregiver) or registration staff manage caregiver access. */
    private Patient requireGrantManagement(UUID patientId) {
        CurrentUser u = TenantContext.requireUser();
        if (u.role() == Role.PATIENT) {
            if (!patientId.equals(u.patientId())) {
                throw ApiException.notFound("Patient");
            }
            return patients.findById(patientId).orElseThrow(() -> ApiException.notFound("Patient"));
        }
        if (!u.has(Permissions.PATIENT_REGISTER)) {
            throw ApiException.forbidden("You do not have permission to manage caregiver access.");
        }
        return access.requirePatient(patientId, AccessPolicy.Scope.DEMOGRAPHICS);
    }

    private User createPortalUser(String email, String fullName, String phone) {
        if (users.findByEmailIgnoreCase(email.trim()).isPresent()) {
            throw ApiException.conflict("EMAIL_IN_USE", "An account with this email already exists.");
        }
        User user = new User();
        user.setEmail(email.trim().toLowerCase());
        user.setFullName(fullName);
        user.setPhone(phone);
        user.setRole(Role.PATIENT);
        user.setStatus(User.Status.ACTIVE);
        // Unusable random password until the invitation is accepted.
        user.setPasswordHash(passwordEncoder.encode(TokenHasher.newToken()));
        user.setMustChangePassword(true);
        user = users.saveAndFlush(user);
        String raw = TokenHasher.newToken();
        PasswordResetToken t = new PasswordResetToken();
        t.setUserId(user.getId());
        t.setTokenHash(TokenHasher.sha256(raw));
        t.setPurpose("INVITE");
        t.setCreatedAt(Instant.now());
        t.setExpiresAt(Instant.now().plusSeconds(7 * 24 * 3600));
        resetTokens.save(t);
        mailGateway.send(user.getOrganizationId(), NotificationDelivery.Channel.EMAIL, user.getEmail(),
                "Your CareFlow patient portal invitation",
                "Hello " + fullName + ",\n\nSet your password to access the patient portal (valid 7 days):\n"
                        + props.frontendBaseUrl() + "/reset-password?token=" + raw);
        return user;
    }

    private void apply(Patient p, PatientRequest r) {
        p.setFirstName(r.firstName().trim());
        p.setLastName(r.lastName().trim());
        p.setDateOfBirth(r.dateOfBirth());
        p.setGender(r.gender());
        p.setBloodGroup(blankToNull(r.bloodGroup()));
        p.setPhone(blankToNull(r.phone()));
        p.setEmail(blankToNull(r.email()));
        p.setAddressLine(blankToNull(r.addressLine()));
        p.setCity(blankToNull(r.city()));
        p.setState(blankToNull(r.state()));
        p.setPincode(blankToNull(r.pincode()));
        p.setEmergencyContactName(blankToNull(r.emergencyContactName()));
        p.setEmergencyContactPhone(blankToNull(r.emergencyContactPhone()));
        p.setEmergencyContactRelation(blankToNull(r.emergencyContactRelation()));
        p.setAbhaNumber(blankToNull(r.abhaNumber()));
        p.setPreferredLanguage(blankToNull(r.preferredLanguage()));
    }

    public CurrentLocation currentLocation(UUID patientId) {
        Optional<Admission> open = admissions.findOpenByPatient(patientId);
        if (open.isEmpty()) {
            return null;
        }
        Admission a = open.get();
        String ward = null, room = null, bed = null;
        var ba = bedAssignments.findByAdmissionIdAndReleasedAtIsNull(a.getId());
        if (ba.isPresent()) {
            Bed b = beds.findById(ba.get().getBedId()).orElse(null);
            if (b != null) {
                bed = b.getBedNumber();
                room = rooms.findById(b.getRoomId()).map(x -> x.getRoomNumber()).orElse(null);
                ward = wards.findById(b.getWardId()).map(x -> x.getName()).orElse(null);
            }
        }
        return new CurrentLocation(a.getId(), a.getAdmissionNumber(), a.getStatus(), ward, room, bed);
    }

    PatientSummary toSummary(Patient p) {
        return new PatientSummary(p.getId(), p.getMrn(), p.fullName(), p.getFirstName(), p.getLastName(),
                p.getDateOfBirth(), p.ageYears(TimeUtil.today()), p.getGender(), p.getPhone(), p.getStatus(),
                p.getUserId() != null, currentLocation(p.getId()), p.getCreatedAt());
    }

    PatientDetail toDetail(Patient p) {
        return new PatientDetail(p.getId(), p.getMrn(), p.getFirstName(), p.getLastName(), p.fullName(),
                p.getDateOfBirth(), p.ageYears(TimeUtil.today()), p.getGender(), p.getBloodGroup(), p.getPhone(),
                p.getEmail(), p.getAddressLine(), p.getCity(), p.getState(), p.getPincode(),
                p.getEmergencyContactName(), p.getEmergencyContactPhone(), p.getEmergencyContactRelation(),
                p.getAbhaNumber(), p.getPreferredLanguage(), p.getStatus(), p.getUserId() != null,
                currentLocation(p.getId()), p.getCreatedAt(), p.getUpdatedAt(), p.getVersion());
    }

    private CaregiverGrantView toGrantView(PatientAccessGrant g) {
        User grantee = users.findById(g.getGranteeUserId()).orElse(null);
        return new CaregiverGrantView(g.getId(), g.getGranteeUserId(),
                grantee == null ? null : grantee.getFullName(), grantee == null ? null : grantee.getEmail(),
                g.getRelationship(), g.getScope(), g.getGrantedAt(), g.getExpiresAt(), g.getRevokedAt(),
                g.isActive(Instant.now()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }
}
