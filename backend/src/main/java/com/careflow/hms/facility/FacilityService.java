package com.careflow.hms.facility;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.admission.BedAssignment;
import com.careflow.hms.admission.BedAssignmentRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.facility.FacilityDtos.BedHistoryEntry;
import com.careflow.hms.facility.FacilityDtos.BedRequest;
import com.careflow.hms.facility.FacilityDtos.BedView;
import com.careflow.hms.facility.FacilityDtos.DepartmentRequest;
import com.careflow.hms.facility.FacilityDtos.DepartmentView;
import com.careflow.hms.facility.FacilityDtos.Occupant;
import com.careflow.hms.facility.FacilityDtos.RoomBoard;
import com.careflow.hms.facility.FacilityDtos.RoomRequest;
import com.careflow.hms.facility.FacilityDtos.RoomView;
import com.careflow.hms.facility.FacilityDtos.WardBoard;
import com.careflow.hms.facility.FacilityDtos.WardRequest;
import com.careflow.hms.facility.FacilityDtos.WardStaffView;
import com.careflow.hms.facility.FacilityDtos.WardView;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.StaffProfileRepository;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FacilityService {

    private final DepartmentRepository departments;
    private final WardRepository wards;
    private final RoomRepository rooms;
    private final BedRepository beds;
    private final BedStatusHistoryRepository bedHistory;
    private final StaffWardAssignmentRepository wardStaff;
    private final BedAssignmentRepository bedAssignments;
    private final AdmissionRepository admissions;
    private final PatientRepository patients;
    private final UserRepository users;
    private final StaffProfileRepository staffProfiles;
    private final AuditService audit;

    // ------------------------------------------------------------------ departments

    @Transactional(readOnly = true)
    public List<DepartmentView> listDepartments() {
        UUID org = TenantContext.requireOrganizationId();
        List<Department> list = departments.findByOrganizationIdOrderByName(org);
        List<Ward> allWards = wards.findByOrganizationIdOrderByName(org);
        return list.stream().map(d -> {
            String head = d.getHeadUserId() == null ? null : users.findById(d.getHeadUserId()).map(User::getFullName).orElse(null);
            long staff = staffProfiles.findByOrganizationIdAndDepartmentId(org, d.getId()).size();
            long wardCount = allWards.stream().filter(w -> d.getId().equals(w.getDepartmentId())).count();
            return new DepartmentView(d.getId(), d.getCode(), d.getName(), d.getSpecialty(), d.getDescription(),
                    d.getHeadUserId(), head, d.isActive(), staff, wardCount);
        }).toList();
    }

    @Transactional
    public DepartmentView saveDepartment(UUID id, DepartmentRequest r) {
        UUID org = TenantContext.requireOrganizationId();
        Department d = id == null ? new Department()
                : departments.findByIdAndOrganizationId(id, org).orElseThrow(() -> ApiException.notFound("Department"));
        if (id == null && departments.existsByOrganizationIdAndCode(org, r.code())) {
            throw ApiException.conflict("CODE_IN_USE", "Department code " + r.code() + " is already used.");
        }
        if (r.headUserId() != null) {
            users.findByIdAndOrganizationId(r.headUserId(), org).orElseThrow(() -> ApiException.notFound("Department head"));
        }
        d.setCode(r.code());
        d.setName(r.name());
        d.setSpecialty(r.specialty());
        d.setDescription(r.description());
        d.setHeadUserId(r.headUserId());
        if (r.active() != null) {
            d.setActive(r.active());
        }
        UUID savedId = departments.saveAndFlush(d).getId();
        audit.record(id == null ? "DEPARTMENT_CREATED" : "DEPARTMENT_UPDATED", "DEPARTMENT", savedId, null, d.getName());
        return listDepartments().stream().filter(v -> v.id().equals(savedId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ wards / rooms / beds

    @Transactional(readOnly = true)
    public List<WardView> listWards() {
        UUID org = TenantContext.requireOrganizationId();
        Map<UUID, String> deptNames = departments.findByOrganizationIdOrderByName(org).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        List<Bed> allBeds = beds.findByOrganizationIdAndActiveTrue(org);
        return wards.findByOrganizationIdOrderByName(org).stream().map(w -> {
            List<Bed> wb = allBeds.stream().filter(b -> b.getWardId().equals(w.getId())).toList();
            return new WardView(w.getId(), w.getCode(), w.getName(), w.getWardType(), w.getDepartmentId(),
                    deptNames.get(w.getDepartmentId()), w.getFloor(), w.getGenderPolicy(), w.isActive(), wb.size(),
                    count(wb, Bed.Status.AVAILABLE), count(wb, Bed.Status.OCCUPIED), count(wb, Bed.Status.RESERVED),
                    count(wb, Bed.Status.CLEANING), count(wb, Bed.Status.MAINTENANCE));
        }).toList();
    }

    @Transactional
    public WardView saveWard(UUID id, WardRequest r) {
        UUID org = TenantContext.requireOrganizationId();
        Ward w = id == null ? new Ward() : wards.findByIdAndOrganizationId(id, org).orElseThrow(() -> ApiException.notFound("Ward"));
        if (id == null && wards.existsByOrganizationIdAndCode(org, r.code())) {
            throw ApiException.conflict("CODE_IN_USE", "Ward code " + r.code() + " is already used.");
        }
        if (r.departmentId() != null) {
            departments.findByIdAndOrganizationId(r.departmentId(), org).orElseThrow(() -> ApiException.notFound("Department"));
        }
        w.setCode(r.code());
        w.setName(r.name());
        w.setWardType(r.wardType());
        w.setDepartmentId(r.departmentId());
        w.setFloor(r.floor());
        if (r.genderPolicy() != null) {
            w.setGenderPolicy(r.genderPolicy());
        }
        if (r.active() != null) {
            w.setActive(r.active());
        }
        UUID savedId = wards.saveAndFlush(w).getId();
        audit.record(id == null ? "WARD_CREATED" : "WARD_UPDATED", "WARD", savedId, null, w.getName());
        return listWards().stream().filter(v -> v.id().equals(savedId)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<RoomView> listRooms(UUID wardId) {
        UUID org = TenantContext.requireOrganizationId();
        wards.findByIdAndOrganizationId(wardId, org).orElseThrow(() -> ApiException.notFound("Ward"));
        return rooms.findByOrganizationIdAndWardIdOrderByRoomNumber(org, wardId).stream()
                .map(r -> new RoomView(r.getId(), r.getWardId(), r.getRoomNumber(), r.getRoomType(), r.isActive())).toList();
    }

    @Transactional
    public RoomView saveRoom(UUID wardId, UUID id, RoomRequest r) {
        UUID org = TenantContext.requireOrganizationId();
        wards.findByIdAndOrganizationId(wardId, org).orElseThrow(() -> ApiException.notFound("Ward"));
        Room room = id == null ? new Room() : rooms.findByIdAndOrganizationId(id, org)
                .filter(x -> x.getWardId().equals(wardId)).orElseThrow(() -> ApiException.notFound("Room"));
        if (id == null && rooms.existsByWardIdAndRoomNumber(wardId, r.roomNumber())) {
            throw ApiException.conflict("ROOM_EXISTS", "Room " + r.roomNumber() + " already exists in this ward.");
        }
        room.setWardId(wardId);
        room.setRoomNumber(r.roomNumber());
        room.setRoomType(r.roomType());
        if (r.active() != null) {
            room.setActive(r.active());
        }
        room = rooms.saveAndFlush(room);
        audit.record(id == null ? "ROOM_CREATED" : "ROOM_UPDATED", "ROOM", room.getId(), null, room.getRoomNumber());
        return new RoomView(room.getId(), room.getWardId(), room.getRoomNumber(), room.getRoomType(), room.isActive());
    }

    @Transactional
    public BedView saveBed(UUID id, BedRequest r) {
        UUID org = TenantContext.requireOrganizationId();
        Room room = rooms.findByIdAndOrganizationId(r.roomId(), org).orElseThrow(() -> ApiException.notFound("Room"));
        Bed bed = id == null ? new Bed() : beds.findByIdAndOrganizationId(id, org).orElseThrow(() -> ApiException.notFound("Bed"));
        if (id != null && Boolean.FALSE.equals(r.active()) && bed.getStatus() == Bed.Status.OCCUPIED) {
            throw ApiException.rule("BED_OCCUPIED", "An occupied bed cannot be deactivated.");
        }
        if (id != null && !bed.getRoomId().equals(r.roomId()) && bed.getStatus() == Bed.Status.OCCUPIED) {
            throw ApiException.rule("BED_OCCUPIED", "An occupied bed cannot be moved to another room.");
        }
        bed.setRoomId(room.getId());
        bed.setWardId(room.getWardId());
        bed.setBedNumber(r.bedNumber());
        bed.setBedType(r.bedType());
        bed.setIsolationCapable(r.isolationCapable());
        bed.setOxygenSupported(r.oxygenSupported());
        bed.setNotes(r.notes());
        if (r.active() != null) {
            bed.setActive(r.active());
        }
        bed = beds.saveAndFlush(bed);
        audit.record(id == null ? "BED_CREATED" : "BED_UPDATED", "BED", bed.getId(), null, bed.getBedNumber());
        return toBedView(bed, Map.of(), Map.of(), occupants(List.of(bed)));
    }

    /** Manual housekeeping status change (cleaning, maintenance, reserved, available). */
    @Transactional
    public BedView changeBedStatus(UUID bedId, Bed.Status target, String reason, Long version) {
        UUID org = TenantContext.requireOrganizationId();
        Bed bed = beds.lockById(bedId, org).orElseThrow(() -> ApiException.notFound("Bed"));
        if (version != null && !version.equals(bed.getVersion())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "The bed status changed. Reload and try again.");
        }
        if (bed.getStatus() == target) {
            return toBedView(bed, Map.of(), Map.of(), occupants(List.of(bed)));
        }
        if (!bed.getStatus().canManuallyMoveTo(target)) {
            throw ApiException.rule("INVALID_BED_TRANSITION", bed.getStatus() == Bed.Status.OCCUPIED
                    ? "An occupied bed is released through discharge or transfer, not by a manual status change."
                    : "Bed cannot move from " + bed.getStatus() + " to " + target + ".");
        }
        if ((target == Bed.Status.MAINTENANCE || target == Bed.Status.RESERVED) && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("REASON_REQUIRED", "A reason is required for " + target.name().toLowerCase() + ".");
        }
        recordStatus(bed, target, reason);
        audit.record("BED_STATUS_CHANGED", "BED", bed.getId(), null, bed.getBedNumber() + " -> " + target);
        return toBedView(bed, Map.of(), Map.of(), occupants(List.of(bed)));
    }

    /** Applies a status change with history. Used by bed allocation too. */
    public void recordStatus(Bed bed, Bed.Status target, String reason) {
        BedStatusHistory h = new BedStatusHistory();
        h.setOrganizationId(bed.getOrganizationId());
        h.setBedId(bed.getId());
        h.setFromStatus(bed.getStatus());
        h.setToStatus(target);
        h.setReason(reason);
        h.setChangedBy(TenantContext.actorId());
        h.setChangedAt(Instant.now());
        bedHistory.save(h);
        bed.setStatus(target);
        bed.setStatusChangedAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public List<WardBoard> bedBoard(UUID wardId, UUID departmentId, Bed.Status status, Bed.BedType bedType) {
        UUID org = TenantContext.requireOrganizationId();
        Map<UUID, String> deptNames = departments.findByOrganizationIdOrderByName(org).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        List<Ward> wardList = wards.findByOrganizationIdOrderByName(org).stream()
                .filter(Ward::isActive)
                .filter(w -> wardId == null || w.getId().equals(wardId))
                .filter(w -> departmentId == null || departmentId.equals(w.getDepartmentId()))
                .toList();
        List<Bed> allBeds = beds.findByOrganizationIdAndActiveTrue(org).stream()
                .filter(b -> status == null || b.getStatus() == status)
                .filter(b -> bedType == null || b.getBedType() == bedType)
                .toList();
        Map<UUID, Room> roomMap = rooms.findByOrganizationId(org).stream().collect(Collectors.toMap(Room::getId, Function.identity()));
        Map<UUID, Ward> wardMap = wardList.stream().collect(Collectors.toMap(Ward::getId, Function.identity()));
        Map<UUID, Occupant> occ = occupants(allBeds);
        List<WardBoard> result = new ArrayList<>();
        for (Ward w : wardList) {
            List<RoomBoard> roomBoards = roomMap.values().stream()
                    .filter(r -> r.getWardId().equals(w.getId()) && r.isActive())
                    .sorted(Comparator.comparing(Room::getRoomNumber))
                    .map(r -> new RoomBoard(r.getId(), r.getRoomNumber(), r.getRoomType(), allBeds.stream()
                            .filter(b -> b.getRoomId().equals(r.getId()))
                            .sorted(Comparator.comparing(Bed::getBedNumber))
                            .map(b -> toBedView(b, roomMap, wardMap, occ)).toList()))
                    .filter(rb -> !rb.beds().isEmpty() || (status == null && bedType == null))
                    .toList();
            if (!roomBoards.isEmpty()) {
                result.add(new WardBoard(w.getId(), w.getCode(), w.getName(), w.getWardType(),
                        deptNames.get(w.getDepartmentId()), w.getFloor(), w.getGenderPolicy(), roomBoards));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<BedView> availableBeds(UUID wardId, Bed.BedType bedType, boolean isolationOnly) {
        UUID org = TenantContext.requireOrganizationId();
        Map<UUID, Room> roomMap = rooms.findByOrganizationId(org).stream().collect(Collectors.toMap(Room::getId, Function.identity()));
        Map<UUID, Ward> wardMap = wards.findByOrganizationIdOrderByName(org).stream().collect(Collectors.toMap(Ward::getId, Function.identity()));
        return beds.findByOrganizationIdAndActiveTrue(org).stream()
                .filter(b -> b.getStatus() == Bed.Status.AVAILABLE)
                .filter(b -> wardId == null || b.getWardId().equals(wardId))
                .filter(b -> bedType == null || b.getBedType() == bedType)
                .filter(b -> !isolationOnly || b.isIsolationCapable())
                .filter(b -> wardMap.containsKey(b.getWardId()) && wardMap.get(b.getWardId()).isActive())
                .sorted(Comparator.comparing((Bed b) -> wardMap.get(b.getWardId()).getName()).thenComparing(Bed::getBedNumber))
                .map(b -> toBedView(b, roomMap, wardMap, Map.of()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BedHistoryEntry> bedHistory(UUID bedId) {
        UUID org = TenantContext.requireOrganizationId();
        beds.findByIdAndOrganizationId(bedId, org).orElseThrow(() -> ApiException.notFound("Bed"));
        List<BedHistoryEntry> entries = new ArrayList<>();
        Map<UUID, String> names = new HashMap<>();
        Function<UUID, String> nameOf = id -> id == null ? "System" : names.computeIfAbsent(id,
                k -> users.findById(k).map(User::getFullName).orElse("Unknown"));
        bedHistory.findTop50ByBedIdOrderByChangedAtDesc(bedId).forEach(h -> entries.add(new BedHistoryEntry(
                h.getChangedAt(), "STATUS", (h.getFromStatus() == null ? "" : h.getFromStatus() + " -> ") + h.getToStatus()
                + (h.getReason() == null ? "" : " (" + h.getReason() + ")"), nameOf.apply(h.getChangedBy()))));
        for (BedAssignment ba : bedAssignments.findTop20ByBedIdOrderByAssignedAtDesc(bedId)) {
            String adm = admissions.findById(ba.getAdmissionId()).map(Admission::getAdmissionNumber).orElse("?");
            entries.add(new BedHistoryEntry(ba.getAssignedAt(), "ASSIGNED", "Assigned to admission " + adm, nameOf.apply(ba.getAssignedBy())));
            if (ba.getReleasedAt() != null) {
                entries.add(new BedHistoryEntry(ba.getReleasedAt(), "RELEASED",
                        "Released from " + adm + " (" + ba.getReleaseReason() + ")", nameOf.apply(ba.getReleasedBy())));
            }
        }
        entries.sort(Comparator.comparing(BedHistoryEntry::at).reversed());
        return entries;
    }

    // ------------------------------------------------------------------ ward staff

    @Transactional(readOnly = true)
    public List<WardStaffView> wardStaff(UUID wardId) {
        UUID org = TenantContext.requireOrganizationId();
        wards.findByIdAndOrganizationId(wardId, org).orElseThrow(() -> ApiException.notFound("Ward"));
        return wardStaff.findByWardIdAndActiveTrue(wardId).stream().map(a -> {
            User u = users.findById(a.getUserId()).orElse(null);
            return new WardStaffView(a.getId(), a.getUserId(), u == null ? "?" : u.getFullName(),
                    u == null ? null : u.getRole().name(), a.getAssignedAt());
        }).toList();
    }

    @Transactional
    public WardStaffView assignWardStaff(UUID wardId, UUID userId) {
        UUID org = TenantContext.requireOrganizationId();
        wards.findByIdAndOrganizationId(wardId, org).orElseThrow(() -> ApiException.notFound("Ward"));
        User u = users.findByIdAndOrganizationId(userId, org).orElseThrow(() -> ApiException.notFound("User"));
        if (u.getRole() != Role.NURSE && u.getRole() != Role.DOCTOR) {
            throw ApiException.rule("INVALID_ROLE", "Only nurses and doctors can be assigned to wards.");
        }
        StaffWardAssignment a = new StaffWardAssignment();
        a.setUserId(userId);
        a.setWardId(wardId);
        a.setAssignedBy(TenantContext.actorId());
        a = wardStaff.saveAndFlush(a);
        audit.record("WARD_STAFF_ASSIGNED", "WARD", wardId, null, u.getFullName() + " assigned");
        return new WardStaffView(a.getId(), userId, u.getFullName(), u.getRole().name(), a.getAssignedAt());
    }

    @Transactional
    public void removeWardStaff(UUID wardId, UUID assignmentId) {
        UUID org = TenantContext.requireOrganizationId();
        StaffWardAssignment a = wardStaff.findById(assignmentId)
                .filter(x -> x.getWardId().equals(wardId) && x.getOrganizationId().equals(org))
                .orElseThrow(() -> ApiException.notFound("Assignment"));
        a.setActive(false);
        a.setEndedAt(Instant.now());
        audit.record("WARD_STAFF_REMOVED", "WARD", wardId, null, "Assignment ended");
    }

    // ------------------------------------------------------------------ helpers

    private Map<UUID, Occupant> occupants(List<Bed> bedList) {
        if (bedList.isEmpty()) {
            return Map.of();
        }
        CurrentUser cu = TenantContext.currentUser().orElse(null);
        boolean canSeeNames = cu != null && cu.role() != Role.SUPER_ADMIN && cu.role() != Role.PATIENT;
        List<BedAssignment> open = bedAssignments.findByBedIdInAndReleasedAtIsNull(bedList.stream().map(Bed::getId).toList());
        Map<UUID, Admission> adms = admissions.findByIdIn(open.stream().map(BedAssignment::getAdmissionId).toList())
                .stream().collect(Collectors.toMap(Admission::getId, Function.identity()));
        Map<UUID, Patient> pts = patients.findByIdIn(adms.values().stream().map(Admission::getPatientId).toList())
                .stream().collect(Collectors.toMap(Patient::getId, Function.identity()));
        Map<UUID, String> doctorNames = users.findAllById(adms.values().stream().map(Admission::getAttendingDoctorId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, User::getFullName));
        Map<UUID, Occupant> result = new HashMap<>();
        for (BedAssignment ba : open) {
            Admission a = adms.get(ba.getAdmissionId());
            Patient p = a == null ? null : pts.get(a.getPatientId());
            if (a == null || p == null) {
                continue;
            }
            result.put(ba.getBedId(), new Occupant(a.getId(), a.getAdmissionNumber(), p.getId(),
                    canSeeNames ? p.fullName() : "Occupied", canSeeNames ? p.getMrn() : null,
                    canSeeNames ? p.getGender().name() : null, ba.getAssignedAt(), a.isRequiresIsolation(),
                    doctorNames.get(a.getAttendingDoctorId()), a.getStatus().name()));
        }
        return result;
    }

    private BedView toBedView(Bed b, Map<UUID, Room> roomMap, Map<UUID, Ward> wardMap, Map<UUID, Occupant> occ) {
        Room r = roomMap.containsKey(b.getRoomId()) ? roomMap.get(b.getRoomId()) : rooms.findById(b.getRoomId()).orElse(null);
        Ward w = wardMap.containsKey(b.getWardId()) ? wardMap.get(b.getWardId()) : wards.findById(b.getWardId()).orElse(null);
        return new BedView(b.getId(), b.getBedNumber(), b.getBedType(), b.getStatus(), b.isIsolationCapable(),
                b.isOxygenSupported(), b.getNotes(), b.getStatusChangedAt(), b.isActive(), b.getVersion(),
                b.getRoomId(), r == null ? null : r.getRoomNumber(), r == null ? null : r.getRoomType(),
                b.getWardId(), w == null ? null : w.getName(), occ.get(b.getId()));
    }

    private static int count(List<Bed> list, Bed.Status s) {
        return (int) list.stream().filter(b -> Objects.equals(b.getStatus(), s)).count();
    }
}
