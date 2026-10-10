package com.careflow.hms.admission;

import com.careflow.hms.admission.AdmissionDtos.AdmissionDetail;
import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.admission.AdmissionDtos.AdmitRequest;
import com.careflow.hms.admission.AdmissionDtos.BedStay;
import com.careflow.hms.admission.AdmissionDtos.CareTeamMember;
import com.careflow.hms.admission.AdmissionDtos.Location;
import com.careflow.hms.admission.AdmissionDtos.PatientBrief;
import com.careflow.hms.admission.AdmissionDtos.UpdateAdmissionRequest;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.ChargeService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.facility.Bed;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.facility.Department;
import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.facility.Room;
import com.careflow.hms.facility.RoomRepository;
import com.careflow.hms.facility.StaffWardAssignmentRepository;
import com.careflow.hms.facility.Ward;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.org.SequenceService;
import com.careflow.hms.patient.Allergy;
import com.careflow.hms.patient.AllergyRepository;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Permissions;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import com.careflow.hms.user.StaffProfileRepository;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdmissionService {

    private static final Map<String, String> SORTS = Map.of(
            "admittedAt", "admittedAt", "admissionNumber", "admissionNumber", "status", "status",
            "expectedDischargeDate", "expectedDischargeDate");

    private final AdmissionRepository admissions;
    private final AdmissionCareTeamRepository careTeam;
    private final BedAssignmentRepository bedAssignments;
    private final TransferRepository transfers;
    private final PatientRepository patients;
    private final AllergyRepository allergies;
    private final DepartmentRepository departments;
    private final UserRepository users;
    private final StaffProfileRepository staffProfiles;
    private final BedRepository beds;
    private final RoomRepository rooms;
    private final WardRepository wards;
    private final StaffWardAssignmentRepository wardAssignments;
    private final BedAllocationService bedAllocation;
    private final SequenceService sequences;
    private final AccessPolicy access;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ChargeService charges;

    // ------------------------------------------------------------------ commands

    @Transactional
    public AdmissionDetail admit(AdmitRequest r) {
        CurrentUser u = TenantContext.requireUser();
        Patient patient = access.requirePatient(r.patientId(), AccessPolicy.Scope.DEMOGRAPHICS);
        if (patient.getStatus() != Patient.Status.ACTIVE) {
            throw ApiException.rule("PATIENT_NOT_ACTIVE", "Only active patients can be admitted.");
        }
        if (admissions.findOpenByPatient(patient.getId()).isPresent()) {
            throw ApiException.conflict("PATIENT_ALREADY_ADMITTED", "The patient already has an open admission.");
        }
        Department dept = requireDepartment(r.departmentId());
        User doctor = requireDoctor(r.attendingDoctorId());
        Instant admittedAt = r.admittedAt() == null ? Instant.now() : r.admittedAt();
        if (admittedAt.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("INVALID_ADMISSION_TIME", "Admission time cannot be in the future.");
        }
        if (r.expectedDischargeDate() != null && r.expectedDischargeDate().isBefore(TimeUtil.localDate(admittedAt))) {
            throw ApiException.badRequest("INVALID_EXPECTED_DISCHARGE", "Expected discharge date cannot be before admission.");
        }

        Admission a = new Admission();
        a.setPatientId(patient.getId());
        a.setAdmissionNumber(sequences.admissionNumber());
        a.setStatus(Admission.Status.PENDING_BED);
        a.setAdmissionType(r.admissionType());
        a.setAdmittedAt(admittedAt);
        a.setAdmissionReason(r.admissionReason().trim());
        a.setDepartmentId(dept.getId());
        a.setAttendingDoctorId(doctor.getId());
        a.setReferringDoctor(r.referringDoctor());
        a.setExpectedDischargeDate(r.expectedDischargeDate());
        a.setRequiresIsolation(r.requiresIsolation());
        a.setBedRequirements(r.bedRequirements());
        a.setCreatedBy(u.userId());
        a = admissions.saveAndFlush(a);

        AdmissionCareTeam attending = new AdmissionCareTeam();
        attending.setAdmissionId(a.getId());
        attending.setUserId(doctor.getId());
        attending.setCareRole(AdmissionCareTeam.CareRole.ATTENDING);
        attending.setAddedBy(u.userId());
        careTeam.save(attending);

        timeline.record(TimelineService.Entry.builder()
                .patientId(patient.getId()).admissionId(a.getId())
                .eventType("PATIENT_ADMITTED").category(TimelineEvent.Category.ADMISSION)
                .title("Admitted to " + dept.getName())
                .detail("Admission " + a.getAdmissionNumber() + " (" + a.getAdmissionType().name().toLowerCase()
                        + ") under " + doctor.getFullName())
                .status(a.getStatus().name()).visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("ADMISSION").entityId(a.getId()).occurredAt(admittedAt).build());

        // Open the admission invoice so charges and the patient's bill are visible from day one.
        charges.openInvoiceForAdmission(a.getId());

        if (r.bedId() != null) {
            doAssignBed(a, r.bedId(), null);
        }

        notifications.notifyUsers(List.of(doctor.getId()), NotificationRequest.builder()
                .category("ASSIGNMENT").priority(Notification.Priority.HIGH)
                .title("New patient assigned: " + patient.fullName())
                .message("Admission " + a.getAdmissionNumber() + " - " + truncate(a.getAdmissionReason(), 140))
                .link("/doctor/patients/" + a.getId())
                .entityType("ADMISSION").entityId(a.getId())
                .dedupeKey("ADMIT_ASSIGN:" + a.getId() + ":" + doctor.getId()).build());
        audit.record("ADMISSION_CREATED", "ADMISSION", a.getId(), patient.getId(),
                a.getAdmissionNumber() + " for " + patient.getMrn());
        return detail(a.getId());
    }

    @Transactional
    public AdmissionDetail assignBed(UUID admissionId, UUID bedId, String notes) {
        Admission a = lockAdmission(admissionId);
        if (a.getStatus() != Admission.Status.PENDING_BED && a.getStatus() != Admission.Status.ADMITTED) {
            throw ApiException.rule("ADMISSION_NOT_ACTIVE", "Beds can only be assigned to active admissions.");
        }
        doAssignBed(a, bedId, notes);
        audit.record("BED_ASSIGNED", "ADMISSION", a.getId(), a.getPatientId(), "Bed assigned");
        return detail(a.getId());
    }

    private void doAssignBed(Admission a, UUID bedId, String notes) {
        BedAssignment ba = bedAllocation.assign(a, bedId, null, notes);
        if (a.getStatus() == Admission.Status.PENDING_BED) {
            a.transitionTo(Admission.Status.ADMITTED);
        }
        String where = describeBed(ba.getBedId());
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("BED_ASSIGNED").category(TimelineEvent.Category.ADMISSION)
                .title("Bed assigned: " + where)
                .status(a.getStatus().name()).visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("BED").entityId(ba.getBedId()).build());
        Patient p = patients.findById(a.getPatientId()).orElseThrow();
        notifications.notifyWardNurses(a.getId(), NotificationRequest.builder()
                .category("ASSIGNMENT").priority(Notification.Priority.HIGH)
                .title("New patient on ward: " + p.fullName())
                .message(where + " - admission " + a.getAdmissionNumber())
                .link("/nurse/patients/" + a.getId())
                .entityType("ADMISSION").entityId(a.getId())
                .dedupeKey("WARD_ARRIVAL:" + a.getId() + ":" + ba.getId()).build());
    }

    @Transactional
    public AdmissionDetail update(UUID admissionId, UpdateAdmissionRequest r) {
        Admission a = lockAdmission(admissionId);
        if (!r.version().equals(a.getVersion())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This admission was changed by someone else. Reload and try again.");
        }
        if (!a.getStatus().isOpen()) {
            throw ApiException.rule("ADMISSION_CLOSED", "A closed admission cannot be edited.");
        }
        requireDepartment(r.departmentId());
        User doctor = requireDoctor(r.attendingDoctorId());
        boolean doctorChanged = !doctor.getId().equals(a.getAttendingDoctorId());
        a.setAdmissionReason(r.admissionReason().trim());
        a.setDepartmentId(r.departmentId());
        a.setReferringDoctor(r.referringDoctor());
        a.setExpectedDischargeDate(r.expectedDischargeDate());
        a.setRequiresIsolation(r.requiresIsolation());
        a.setBedRequirements(r.bedRequirements());
        if (doctorChanged) {
            careTeam.findByAdmissionIdAndUserIdAndRemovedAtIsNull(a.getId(), a.getAttendingDoctorId())
                    .ifPresent(t -> t.setRemovedAt(Instant.now()));
            a.setAttendingDoctorId(doctor.getId());
            if (!careTeam.existsByAdmissionIdAndUserIdAndRemovedAtIsNull(a.getId(), doctor.getId())) {
                AdmissionCareTeam t = new AdmissionCareTeam();
                t.setAdmissionId(a.getId());
                t.setUserId(doctor.getId());
                t.setCareRole(AdmissionCareTeam.CareRole.ATTENDING);
                t.setAddedBy(TenantContext.actorId());
                careTeam.save(t);
            }
            timeline.record(TimelineService.Entry.builder()
                    .patientId(a.getPatientId()).admissionId(a.getId())
                    .eventType("ATTENDING_CHANGED").category(TimelineEvent.Category.ADMISSION)
                    .title("Treating doctor changed to " + doctor.getFullName())
                    .visibility(TimelineEvent.Visibility.PATIENT).entityType("ADMISSION").entityId(a.getId()).build());
            notifications.notifyUsers(List.of(doctor.getId()), NotificationRequest.builder()
                    .category("ASSIGNMENT").priority(Notification.Priority.HIGH)
                    .title("Patient assigned to you").message("Admission " + a.getAdmissionNumber())
                    .link("/doctor/patients/" + a.getId()).entityType("ADMISSION").entityId(a.getId())
                    .dedupeKey("ADMIT_ASSIGN:" + a.getId() + ":" + doctor.getId()).build());
        }
        audit.record("ADMISSION_UPDATED", "ADMISSION", a.getId(), a.getPatientId(), "Admission details updated");
        return detail(admissions.saveAndFlush(a).getId());
    }

    /** Clinical discharge planning: the treating team records/changes the expected discharge date. */
    @Transactional
    public AdmissionDetail updateExpectedDischarge(UUID admissionId, java.time.LocalDate date) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = u.has(Permissions.ADMISSION_MANAGE)
                ? lockAdmission(admissionId)
                : access.requireAdmission(admissionId, AccessPolicy.Scope.CLINICAL);
        if (!a.getStatus().isOpen()) {
            throw ApiException.rule("ADMISSION_CLOSED", "The admission is closed.");
        }
        if (date != null && date.isBefore(TimeUtil.localDate(a.getAdmittedAt()))) {
            throw ApiException.badRequest("INVALID_EXPECTED_DISCHARGE", "Expected discharge date cannot be before admission.");
        }
        a.setExpectedDischargeDate(date);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("EXPECTED_DISCHARGE_UPDATED").category(TimelineEvent.Category.DISCHARGE)
                .title(date == null ? "Expected discharge date cleared" : "Expected discharge date set to " + date)
                .visibility(TimelineEvent.Visibility.PATIENT).entityType("ADMISSION").entityId(a.getId()).build());
        audit.record("EXPECTED_DISCHARGE_UPDATED", "ADMISSION", a.getId(), a.getPatientId(), String.valueOf(date));
        return detail(a.getId());
    }

    @Transactional
    public AdmissionDetail cancel(UUID admissionId, String reason) {
        Admission a = lockAdmission(admissionId);
        if (a.getStatus() != Admission.Status.PENDING_BED && a.getStatus() != Admission.Status.ADMITTED) {
            throw ApiException.rule("CANNOT_CANCEL", "Only pending or newly admitted admissions can be cancelled; use discharge otherwise.");
        }
        if (a.getStatus() == Admission.Status.ADMITTED && a.getAdmittedAt().isBefore(Instant.now().minus(Duration.ofHours(24)))) {
            throw ApiException.rule("CANNOT_CANCEL", "Admissions older than 24 hours must be closed through the discharge workflow.");
        }
        bedAllocation.release(a, BedAssignment.ReleaseReason.CANCELLED, Bed.Status.CLEANING);
        a.setStatus(Admission.Status.CANCELLED);
        a.setCancellationReason(reason);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("ADMISSION_CANCELLED").category(TimelineEvent.Category.ADMISSION)
                .title("Admission cancelled").detail(reason).status("CANCELLED")
                .visibility(TimelineEvent.Visibility.PATIENT).entityType("ADMISSION").entityId(a.getId()).build());
        audit.record("ADMISSION_CANCELLED", "ADMISSION", a.getId(), a.getPatientId(), reason);
        return detail(a.getId());
    }

    @Transactional
    public List<CareTeamMember> addCareTeamMember(UUID admissionId, UUID userId, AdmissionCareTeam.CareRole role) {
        Admission a = requireCareTeamManagement(admissionId);
        User member = users.findByIdAndOrganizationId(userId, a.getOrganizationId())
                .filter(x -> x.getStatus() == User.Status.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("Staff member"));
        boolean nurseRole = role == AdmissionCareTeam.CareRole.PRIMARY_NURSE;
        if (nurseRole && member.getRole() != Role.NURSE || !nurseRole && member.getRole() != Role.DOCTOR) {
            throw ApiException.rule("INVALID_CARE_ROLE", "Care role " + role + " does not match the staff member's role.");
        }
        if (careTeam.existsByAdmissionIdAndUserIdAndRemovedAtIsNull(a.getId(), userId)) {
            throw ApiException.conflict("ALREADY_ON_TEAM", member.getFullName() + " is already on the care team.");
        }
        AdmissionCareTeam t = new AdmissionCareTeam();
        t.setAdmissionId(a.getId());
        t.setUserId(userId);
        t.setCareRole(role);
        t.setAddedBy(TenantContext.actorId());
        careTeam.save(t);
        notifications.notifyUsers(List.of(userId), NotificationRequest.builder()
                .category("ASSIGNMENT").title("Added to care team").message("Admission " + a.getAdmissionNumber())
                .link((member.getRole() == Role.NURSE ? "/nurse/patients/" : "/doctor/patients/") + a.getId())
                .entityType("ADMISSION").entityId(a.getId()).dedupeKey("CARE_TEAM:" + a.getId() + ":" + userId + ":" + Instant.now().getEpochSecond()).build());
        audit.record("CARE_TEAM_ADDED", "ADMISSION", a.getId(), a.getPatientId(), member.getFullName() + " as " + role);
        return careTeamOf(a.getId());
    }

    @Transactional
    public List<CareTeamMember> removeCareTeamMember(UUID admissionId, UUID memberId) {
        Admission a = requireCareTeamManagement(admissionId);
        AdmissionCareTeam t = careTeam.findById(memberId)
                .filter(x -> x.getAdmissionId().equals(a.getId()) && x.getRemovedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Care team member"));
        if (t.getUserId().equals(a.getAttendingDoctorId())) {
            throw ApiException.rule("ATTENDING_REQUIRED", "Change the attending doctor before removing them from the care team.");
        }
        t.setRemovedAt(Instant.now());
        audit.record("CARE_TEAM_REMOVED", "ADMISSION", a.getId(), a.getPatientId(), "Care team member removed");
        return careTeamOf(a.getId());
    }

    private Admission requireCareTeamManagement(UUID admissionId) {
        CurrentUser u = TenantContext.requireUser();
        if (u.has(Permissions.ADMISSION_MANAGE)) {
            return lockAdmission(admissionId);
        }
        Admission a = access.requireAdmission(admissionId, AccessPolicy.Scope.CLINICAL);
        if (!u.userId().equals(a.getAttendingDoctorId())) {
            throw ApiException.forbidden("Only the attending doctor or admissions staff can change the care team.");
        }
        return a;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<AdmissionSummary> search(Collection<Admission.Status> statuses, UUID departmentId, UUID doctorId,
                                                 String q, int page, int size, String sort) {
        CurrentUser u = TenantContext.requireUser();
        Collection<Admission.Status> st = statuses == null || statuses.isEmpty() ? EnumSet.allOf(Admission.Status.class) : statuses;
        var pageable = Paging.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "admittedAt"));
        var result = admissions.search(u.organizationId(), st, departmentId, doctorId, Paging.likePattern(q), pageable);
        List<AdmissionSummary> content = summaries(result.getContent(), false);
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /** Clinician worklist: doctor = attending/care team/department; nurse = assigned wards/primary nurse. */
    @Transactional(readOnly = true)
    public List<AdmissionSummary> myAdmissions() {
        CurrentUser u = TenantContext.requireUser();
        Map<UUID, Admission> result = new LinkedHashMap<>();
        admissions.findOpenForClinician(u.organizationId(), u.userId()).forEach(a -> result.put(a.getId(), a));
        if (u.role() == Role.NURSE) {
            List<UUID> wardIds = wardAssignments.activeWardIds(u.userId());
            if (!wardIds.isEmpty()) {
                admissions.findOpenInWards(u.organizationId(), wardIds).forEach(a -> result.putIfAbsent(a.getId(), a));
            }
        } else if (u.role() == Role.DOCTOR) {
            staffProfiles.findById(u.userId()).map(sp -> sp.getDepartmentId()).ifPresent(deptId ->
                    admissions.search(u.organizationId(), EnumSet.of(Admission.Status.PENDING_BED, Admission.Status.ADMITTED,
                                    Admission.Status.DISCHARGE_IN_PROGRESS), deptId, null, null,
                            org.springframework.data.domain.PageRequest.of(0, 200, Sort.by("admittedAt")))
                            .forEach(a -> result.putIfAbsent(a.getId(), a)));
        }
        return summaries(new ArrayList<>(result.values()), true);
    }

    @Transactional(readOnly = true)
    public AdmissionDetail get(UUID admissionId) {
        Admission a = access.requireAdmission(admissionId, AccessPolicy.Scope.DEMOGRAPHICS);
        return detail(a.getId());
    }

    @Transactional(readOnly = true)
    public List<AdmissionSummary> forPatient(UUID patientId) {
        access.requirePatient(patientId, AccessPolicy.Scope.DEMOGRAPHICS);
        return summaries(admissions.findByPatientIdOrderByAdmittedAtDesc(patientId), false);
    }

    @Transactional(readOnly = true)
    public AdmissionDetail detail(UUID admissionId) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = admissions.findById(admissionId).orElseThrow(() -> ApiException.notFound("Admission"));
        boolean clinical = access.canAccessAdmission(u, a, AccessPolicy.Scope.CLINICAL);
        AdmissionSummary summary = summaries(List.of(a), clinical).get(0);
        Map<UUID, String> names = new HashMap<>();
        Function<UUID, String> nameOf = id -> id == null ? null : names.computeIfAbsent(id,
                k -> users.findById(k).map(User::getFullName).orElse("Unknown"));
        List<BedStay> stays = bedAssignments.findByAdmissionIdOrderByAssignedAtAsc(a.getId()).stream().map(ba -> {
            Bed b = beds.findById(ba.getBedId()).orElse(null);
            Room r = b == null ? null : rooms.findById(b.getRoomId()).orElse(null);
            Ward w = b == null ? null : wards.findById(b.getWardId()).orElse(null);
            return new BedStay(ba.getBedId(), b == null ? null : b.getBedNumber(), r == null ? null : r.getRoomNumber(),
                    w == null ? null : w.getName(), ba.getAssignedAt(), ba.getReleasedAt(),
                    ba.getReleaseReason() == null ? null : ba.getReleaseReason().name());
        }).toList();
        return new AdmissionDetail(summary, a.getAdmissionReason(), a.getReferringDoctor(), a.getBedRequirements(),
                a.getCancellationReason(), a.getDischargeDisposition(), nameOf.apply(a.getCreatedBy()), a.getCreatedAt(),
                careTeamOf(a.getId()), stays, transferViews(transfers.findByAdmissionIdOrderByRequestedAtDesc(a.getId())),
                clinical, a.getVersion());
    }

    public List<CareTeamMember> careTeamOf(UUID admissionId) {
        return careTeam.findByAdmissionIdAndRemovedAtIsNull(admissionId).stream().map(t -> {
            User m = users.findById(t.getUserId()).orElse(null);
            return new CareTeamMember(t.getId(), t.getUserId(), m == null ? "?" : m.getFullName(),
                    m == null ? null : m.getRole().name(), t.getCareRole(), t.getAddedAt());
        }).toList();
    }

    /** Batch-maps admissions to summaries (avoids N+1 on lists). */
    public List<AdmissionSummary> summaries(List<Admission> list, boolean includeAllergies) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> patientIds = list.stream().map(Admission::getPatientId).collect(Collectors.toSet());
        Map<UUID, Patient> pts = patients.findByIdIn(patientIds).stream().collect(Collectors.toMap(Patient::getId, Function.identity()));
        Map<UUID, String> deptNames = departments.findByOrganizationIdOrderByName(list.get(0).getOrganizationId()).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        Map<UUID, String> doctorNames = users.findAllById(list.stream().map(Admission::getAttendingDoctorId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, User::getFullName));
        Map<UUID, BedAssignment> open = bedAssignments.findByAdmissionIdInAndReleasedAtIsNull(list.stream().map(Admission::getId).toList())
                .stream().collect(Collectors.toMap(BedAssignment::getAdmissionId, Function.identity()));
        Map<UUID, Bed> bedMap = beds.findByIdIn(open.values().stream().map(BedAssignment::getBedId).toList())
                .stream().collect(Collectors.toMap(Bed::getId, Function.identity()));
        Map<UUID, Room> roomMap = rooms.findByIdIn(bedMap.values().stream().map(Bed::getRoomId).toList())
                .stream().collect(Collectors.toMap(Room::getId, Function.identity()));
        Map<UUID, Ward> wardMap = wards.findAllById(bedMap.values().stream().map(Bed::getWardId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Ward::getId, Function.identity()));
        Map<UUID, List<String>> allergyMap = new HashMap<>();
        if (includeAllergies) {
            allergies.findByPatientIdInAndStatus(patientIds, Allergy.Status.ACTIVE).forEach(al ->
                    allergyMap.computeIfAbsent(al.getPatientId(), k -> new ArrayList<>())
                            .add(al.getAllergen() + " (" + al.getSeverity().name().toLowerCase().replace('_', '-') + ")"));
        }
        java.time.LocalDate today = TimeUtil.today();
        return list.stream().map(a -> {
            Patient p = pts.get(a.getPatientId());
            BedAssignment ba = open.get(a.getId());
            Location loc = null;
            if (ba != null && bedMap.containsKey(ba.getBedId())) {
                Bed b = bedMap.get(ba.getBedId());
                Room r = roomMap.get(b.getRoomId());
                Ward w = wardMap.get(b.getWardId());
                loc = new Location(b.getId(), b.getBedNumber(), b.getRoomId(), r == null ? null : r.getRoomNumber(),
                        b.getWardId(), w == null ? null : w.getName(), ba.getAssignedAt());
            }
            Instant end = a.getDischargedAt() != null ? a.getDischargedAt() : Instant.now();
            long los = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(TimeUtil.localDate(a.getAdmittedAt()),
                    a.getDischargedAt() != null ? TimeUtil.localDate(end) : today));
            PatientBrief pb = p == null ? null : new PatientBrief(p.getId(), p.getMrn(), p.fullName(),
                    p.ageYears(today), p.getGender().name(), p.getBloodGroup());
            return new AdmissionSummary(a.getId(), a.getAdmissionNumber(), a.getStatus(), a.getAdmissionType(),
                    a.getAdmittedAt(), a.getExpectedDischargeDate(), a.getDischargedAt(), pb, a.getDepartmentId(),
                    deptNames.get(a.getDepartmentId()), a.getAttendingDoctorId(), doctorNames.get(a.getAttendingDoctorId()),
                    loc, los, a.isRequiresIsolation(), includeAllergies ? allergyMap.getOrDefault(a.getPatientId(), List.of()) : null);
        }).toList();
    }

    List<AdmissionDtos.TransferView> transferViews(List<Transfer> list) {
        Map<UUID, String> names = new HashMap<>();
        Function<UUID, String> nameOf = id -> id == null ? null : names.computeIfAbsent(id,
                k -> users.findById(k).map(User::getFullName).orElse("Unknown"));
        return list.stream().map(t -> {
            Admission a = admissions.findById(t.getAdmissionId()).orElse(null);
            Patient p = a == null ? null : patients.findById(a.getPatientId()).orElse(null);
            String toWard = t.getToWardId() == null ? null : wards.findById(t.getToWardId()).map(Ward::getName).orElse(null);
            return new AdmissionDtos.TransferView(t.getId(), t.getAdmissionId(), a == null ? null : a.getAdmissionNumber(),
                    p == null ? null : p.fullName(), p == null ? null : p.getMrn(), t.getStatus(), t.getPriority(),
                    t.getReason(), t.getFromBedId() == null ? null : describeBed(t.getFromBedId()), t.getToWardId(), toWard,
                    t.getToBedId(), t.getToBedId() == null ? null : describeBed(t.getToBedId()), nameOf.apply(t.getRequestedBy()),
                    t.getRequestedAt(), nameOf.apply(t.getDecidedBy()), t.getDecidedAt(), t.getDecisionNotes(),
                    nameOf.apply(t.getCompletedBy()), t.getCompletedAt(), t.getVersion());
        }).toList();
    }

    public String describeBed(UUID bedId) {
        Bed b = beds.findById(bedId).orElse(null);
        if (b == null) {
            return null;
        }
        String ward = wards.findById(b.getWardId()).map(Ward::getName).orElse("?");
        String room = rooms.findById(b.getRoomId()).map(Room::getRoomNumber).orElse("?");
        return ward + ", Room " + room + ", Bed " + b.getBedNumber();
    }

    // ------------------------------------------------------------------ helpers

    Admission lockAdmission(UUID admissionId) {
        UUID org = TenantContext.requireOrganizationId();
        return admissions.lockById(admissionId, org).orElseThrow(() -> ApiException.notFound("Admission"));
    }

    private Department requireDepartment(UUID id) {
        Department d = departments.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Department"));
        if (!d.isActive()) {
            throw ApiException.rule("DEPARTMENT_INACTIVE", d.getName() + " is not active.");
        }
        return d;
    }

    private User requireDoctor(UUID id) {
        User d = users.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Doctor"));
        if (d.getRole() != Role.DOCTOR || d.getStatus() != User.Status.ACTIVE) {
            throw ApiException.rule("INVALID_DOCTOR", "The attending clinician must be an active doctor.");
        }
        return d;
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
