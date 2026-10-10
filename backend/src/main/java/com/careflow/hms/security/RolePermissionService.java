package com.careflow.hms.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Loads (and caches) the effective permission set of a role within an organization. */
@Service
@RequiredArgsConstructor
public class RolePermissionService {

    private final JdbcTemplate jdbc;
    private final Map<String, Set<String>> cache = new ConcurrentHashMap<>();

    public Set<String> permissionsFor(UUID orgId, Role role) {
        return cache.computeIfAbsent(orgId + ":" + role, k -> Set.copyOf(jdbc.queryForList(
                "SELECT permission_code FROM role_permissions WHERE organization_id = ? AND role_code = ?",
                String.class, orgId, role.name())));
    }

    /** Applies the default least-privilege templates to a new organization. */
    @Transactional
    public void applyDefaultTemplates(UUID orgId) {
        Permissions.DEFAULT_TEMPLATES.forEach((role, perms) -> perms.forEach(p -> jdbc.update("""
                INSERT INTO role_permissions (organization_id, role_code, permission_code)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, orgId, role.name(), p)));
        evict(orgId);
    }

    @Transactional
    public void replace(UUID orgId, Role role, Set<String> permissions, UUID changedBy) {
        Set<String> valid = new HashSet<>(jdbc.queryForList("SELECT code FROM permissions", String.class));
        for (String p : permissions) {
            if (!valid.contains(p)) {
                throw com.careflow.hms.common.ApiException.badRequest("UNKNOWN_PERMISSION", "Unknown permission: " + p);
            }
        }
        if (role == Role.PATIENT && permissions.stream().anyMatch(p -> !p.equals(Permissions.PORTAL_ACCESS))) {
            throw com.careflow.hms.common.ApiException.rule("INVALID_TEMPLATE", "Patients can only hold portal access.");
        }
        jdbc.update("DELETE FROM role_permissions WHERE organization_id = ? AND role_code = ?", orgId, role.name());
        for (String p : permissions) {
            jdbc.update("INSERT INTO role_permissions (organization_id, role_code, permission_code, granted_by) VALUES (?, ?, ?, ?)",
                    orgId, role.name(), p, changedBy);
        }
        evict(orgId);
    }

    public List<Map<String, Object>> catalogue() {
        return jdbc.queryForList("SELECT code, category, description FROM permissions ORDER BY category, code");
    }

    public void evict(UUID orgId) {
        cache.keySet().removeIf(k -> k.startsWith(orgId + ":"));
    }
}
