package com.careflow.hms.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Finds the active users of an organization whose role currently holds a permission. */
@Component
@RequiredArgsConstructor
public class PermissionDirectory {

    private final JdbcTemplate jdbc;

    public List<UUID> activeUsersWith(UUID orgId, String permission) {
        return jdbc.queryForList("""
                SELECT u.id FROM users u
                JOIN role_permissions rp ON rp.organization_id = u.organization_id AND rp.role_code = u.role_code
                WHERE u.organization_id = ? AND rp.permission_code = ? AND u.status = 'ACTIVE'
                """, UUID.class, orgId, permission);
    }

    public boolean userHas(UUID orgId, UUID userId, String permission) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM users u
                JOIN role_permissions rp ON rp.organization_id = u.organization_id AND rp.role_code = u.role_code
                WHERE u.organization_id = ? AND u.id = ? AND rp.permission_code = ? AND u.status = 'ACTIVE'
                """, Integer.class, orgId, userId, permission);
        return n != null && n > 0;
    }
}
