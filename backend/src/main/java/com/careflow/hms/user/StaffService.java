package com.careflow.hms.user;

import com.careflow.hms.audit.AuditService;
import com.careflow.hms.auth.PasswordPolicy;
import com.careflow.hms.auth.PasswordResetToken;
import com.careflow.hms.auth.PasswordResetTokenRepository;
import com.careflow.hms.auth.TokenHasher;
import com.careflow.hms.auth.UserSessionRepository;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.config.CareFlowProperties;
import com.careflow.hms.facility.Department;
import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.facility.StaffWardAssignment;
import com.careflow.hms.facility.StaffWardAssignmentRepository;
import com.careflow.hms.facility.Ward;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.notification.ExternalChannelGateway;
import com.careflow.hms.notification.NotificationDelivery;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.RolePermissionService;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.UserDtos.CreateStaffRequest;
import com.careflow.hms.user.UserDtos.DirectoryEntry;
import com.careflow.hms.user.UserDtos.RoleInfo;
import com.careflow.hms.user.UserDtos.RoleMatrix;
import com.careflow.hms.user.UserDtos.StaffView;
import com.careflow.hms.user.UserDtos.UpdateStaffRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StaffService {

    private static final Map<String, String> SORTS = Map.of("name", "fullName", "email", "email", "role", "role",
            "createdAt", "createdAt", "lastLoginAt", "lastLoginAt");

    private final UserRepository users;
    private final StaffProfileRepository profiles;
    private final DepartmentRepository departments;
    private final WardRepository wards;
    private final StaffWardAssignmentRepository wardAssignments;
    private final PasswordResetTokenRepository resetTokens;
    private final UserSessionRepository sessions;
    private final RolePermissionService rolePermissions;
    private final PasswordEncoder passwordEncoder;
    private final ExternalChannelGateway mailGateway;
    private final AuditService audit;
    private final CareFlowProperties props;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public PageResponse<StaffView> search(Role role, User.Status status, String q, int page, int size, String sort) {
        UUID org = TenantContext.requireOrganizationId();
        var pageable = Paging.of(page, size, sort, SORTS, Sort.by("fullName"));
        var result = users.search(org, role, status, Paging.likePattern(q), pageable);
        List<StaffView> content = result.getContent().stream()
                .filter(u -> u.getRole() != Role.PATIENT && u.getRole() != Role.SUPER_ADMIN)
                .map(this::view).toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public StaffView get(UUID id) {
        return view(require(id));
    }

    @Transactional
    public StaffView create(CreateStaffRequest r) {
        CurrentUser actor = TenantContext.requireUser();
        if (r.role() == Role.PATIENT || r.role() == Role.SUPER_ADMIN) {
            throw ApiException.rule("INVALID_ROLE", "Staff accounts cannot have the " + r.role() + " role.");
        }
        if (users.findByEmailIgnoreCase(r.email().trim()).isPresent()) {
            throw ApiException.conflict("EMAIL_IN_USE", "An account with this email already exists.");
        }
        validateDepartment(r.departmentId());
        User u = new User();
        u.setEmail(r.email().trim().toLowerCase());
        u.setFullName(r.fullName().trim());
        u.setPhone(r.phone());
        u.setRole(r.role());
        u.setStatus(User.Status.ACTIVE);
        boolean invite = r.temporaryPassword() == null || r.temporaryPassword().isBlank();
        if (!invite) {
            PasswordPolicy.validate(r.temporaryPassword(), r.email());
        }
        u.setPasswordHash(passwordEncoder.encode(invite ? TokenHasher.newToken() : r.temporaryPassword()));
        u.setMustChangePassword(true);
        u = users.saveAndFlush(u);

        StaffProfile p = new StaffProfile();
        p.setUserId(u.getId());
        p.setOrganizationId(u.getOrganizationId());
        applyProfile(p, r.departmentId(), r.designation(), r.specialty(), r.registrationNumber(), r.qualification(), r.employeeCode());
        profiles.save(p);

        if (r.wardIds() != null) {
            for (UUID wardId : r.wardIds()) {
                wards.findByIdAndOrganizationId(wardId, u.getOrganizationId()).orElseThrow(() -> ApiException.notFound("Ward"));
                StaffWardAssignment a = new StaffWardAssignment();
                a.setUserId(u.getId());
                a.setWardId(wardId);
                a.setAssignedBy(actor.userId());
                wardAssignments.save(a);
            }
        }
        if (invite) {
            sendInvite(u);
        }
        audit.record("USER_CREATED", "USER", u.getId(), null, u.getFullName() + " (" + u.getRole() + ")");
        return view(u);
    }

    @Transactional
    public StaffView update(UUID id, UpdateStaffRequest r) {
        User u = require(id);
        if (!r.version().equals(u.getVersion())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This account was changed by someone else. Reload and try again.");
        }
        validateDepartment(r.departmentId());
        u.setFullName(r.fullName().trim());
        u.setPhone(r.phone());
        StaffProfile p = profiles.findById(u.getId()).orElseGet(() -> {
            StaffProfile np = new StaffProfile();
            np.setUserId(u.getId());
            np.setOrganizationId(u.getOrganizationId());
            return np;
        });
        applyProfile(p, r.departmentId(), r.designation(), r.specialty(), r.registrationNumber(), r.qualification(), r.employeeCode());
        profiles.save(p);
        audit.record("USER_UPDATED", "USER", u.getId(), null, u.getFullName());
        return view(users.saveAndFlush(u));
    }

    @Transactional
    public StaffView changeStatus(UUID id, User.Status status, String reason) {
        CurrentUser actor = TenantContext.requireUser();
        User u = require(id);
        if (u.getId().equals(actor.userId())) {
            throw ApiException.rule("SELF_STATUS", "You cannot change the status of your own account.");
        }
        u.setStatus(status);
        if (status == User.Status.ACTIVE) {
            u.setLockedUntil(null);
            u.setFailedLoginCount(0);
        } else {
            sessions.revokeAllForUser(u.getId(), Instant.now(), "ACCOUNT_" + status);
        }
        audit.record("USER_STATUS_CHANGED", "USER", u.getId(), null, status + (reason == null ? "" : ": " + reason));
        return view(u);
    }

    @Transactional
    public void resendInvite(UUID id) {
        User u = require(id);
        sendInvite(u);
        audit.record("USER_INVITE_SENT", "USER", u.getId(), null, "Set-password link sent");
    }

    @Transactional(readOnly = true)
    public List<DirectoryEntry> directory(Role role, UUID departmentId) {
        UUID org = TenantContext.requireOrganizationId();
        Set<Role> roles = role == null ? EnumSet.of(Role.DOCTOR, Role.NURSE, Role.PHARMACIST, Role.BILLING, Role.HOSPITAL_ADMIN) : EnumSet.of(role);
        Map<UUID, String> deptNames = departments.findByOrganizationIdOrderByName(org).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        return roles.stream()
                .flatMap(r -> users.findByOrganizationIdAndRoleAndStatus(org, r, User.Status.ACTIVE).stream())
                .map(u -> {
                    StaffProfile p = profiles.findById(u.getId()).orElse(null);
                    UUID dept = p == null ? null : p.getDepartmentId();
                    return new DirectoryEntry(u.getId(), u.getFullName(), u.getRole(), dept, deptNames.get(dept),
                            p == null ? null : p.getDesignation(), p == null ? null : p.getSpecialty());
                })
                .filter(e -> departmentId == null || departmentId.equals(e.departmentId()))
                .sorted((a, b) -> a.fullName().compareToIgnoreCase(b.fullName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleMatrix roleMatrix() {
        UUID org = TenantContext.requireOrganizationId();
        List<Map<String, Object>> roleRows = jdbc.queryForList("SELECT code, name, description FROM roles ORDER BY code");
        List<RoleInfo> roles = roleRows.stream()
                .filter(row -> !Role.SUPER_ADMIN.name().equals(row.get("code")))
                .map(row -> {
                    Role role = Role.valueOf((String) row.get("code"));
                    return new RoleInfo(role, (String) row.get("name"), (String) row.get("description"),
                            rolePermissions.permissionsFor(org, role), users.countByOrganizationIdAndRole(org, role));
                }).toList();
        List<UserDtos.PermissionInfo> perms = rolePermissions.catalogue().stream()
                .filter(m -> !"Platform".equals(m.get("category")))
                .map(m -> new UserDtos.PermissionInfo((String) m.get("code"), (String) m.get("category"), (String) m.get("description")))
                .toList();
        return new RoleMatrix(roles, perms);
    }

    @Transactional
    public RoleMatrix updateRolePermissions(Role role, Set<String> permissions) {
        CurrentUser actor = TenantContext.requireUser();
        if (role == Role.SUPER_ADMIN) {
            throw ApiException.rule("INVALID_ROLE", "Platform roles are not editable here.");
        }
        Set<String> platformOnly = Set.of("ORG_MANAGE", "PLATFORM_AUDIT_READ");
        if (permissions.stream().anyMatch(platformOnly::contains)) {
            throw ApiException.rule("INVALID_PERMISSION", "Platform permissions cannot be granted to hospital roles.");
        }
        if (role == Role.HOSPITAL_ADMIN && actor.role() == Role.HOSPITAL_ADMIN
                && (!permissions.contains("ROLE_MANAGE") || !permissions.contains("USER_MANAGE"))) {
            throw ApiException.rule("LOCKOUT_PREVENTION", "Administrators must keep role and user management to avoid lock-out.");
        }
        Set<String> before = new HashSet<>(rolePermissions.permissionsFor(actor.organizationId(), role));
        rolePermissions.replace(actor.organizationId(), role, permissions, actor.userId());
        Set<String> added = new HashSet<>(permissions);
        added.removeAll(before);
        Set<String> removed = new HashSet<>(before);
        removed.removeAll(permissions);
        audit.record("ROLE_PERMISSIONS_CHANGED", "ROLE", null, null, role + " +" + added + " -" + removed);
        return roleMatrix();
    }

    private void sendInvite(User u) {
        Instant now = Instant.now();
        resetTokens.invalidateOutstanding(u.getId(), now);
        String raw = TokenHasher.newToken();
        PasswordResetToken t = new PasswordResetToken();
        t.setUserId(u.getId());
        t.setTokenHash(TokenHasher.sha256(raw));
        t.setPurpose("INVITE");
        t.setCreatedAt(now);
        t.setExpiresAt(now.plusSeconds(3 * 24 * 3600));
        resetTokens.save(t);
        mailGateway.send(u.getOrganizationId(), NotificationDelivery.Channel.EMAIL, u.getEmail(),
                "Your CareFlow account", "Hello " + u.getFullName() + ",\n\nAn account was created for you. Set your password (valid 3 days):\n"
                        + props.frontendBaseUrl() + "/reset-password?token=" + raw);
    }

    private void applyProfile(StaffProfile p, UUID dept, String designation, String specialty, String reg, String qual, String code) {
        p.setDepartmentId(dept);
        p.setDesignation(designation);
        p.setSpecialty(specialty);
        p.setRegistrationNumber(reg);
        p.setQualification(qual);
        p.setEmployeeCode(code == null || code.isBlank() ? null : code.trim());
    }

    private void validateDepartment(UUID dept) {
        if (dept != null) {
            departments.findByIdAndOrganizationId(dept, TenantContext.requireOrganizationId())
                    .orElseThrow(() -> ApiException.notFound("Department"));
        }
    }

    private User require(UUID id) {
        User u = users.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("User"));
        if (u.getRole() == Role.PATIENT || u.getRole() == Role.SUPER_ADMIN) {
            throw ApiException.notFound("User");
        }
        return u;
    }

    private StaffView view(User u) {
        StaffProfile p = profiles.findById(u.getId()).orElse(null);
        String dept = p == null || p.getDepartmentId() == null ? null
                : departments.findById(p.getDepartmentId()).map(Department::getName).orElse(null);
        List<UUID> wardIds = wardAssignments.activeWardIds(u.getId());
        List<String> wardNames = wardIds.isEmpty() ? List.of()
                : wards.findByOrganizationIdAndIdIn(u.getOrganizationId(), wardIds).stream().map(Ward::getName).toList();
        boolean locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(Instant.now());
        return new StaffView(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getRole(), u.getStatus(), locked,
                u.getLastLoginAt(), u.getCreatedAt(), p == null ? null : p.getDepartmentId(), dept,
                p == null ? null : p.getDesignation(), p == null ? null : p.getSpecialty(),
                p == null ? null : p.getRegistrationNumber(), p == null ? null : p.getQualification(),
                p == null ? null : p.getEmployeeCode(), wardNames, u.getVersion());
    }
}
