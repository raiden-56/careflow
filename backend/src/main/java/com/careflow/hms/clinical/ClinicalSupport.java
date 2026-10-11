package com.careflow.hms.clinical;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.admission.AdmissionService;
import com.careflow.hms.admission.BedAssignmentRepository;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.TenantEntity;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.facility.Ward;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shared helpers for the clinical and medication modules: object-level access, the caller's
 * worklist, name lookups and patient/location references for list rows.
 */
@Component
@RequiredArgsConstructor
public class ClinicalSupport {

    private final AccessPolicy access;
    private final AdmissionRepository admissions;
    private final AdmissionService admissionService;
    private final BedAssignmentRepository bedAssignments;
    private final BedRepository beds;
    private final WardRepository wards;
    private final UserRepository users;

    /** Patient / location context attached to list rows. */
    public record PatientRef(UUID admissionId, String admissionNumber, UUID patientId, String patientName, String mrn,
                             String location, UUID wardId, String wardName, Admission.Status admissionStatus,
                             UUID attendingDoctorId, String attendingDoctorName, List<String> allergies) {
    }

    /** Clinical access to an admission (403/404 otherwise). */
    public Admission requireClinical(UUID admissionId) {
        return access.requireAdmission(admissionId, AccessPolicy.Scope.CLINICAL);
    }

    /** Clinical access to an admission that is still open (PENDING_BED / ADMITTED / DISCHARGE_IN_PROGRESS). */
    public Admission requireOpenClinical(UUID admissionId) {
        Admission a = requireClinical(admissionId);
        if (!a.getStatus().isOpen()) {
            throw ApiException.rule("ADMISSION_CLOSED", "This admission is closed; clinical records can no longer be added.");
        }
        return a;
    }

    /** Loads an org-scoped row by id (404 when missing or in another organization). */
    public <T extends TenantEntity> T requireOrg(Optional<T> row, String what) {
        UUID org = TenantContext.requireOrganizationId();
        return row.filter(r -> Objects.equals(r.getOrganizationId(), org)).orElseThrow(() -> ApiException.notFound(what));
    }

    /** Open admissions on the caller's worklist (doctor: attending/care team/department; nurse: wards/primary). */
    public List<AdmissionSummary> myAdmissions() {
        return admissionService.myAdmissions();
    }

    public List<UUID> myAdmissionIds() {
        return myAdmissions().stream().map(AdmissionSummary::id).toList();
    }

    /** Summaries keyed by admission id for arbitrary admissions (batch). */
    public Map<UUID, AdmissionSummary> summaries(Collection<UUID> admissionIds, boolean allergies) {
        if (admissionIds.isEmpty()) {
            return Map.of();
        }
        List<Admission> list = admissions.findByIdIn(Set.copyOf(admissionIds));
        return admissionService.summaries(list, allergies).stream()
                .collect(Collectors.toMap(AdmissionSummary::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    public Map<UUID, PatientRef> refs(Collection<UUID> admissionIds) {
        Map<UUID, PatientRef> out = new HashMap<>();
        summaries(admissionIds, true).forEach((id, s) -> out.put(id, ref(s)));
        return out;
    }

    public PatientRef ref(UUID admissionId) {
        return refs(List.of(admissionId)).get(admissionId);
    }

    public static PatientRef ref(AdmissionSummary s) {
        String loc = s.location() == null ? null
                : s.location().wardName() + " · " + s.location().roomNumber() + "-" + s.location().bedNumber();
        return new PatientRef(s.id(), s.admissionNumber(), s.patient() == null ? null : s.patient().id(),
                s.patient() == null ? null : s.patient().fullName(), s.patient() == null ? null : s.patient().mrn(),
                loc, s.location() == null ? null : s.location().wardId(),
                s.location() == null ? null : s.location().wardName(), s.status(), s.attendingDoctorId(),
                s.attendingDoctorName(), s.allergies());
    }

    /** Ward where the admission currently has a bed, if any. */
    public Optional<UUID> currentWardId(UUID admissionId) {
        return bedAssignments.findByAdmissionIdAndReleasedAtIsNull(admissionId)
                .flatMap(ba -> beds.findById(ba.getBedId()))
                .map(b -> b.getWardId());
    }

    public Optional<Ward> ward(UUID wardId) {
        return wardId == null ? Optional.empty() : wards.findById(wardId);
    }

    public String userName(UUID id) {
        return id == null ? null : users.findById(id).map(User::getFullName).orElse("Unknown");
    }

    public Map<UUID, String> userNames(Collection<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        List<UUID> clean = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (clean.isEmpty()) {
            return out;
        }
        users.findAllById(clean).forEach(u -> out.put(u.getId(), u.getFullName()));
        return out;
    }

    public CurrentUser me() {
        return TenantContext.requireUser();
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
