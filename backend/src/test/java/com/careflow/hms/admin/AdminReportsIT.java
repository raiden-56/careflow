package com.careflow.hms.admin;

import com.careflow.hms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Admin dashboard, operational reports and exports, audit log search, and clinical-data boundaries for administrators. */
class AdminReportsIT extends IntegrationTestBase {

    private static final String ADMIN = "admin@careflow.local";

    @Test
    void dashboardSeparatesCollectionsFromReceivablesAndRespectsFinancialPermission() throws Exception {
        MvcResult r = call(ADMIN, "GET", "/api/dashboard/admin", null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode d = readJson(r);
        assertThat(d.get("financial").isNull()).isFalse();
        assertThat(d.get("financial").toString()).containsIgnoringCase("collection").containsIgnoringCase("receivable");
        assertThat(d.get("wards").size()).isPositive();

        // Without REPORT_OPERATIONAL there is no dashboard at all.
        assertThat(call("nurse.priya@careflow.local", "GET", "/api/dashboard/admin", null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("billing@careflow.local", "GET", "/api/dashboard/admin", null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void operationalReportHandlesFiltersEmptyRangesAndExports() throws Exception {
        JsonNode report = readJson(call(ADMIN, "GET", "/api/reports/operational", null));
        assertThat(report.get("definitions").size()).isPositive();
        assertThat(report.get("daily").size()).isPositive();

        // A range in the distant past has no data but still renders (no division by zero).
        MvcResult empty = call(ADMIN, "GET", "/api/reports/operational?from=2001-01-01&to=2001-01-31", null);
        assertThat(empty.getResponse().getStatus()).as(empty.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode summary = readJson(empty).get("summary");
        assertThat(summary.get("admissions").asLong()).isZero();
        assertThat(summary.get("discharges").asLong()).isZero();

        UUID ward = jdbc.queryForObject("SELECT id FROM wards WHERE code = 'MWA'", UUID.class);
        MvcResult byWard = call(ADMIN, "GET", "/api/reports/operational?wardId=" + ward, null);
        assertThat(byWard.getResponse().getStatus()).isEqualTo(200);
        assertThat(readJson(byWard).get("filter").get("wardName").asText()).isEqualTo("Medical Ward A");
        assertThat(call(ADMIN, "GET", "/api/reports/operational?wardId=" + UUID.randomUUID(), null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(ADMIN, "GET", "/api/reports/operational?from=2026-02-10&to=2026-01-01", null).getResponse().getStatus()).isEqualTo(400);

        MvcResult csv = call(ADMIN, "GET", "/api/reports/operational/export?format=csv", null);
        assertThat(csv.getResponse().getStatus()).isEqualTo(200);
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getContentAsString()).contains("Operational report");
        MvcResult pdf = call(ADMIN, "GET", "/api/reports/operational/export?format=pdf", null);
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");
        assertThat(call(ADMIN, "GET", "/api/reports/operational/export?format=xlsx", null).getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'REPORT_EXPORTED'", Integer.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void clinicalQualitySummaryRequiresClinicalReportingPermission() throws Exception {
        assertThat(call(ADMIN, "GET", "/api/reports/clinical-quality", null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(ADMIN, "GET", "/api/reports/clinical-quality/export?format=csv", null).getResponse().getStatus()).isEqualTo(403);
        MvcResult doctor = call("dr.mehta@careflow.local", "GET", "/api/reports/clinical-quality", null);
        assertThat(doctor.getResponse().getStatus()).as(doctor.getResponse().getContentAsString()).isEqualTo(200);
    }

    @Test
    void administratorCannotReadClinicalContentByDefault() throws Exception {
        UUID ravi = patientId("Ravi");
        UUID admission = openAdmissionId("Ravi");
        assertThat(call(ADMIN, "GET", "/api/documents?patientId=" + ravi, null).getResponse().getStatus()).isEqualTo(403);
        UUID document = jdbc.queryForObject("SELECT id FROM documents WHERE patient_id = ? LIMIT 1", UUID.class, ravi);
        assertThat(call(ADMIN, "POST", "/api/documents/" + document + "/download-token", null).getResponse().getStatus()).isEqualTo(403);
        JsonNode detail = readJson(call(ADMIN, "GET", "/api/admissions/" + admission, null));
        assertThat(detail.get("clinicalAccess").asBoolean()).isFalse();
        String timeline = call(ADMIN, "GET", "/api/admissions/" + admission + "/timeline?size=100", null).getResponse().getContentAsString();
        assertThat(timeline).doesNotContain("\"category\":\"CLINICAL\"");
    }

    @Test
    void auditLogSearchDetailAndExportAreOrganizationScoped() throws Exception {
        JsonNode denied = readJson(call(ADMIN, "GET", "/api/audit?outcome=DENIED&size=50", null));
        assertThat(denied.get("totalElements").asLong()).isPositive();
        denied.get("content").forEach(e -> assertThat(e.get("outcome").asText()).isEqualTo("DENIED"));

        JsonNode byActor = readJson(call(ADMIN, "GET", "/api/audit?actor=Mehta&size=50", null));
        byActor.get("content").forEach(e -> assertThat(e.get("actorName").asText()).containsIgnoringCase("mehta"));

        String mrn = jdbc.queryForObject("SELECT mrn FROM patients WHERE first_name = 'Ravi'", String.class);
        JsonNode byPatient = readJson(call(ADMIN, "GET", "/api/audit?patientMrn=" + mrn + "&size=50", null));
        assertThat(byPatient.get("totalElements").asLong()).isPositive();
        byPatient.get("content").forEach(e -> assertThat(e.get("patientMrn").asText()).isEqualTo(mrn));

        JsonNode recent = readJson(call(ADMIN, "GET", "/api/audit?from=" + LocalDate.now().minusDays(30) + "&to=" + LocalDate.now() + "&size=5", null));
        String id = recent.get("content").get(0).get("id").asText();
        assertThat(call(ADMIN, "GET", "/api/audit/" + id, null).getResponse().getStatus()).isEqualTo(200);

        MvcResult csv = call(ADMIN, "GET", "/api/audit/export?outcome=DENIED", null);
        assertThat(csv.getResponse().getStatus()).isEqualTo(200);
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");

        assertThat(call("dr.mehta@careflow.local", "GET", "/api/audit", null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("superadmin@careflow.local", "GET", "/api/audit", null).getResponse().getStatus()).isEqualTo(403);
    }
}
