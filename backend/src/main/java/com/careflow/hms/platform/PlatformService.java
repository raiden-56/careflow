package com.careflow.hms.platform;

import com.careflow.hms.admin.IntegrationService;
import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.auth.TokenHasher;
import com.careflow.hms.auth.UserSessionRepository;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.config.CareFlowProperties;
import com.careflow.hms.integration.IntegrationConfig;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationProvisioningService;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.platform.PlatformDtos.AdminAccount;
import com.careflow.hms.platform.PlatformDtos.CreateAdminRequest;
import com.careflow.hms.platform.PlatformDtos.CreateOrganizationRequest;
import com.careflow.hms.platform.PlatformDtos.FlywayMigration;
import com.careflow.hms.platform.PlatformDtos.Health;
import com.careflow.hms.platform.PlatformDtos.IntegrationRow;
import com.careflow.hms.platform.PlatformDtos.OrgRoleMatrix;
import com.careflow.hms.platform.PlatformDtos.OrgRoleTemplate;
import com.careflow.hms.platform.PlatformDtos.OrganizationDetail;
import com.careflow.hms.platform.PlatformDtos.OrganizationRow;
import com.careflow.hms.platform.PlatformDtos.Overview;
import com.careflow.hms.platform.PlatformDtos.PermissionInfo;
import com.careflow.hms.platform.PlatformDtos.RoleInfo;
import com.careflow.hms.platform.PlatformDtos.SecurityEvent;
import com.careflow.hms.platform.PlatformDtos.SecuritySummary;
import com.careflow.hms.platform.PlatformDtos.TemplateCatalogue;
import com.careflow.hms.platform.PlatformDtos.UpdateOrganizationRequest;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Permissions;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.RolePermissionService;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.StaffProfile;
import com.careflow.hms.user.StaffProfileRepository;
import com.careflow.hms.user.StaffService;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Super-administrator platform operations. Works only with organization metadata, accounts, permission
 * templates, integration configuration, security events and system health - never with patient,
 * clinical or financial records.
 */
@Service
@RequiredArgsConstructor
public class PlatformService {

    /** Audit actions surfaced on the platform security monitor. */
    static final Map<String, String> SECURITY_CATEGORIES = new LinkedHashMap<>();

    static {
        SECURITY_CATEGORIES.put("FAILED_LOGIN", "'LOGIN_FAILED'");
        SECURITY_CATEGORIES.put("LOCKOUT", "'LOGIN_BLOCKED'");
        SECURITY_CATEGORIES.put("ACCESS_DENIED", "'ACCESS_DENIED','DOCUMENT_DOWNLOAD_REJECTED'");
        SECURITY_CATEGORIES.put("SESSION_REUSE", "'SESSION_REUSE_DETECTED'");
        SECURITY_CATEGORIES.put("ACCOUNT_CHANGE", "'USER_STATUS_CHANGED','PASSWORD_RESET','PASSWORD_CHANGED','ADMIN_ACCOUNT_CREATED','ADMIN_STATUS_CHANGED'");
        SECURITY_CATEGORIES.put("PERMISSION_CHANGE", "'ROLE_PERMISSIONS_CHANGED','ROLE_TEMPLATE_RESET'");
        SECURITY_CATEGORIES.put("ORGANIZATION_CHANGE", "'ORG_CREATED','ORG_UPDATED','ORG_SUSPENDED','ORG_REACTIVATED'");
        SECURITY_CATEGORIES.put("INTEGRATION_CHANGE", "'INTEGRATION_MODE_CHANGED','INTEGRATION_UPDATED'");
    }

    private final OrganizationRepository organizations;
    private final OrganizationProvisioningService provisioning;
    private final UserRepository users;
    private final StaffProfileRepository profiles;
    private final StaffService staffService;
    private final UserSessionRepository sessions;
    private final RolePermissionService rolePermissions;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final HealthEndpoint healthEndpoint;
    private final ScheduledJobMonitor jobMonitor;
    private final CareFlowProperties props;

    // ================================================================== overview

