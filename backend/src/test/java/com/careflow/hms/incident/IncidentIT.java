package com.careflow.hms.incident;

import com.careflow.hms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Safety incident reporting: reporter visibility, reviewer workflow, audit, timeline and notifications. */
class IncidentIT extends IntegrationTestBase {

    private static final String PRIYA = "nurse.priya@careflow.local";
    private static final String RAHUL = "nurse.rahul@careflow.local";
    private static final String ADMIN = "admin@careflow.local";

    @Test
    void reporterSeesOwnReviewerSeesAllOthersAreDenied() throws Exception {
        MvcResult created = call(PRIYA, "POST", "/api/incidents", body("FALL", "MINOR", null));
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode incident = readJson(created);
        String id = incident.get("id").asText();
        assertThat(incident.get("incidentNumber").asText()).isNotBlank();
        assertThat(incident.get("status").asText()).isEqualTo("REPORTED");

        // Reporter: own list and detail.
        assertThat(readJson(call(PRIYA, "GET", "/api/incidents/mine?size=100", null)).toString()).contains(id);
        assertThat(call(PRIYA, "GET", "/api/incidents/" + id, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(PRIYA, "GET", "/api/incidents", null).getResponse().getStatus()).isEqualTo(403);

        // Another nurse cannot see it (404, and the attempt is audited) nor list everything.
        assertThat(readJson(call(RAHUL, "GET", "/api/incidents/mine?size=100", null)).toString()).doesNotContain(id);
        assertThat(call(RAHUL, "GET", "/api/incidents/" + id, null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(RAHUL, "GET", "/api/incidents/" + id + "/history", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'ACCESS_DENIED' AND entity_type = 'INCIDENT' "
                + "AND entity_id = ? AND actor_user_id = ?", Integer.class, UUID.fromString(id), userId(RAHUL))).isPositive();
        assertThat(call(RAHUL, "POST", "/api/incidents/" + id + "/transition",
                Map.of("status", "UNDER_REVIEW", "version", incident.get("version").asLong())).getResponse().getStatus()).isEqualTo(403);

        // Patients and the platform team cannot use incident endpoints at all.
        assertThat(call("ravi.sharma@demo.careflow.local", "GET", "/api/incidents/mine", null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("ravi.sharma@demo.careflow.local", "POST", "/api/incidents", body("FALL", "MINOR", null)).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("superadmin@careflow.local", "GET", "/api/incidents/" + id, null).getResponse().getStatus()).isEqualTo(403);

        // Reviewer (hospital admin) sees everything.
        assertThat(readJson(call(ADMIN, "GET", "/api/incidents?size=100", null)).toString()).contains(id);
        assertThat(readJson(call(ADMIN, "GET", "/api/incidents/" + id, null)).get("canReview").asBoolean()).isTrue();
    }

    @Test
    void reviewWorkflowEnforcesTransitionsAndRecordsHistory() throws Exception {
        JsonNode incident = readJson(call(PRIYA, "POST", "/api/incidents", body("EQUIPMENT", "NO_HARM", null)));
        String id = incident.get("id").asText();

        // Cannot jump straight to CLOSED.
        MvcResult skip = call(ADMIN, "POST", "/api/incidents/" + id + "/transition",
                Map.of("status", "CLOSED", "reviewNotes", "n/a", "correctiveActions", "n/a", "version", incident.get("version").asLong()));
        assertThat(skip.getResponse().getStatus()).isEqualTo(422);

        MvcResult assigned = call(ADMIN, "POST", "/api/incidents/" + id + "/assign", Map.of("reviewerId", userId(ADMIN)));
        assertThat(assigned.getResponse().getStatus()).as(assigned.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode a = readJson(assigned);
        assertThat(a.get("reviewerName").asText()).isNotBlank();

        JsonNode review = transition(id, "UNDER_REVIEW", null, null, a.get("version").asLong(), 200);
        // ACTION_REQUIRED needs corrective actions.
        transition(id, "ACTION_REQUIRED", "Pump alarm threshold misconfigured", null, review.get("version").asLong(), 400);
        JsonNode action = transition(id, "ACTION_REQUIRED", "Pump alarm threshold misconfigured",
                "Biomedical team to recalibrate all pumps on the ward", review.get("version").asLong(), 200);
        // Stale version is a conflict.
        transition(id, "CLOSED", "Done", "Recalibrated", review.get("version").asLong(), 409);
        JsonNode closed = transition(id, "CLOSED", "Recalibration verified", null, action.get("version").asLong(), 200);
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("closedAt").isNull()).isFalse();

        // The reporter sees the outcome; history shows each step.
        assertThat(readJson(call(PRIYA, "GET", "/api/incidents/" + id, null)).get("status").asText()).isEqualTo("CLOSED");
        JsonNode history = readJson(call(ADMIN, "GET", "/api/incidents/" + id + "/history", null));
        assertThat(history.size()).isGreaterThanOrEqualTo(4);
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE entity_id = ? ORDER BY occurred_at", String.class,
                UUID.fromString(id))).contains("INCIDENT_REPORTED");
    }

    @Test
    void patientLinkedSevereIncidentCreatesInternalTimelineEventAndNotifiesReviewers() throws Exception {
        UUID admission = openAdmissionId("Ravi");
        MvcResult created = call(PRIYA, "POST", "/api/incidents", body("MEDICATION_ERROR", "SEVERE", admission));
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        UUID id = UUID.fromString(readJson(created).get("id").asText());

        Map<String, Object> event = jdbc.queryForMap("SELECT category, visibility FROM timeline_events WHERE entity_id = ?", id);
        assertThat(event.get("category")).isEqualTo("SAFETY");
        assertThat(event.get("visibility")).isEqualTo("INTERNAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE entity_id = ? AND recipient_user_id = ?",
                Integer.class, id, userId(ADMIN))).isEqualTo(1);
        // The incident is not shown to the patient through the portal timeline.
        String portal = call("ravi.sharma@demo.careflow.local", "GET", "/api/patients/" + patientId("Ravi") + "/timeline?size=100", null)
                .getResponse().getContentAsString();
        assertThat(portal).doesNotContain(id.toString());

        // An admission from another organization cannot be linked.
        assertThat(call("admin@sunrise.careflow.local", "POST", "/api/incidents", body("FALL", "MINOR", admission))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void reportValidation() throws Exception {
        Map<String, Object> future = body("FALL", "MINOR", null);
        future.put("occurredAt", Instant.now().plusSeconds(3600).toString());
        assertThat(call(PRIYA, "POST", "/api/incidents", future).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> shortText = body("FALL", "MINOR", null);
        shortText.put("description", "short");
        assertThat(call(PRIYA, "POST", "/api/incidents", shortText).getResponse().getStatus()).isEqualTo(400);
    }

    private JsonNode transition(String id, String status, String notes, String actions, long version, int expected) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status);
        m.put("reviewNotes", notes);
        m.put("correctiveActions", actions);
        m.put("version", version);
        MvcResult r = call(ADMIN, "POST", "/api/incidents/" + id + "/transition", m);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        return readJson(r);
    }

    private static Map<String, Object> body(String type, String severity, UUID admissionId) {
        Map<String, Object> m = new HashMap<>();
        m.put("incidentType", type);
        m.put("severity", severity);
        m.put("occurredAt", Instant.now().minusSeconds(1800).toString());
        m.put("location", "Medical Ward A");
        m.put("admissionId", admissionId);
        m.put("description", "Integration test incident: details of what happened on the ward.");
        m.put("immediateActions", "Area made safe and senior nurse informed.");
        return m;
    }
}
