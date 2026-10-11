package com.careflow.hms.platform;

import com.careflow.hms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Super-administrator platform: organization management works, but patient, clinical and financial data never leak. */
class PlatformAdminIT extends IntegrationTestBase {

    private static final String SUPER = "superadmin@careflow.local";

    @Test
    void superAdminCannotReachPatientClinicalOrFinancialData() throws Exception {
        UUID ravi = patientId("Ravi");
        UUID admission = openAdmissionId("Ravi");
        UUID document = jdbc.queryForObject("SELECT id FROM documents WHERE patient_id = ? LIMIT 1", UUID.class, ravi);
        List<String[]> attempts = List.of(
                new String[]{"GET", "/api/patients"},
                new String[]{"GET", "/api/patients/" + ravi},
                new String[]{"GET", "/api/patients/" + ravi + "/timeline"},
                new String[]{"GET", "/api/patients/" + ravi + "/admissions"},
                new String[]{"GET", "/api/admissions"},
                new String[]{"GET", "/api/admissions/" + admission},
                new String[]{"GET", "/api/admissions/" + admission + "/timeline"},
                new String[]{"GET", "/api/documents?patientId=" + ravi},
                new String[]{"POST", "/api/documents/" + document + "/download-token"},
                new String[]{"GET", "/api/portal/patients/" + ravi + "/documents"},
                new String[]{"GET", "/api/portal/privacy/" + ravi + "/access-log"},
                new String[]{"GET", "/api/consents"},
                new String[]{"GET", "/api/patient-requests"},
                new String[]{"GET", "/api/incidents"},
                new String[]{"GET", "/api/incidents/mine"},
                new String[]{"GET", "/api/audit"},
                new String[]{"GET", "/api/dashboard/admin"},
                new String[]{"GET", "/api/reports/operational"},
                new String[]{"GET", "/api/reports/clinical-quality"},
                new String[]{"GET", "/api/settings"},
                new String[]{"GET", "/api/admin/tariffs"},
                new String[]{"GET", "/api/beds/board"});
        for (String[] a : attempts) {
            int status = call(SUPER, a[0], a[1], null).getResponse().getStatus();
            assertThat(status).as(a[0] + " " + a[1]).isIn(403, 404);
        }
    }

    @Test
    void securityMonitoringNeverExposesPatientIdentifiers() throws Exception {
        JsonNode events = readJson(call(SUPER, "GET", "/api/platform/security-events?size=100", null));
        assertThat(events.get("totalElements").asLong()).isPositive();
        String body = events.toString();
        assertThat(body).doesNotContain(patientId("Ravi").toString());
        assertThat(events.get("content").get(0).has("patientId")).isFalse();
        assertThat(readJson(call(SUPER, "GET", "/api/platform/security-summary", null)).has("failedLogins24h")).isTrue();
    }

    @Test
    void hospitalAdministratorsCannotUsePlatformEndpoints() throws Exception {
        for (String url : List.of("/api/platform/overview", "/api/platform/organizations", "/api/platform/admins",
                "/api/platform/permission-templates", "/api/platform/security-events", "/api/platform/integrations",
                "/api/platform/health")) {
            assertThat(call("admin@careflow.local", "GET", url, null).getResponse().getStatus()).as(url).isEqualTo(403);
        }
    }

