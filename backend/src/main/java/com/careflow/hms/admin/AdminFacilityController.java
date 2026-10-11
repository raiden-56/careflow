package com.careflow.hms.admin;

import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.facility.Bed;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.facility.Room;
import com.careflow.hms.facility.RoomRepository;
import com.careflow.hms.facility.StaffWardAssignment;
import com.careflow.hms.facility.StaffWardAssignmentRepository;
import com.careflow.hms.facility.Ward;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Configuration helpers for the facility and staff screens: every bed of a ward (including inactive
 * beds, which the live bed board hides) and syncing a staff member's ward assignments in one call.
 */
@Tag(name = "Facility & staff configuration")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminFacilityController {

    private final WardRepository wards;
    private final RoomRepository rooms;
    private final BedRepository beds;
    private final UserRepository users;
    private final StaffWardAssignmentRepository assignments;
    private final AuditService audit;

    public record ConfigBed(UUID id, UUID roomId, String roomNumber, String bedNumber, Bed.BedType bedType, Bed.Status status,
                            boolean isolationCapable, boolean oxygenSupported, String notes, boolean active, Long version) {
    }

    public record WardRef(UUID assignmentId, UUID wardId, String wardName, Instant assignedAt) {
    }

    public record WardIdsRequest(@NotNull List<UUID> wardIds) {
    }

    @GetMapping("/wards/{wardId}/beds")
    @PreAuthorize("hasAuthority('FACILITY_MANAGE')")
    @Transactional(readOnly = true)
    public List<ConfigBed> wardBeds(@PathVariable UUID wardId) {
        UUID org = TenantContext.requireOrganizationId();
        wards.findByIdAndOrganizationId(wardId, org).orElseThrow(() -> ApiException.notFound("Ward"));
        Map<UUID, Room> roomMap = rooms.findByOrganizationIdAndWardIdOrderByRoomNumber(org, wardId).stream()
                .collect(Collectors.toMap(Room::getId, Function.identity()));
        return beds.findByOrganizationIdAndWardId(org, wardId).stream()
                .sorted(Comparator.comparing((Bed b) -> roomMap.containsKey(b.getRoomId()) ? roomMap.get(b.getRoomId()).getRoomNumber() : "")
                        .thenComparing(Bed::getBedNumber))
                .map(b -> new ConfigBed(b.getId(), b.getRoomId(), roomMap.containsKey(b.getRoomId()) ? roomMap.get(b.getRoomId()).getRoomNumber() : null,
                        b.getBedNumber(), b.getBedType(), b.getStatus(), b.isIsolationCapable(), b.isOxygenSupported(), b.getNotes(),
                        b.isActive(), b.getVersion()))
                .toList();
    }

    @GetMapping("/staff/{userId}/wards")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional(readOnly = true)
    public List<WardRef> staffWards(@PathVariable UUID userId) {
        UUID org = TenantContext.requireOrganizationId();
        requireStaff(userId, org);
        return current(userId, org);
    }

    @PutMapping("/staff/{userId}/wards")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public List<WardRef> replaceStaffWards(@PathVariable UUID userId, @Valid @RequestBody WardIdsRequest body) {
        UUID org = TenantContext.requireOrganizationId();
        User u = requireStaff(userId, org);
        Set<UUID> wanted = new HashSet<>(body.wardIds());
        if (!wanted.isEmpty() && u.getRole() != Role.NURSE && u.getRole() != Role.DOCTOR) {
            throw ApiException.rule("INVALID_ROLE", "Only nurses and doctors can be assigned to wards.");
        }
        Map<UUID, Ward> wardMap = wanted.isEmpty() ? Map.of() : wards.findByOrganizationIdAndIdIn(org, wanted).stream()
                .collect(Collectors.toMap(Ward::getId, Function.identity()));
        if (wardMap.size() != wanted.size()) {
            throw ApiException.notFound("Ward");
        }
        List<StaffWardAssignment> active = assignments.findByUserIdAndActiveTrue(userId);
        Set<UUID> existing = active.stream().map(StaffWardAssignment::getWardId).collect(Collectors.toSet());
        Map<String, Object> changes = new LinkedHashMap<>();
        for (StaffWardAssignment a : active) {
            if (!wanted.contains(a.getWardId())) {
                a.setActive(false);
                a.setEndedAt(Instant.now());
                changes.put("removed:" + a.getWardId(), wards.findById(a.getWardId()).map(Ward::getName).orElse("?"));
            }
        }
        assignments.flush();
        for (UUID wardId : wanted) {
            if (!existing.contains(wardId)) {
                StaffWardAssignment a = new StaffWardAssignment();
                a.setUserId(userId);
                a.setWardId(wardId);
                a.setAssignedBy(TenantContext.actorId());
                assignments.save(a);
                changes.put("added:" + wardId, wardMap.get(wardId).getName());
            }
        }
        assignments.flush();
        if (!changes.isEmpty()) {
            audit.record("STAFF_WARDS_UPDATED", "USER", userId, null, AuditEvent.Outcome.SUCCESS,
                    u.getFullName() + " ward assignments updated", changes);
        }
        return current(userId, org);
    }

    private List<WardRef> current(UUID userId, UUID org) {
        return assignments.findByUserIdAndActiveTrue(userId).stream()
                .filter(a -> org.equals(a.getOrganizationId()))
                .map(a -> new WardRef(a.getId(), a.getWardId(), wards.findById(a.getWardId()).map(Ward::getName).orElse(null), a.getAssignedAt()))
                .sorted(Comparator.comparing(WardRef::wardName, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private User requireStaff(UUID userId, UUID org) {
        User u = users.findByIdAndOrganizationId(userId, org).orElseThrow(() -> ApiException.notFound("User"));
        if (u.getRole() == Role.PATIENT || u.getRole() == Role.SUPER_ADMIN) {
            throw ApiException.notFound("User");
        }
        return u;
    }
}
