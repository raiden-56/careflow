package com.careflow.hms.admin;

import com.careflow.hms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Hospital configuration: role matrix, tariffs, settings and integration boundaries. */
class ConfigurationIT extends IntegrationTestBase {

    private static final String ADMIN = "admin@careflow.local";

    // ------------------------------------------------------------------ role permissions

    @Test
    void rolePermissionEditsTakeEffectImmediatelyAndAreAudited() throws Exception {
        Set<String> original = rolePermissions("PHARMACIST");
        assertThat(original).contains("INCIDENT_REPORT");
        Map<String, Object> report = incidentBody();
        try {
            Set<String> reduced = new TreeSet<>(original);
            reduced.remove("INCIDENT_REPORT");
            MvcResult r = call(ADMIN, "PUT", "/api/roles/PHARMACIST/permissions", Map.of("permissions", reduced));
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(rolePermissions("PHARMACIST")).doesNotContain("INCIDENT_REPORT");

            // The pharmacist's existing session is refused straight away.
            assertThat(call("pharmacy@careflow.local", "POST", "/api/incidents", report).getResponse().getStatus()).isEqualTo(403);

            String summary = jdbc.queryForObject("""
                    SELECT summary FROM audit_events WHERE action = 'ROLE_PERMISSIONS_CHANGED' AND actor_user_id = ?
                    ORDER BY occurred_at DESC LIMIT 1""", String.class, userId(ADMIN));
            assertThat(summary).contains("PHARMACIST").contains("INCIDENT_REPORT");
        } finally {
            call(ADMIN, "PUT", "/api/roles/PHARMACIST/permissions", Map.of("permissions", original));
        }
        assertThat(rolePermissions("PHARMACIST")).contains("INCIDENT_REPORT");
        assertThat(call("pharmacy@careflow.local", "POST", "/api/incidents", report).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void lockOutProtectionAndPlatformPermissionsAreEnforced() throws Exception {
        Set<String> admin = rolePermissions("HOSPITAL_ADMIN");
        Set<String> withoutRoleManage = new TreeSet<>(admin);
        withoutRoleManage.remove("ROLE_MANAGE");
        MvcResult lockout = call(ADMIN, "PUT", "/api/roles/HOSPITAL_ADMIN/permissions", Map.of("permissions", withoutRoleManage));
        assertThat(lockout.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(lockout).get("code").asText()).isEqualTo("LOCKOUT_PREVENTION");
        assertThat(rolePermissions("HOSPITAL_ADMIN")).contains("ROLE_MANAGE");

        Set<String> escalated = new TreeSet<>(rolePermissions("NURSE"));
        escalated.add("ORG_MANAGE");
        assertThat(call(ADMIN, "PUT", "/api/roles/NURSE/permissions", Map.of("permissions", escalated))
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(call("dr.mehta@careflow.local", "PUT", "/api/roles/NURSE/permissions", Map.of("permissions", escalated))
                .getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ tariffs

    @Test
    void tariffValidationUniqueCodesAndPriceChangeAudit() throws Exception {
        String code = "IT_TARIFF_" + (int) (Math.random() * 90000 + 10000);
        Map<String, Object> body = tariff(code, "1200.00", "5.00");
        MvcResult created = call(ADMIN, "POST", "/api/admin/tariffs", body);
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode t = readJson(created);
        UUID id = UUID.fromString(t.get("id").asText());

        assertThat(call(ADMIN, "POST", "/api/admin/tariffs", body).getResponse().getStatus()).isEqualTo(409);
        assertThat(call(ADMIN, "POST", "/api/admin/tariffs", tariff(code + "N", "-1.00", "5.00")).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(ADMIN, "POST", "/api/admin/tariffs", tariff(code + "T", "10.00", "100.50")).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(ADMIN, "POST", "/api/admin/tariffs", tariff("bad code", "10.00", "5.00")).getResponse().getStatus()).isEqualTo(400);
        assertThat(call("billing@careflow.local", "POST", "/api/admin/tariffs", tariff(code + "B", "10.00", "5.00"))
                .getResponse().getStatus()).isEqualTo(403);

        Map<String, Object> update = tariff(code, "1350.00", "12.00");
        update.put("version", t.get("version").asLong());
        MvcResult updated = call(ADMIN, "PUT", "/api/admin/tariffs/" + id, update);
        assertThat(updated.getResponse().getStatus()).as(updated.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(readJson(updated).get("unitPrice").asText()).isEqualTo("1350.00");

        Map<String, Object> details = jdbc.queryForMap("""
                SELECT details ->> 'oldUnitPrice' AS old_price, details ->> 'newUnitPrice' AS new_price,
                       details ->> 'oldTaxRate' AS old_tax, details ->> 'newTaxRate' AS new_tax
                FROM audit_events WHERE action = 'TARIFF_PRICE_CHANGED' AND entity_id = ?""", id);
        assertThat(details.get("old_price")).isEqualTo("1200.00");
        assertThat(details.get("new_price")).isEqualTo("1350.00");
        assertThat(details.get("old_tax")).isEqualTo("5.00");
        assertThat(details.get("new_tax")).isEqualTo("12.00");

        // Deactivate instead of delete; the row remains and is excluded from the active list.
        MvcResult deactivated = call(ADMIN, "POST", "/api/admin/tariffs/" + id + "/deactivate", Map.of("reason", "Service discontinued"));
        assertThat(readJson(deactivated).get("active").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_tariffs WHERE id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(readJson(call(ADMIN, "GET", "/api/admin/tariffs?q=" + code, null)).get("totalElements").asLong()).isZero();
        assertThat(readJson(call(ADMIN, "GET", "/api/admin/tariffs?includeInactive=true&q=" + code, null)).get("totalElements").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'TARIFF_DEACTIVATED' AND entity_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ settings

    @Test
    void settingsAreValidatedPerKeyAndChangesAudited() throws Exception {
        String key = "followUp.reminderHoursBefore";
        MvcResult invalid = putSettings(ADMIN, Map.of(
                "medication.overdue.graceMinutes", "abc", "handover.requireAcknowledgement", "maybe",
                "no.such.setting", "1", key, "36"));
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        JsonNode errors = readJson(invalid).get("fieldErrors");
        List<String> fields = new ArrayList<>();
        errors.forEach(e -> fields.add(e.get("field").asText()));
        assertThat(fields).contains("medication.overdue.graceMinutes", "handover.requireAcknowledgement", "no.such.setting");
        // Nothing was saved from a partially invalid request.
        assertThat(setting(key)).isEqualTo("24");

        assertThat(putSettings(ADMIN, Map.of("medication.overdue.graceMinutes", "999")).getResponse().getStatus()).isEqualTo(400);
        assertThat(putSettings(ADMIN, Map.of("followUp.notificationChannels", "FAX")).getResponse().getStatus()).isEqualTo(400);
        assertThat(putSettings(ADMIN, Map.of()).getResponse().getStatus()).isEqualTo(400);
        assertThat(putSettings("nurse.priya@careflow.local", Map.of(key, "36")).getResponse().getStatus()).isEqualTo(403);

        Instant before = Instant.now();
        try {
            MvcResult ok = putSettings(ADMIN, Map.of(key, " 36 "));
            assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(setting(key)).isEqualTo("36");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM audit_events WHERE action = 'SETTINGS_UPDATED' AND occurred_at >= ?
                      AND details -> 'followUp.reminderHoursBefore' ->> 'new' = '36'""", Integer.class,
                    java.sql.Timestamp.from(before.minusSeconds(1)))).isEqualTo(1);
        } finally {
            putSettings(ADMIN, Map.of(key, "24"));
        }
    }

    // ------------------------------------------------------------------ integrations

    @Test
    void integrationsNeverStoreSecretsAndLiveNeedsConfirmation() throws Exception {
        JsonNode sms = integration("SMS");
        assertThat(sms.get("liveImplemented").asBoolean()).isFalse();
        String url = "/api/admin/integrations/SMS";

        Map<String, Object> live = new HashMap<>();
        live.put("mode", "LIVE");
        live.put("endpointUrl", "https://sms-gateway.example.org/v1");
        live.put("secretName", "SMS_GATEWAY_API_KEY");
        live.put("confirmLive", false);
        live.put("version", sms.get("version").asLong());
        MvcResult unconfirmed = call(ADMIN, "PUT", url, live);
        assertThat(unconfirmed.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(unconfirmed).get("code").asText()).isEqualTo("LIVE_CONFIRMATION_REQUIRED");

        Map<String, Object> secretInNotes = new HashMap<>(live);
        secretInNotes.put("confirmLive", true);
        secretInNotes.put("notes", "api_key=abcd1234");
        assertThat(call(ADMIN, "PUT", url, secretInNotes).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> rawSecret = new HashMap<>(live);
        rawSecret.put("confirmLive", true);
        rawSecret.put("secretName", "s3cr3t-value!");
        assertThat(call(ADMIN, "PUT", url, rawSecret).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> plainHttp = new HashMap<>(live);
        plainHttp.put("confirmLive", true);
        plainHttp.put("endpointUrl", "http://sms-gateway.example.org/v1");
        assertThat(call(ADMIN, "PUT", url, plainHttp).getResponse().getStatus()).isEqualTo(400);

        live.put("confirmLive", true);
        MvcResult confirmed = call(ADMIN, "PUT", url, live);
        assertThat(confirmed.getResponse().getStatus()).as(confirmed.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode updated = readJson(confirmed);
        assertThat(updated.get("mode").asText()).isEqualTo("LIVE");
        assertThat(updated.get("liveImplemented").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_type = 'INTEGRATION' AND entity_id = ? AND summary ILIKE '%LIVE%'",
                Integer.class, UUID.fromString(sms.get("id").asText()))).isPositive();
        // Only the secret's name is stored.
        assertThat(jdbc.queryForObject("SELECT settings::text FROM integration_configs WHERE id = ?", String.class,
                UUID.fromString(sms.get("id").asText()))).contains("SMS_GATEWAY_API_KEY");

        Map<String, Object> revert = new HashMap<>();
        revert.put("mode", "DEV_ADAPTER");
        revert.put("version", updated.get("version").asLong());
        assertThat(call(ADMIN, "PUT", url, revert).getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ helpers

    private Set<String> rolePermissions(String role) throws Exception {
        JsonNode m = readJson(call(ADMIN, "GET", "/api/roles", null));
        for (JsonNode r : m.get("roles")) {
            if (role.equals(r.get("role").asText())) {
                Set<String> s = new TreeSet<>();
                r.get("permissions").forEach(p -> s.add(p.asText()));
                return s;
            }
        }
        throw new AssertionError("role " + role + " missing");
    }

    private JsonNode integration(String type) throws Exception {
        for (JsonNode i : readJson(call(ADMIN, "GET", "/api/admin/integrations", null))) {
            if (type.equals(i.get("integrationType").asText())) {
                return i;
            }
        }
        throw new AssertionError("integration " + type + " missing");
    }

    private MvcResult putSettings(String email, Map<String, String> values) throws Exception {
        return call(email, "PUT", "/api/settings", Map.of("values", values));
    }

    private String setting(String key) throws Exception {
        for (JsonNode s : readJson(call(ADMIN, "GET", "/api/settings", null))) {
            if (key.equals(s.get("key").asText())) {
                return s.get("value").asText();
            }
        }
        throw new AssertionError("setting " + key + " missing");
    }

    private static Map<String, Object> tariff(String code, String price, String tax) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("name", "Integration test service");
        m.put("category", "PROCEDURE");
        m.put("unitPrice", price);
        m.put("taxRate", tax);
        m.put("billingUnit", "PER_UNIT");
        return m;
    }

    private static Map<String, Object> incidentBody() {
        Map<String, Object> m = new HashMap<>();
        m.put("incidentType", "OTHER");
        m.put("severity", "NO_HARM");
        m.put("occurredAt", Instant.now().minusSeconds(3600).toString());
        m.put("location", "Pharmacy store");
        m.put("description", "Integration test: shelf label mismatch found during stock check.");
        return m;
    }
}
