package com.careflow.hms.org;

import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Hospital-configurable settings with documented defaults. */
@Service
@RequiredArgsConstructor
public class SettingsService {

    public record SettingDef(String key, String defaultValue, String description) {
    }

    public static final String MED_OVERDUE_GRACE_MINUTES = "medication.overdue.graceMinutes";
    public static final String MED_DUE_WINDOW_MINUTES = "medication.due.windowMinutes";
    public static final String MED_SCHEDULE_HORIZON_HOURS = "medication.schedule.horizonHours";
    public static final String DISCHARGE_REQUIRE_FINANCIAL_CLEARANCE = "discharge.requireFinancialClearance";
    public static final String DISCHARGE_ALLOW_FINANCIAL_EXCEPTION = "discharge.allowFinancialException";
    public static final String HANDOVER_REQUIRE_ACK = "handover.requireAcknowledgement";
    public static final String FOLLOWUP_REMINDER_HOURS = "followUp.reminderHoursBefore";
    public static final String FOLLOWUP_CHANNELS = "followUp.notificationChannels";
    public static final String ROOM_CHARGE_MODE = "billing.roomChargeMode";
    public static final String OPERATING_HOURS = "hospital.operatingHours";
    public static final String BED_TURNOVER_STATUS = "bed.turnoverStatusAfterDischarge";
    public static final String VITALS_EXPECTED_INTERVAL_HOURS = "observations.expectedIntervalHours";
    public static final String NURSE_MAY_HOLD_DOSE = "medication.nurseMayHoldDose";
    public static final String BILLING_OVERDUE_DAYS = "billing.overdueAfterDays";

    public static final List<SettingDef> DEFINITIONS = List.of(
            new SettingDef(MED_OVERDUE_GRACE_MINUTES, "30", "Minutes after the scheduled time before a dose is flagged overdue"),
            new SettingDef(MED_DUE_WINDOW_MINUTES, "60", "Minutes before the scheduled time when a dose appears as due"),
            new SettingDef(MED_SCHEDULE_HORIZON_HOURS, "48", "How far ahead scheduled doses are generated from active orders"),
            new SettingDef(DISCHARGE_REQUIRE_FINANCIAL_CLEARANCE, "true", "Discharge completion requires financial clearance or an authorized exception"),
            new SettingDef(DISCHARGE_ALLOW_FINANCIAL_EXCEPTION, "true", "Billing staff may record an authorized financial exception for an unpaid balance"),
            new SettingDef(HANDOVER_REQUIRE_ACK, "true", "Shift handovers must be acknowledged by the receiving nurse"),
            new SettingDef(FOLLOWUP_REMINDER_HOURS, "24", "Hours before a follow-up appointment when the patient is reminded"),
            new SettingDef(FOLLOWUP_CHANNELS, "IN_APP,EMAIL,SMS", "Channels used for follow-up notifications (development adapters unless configured)"),
            new SettingDef(ROOM_CHARGE_MODE, "CALENDAR_DAY", "Room charging: CALENDAR_DAY charges each occupied calendar day except the discharge day"),
            new SettingDef(OPERATING_HOURS, "IPD 24x7; OPD Mon-Sat 09:00-17:00; Billing desk 08:00-20:00", "Hospital operating hours shown to staff and patients"),
            new SettingDef(BED_TURNOVER_STATUS, "CLEANING", "Bed status applied when a patient vacates a bed (CLEANING or MAINTENANCE)"),
            new SettingDef(VITALS_EXPECTED_INTERVAL_HOURS, "6", "Expected maximum interval between vital-sign recordings (missing-data indicator)"),
            new SettingDef(NURSE_MAY_HOLD_DOSE, "true", "Nurses may record a HELD dose with a documented clinical reason (does not change the prescription)"),
            new SettingDef(BILLING_OVERDUE_DAYS, "30", "Days after discharge from which an unpaid balance is flagged overdue"));

    private static final Map<String, SettingDef> BY_KEY = new LinkedHashMap<>();

    static {
        DEFINITIONS.forEach(d -> BY_KEY.put(d.key(), d));
    }

    private final JdbcTemplate jdbc;

    public String get(String key) {
        return get(TenantContext.requireOrganizationId(), key);
    }

    public String get(UUID orgId, String key) {
        List<String> v = jdbc.queryForList(
                "SELECT setting_value FROM organization_settings WHERE organization_id = ? AND setting_key = ?",
                String.class, orgId, key);
        if (!v.isEmpty()) {
            return v.get(0);
        }
        SettingDef def = BY_KEY.get(key);
        return def == null ? null : def.defaultValue();
    }

    public int getInt(String key) {
        return Integer.parseInt(get(key).trim());
    }

    public int getInt(UUID orgId, String key) {
        return Integer.parseInt(get(orgId, key).trim());
    }

    public boolean getBool(String key) {
        return Boolean.parseBoolean(get(key).trim());
    }

    public Map<String, String> all(UUID orgId) {
        Map<String, String> result = new LinkedHashMap<>();
        DEFINITIONS.forEach(d -> result.put(d.key(), d.defaultValue()));
        jdbc.query("SELECT setting_key, setting_value FROM organization_settings WHERE organization_id = ?",
                rs -> {
                    result.put(rs.getString(1), rs.getString(2));
                }, orgId);
        return result;
    }

    @Transactional
    public void set(UUID orgId, String key, String value, UUID updatedBy) {
        if (!BY_KEY.containsKey(key)) {
            throw com.careflow.hms.common.ApiException.badRequest("UNKNOWN_SETTING", "Unknown setting: " + key);
        }
        jdbc.update("""
                INSERT INTO organization_settings (organization_id, setting_key, setting_value, description, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (organization_id, setting_key)
                DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_by = EXCLUDED.updated_by, updated_at = now()
                """, orgId, key, value, BY_KEY.get(key).description(), updatedBy);
    }
}
