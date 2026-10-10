package com.careflow.hms.seed;

import com.careflow.hms.org.Organization;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.RolePermissionService;
import com.careflow.hms.user.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Shared state for demo seeding. Holds the ids created by the core seeder (keyed by stable
 * demo keys) and lets contributors act as a specific demo user so that real services - with
 * their validation, ledger, timeline and audit side effects - are used to create data.
 *
 * <p>Keys created by the core seeder:
 * <ul>
 *   <li>users: superadmin, admin, dr_mehta, dr_iyer, dr_khan, dr_rao_paeds, nurse_priya, nurse_rahul,
 *       nurse_fatima, nurse_anita, billing, pharmacist, and portal users p_ravi, p_lakshmi, p_vikram,
 *       cg_sunita (caregiver of Ravi), cg_kiran (parent of Aarav)</li>
 *   <li>patients: ravi, lakshmi, irfan, ananya, george, deepa, aarav, fatima, rohan, neha, vikram, sanjay</li>
 *   <li>admissions (open): ravi, irfan, ananya, george, deepa (DISCHARGE_IN_PROGRESS), aarav, vikram,
 *       fatima (PENDING_BED); (discharged): lakshmi, rohan, sanjay</li>
 *   <li>departments: GEN_MED, CARD, SURG, ORTHO, PAED, CRIT; wards: MWA, CCU, SWB, ICU, PVT, PAE</li>
 *   <li>tariffs: by code (e.g. ROOM_GENERAL, LAB_CBC, CONS_DAILY)</li>
 *   <li>medications: catalogue by code (MED-...); ACTIVE medication orders exist for the open admissions
 *       (no MAR rows, dispensations or stock yet). deepa is ADMITTED (the discharge contributor starts her workflow).</li>
 * </ul>
 */
public class DemoContext {

    private final Organization organization;
    private final RolePermissionService rolePermissions;
    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, UUID> patients = new LinkedHashMap<>();
    public final Map<String, UUID> admissions = new LinkedHashMap<>();
    public final Map<String, UUID> departments = new LinkedHashMap<>();
    public final Map<String, UUID> wards = new LinkedHashMap<>();
    public final Map<String, UUID> tariffs = new LinkedHashMap<>();
    /** Medication catalogue ids by code (e.g. MED-CEFTRX1G); active prescriptions are seeded for open admissions. */
    public final Map<String, UUID> medications = new LinkedHashMap<>();
    public final List<String> log = new ArrayList<>();

    public DemoContext(Organization organization, RolePermissionService rolePermissions) {
        this.organization = organization;
        this.rolePermissions = rolePermissions;
    }

    public Organization organization() {
        return organization;
    }

    public UUID orgId() {
        return organization.getId();
    }

    public User user(String key) {
        User u = users.get(key);
        if (u == null) {
            throw new IllegalArgumentException("Unknown demo user " + key);
        }
        return u;
    }

    public UUID patient(String key) {
        return require(patients, key, "patient");
    }

    public UUID admission(String key) {
        return require(admissions, key, "admission");
    }

    public UUID tariff(String code) {
        return require(tariffs, code, "tariff");
    }

    /** Runs work authenticated as the given demo user (full permission set of their role). */
    public <T> T actAs(String userKey, Supplier<T> work) {
        User u = user(userKey);
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        var perms = rolePermissions.permissionsFor(u.getOrganizationId(), u.getRole());
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()));
        perms.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        UUID patientId = null;
        for (Map.Entry<String, UUID> e : patients.entrySet()) {
            if (("p_" + e.getKey()).equals(userKey)) {
                patientId = e.getValue();
            }
        }
        CurrentUser cu = new CurrentUser(u.getId(), u.getOrganizationId(), u.getRole(), u.getFullName(), u.getEmail(),
                perms, patientId, UUID.randomUUID(), ZoneId.of(organization.getTimezone()));
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(cu, null, authorities));
        SecurityContextHolder.setContext(ctx);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    public void actAs(String userKey, Runnable work) {
        actAs(userKey, () -> {
            work.run();
            return null;
        });
    }

    /** Convenience: an instant relative to now (negative = past). */
    public static Instant hoursAgo(double hours) {
        return Instant.now().minusSeconds((long) (hours * 3600));
    }

    public static Instant daysAgo(double days) {
        return hoursAgo(days * 24);
    }

    private static UUID require(Map<String, UUID> map, String key, String what) {
        UUID id = map.get(key);
        if (id == null) {
            throw new IllegalArgumentException("Unknown demo " + what + " " + key);
        }
        return id;
    }
}
