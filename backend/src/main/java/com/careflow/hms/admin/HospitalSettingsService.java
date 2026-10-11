package com.careflow.hms.admin;

import com.careflow.hms.admin.ConfigDtos.OrganizationProfile;
import com.careflow.hms.admin.ConfigDtos.OrganizationProfileRequest;
import com.careflow.hms.admin.ConfigDtos.SettingType;
import com.careflow.hms.admin.ConfigDtos.SettingView;
import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiError;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.org.SettingsService;
import com.careflow.hms.org.SettingsService.SettingDef;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Typed view and validation over {@link SettingsService#DEFINITIONS}. Unknown keys are validated as text. */
@Service
@RequiredArgsConstructor
public class HospitalSettingsService {

    record Meta(String label, SettingType type, Integer min, Integer max, List<String> options) {
    }

    private static final Map<String, Meta> META = Map.ofEntries(
            Map.entry(SettingsService.MED_OVERDUE_GRACE_MINUTES, new Meta("Overdue grace period (minutes)", SettingType.INTEGER, 0, 240, null)),
            Map.entry(SettingsService.MED_DUE_WINDOW_MINUTES, new Meta("Due window before scheduled time (minutes)", SettingType.INTEGER, 0, 240, null)),
            Map.entry(SettingsService.MED_SCHEDULE_HORIZON_HOURS, new Meta("Dose schedule horizon (hours)", SettingType.INTEGER, 12, 168, null)),
            Map.entry(SettingsService.DISCHARGE_REQUIRE_FINANCIAL_CLEARANCE, new Meta("Require financial clearance before discharge", SettingType.BOOLEAN, null, null, null)),
            Map.entry(SettingsService.DISCHARGE_ALLOW_FINANCIAL_EXCEPTION, new Meta("Allow authorized financial exceptions", SettingType.BOOLEAN, null, null, null)),
            Map.entry(SettingsService.HANDOVER_REQUIRE_ACK, new Meta("Handover requires acknowledgement", SettingType.BOOLEAN, null, null, null)),
            Map.entry(SettingsService.FOLLOWUP_REMINDER_HOURS, new Meta("Follow-up reminder lead time (hours)", SettingType.INTEGER, 1, 168, null)),
            Map.entry(SettingsService.FOLLOWUP_CHANNELS, new Meta("Follow-up notification channels", SettingType.MULTI_ENUM, null, null, List.of("IN_APP", "EMAIL", "SMS"))),
            Map.entry(SettingsService.ROOM_CHARGE_MODE, new Meta("Room charge mode", SettingType.ENUM, null, null, List.of("CALENDAR_DAY"))),
            Map.entry(SettingsService.OPERATING_HOURS, new Meta("Operating hours", SettingType.TEXT, 3, 500, null)),
            Map.entry(SettingsService.BED_TURNOVER_STATUS, new Meta("Bed status after a patient vacates", SettingType.ENUM, null, null, List.of("CLEANING", "MAINTENANCE"))),
            Map.entry(SettingsService.VITALS_EXPECTED_INTERVAL_HOURS, new Meta("Expected vital-signs interval (hours)", SettingType.INTEGER, 1, 24, null)),
            Map.entry(SettingsService.NURSE_MAY_HOLD_DOSE, new Meta("Nurses may hold a dose with a reason", SettingType.BOOLEAN, null, null, null)));

    private final SettingsService settings;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final OrganizationRepository organizations;

    @Transactional(readOnly = true)
    public List<SettingView> list() {
        UUID org = TenantContext.requireOrganizationId();
        Map<String, Object[]> stored = new HashMap<>();
        jdbc.query("""
                SELECT s.setting_key, s.setting_value, s.updated_at, u.full_name FROM organization_settings s
                LEFT JOIN users u ON u.id = s.updated_by WHERE s.organization_id = ?
                """, rs -> {
            stored.put(rs.getString(1), new Object[]{rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4)});
        }, org);
        List<SettingView> result = new ArrayList<>();
        for (SettingDef d : SettingsService.DEFINITIONS) {
            Meta m = meta(d.key());
            Object[] s = stored.get(d.key());
            result.add(new SettingView(d.key(), group(d.key()), m.label(), s == null ? d.defaultValue() : (String) s[0],
                    d.defaultValue(), d.description(), m.type(), m.min(), m.max(), m.options(), s != null,
                    s == null ? null : (Instant) s[1], s == null ? null : (String) s[2]));
        }
        return result;
    }

    @Transactional
    public List<SettingView> update(Map<String, String> values) {
        CurrentUser u = TenantContext.requireUser();
        Map<String, SettingDef> defs = new LinkedHashMap<>();
        SettingsService.DEFINITIONS.forEach(d -> defs.put(d.key(), d));
        List<ApiError.FieldError> errors = new ArrayList<>();
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!defs.containsKey(e.getKey())) {
                errors.add(new ApiError.FieldError(e.getKey(), "Unknown setting"));
                continue;
            }
            String error = validate(meta(e.getKey()), e.getValue());
            if (error != null) {
                errors.add(new ApiError.FieldError(e.getKey(), error));
            } else {
                normalized.put(e.getKey(), normalize(meta(e.getKey()), e.getValue()));
            }
        }
        if (!errors.isEmpty()) {
            throw new SettingsValidationException(errors);
        }
        Map<String, String> current = settings.all(u.organizationId());
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : normalized.entrySet()) {
            String old = current.get(e.getKey());
            if (!Objects.equals(old, e.getValue())) {
                settings.set(u.organizationId(), e.getKey(), e.getValue(), u.userId());
                changes.put(e.getKey(), Map.of("old", old == null ? "" : old, "new", e.getValue()));
            }
        }
        if (!changes.isEmpty()) {
            audit.record("SETTINGS_UPDATED", "SETTINGS", null, null, AuditEvent.Outcome.SUCCESS,
                    "Updated " + String.join(", ", changes.keySet()), changes);
        }
        return list();
    }

    /** Validation error with per-key field errors (rendered as 400 VALIDATION_FAILED). */
    public static class SettingsValidationException extends ApiException {
        private final List<ApiError.FieldError> fieldErrors;

        public SettingsValidationException(List<ApiError.FieldError> fieldErrors) {
            super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some settings are invalid: "
                    + String.join("; ", fieldErrors.stream().map(f -> f.field() + " " + f.message()).toList()));
            this.fieldErrors = fieldErrors;
        }

        public List<ApiError.FieldError> fieldErrors() {
            return fieldErrors;
        }
    }

    static String validate(Meta m, String raw) {
        if (raw == null) {
            return "A value is required";
        }
        String v = raw.trim();
        switch (m.type()) {
            case INTEGER -> {
                if (!v.matches("-?\\d{1,9}")) {
                    return "Must be a whole number";
                }
                long n = Long.parseLong(v);
                if (m.min() != null && n < m.min() || m.max() != null && n > m.max()) {
                    return "Must be between " + m.min() + " and " + m.max();
                }
            }
            case BOOLEAN -> {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    return "Must be true or false";
                }
            }
            case ENUM -> {
                if (!m.options().contains(v)) {
                    return "Must be one of " + String.join(", ", m.options());
                }
            }
            case MULTI_ENUM -> {
                List<String> parts = Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                if (parts.isEmpty()) {
                    return "Select at least one option";
                }
                for (String p : parts) {
                    if (!m.options().contains(p)) {
                        return "Unsupported option " + p;
                    }
                }
            }
            case TEXT -> {
                int min = m.min() == null ? 0 : m.min();
                int max = m.max() == null ? 2000 : m.max();
                if (v.length() < min || v.length() > max) {
                    return "Must be " + min + "-" + max + " characters";
                }
            }
        }
        return null;
    }

    static String normalize(Meta m, String raw) {
        String v = raw.trim();
        return switch (m.type()) {
            case BOOLEAN -> v.toLowerCase();
            case INTEGER -> String.valueOf(Long.parseLong(v));
            case MULTI_ENUM -> {
                Set<String> set = new LinkedHashSet<>();
                m.options().forEach(o -> {
                    if (Arrays.stream(v.split(",")).map(String::trim).anyMatch(o::equals)) {
                        set.add(o);
                    }
                });
                yield String.join(",", set);
            }
            default -> v;
        };
    }

    private static Meta meta(String key) {
        Meta m = META.get(key);
        if (m != null) {
            return m;
        }
        String label = key.substring(key.lastIndexOf('.') + 1).replaceAll("([A-Z])", " $1").toLowerCase();
        return new Meta(Character.toUpperCase(label.charAt(0)) + label.substring(1), SettingType.TEXT, 0, 2000, null);
    }

    private static String group(String key) {
        String prefix = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
        return switch (prefix) {
            case "medication" -> "Medication";
            case "discharge" -> "Discharge";
            case "handover", "observations" -> "Nursing";
            case "followUp" -> "Follow-up";
            case "billing" -> "Billing";
            case "hospital" -> "Hospital";
            case "bed" -> "Beds";
            default -> Character.toUpperCase(prefix.charAt(0)) + prefix.substring(1);
        };
    }

    // ------------------------------------------------------------------ organization profile

    @Transactional(readOnly = true)
    public OrganizationProfile profile() {
        return toProfile(requireOrg());
    }

    @Transactional
    public OrganizationProfile updateProfile(OrganizationProfileRequest r) {
        Organization o = requireOrg();
        if (!Objects.equals(o.getVersion(), r.version())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "The hospital profile was changed by someone else. Reload and try again.");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        track(changes, "name", o.getName(), r.name().trim());
        track(changes, "legalName", o.getLegalName(), blankToNull(r.legalName()));
        track(changes, "address", o.getAddress(), blankToNull(r.address()));
        track(changes, "city", o.getCity(), blankToNull(r.city()));
        track(changes, "state", o.getState(), blankToNull(r.state()));
        track(changes, "phone", o.getPhone(), blankToNull(r.phone()));
        track(changes, "email", o.getEmail(), blankToNull(r.email()));
        track(changes, "registrationNumber", o.getRegistrationNumber(), blankToNull(r.registrationNumber()));
        o.setName(r.name().trim());
        o.setLegalName(blankToNull(r.legalName()));
        o.setAddress(blankToNull(r.address()));
        o.setCity(blankToNull(r.city()));
        o.setState(blankToNull(r.state()));
        o.setPhone(blankToNull(r.phone()));
        o.setEmail(blankToNull(r.email()));
        o.setRegistrationNumber(blankToNull(r.registrationNumber()));
        organizations.saveAndFlush(o);
        if (!changes.isEmpty()) {
            audit.record("ORGANIZATION_PROFILE_UPDATED", "ORGANIZATION", o.getId(), null, AuditEvent.Outcome.SUCCESS,
                    "Hospital profile updated", changes);
        }
        return toProfile(o);
    }

    private Organization requireOrg() {
        return organizations.findById(TenantContext.requireOrganizationId()).orElseThrow(() -> ApiException.notFound("Organization"));
    }

    private static void track(Map<String, Object> changes, String field, String old, String now) {
        if (!Objects.equals(old, now)) {
            changes.put(field, Map.of("old", old == null ? "" : old, "new", now == null ? "" : now));
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static OrganizationProfile toProfile(Organization o) {
        return new OrganizationProfile(o.getId(), o.getCode(), o.getName(), o.getLegalName(), o.getTimezone(), o.getCurrency(),
                o.getAddress(), o.getCity(), o.getState(), o.getPhone(), o.getEmail(), o.getRegistrationNumber(), o.getVersion());
    }
}
