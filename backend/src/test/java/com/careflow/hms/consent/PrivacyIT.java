package com.careflow.hms.consent;

import com.careflow.hms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Consent records, patient requests and the patient's "who accessed my record" view. */
class PrivacyIT extends IntegrationTestBase {

    private static final String ADMIN = "admin@careflow.local";
    private static final String RAVI = "ravi.sharma@demo.careflow.local";

    @Test
    void patientSeesOnlyOwnAccessEntriesInPlainLanguage() throws Exception {
        UUID ravi = patientId("Ravi");
        UUID lakshmi = patientId("Lakshmi");
        UUID org = jdbc.queryForObject("SELECT organization_id FROM patients WHERE id = ?", UUID.class, ravi);
        String marker = "Dr. Access Marker " + UUID.randomUUID().toString().substring(0, 6);
        String foreign = "Dr. Foreign Marker " + UUID.randomUUID().toString().substring(0, 6);
        insertAccess(org, marker, "DOCTOR", ravi, "SUCCESS");
        insertAccess(org, foreign, "DOCTOR", lakshmi, "SUCCESS");
        insertAccess(org, foreign + " denied", "NURSE", ravi, "DENIED");

        MvcResult r = call(RAVI, "GET", "/api/portal/privacy/" + ravi + "/access-log?size=50", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode log = readJson(r);
        String body = log.toString();
        assertThat(body).contains(marker).doesNotContain(foreign);
        JsonNode first = log.get("content").get(0);
        assertThat(first.has("staffName")).isTrue();
        assertThat(first.has("role")).isTrue();
        assertThat(first.has("activity")).isTrue();
        // No internal details reach the patient.
        assertThat(first.has("ipAddress")).isFalse();
        assertThat(first.has("summary")).isFalse();
        assertThat(first.has("details")).isFalse();
        assertThat(body).doesNotContain("10.0.0.99");
        log.get("content").forEach(e -> assertThat(e.get("role").asText()).isNotEqualTo("PATIENT"));

        // Another patient's access log does not exist for Ravi.
        assertThat(call(RAVI, "GET", "/api/portal/privacy/" + lakshmi + "/access-log", null).getResponse().getStatus()).isEqualTo(404);
        // Staff cannot use the portal endpoints.
        assertThat(call("nurse.priya@careflow.local", "GET", "/api/portal/privacy/" + ravi + "/access-log", null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void patientRequestLifecycle() throws Exception {
        UUID ravi = patientId("Ravi");
        MvcResult submitted = call(RAVI, "POST", "/api/portal/privacy/" + ravi + "/requests", Map.of(
                "requestType", "ACCESS_REVIEW", "subject", "Review of access to my record",
                "description", "Please confirm which teams have opened my record this month."));
        assertThat(submitted.getResponse().getStatus()).as(submitted.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode req = readJson(submitted);
        String id = req.get("id").asText();
        assertThat(req.get("status").asText()).isEqualTo("OPEN");
        assertThat(call(RAVI, "POST", "/api/portal/privacy/" + ravi + "/requests", Map.of(
                "requestType", "ACCESS_REVIEW", "subject", "x", "description", "short")).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(RAVI, "POST", "/api/portal/privacy/" + patientId("Lakshmi") + "/requests", Map.of(
                "requestType", "RECORD_COPY", "subject", "Copy of records", "description", "Please send a copy of the records."))
                .getResponse().getStatus()).isEqualTo(404);

        // Privacy desk (hospital admin) works the request; other staff cannot.
        assertThat(readJson(call(ADMIN, "GET", "/api/patient-requests?size=100", null)).toString()).contains(id);
        assertThat(call("nurse.priya@careflow.local", "GET", "/api/patient-requests", null).getResponse().getStatus()).isEqualTo(403);
        Map<String, Object> respond = new HashMap<>();
        respond.put("status", "COMPLETED");
        respond.put("response", "Access review completed; only your care team opened your record.");
        respond.put("version", req.get("version").asLong());
        MvcResult done = call(ADMIN, "POST", "/api/patient-requests/" + id + "/respond", respond);
        assertThat(done.getResponse().getStatus()).as(done.getResponse().getContentAsString()).isEqualTo(200);
        respond.put("version", readJson(done).get("version").asLong());
        assertThat(call(ADMIN, "POST", "/api/patient-requests/" + id + "/respond", respond).getResponse().getStatus()).isEqualTo(422);

        JsonNode mine = readJson(call(RAVI, "GET", "/api/portal/privacy/" + ravi + "/requests", null));
        boolean found = false;
        for (JsonNode n : mine) {
            if (id.equals(n.get("id").asText())) {
                found = true;
                assertThat(n.get("status").asText()).isEqualTo("COMPLETED");
            }
        }
        assertThat(found).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ? AND action = 'PATIENT_REQUEST_COMPLETED'",
                Integer.class, UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void consentsRecordedByStaffAndWithdrawnInThePortal() throws Exception {
        UUID ravi = patientId("Ravi");
        Map<String, Object> research = consent(ravi, "RESEARCH", null);
        MvcResult created = call(ADMIN, "POST", "/api/consents", research);
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        String researchId = readJson(created).get("id").asText();
        assertThat(readJson(created).get("portalWithdrawable").asBoolean()).isTrue();

        // Validation: sharing consents must state the recipient, expiry must follow the grant.
        assertThat(call(ADMIN, "POST", "/api/consents", consent(ravi, "DATA_SHARING", null)).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> badExpiry = consent(ravi, "RESEARCH", null);
        badExpiry.put("expiresAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        assertThat(call(ADMIN, "POST", "/api/consents", badExpiry).getResponse().getStatus()).isEqualTo(400);
        assertThat(call("billing@careflow.local", "POST", "/api/consents", research).getResponse().getStatus()).isEqualTo(403);

        String treatmentId = readJson(call(ADMIN, "POST", "/api/consents", consent(ravi, "TREATMENT", null))).get("id").asText();

        // The patient sees both, can withdraw research but must ask the care team about treatment consent.
        String portal = call(RAVI, "GET", "/api/portal/privacy/" + ravi + "/consents", null).getResponse().getContentAsString();
        assertThat(portal).contains(researchId).contains(treatmentId);
        assertThat(call(RAVI, "POST", "/api/portal/privacy/" + ravi + "/consents/" + treatmentId + "/withdraw",
                Map.of("reason", "Changed my mind")).getResponse().getStatus()).isEqualTo(422);
        MvcResult withdrawn = call(RAVI, "POST", "/api/portal/privacy/" + ravi + "/consents/" + researchId + "/withdraw",
                Map.of("reason", "I no longer wish to take part"));
        assertThat(withdrawn.getResponse().getStatus()).as(withdrawn.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(readJson(withdrawn).get("status").asText()).isEqualTo("WITHDRAWN");

        // Another patient's consent cannot be withdrawn through Ravi's portal.
        UUID other = jdbc.queryForObject("SELECT id FROM consent_records WHERE patient_id <> ? LIMIT 1", UUID.class, ravi);
        assertThat(call(RAVI, "POST", "/api/portal/privacy/" + ravi + "/consents/" + other + "/withdraw",
                Map.of("reason", "Not mine")).getResponse().getStatus()).isEqualTo(404);
    }

    private void insertAccess(UUID org, String actorName, String role, UUID patientId, String outcome) {
        jdbc.update("""
                INSERT INTO audit_events (organization_id, actor_name, actor_role, action, entity_type, entity_id, patient_id,
                                          outcome, summary, ip_address, occurred_at)
                VALUES (?, ?, ?, 'READ_CLINICAL', 'PATIENT', ?, ?, ?, 'internal summary', '10.0.0.99', now())
                """, org, actorName, role, patientId, patientId, outcome);
    }

    private static Map<String, Object> consent(UUID patientId, String type, String sharedWith) {
        Map<String, Object> m = new HashMap<>();
        m.put("patientId", patientId);
        m.put("consentType", type);
        m.put("purpose", "Integration test " + type.toLowerCase());
        m.put("sharedWith", sharedWith);
        m.put("captureMethod", "WRITTEN");
        m.put("decision", "GRANTED");
        return m;
    }
}