    @Transactional(readOnly = true)
    public Overview overview() {
        Map<String, Object> o = jdbc.queryForMap("""
                SELECT count(*) AS total, count(*) FILTER (WHERE status = 'ACTIVE') AS active,
                       count(*) FILTER (WHERE status = 'SUSPENDED') AS suspended
                FROM organizations WHERE NOT is_platform
                """);
        Map<String, Object> u = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE u.role_code NOT IN ('PATIENT','SUPER_ADMIN')) AS staff,
                       count(*) FILTER (WHERE u.role_code = 'HOSPITAL_ADMIN') AS admins
                FROM users u JOIN organizations o ON o.id = u.organization_id WHERE NOT o.is_platform
                """);
        Map<String, Long> modes = new LinkedHashMap<>();
        jdbc.query("SELECT mode, count(*) AS n FROM integration_configs GROUP BY mode ORDER BY mode",
                rs -> {
                    modes.put(rs.getString("mode"), rs.getLong("n"));
                });
        List<OrganizationRow> recent = organizationRows(null, null, 0, 5).content();
        return new Overview(num(o, "total"), num(o, "active"), num(o, "suspended"), num(u, "staff"), num(u, "admins"),
                activeSessions(), securitySummary(), recent, modes);
    }

    // ================================================================== organizations

    @Transactional(readOnly = true)
    public PageResponse<OrganizationRow> organizationRows(String q, Organization.Status status, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE NOT o.is_platform");
        if (q != null && !q.isBlank()) {
            where.append(" AND (lower(o.name) LIKE ? OR lower(o.code) LIKE ? OR lower(coalesce(o.city,'')) LIKE ?)");
            String like = "%" + q.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null) {
            where.append(" AND o.status = ?");
            args.add(status.name());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM organizations o" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);
        List<OrganizationRow> rows = jdbc.query("""
                SELECT o.id, o.code, o.name, o.legal_name, o.city, o.state, o.timezone, o.status, o.created_at,
                       (SELECT count(*) FROM users u WHERE u.organization_id = o.id AND u.role_code NOT IN ('PATIENT','SUPER_ADMIN')) AS staff,
                       (SELECT count(*) FROM users u WHERE u.organization_id = o.id AND u.role_code = 'HOSPITAL_ADMIN') AS admins,
                       (SELECT count(*) FROM wards w WHERE w.organization_id = o.id AND w.active) AS wards,
                       (SELECT count(*) FROM beds b WHERE b.organization_id = o.id AND b.active) AS beds,
                       (SELECT max(u.last_login_at) FROM users u WHERE u.organization_id = o.id AND u.role_code <> 'PATIENT') AS last_login
                FROM organizations o""" + where + " ORDER BY o.created_at DESC LIMIT ? OFFSET ?", (rs, i) -> new OrganizationRow(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("legal_name"),
                rs.getString("city"), rs.getString("state"), rs.getString("timezone"), Organization.Status.valueOf(rs.getString("status")),
                rs.getLong("staff"), rs.getLong("admins"), rs.getLong("wards"), rs.getLong("beds"),
                rs.getTimestamp("last_login") == null ? null : rs.getTimestamp("last_login").toInstant(),
                rs.getTimestamp("created_at").toInstant()), pageArgs.toArray());
        long t = total == null ? 0 : total;
        return new PageResponse<>(rows, safePage, safeSize, t, (int) Math.ceil(t / (double) safeSize));
    }

    @Transactional(readOnly = true)
    public OrganizationDetail organization(UUID id) {
        Organization o = requireHospital(id);
        Map<String, Long> byRole = new LinkedHashMap<>();
        jdbc.query("SELECT role_code, count(*) AS n FROM users WHERE organization_id = ? AND role_code <> 'SUPER_ADMIN' GROUP BY role_code ORDER BY role_code",
                rs -> {
                    byRole.put(rs.getString("role_code"), rs.getLong("n"));
                }, id);
        Long wards = jdbc.queryForObject("SELECT count(*) FROM wards WHERE organization_id = ? AND active", Long.class, id);
        Long beds = jdbc.queryForObject("SELECT count(*) FROM beds WHERE organization_id = ? AND active", Long.class, id);
        return new OrganizationDetail(o.getId(), o.getCode(), o.getName(), o.getLegalName(), o.getTimezone(), o.getCurrency(),
                o.getAddress(), o.getCity(), o.getState(), o.getPhone(), o.getEmail(), o.getRegistrationNumber(), o.getStatus(),
                o.getCreatedAt(), o.getUpdatedAt(), o.getVersion(), byRole, wards == null ? 0 : wards, beds == null ? 0 : beds,
                admins(id, null, 0, 50).content());
    }

    @Transactional
    public OrganizationDetail create(CreateOrganizationRequest r) {
        String code = r.code().trim().toUpperCase();
        if (organizations.existsByCode(code)) {
            throw ApiException.conflict("CODE_IN_USE", "An organization with code " + code + " already exists.");
        }
        Organization o = new Organization();
        o.setCode(code);
        applyProfile(o, r.name(), r.legalName(), r.timezone(), r.address(), r.city(), r.state(), r.phone(), r.email(),
                r.registrationNumber());
        o = organizations.saveAndFlush(o);
        provisioning.provision(o);
        Map<String, Object> details = Map.of("organizationId", o.getId().toString(), "code", o.getCode());
        audit.record("ORG_CREATED", "ORGANIZATION", o.getId(), null, AuditEvent.Outcome.SUCCESS,
                "Registered " + o.getName() + " (" + o.getCode() + ") with default templates", details);
        recordInTarget(o.getId(), "ORG_CREATED", "ORGANIZATION", o.getId(), "Organization registered by the platform team", details);
        return organization(o.getId());
    }

    @Transactional
    public OrganizationDetail update(UUID id, UpdateOrganizationRequest r) {
        Organization o = requireHospital(id);
        if (!Objects.equals(o.getVersion(), r.version())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "The organization was changed by someone else. Reload and try again.");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        diff(changes, "name", o.getName(), r.name().trim());
        diff(changes, "timezone", o.getTimezone(), r.timezone().trim());
        diff(changes, "email", o.getEmail(), blank(r.email()));
        diff(changes, "phone", o.getPhone(), blank(r.phone()));
        applyProfile(o, r.name(), r.legalName(), r.timezone(), r.address(), r.city(), r.state(), r.phone(), r.email(),
                r.registrationNumber());
        organizations.saveAndFlush(o);
        audit.record("ORG_UPDATED", "ORGANIZATION", o.getId(), null, AuditEvent.Outcome.SUCCESS, "Updated " + o.getName(), changes);
        return organization(id);
    }

    @Transactional
    public OrganizationDetail suspend(UUID id, String reason) {
        Organization o = requireHospital(id);
        if (o.getStatus() == Organization.Status.SUSPENDED) {
            throw ApiException.rule("ALREADY_SUSPENDED", "The organization is already suspended.");
        }
        o.setStatus(Organization.Status.SUSPENDED);
        organizations.saveAndFlush(o);
        int revoked = jdbc.update("""
                UPDATE user_sessions SET revoked_at = now(), revoke_reason = 'ORG_SUSPENDED'
                WHERE revoked_at IS NULL AND user_id IN (SELECT id FROM users WHERE organization_id = ?)
                """, id);
        Map<String, Object> details = Map.of("reason", reason.trim(), "sessionsRevoked", revoked);
        audit.record("ORG_SUSPENDED", "ORGANIZATION", o.getId(), null, AuditEvent.Outcome.SUCCESS,
                o.getName() + " suspended: " + reason.trim(), details);
        recordInTarget(id, "ORG_SUSPENDED", "ORGANIZATION", id, "Organization suspended by the platform team", details);
        return organization(id);
    }

    @Transactional
    public OrganizationDetail reactivate(UUID id) {
        Organization o = requireHospital(id);
        if (o.getStatus() == Organization.Status.ACTIVE) {
            throw ApiException.rule("ALREADY_ACTIVE", "The organization is already active.");
        }
        o.setStatus(Organization.Status.ACTIVE);
        organizations.saveAndFlush(o);
        audit.record("ORG_REACTIVATED", "ORGANIZATION", o.getId(), null, AuditEvent.Outcome.SUCCESS, o.getName() + " reactivated", null);
        recordInTarget(id, "ORG_REACTIVATED", "ORGANIZATION", id, "Organization reactivated by the platform team", null);
        return organization(id);
    }

    // ================================================================== hospital administrator accounts

    @Transactional(readOnly = true)
    public PageResponse<AdminAccount> admins(UUID orgId, String q, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE u.role_code = 'HOSPITAL_ADMIN' AND NOT o.is_platform");
        if (orgId != null) {
            where.append(" AND u.organization_id = ?");
            args.add(orgId);
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (lower(u.full_name) LIKE ? OR lower(u.email) LIKE ?)");
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM users u JOIN organizations o ON o.id = u.organization_id" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);
        List<AdminAccount> rows = jdbc.query("""
                SELECT u.id, u.organization_id, o.name AS org_name, o.code AS org_code, u.email, u.full_name, u.phone, u.status,
                       u.locked_until, u.last_login_at, u.must_change_password, u.created_at
                FROM users u JOIN organizations o ON o.id = u.organization_id""" + where
                + " ORDER BY o.name, u.full_name LIMIT ? OFFSET ?", (rs, i) -> {
            Timestamp locked = rs.getTimestamp("locked_until");
            Timestamp last = rs.getTimestamp("last_login_at");
            return new AdminAccount(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                    rs.getString("org_name"), rs.getString("org_code"), rs.getString("email"), rs.getString("full_name"),
                    rs.getString("phone"), User.Status.valueOf(rs.getString("status")),
                    locked != null && locked.toInstant().isAfter(Instant.now()),
                    last == null && rs.getBoolean("must_change_password"),
                    last == null ? null : last.toInstant(), rs.getTimestamp("created_at").toInstant());
        }, pageArgs.toArray());
        long t = total == null ? 0 : total;
        return new PageResponse<>(rows, safePage, safeSize, t, (int) Math.ceil(t / (double) safeSize));
    }

    @Transactional
    public AdminAccount createAdmin(UUID orgId, CreateAdminRequest r) {
        CurrentUser actor = TenantContext.requireUser();
        Organization o = requireHospital(orgId);
        if (o.getStatus() != Organization.Status.ACTIVE) {
            throw ApiException.rule("ORG_SUSPENDED", "Reactivate the organization before provisioning administrators.");
        }
        String email = r.email().trim().toLowerCase();
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            throw ApiException.conflict("EMAIL_IN_USE", "An account with this email already exists.");
        }
        User created = TenantContext.runAs(orgId, actor.userId(), () -> {
            User u = new User();
            u.setOrganizationId(orgId);
            u.setEmail(email);
            u.setFullName(r.fullName().trim());
            u.setPhone(blank(r.phone()));
            u.setRole(Role.HOSPITAL_ADMIN);
            u.setStatus(User.Status.ACTIVE);
            u.setPasswordHash(passwordEncoder.encode(TokenHasher.newToken()));
            u.setMustChangePassword(true);
            u = users.saveAndFlush(u);
            StaffProfile p = new StaffProfile();
            p.setUserId(u.getId());
            p.setOrganizationId(orgId);
            p.setDesignation(blank(r.designation()) == null ? "Hospital Administrator" : r.designation().trim());
            profiles.save(p);
            staffService.sendInvitation(u);
            return u;
        });
        Map<String, Object> details = Map.of("organizationId", orgId.toString(), "email", email);
        audit.record("ADMIN_ACCOUNT_CREATED", "USER", created.getId(), null, AuditEvent.Outcome.SUCCESS,
                "Provisioned hospital administrator " + created.getFullName() + " for " + o.getName(), details);
        recordInTarget(orgId, "USER_CREATED", "USER", created.getId(), created.getFullName() + " (HOSPITAL_ADMIN) provisioned by the platform team", details);
        return admins(orgId, email, 0, 1).content().get(0);
    }

    @Transactional
    public AdminAccount changeAdminStatus(UUID userId, User.Status status, String reason) {
        User u = requireAdmin(userId);
        u.setStatus(status);
        if (status == User.Status.ACTIVE) {
            u.setLockedUntil(null);
            u.setFailedLoginCount(0);
        } else {
            sessions.revokeAllForUser(u.getId(), Instant.now(), "ACCOUNT_" + status);
        }
        users.saveAndFlush(u);
        String summary = u.getFullName() + " -> " + status + (reason == null || reason.isBlank() ? "" : ": " + reason.trim());
        audit.record("ADMIN_STATUS_CHANGED", "USER", u.getId(), null, AuditEvent.Outcome.SUCCESS, summary,
                Map.of("organizationId", u.getOrganizationId().toString(), "status", status.name()));
        recordInTarget(u.getOrganizationId(), "USER_STATUS_CHANGED", "USER", u.getId(), summary + " (platform team)", null);
        return admins(u.getOrganizationId(), u.getEmail(), 0, 1).content().get(0);
    }

    @Transactional
    public void resendAdminInvite(UUID userId) {
        User u = requireAdmin(userId);
        if (u.getStatus() != User.Status.ACTIVE) {
            throw ApiException.rule("ACCOUNT_NOT_ACTIVE", "Enable the account before sending a new invitation.");
        }
        staffService.sendInvitation(u);
        audit.record("ADMIN_INVITE_SENT", "USER", u.getId(), null, AuditEvent.Outcome.SUCCESS,
                "Set-password link sent to " + u.getFullName(), Map.of("organizationId", u.getOrganizationId().toString()));
    }

    // ================================================================== permission templates

    public TemplateCatalogue templates() {
        Map<Role, Set<String>> defaults = new EnumMap<>(Role.class);
        Permissions.DEFAULT_TEMPLATES.forEach((role, perms) -> defaults.put(role, new TreeSet<>(perms)));
        List<PermissionInfo> perms = rolePermissions.catalogue().stream()
                .map(m -> new PermissionInfo((String) m.get("code"), (String) m.get("category"), (String) m.get("description"))).toList();
        List<RoleInfo> roles = jdbc.query("SELECT code, name, description FROM roles ORDER BY code",
                (rs, i) -> new RoleInfo(Role.valueOf(rs.getString("code")), rs.getString("name"), rs.getString("description")));
        return new TemplateCatalogue(defaults, perms, roles);
    }

    @Transactional(readOnly = true)
    public OrgRoleMatrix orgRoles(UUID orgId) {
        Organization o = requireHospital(orgId);
        Map<String, Set<String>> current = new HashMap<>();
        jdbc.query("SELECT role_code, permission_code FROM role_permissions WHERE organization_id = ?", rs -> {
            current.computeIfAbsent(rs.getString(1), k -> new TreeSet<>()).add(rs.getString(2));
        }, orgId);
        Map<String, String> names = new HashMap<>();
        jdbc.query("SELECT code, name FROM roles", rs -> {
            names.put(rs.getString(1), rs.getString(2));
        });
        List<OrgRoleTemplate> roles = new ArrayList<>();
        for (Role role : Role.values()) {
            if (role == Role.SUPER_ADMIN) {
                continue;
            }
            Set<String> cur = current.getOrDefault(role.name(), new TreeSet<>());
            Set<String> def = new TreeSet<>(Permissions.DEFAULT_TEMPLATES.getOrDefault(role, Set.of()));
            Set<String> added = new TreeSet<>(cur);
            added.removeAll(def);
            Set<String> removed = new TreeSet<>(def);
            removed.removeAll(cur);
            roles.add(new OrgRoleTemplate(role, names.getOrDefault(role.name(), role.name()), cur, def, added, removed,
                    added.isEmpty() && removed.isEmpty(), users.countByOrganizationIdAndRole(orgId, role)));
        }
        return new OrgRoleMatrix(o.getId(), o.getName(), roles);
    }

    @Transactional
    public OrgRoleMatrix resetRole(UUID orgId, Role role) {
        CurrentUser actor = TenantContext.requireUser();
        Organization o = requireHospital(orgId);
        if (role == Role.SUPER_ADMIN) {
            throw ApiException.rule("INVALID_ROLE", "The platform role is not part of hospital templates.");
        }
        Set<String> before = new TreeSet<>(jdbc.queryForList(
                "SELECT permission_code FROM role_permissions WHERE organization_id = ? AND role_code = ?", String.class, orgId, role.name()));
        Set<String> defaults = Permissions.DEFAULT_TEMPLATES.getOrDefault(role, Set.of());
        rolePermissions.replace(orgId, role, defaults, actor.userId());
        Set<String> added = new TreeSet<>(defaults);
        added.removeAll(before);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(defaults);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("organizationId", orgId.toString());
        details.put("role", role.name());
        details.put("added", List.copyOf(added));
        details.put("removed", List.copyOf(removed));
        audit.record("ROLE_TEMPLATE_RESET", "ROLE", null, null, AuditEvent.Outcome.SUCCESS,
                o.getName() + ": " + role + " reset to the default template", details);
        recordInTarget(orgId, "ROLE_TEMPLATE_RESET", "ROLE", null, role + " permissions reset to the default template by the platform team", details);
        return orgRoles(orgId);
    }

    // ================================================================== security monitoring

    @Transactional(readOnly = true)
    public PageResponse<SecurityEvent> securityEvents(UUID orgId, String category, String outcome, Instant from, Instant to,
                                                      String q, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE e.occurred_at >= ? AND e.occurred_at < ?");
        args.add(Timestamp.from(from == null ? Instant.now().minus(Duration.ofDays(7)) : from));
        args.add(Timestamp.from(to == null ? Instant.now().plusSeconds(60) : to));
        if (category != null && !category.isBlank()) {
            String actions = SECURITY_CATEGORIES.get(category);
            if (actions == null) {
                throw ApiException.badRequest("INVALID_CATEGORY", "Unknown category " + category);
            }
            where.append(" AND e.action IN (").append(actions).append(")");
        } else {
            where.append(" AND (e.action IN (").append(String.join(",", SECURITY_CATEGORIES.values()))
                    .append(") OR e.outcome <> 'SUCCESS')");
        }
        if (orgId != null) {
            where.append(" AND e.organization_id = ?");
            args.add(orgId);
        }
        if (outcome != null && !outcome.isBlank()) {
            if (!Set.of("SUCCESS", "DENIED", "FAILURE").contains(outcome)) {
                throw ApiException.badRequest("INVALID_OUTCOME", "Unknown outcome " + outcome);
            }
            where.append(" AND e.outcome = ?");
            args.add(outcome);
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (lower(coalesce(e.actor_name,'')) LIKE ? OR lower(coalesce(e.ip_address,'')) LIKE ?)");
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_events e" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);
        Map<String, String> categoryOf = new HashMap<>();
        SECURITY_CATEGORIES.forEach((cat, list) -> {
            for (String a : list.replace("'", "").split(",")) {
                categoryOf.put(a, cat);
            }
        });
        // Patient identifiers, entity ids and details are deliberately not selected: the platform team sees
        // security signals, never patient records.
        List<SecurityEvent> rows = jdbc.query("""
                SELECT e.id, e.occurred_at, e.organization_id, o.name AS org_name, e.actor_name, e.actor_role, e.action,
                       e.outcome, e.summary, e.ip_address
                FROM audit_events e LEFT JOIN organizations o ON o.id = e.organization_id""" + where
                + " ORDER BY e.occurred_at DESC LIMIT ? OFFSET ?", (rs, i) -> new SecurityEvent(rs.getObject("id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getObject("organization_id", UUID.class), rs.getString("org_name"),
                rs.getString("actor_name"), rs.getString("actor_role"), rs.getString("action"),
                categoryOf.getOrDefault(rs.getString("action"), "OTHER"), rs.getString("outcome"),
                platformSafeSummary(rs.getString("action"), rs.getString("summary")), rs.getString("ip_address")), pageArgs.toArray());
        long t = total == null ? 0 : total;
        return new PageResponse<>(rows, safePage, safeSize, t, (int) Math.ceil(t / (double) safeSize));
    }

    /** Summaries of patient-related denials are generalized so no record detail reaches the platform team. */
    static String platformSafeSummary(String action, String summary) {
        if (summary == null) {
            return null;
        }
        if ("ACCESS_DENIED".equals(action) || action.startsWith("DOCUMENT_")) {
            return summary.replaceAll("(?i)patient\\S*", "record");
        }
        return summary;
    }

    @Transactional(readOnly = true)
    public SecuritySummary securitySummary() {
        Timestamp day = Timestamp.from(Instant.now().minus(Duration.ofHours(24)));
        Timestamp week = Timestamp.from(Instant.now().minus(Duration.ofDays(7)));
        Map<String, Object> m = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE action = 'LOGIN_FAILED' AND occurred_at >= ?) AS failed24,
                       count(*) FILTER (WHERE action = 'LOGIN_BLOCKED' AND occurred_at >= ?) AS lock24,
                       count(*) FILTER (WHERE action = 'ACCESS_DENIED' AND occurred_at >= ?) AS denied24,
                       count(*) FILTER (WHERE action = 'SESSION_REUSE_DETECTED') AS reuse7,
                       count(*) FILTER (WHERE action = 'LOGIN_FAILED') AS failed7,
                       count(*) FILTER (WHERE action = 'ACCESS_DENIED') AS denied7
                FROM audit_events WHERE occurred_at >= ?
                """, day, day, day, week);
        return new SecuritySummary(num(m, "failed24"), num(m, "lock24"), num(m, "denied24"), num(m, "reuse7"),
                num(m, "failed7"), num(m, "denied7"));
    }

    // ================================================================== integrations overview

    @Transactional(readOnly = true)
    public List<IntegrationRow> integrations(UUID orgId) {
        List<Object> args = new ArrayList<>();
        String where = " WHERE NOT o.is_platform";
        if (orgId != null) {
            where += " AND o.id = ?";
            args.add(orgId);
        }
        return jdbc.query("""
                SELECT o.id AS org_id, o.name, o.code, c.integration_type, c.mode, c.endpoint_url, c.updated_at,
                       (c.settings ->> 'secretName') IS NOT NULL AS has_secret
                FROM integration_configs c JOIN organizations o ON o.id = c.organization_id""" + where
                + " ORDER BY o.name, c.integration_type", (rs, i) -> {
            IntegrationConfig.IntegrationType type = IntegrationConfig.IntegrationType.valueOf(rs.getString("integration_type"));
            IntegrationConfig.Mode mode = IntegrationConfig.Mode.valueOf(rs.getString("mode"));
            return new IntegrationRow(rs.getObject("org_id", UUID.class), rs.getString("name"), rs.getString("code"), type.name(),
                    IntegrationService.LABELS.get(type), mode.name(), rs.getString("endpoint_url"), rs.getBoolean("has_secret"),
                    IntegrationService.adapterStatus(type, mode), rs.getTimestamp("updated_at").toInstant());
        }, args.toArray());
    }

    // ================================================================== health

    public Health health() {
        String status;
        try {
            status = healthEndpoint.health().getStatus().getCode();
        } catch (RuntimeException e) {
            status = "UNKNOWN";
        }
        boolean dbUp;
        String dbVersion = null;
        long latency;
        long start = System.nanoTime();
        try {
            dbVersion = jdbc.queryForObject("SELECT version()", String.class);
            dbUp = true;
        } catch (RuntimeException e) {
            dbUp = false;
        }
        latency = (System.nanoTime() - start) / 1_000_000;
        List<FlywayMigration> migrations = List.of();
        long orgs = 0;
        long userCount = 0;
        long activeSessions = 0;
        if (dbUp) {
            migrations = jdbc.query("""
                    SELECT installed_rank, version, description, installed_on, success, execution_time
                    FROM flyway_schema_history ORDER BY installed_rank
                    """, (rs, i) -> new FlywayMigration(rs.getInt("installed_rank"), rs.getString("version"),
                    rs.getString("description"), rs.getTimestamp("installed_on").toInstant(), rs.getBoolean("success"),
                    rs.getInt("execution_time")));
            orgs = Objects.requireNonNullElse(jdbc.queryForObject("SELECT count(*) FROM organizations WHERE NOT is_platform", Long.class), 0L);
            userCount = Objects.requireNonNullElse(jdbc.queryForObject("SELECT count(*) FROM users", Long.class), 0L);
            activeSessions = activeSessions();
        }
        String current = migrations.stream().filter(m -> m.version() != null && m.success())
                .reduce((a, b) -> b).map(FlywayMigration::version).orElse(null);
        Runtime rt = Runtime.getRuntime();
        return new Health(status, Instant.now(), dbUp, dbVersion, latency, current, migrations.size(),
                migrations.stream().allMatch(FlywayMigration::success), migrations, orgs, userCount, activeSessions,
                jobMonitor.snapshot(), ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024), rt.maxMemory() / (1024 * 1024), rt.availableProcessors(),
                ManagementFactory.getThreadMXBean().getThreadCount(), System.getProperty("java.version"),
                props.demo() != null && props.demo().enabled(), props.dev() != null && props.dev().outboxEnabled());
    }

    // ================================================================== helpers

    private long activeSessions() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL AND expires_at > now()", Long.class);
        return n == null ? 0 : n;
    }

    private Organization requireHospital(UUID id) {
        Organization o = organizations.findById(id).orElseThrow(() -> ApiException.notFound("Organization"));
        if (o.isPlatform()) {
            throw ApiException.rule("PLATFORM_ORG", "The platform organization cannot be managed here.");
        }
        return o;
    }

    private User requireAdmin(UUID userId) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Administrator"));
        if (u.getRole() != Role.HOSPITAL_ADMIN) {
            throw ApiException.notFound("Administrator");
        }
        Organization o = organizations.findById(u.getOrganizationId()).orElseThrow(() -> ApiException.notFound("Administrator"));
        if (o.isPlatform()) {
            throw ApiException.notFound("Administrator");
        }
        return u;
    }

    private void applyProfile(Organization o, String name, String legalName, String timezone, String address, String city,
                              String state, String phone, String email, String reg) {
        try {
            ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            throw ApiException.badRequest("INVALID_TIMEZONE", "Unknown time zone " + timezone + ".");
        }
        o.setName(name.trim());
        o.setLegalName(blank(legalName));
        o.setTimezone(timezone.trim());
        o.setAddress(blank(address));
        o.setCity(blank(city));
        o.setState(blank(state));
        o.setPhone(blank(phone));
        o.setEmail(blank(email));
        o.setRegistrationNumber(blank(reg));
    }

    /** Mirrors a platform action into the affected hospital's own audit log. */
    private void recordInTarget(UUID orgId, String action, String entityType, UUID entityId, String summary,
                                Map<String, Object> details) {
        CurrentUser actor = TenantContext.requireUser();
        Runnable write = () -> audit.recordAs(orgId, actor.userId(), actor.fullName(), actor.role().name(), action, entityType,
                entityId, null, AuditEvent.Outcome.SUCCESS, summary, details);
        // The hospital-side entry is written in its own transaction; wait for this one to commit so that a newly
        // registered organization exists (foreign key) and nothing is recorded for a change that rolled back.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            write.run();
                        }
                    });
        } else {
            write.run();
        }
    }

    private static void diff(Map<String, Object> changes, String field, Object old, Object now) {
        if (!Objects.equals(old, now)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("old", old == null ? null : String.valueOf(old));
            m.put("new", now == null ? null : String.valueOf(now));
            changes.put(field, m);
        }
    }

    private static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