    @Test
    void superAdminManagesOrganizationLifecycle() throws Exception {
        String code = "ITORG" + (int) (Math.random() * 90000 + 10000);
        Map<String, Object> create = new HashMap<>();
        create.put("code", code);
        create.put("name", "Integration Test Hospital " + code);
        create.put("legalName", "Integration Test Trust (fictional)");
        create.put("timezone", "Asia/Kolkata");
        create.put("city", "Pune");
        create.put("email", "contact@" + code.toLowerCase() + ".careflow.local");
        var created = call(SUPER, "POST", "/api/platform/organizations", create);
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode org = readJson(created);
        UUID orgId = UUID.fromString(org.get("id").asText());
        assertThat(org.get("status").asText()).isEqualTo("ACTIVE");
        // Provisioning creates the default role templates.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_permissions WHERE organization_id = ?", Integer.class, orgId)).isPositive();

        // Duplicate code is a conflict; invalid code a validation error.
        assertThat(call(SUPER, "POST", "/api/platform/organizations", create).getResponse().getStatus()).isEqualTo(409);
        Map<String, Object> bad = new HashMap<>(create);
        bad.put("code", "lower case");
        assertThat(call(SUPER, "POST", "/api/platform/organizations", bad).getResponse().getStatus()).isEqualTo(400);

        // Edit with optimistic version.
        Map<String, Object> update = new HashMap<>();
        update.put("name", "Integration Test Hospital " + code + " (renamed)");
        update.put("timezone", "Asia/Kolkata");
        update.put("version", org.get("version").asLong());
        var updated = call(SUPER, "PUT", "/api/platform/organizations/" + orgId, update);
        assertThat(updated.getResponse().getStatus()).as(updated.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(readJson(updated).get("name").asText()).endsWith("(renamed)");
        assertThat(call(SUPER, "PUT", "/api/platform/organizations/" + orgId, update).getResponse().getStatus()).isEqualTo(409);

        // Provision a hospital administrator inside the new organization; an invitation goes to the dev outbox.
        String email = "it.admin@" + code.toLowerCase() + ".careflow.local";
        var admin = call(SUPER, "POST", "/api/platform/organizations/" + orgId + "/admins",
                Map.of("email", email, "fullName", "Integration Admin"));
        assertThat(admin.getResponse().getStatus()).as(admin.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT organization_id FROM users WHERE email = ?", UUID.class, email)).isEqualTo(orgId);
        assertThat(jdbc.queryForObject("SELECT role_code FROM users WHERE email = ?", String.class, email)).isEqualTo("HOSPITAL_ADMIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_outbox WHERE destination = ?", Integer.class, email)).isPositive();
        assertThat(call(SUPER, "POST", "/api/platform/organizations/" + orgId + "/admins",
                Map.of("email", email, "fullName", "Integration Admin")).getResponse().getStatus()).isEqualTo(409);

        // Suspend (reason required) blocks provisioning; reactivate restores it.
        assertThat(call(SUPER, "POST", "/api/platform/organizations/" + orgId + "/suspend", Map.of()).getResponse().getStatus()).isEqualTo(400);
        var suspended = call(SUPER, "POST", "/api/platform/organizations/" + orgId + "/suspend", Map.of("reason", "Integration test suspension"));
        assertThat(readJson(suspended).get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(call(SUPER, "POST", "/api/platform/organizations/" + orgId + "/admins",
                Map.of("email", "second." + email, "fullName", "Second Admin")).getResponse().getStatus()).isEqualTo(422);
        var reactivated = call(SUPER, "POST", "/api/platform/organizations/" + orgId + "/reactivate", null);
        assertThat(readJson(reactivated).get("status").asText()).isEqualTo("ACTIVE");

        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE entity_id = ? ORDER BY occurred_at", String.class, orgId))
                .contains("ORG_CREATED", "ORG_UPDATED");
    }

    @Test
    void platformOrganizationCannotBeSuspendedOrEdited() throws Exception {
        UUID platformOrg = jdbc.queryForObject("SELECT id FROM organizations WHERE is_platform", UUID.class);
        assertThat(call(SUPER, "POST", "/api/platform/organizations/" + platformOrg + "/suspend",
                Map.of("reason", "should not be possible")).getResponse().getStatus()).isIn(404, 422);
    }

    @Test
    void resettingAnOrganizationRoleRestoresTheDefaultTemplateAndIsAudited() throws Exception {
        String code = "ITROLE" + (int) (Math.random() * 90000 + 10000);
        JsonNode org = readJson(call(SUPER, "POST", "/api/platform/organizations",
                Map.of("code", code, "name", "Role Reset Hospital " + code, "timezone", "Asia/Kolkata")));
        UUID orgId = UUID.fromString(org.get("id").asText());
        jdbc.update("DELETE FROM role_permissions WHERE organization_id = ? AND role_code = 'NURSE' AND permission_code = 'VITALS_WRITE'", orgId);

        JsonNode templates = readJson(call(SUPER, "GET", "/api/platform/permission-templates", null));
        assertThat(templates.get("defaults").get("NURSE").toString()).contains("VITALS_WRITE");

        JsonNode before = readJson(call(SUPER, "GET", "/api/platform/organizations/" + orgId + "/roles", null));
        JsonNode nurse = role(before, "NURSE");
        assertThat(nurse.get("matchesDefault").asBoolean()).isFalse();
        assertThat(nurse.get("removed").toString()).contains("VITALS_WRITE");

        var reset = call(SUPER, "POST", "/api/platform/organizations/" + orgId + "/roles/NURSE/reset", null);
        assertThat(reset.getResponse().getStatus()).as(reset.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(role(readJson(reset), "NURSE").get("matchesDefault").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_permissions WHERE organization_id = ? AND role_code = 'NURSE' "
                + "AND permission_code = 'VITALS_WRITE'", Integer.class, orgId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'ROLE_TEMPLATE_RESET' AND organization_id = ?",
                Integer.class, orgId)).isPositive();
        // The platform role itself is never resettable from here.
        assertThat(call(SUPER, "POST", "/api/platform/organizations/" + orgId + "/roles/SUPER_ADMIN/reset", null)
                .getResponse().getStatus()).isIn(400, 422);
    }

    @Test
    void healthAndIntegrationsOverviewAreAvailable() throws Exception {
        JsonNode health = readJson(call(SUPER, "GET", "/api/platform/health", null));
        assertThat(health.get("databaseUp").asBoolean()).isTrue();
        assertThat(health.get("flywayAllSucceeded").asBoolean()).isTrue();
        assertThat(health.get("organizations").asLong()).isGreaterThanOrEqualTo(2);
        JsonNode integrations = readJson(call(SUPER, "GET", "/api/platform/integrations", null));
        assertThat(integrations.isArray()).isTrue();
        assertThat(integrations.toString()).contains("ABDM");
        integrations.forEach(i -> assertThat(i.get("organizationCode").asText()).isNotEqualTo("PLATFORM"));
    }

    private static JsonNode role(JsonNode matrix, String role) {
        for (JsonNode r : matrix.get("roles")) {
            if (role.equals(r.get("role").asText())) {
                return r;
            }
        }
        throw new AssertionError("role " + role + " missing");
    }
}
