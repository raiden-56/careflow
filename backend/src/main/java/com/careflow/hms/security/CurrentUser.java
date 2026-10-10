package com.careflow.hms.security;

import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticated principal stored in the Spring Security context.
 *
 * @param patientId the patient record linked to this login (PATIENT role only), else null
 */
public record CurrentUser(
        UUID userId,
        UUID organizationId,
        Role role,
        String fullName,
        String email,
        Set<String> permissions,
        UUID patientId,
        UUID sessionId,
        ZoneId zone) {

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    public boolean is(Role r) {
        return role == r;
    }
}
