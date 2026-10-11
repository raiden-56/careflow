package com.careflow.hms.admin;

import com.careflow.hms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-tenant isolation: the administrator of the second demo organization (Sunrise, CFSUN) must not see
 * or change anything that belongs to the main demo hospital (CFDEMO), and vice versa. Foreign records are 404.
 */
class TenantIsolationIT extends IntegrationTestBase {

    private static final String SUNRISE = "admin@sunrise.careflow.local";
    private static final String DEMO = "admin@careflow.local";

    @Test
    void otherOrganizationsPatientsAndAdmissionsAreNotFound() throws Exception {
        UUID ravi = patientId("Ravi");
        UUID raviAdmission = openAdmissionId("Ravi");
        assertThat(status(SUNRISE, "GET", "/api/patients/" + ravi)).isEqualTo(404);
        assertThat(status(SUNRISE, "GET", "/api/patients/" + ravi + "/admissions")).isEqualTo(404);
        assertThat(status(SUNRISE, "GET", "/api/admissions/" + raviAdmission)).isEqualTo(404);
        assertThat(status(SUNRISE, "GET", "/api/admissions/" + raviAdmission + "/care-team")).isEqualTo(404);

        // Lists are scoped to the caller's organization.
        JsonNode list = readJson(call(SUNRISE, "GET", "/api/patients?q=Ravi", null));
        assertThat(list.get("totalElements").asLong()).isZero();
        JsonNode admissions = readJson(call(SUNRISE, "GET", "/api/admissions?size=100", null));
        admissions.get("content").forEach(a -> assertThat(a.get("id").asText()).isNotEqualTo(raviAdmission.toString()));

        // The reverse direction holds too.
        UUID kamala = patientId("Kamala");
        assertThat(status(DEMO, "GET", "/api/patients/" + kamala)).isEqualTo(404);
        assertThat(status(SUNRISE, "GET", "/api/patients/" + kamala)).isEqualTo(200);
    }

    @Test
    void otherOrganizationsBedsAndWardsAreNotFound() throws Exception {
        UUID demoBed = availableBed("MWA");
        UUID demoWard = jdbc.queryForObject("SELECT id FROM wards WHERE code = 'MWA'", UUID.class);
        assertThat(status(SUNRISE, "GET", "/api/beds/" + demoBed + "/history")).isEqualTo(404);
        assertThat(call(SUNRISE, "PATCH", "/api/beds/" + demoBed + "/status",
                Map.of("status", "MAINTENANCE", "reason", "cross-tenant attempt")).getResponse().getStatus()).isEqualTo(404);
        assertThat(status(SUNRISE, "GET", "/api/wards/" + demoWard + "/rooms")).isEqualTo(404);
        assertThat(status(SUNRISE, "GET", "/api/admin/wards/" + demoWard + "/beds")).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT status FROM beds WHERE id = ?", String.class, demoBed)).isEqualTo("AVAILABLE");

        JsonNode wards = readJson(call(SUNRISE, "GET", "/api/wards", null));
        assertThat(wards.toString()).doesNotContain(demoWard.toString()).contains("SUN-GW");
        JsonNode board = readJson(call(SUNRISE, "GET", "/api/beds/board", null));
        assertThat(board.toString()).doesNotContain(demoBed.toString());
    }

    @Test
    void otherOrganizationsStaffIsNotFoundAndCannotBeChanged() throws Exception {
        UUID mehta = userId("dr.mehta@careflow.local");
        assertThat(status(SUNRISE, "GET", "/api/staff/" + mehta)).isEqualTo(404);
        assertThat(call(SUNRISE, "POST", "/api/staff/" + mehta + "/status",
                Map.of("status", "DISABLED", "reason", "cross-tenant attempt")).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, mehta)).isEqualTo("ACTIVE");
        JsonNode staff = readJson(call(SUNRISE, "GET", "/api/staff?size=100", null));
        assertThat(staff.toString()).doesNotContain("dr.mehta@careflow.local").contains("admin@sunrise.careflow.local");
    }

    @Test
    void auditLogIncidentsAndPrivacyRecordsAreOrganizationScoped() throws Exception {
        JsonNode audit = readJson(call(SUNRISE, "GET", "/api/audit?size=100", null));
        audit.get("content").forEach(e -> assertThat(e.get("actorName").asText("")).isNotEqualTo("Dr. Arjun Mehta"));
        UUID demoAuditEvent = jdbc.queryForObject("SELECT id FROM audit_events WHERE organization_id = "
                + "(SELECT id FROM organizations WHERE code = 'CFDEMO') LIMIT 1", UUID.class);
        assertThat(status(SUNRISE, "GET", "/api/audit/" + demoAuditEvent)).isEqualTo(404);

        UUID demoIncident = jdbc.queryForObject("SELECT id FROM incident_reports WHERE organization_id = "
                + "(SELECT id FROM organizations WHERE code = 'CFDEMO') LIMIT 1", UUID.class);
        assertThat(status(SUNRISE, "GET", "/api/incidents/" + demoIncident)).isEqualTo(404);
        assertThat(readJson(call(SUNRISE, "GET", "/api/incidents", null)).get("totalElements").asLong()).isZero();

        assertThat(readJson(call(SUNRISE, "GET", "/api/patient-requests", null)).get("totalElements").asLong()).isZero();
        UUID demoConsent = jdbc.queryForObject("SELECT id FROM consent_records LIMIT 1", UUID.class);
        assertThat(call(SUNRISE, "POST", "/api/consents/" + demoConsent + "/withdraw", Map.of("reason", "cross-tenant attempt"))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void dashboardsOnlyCountOwnOrganization() throws Exception {
        JsonNode sunrise = readJson(call(SUNRISE, "GET", "/api/dashboard/admin", null));
        JsonNode demo = readJson(call(DEMO, "GET", "/api/dashboard/admin", null));
        assertThat(sunrise.toString()).doesNotContain("Medical Ward A");
        assertThat(demo.toString()).contains("Medical Ward A").doesNotContain("Sunrise General Ward");
    }

    private int status(String email, String method, String url) throws Exception {
        return call(email, method, url, null).getResponse().getStatus();
    }
}
