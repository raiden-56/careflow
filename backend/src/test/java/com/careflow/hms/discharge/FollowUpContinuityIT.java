package com.careflow.hms.discharge;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Workflow F (follow-up and continuity of care) and patient-portal isolation. */
class FollowUpContinuityIT extends DischargeTestSupport {

    @Autowired
    DischargeJobs jobs;

    private UUID lakshmiAdmission() {
        return jdbc.queryForObject("""
                SELECT a.id FROM admissions a JOIN patients p ON p.id = a.patient_id
                WHERE p.first_name = 'Lakshmi' AND a.status = 'DISCHARGED' ORDER BY a.admitted_at DESC LIMIT 1
                """, UUID.class);
    }

    private MvcResult createFollowUp(UUID admission, String title, Instant when) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "APPOINTMENT");
        body.put("title", title);
        body.put("description", "Bring your weight diary.");
        body.put("scheduledAt", when.toString());
        body.put("departmentId", departmentId("CARD"));
        body.put("clinicianId", userId(DR_IYER));
        body.put("location", "Cardiology OPD, Room 305");
        return call(DR_IYER, "POST", "/api/discharge/admissions/" + admission + "/follow-ups", body);
    }

    @Test
    void workflowF_followUpNotifiesPatientThroughConfiguredChannelsAndAppearsInPortal() throws Exception {
        UUID adm = lakshmiAdmission();
        UUID patient = patientId("Lakshmi");
        String title = "Echo review " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = createFollowUp(adm, title, Instant.now().plusSeconds(5 * 86400));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode fu = readJson(r);
        UUID id = UUID.fromString(fu.get("id").asText());
        assertThat(fu.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(fu.get("patientNotifiedAt").isNull()).isFalse();
        assertThat(fu.get("deliveries").findValuesAsText("channel")).contains("IN_APP", "EMAIL", "SMS");
        assertThat(fu.get("deliveries").findValuesAsText("status")).doesNotContain("FAILED");

        // Development outbox rows (email + SMS) rendered from the hospital's templates.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_outbox WHERE body LIKE ?", Integer.class, "%" + title + "%")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_outbox WHERE channel = 'SMS' AND body LIKE ? AND body LIKE '%CareFlow General Hospital%'",
                Integer.class, "%" + title + "%")).isEqualTo(1);
        // Delivery records on the patient's in-app notification and in the follow-up log.
        UUID notification = jdbc.queryForObject("""
                SELECT n.id FROM notifications n JOIN patients p ON p.user_id = n.recipient_user_id
                WHERE p.id = ? AND n.entity_id = ?
                """, UUID.class, patient, id);
        assertThat(jdbc.queryForList("SELECT channel FROM notification_deliveries WHERE notification_id = ?", String.class, notification))
                .contains("IN_APP", "EMAIL", "SMS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM follow_up_deliveries WHERE follow_up_id = ? AND status = 'SENT'", Integer.class, id)).isEqualTo(2);

        // The patient sees it in the portal (no internal outcome notes, result reviews hidden).
        JsonNode portal = readJson(call(LAKSHMI, "GET", "/api/portal/patients/" + patient + "/follow-ups", null));
        assertThat(portal.get("upcoming").findValuesAsText("title")).contains(title);
        assertThat(portal.findValuesAsText("type")).doesNotContain("RESULT_REVIEW");

        // Re-scheduling notifies again; completing records the outcome.
        Map<String, Object> edit = new HashMap<>();
        edit.put("type", "APPOINTMENT");
        edit.put("title", title);
        edit.put("scheduledAt", Instant.now().plusSeconds(6 * 86400).toString());
        edit.put("departmentId", departmentId("CARD"));
        edit.put("version", fu.get("version").asLong());
        MvcResult moved = call(DR_IYER, "PUT", "/api/discharge/follow-ups/" + id, edit);
        assertThat(moved.getResponse().getStatus()).as(moved.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(readJson(moved).get("deliveries").findValuesAsText("event")).contains("RESCHEDULED");
        MvcResult missedTooEarly = call(DR_IYER, "POST", "/api/discharge/follow-ups/" + id + "/outcome", Map.of("status", "MISSED", "outcomeNotes", "x"));
        assertThat(readJson(missedTooEarly).get("code").asText()).isEqualTo("NOT_YET_DUE");
        MvcResult cancelled = call(DR_IYER, "POST", "/api/discharge/follow-ups/" + id + "/outcome", Map.of("status", "CANCELLED", "outcomeNotes", "Merged with the heart failure review"));
        assertThat(readJson(cancelled).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(readJson(cancelled).get("deliveries").findValuesAsText("event")).contains("CANCELLED");
    }

    @Test
    void followUpChannelsFollowHospitalSettingAndRemindersAreSentOnce() throws Exception {
        UUID adm = lakshmiAdmission();
        UUID org = orgId();
        jdbc.update("INSERT INTO organization_settings (organization_id, setting_key, setting_value) VALUES (?, 'followUp.notificationChannels', 'IN_APP,EMAIL')", org);
        UUID id;
        try {
            String title = "Lipid clinic " + UUID.randomUUID().toString().substring(0, 8);
            MvcResult r = createFollowUp(adm, title, Instant.now().plusSeconds(2 * 3600));
            assertThat(r.getResponse().getStatus()).isEqualTo(201);
            JsonNode fu = readJson(r);
            id = UUID.fromString(fu.get("id").asText());
            assertThat(fu.get("deliveries").findValuesAsText("channel")).containsOnly("IN_APP", "EMAIL");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_outbox WHERE body LIKE ? AND channel = 'SMS'", Integer.class, "%" + title + "%")).isZero();
        } finally {
            jdbc.update("DELETE FROM organization_settings WHERE organization_id = ? AND setting_key = 'followUp.notificationChannels'", org);
        }
        jobs.sendReminders(org);
        jobs.sendReminders(org);
        assertThat(jdbc.queryForObject("SELECT reminder_sent_at IS NOT NULL FROM follow_ups WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM follow_up_deliveries WHERE follow_up_id = ? AND event = 'REMINDER' AND channel = 'IN_APP'",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void patientCannotReadAnotherPatientsDischargeInformation() throws Exception {
        UUID lakshmi = patientId("Lakshmi");
        UUID adm = lakshmiAdmission();
        assertThat(call(RAVI, "GET", "/api/portal/patients/" + lakshmi + "/discharge", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(RAVI, "GET", "/api/portal/patients/" + lakshmi + "/follow-ups", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(RAVI, "POST", "/api/portal/patients/" + lakshmi + "/discharge/acknowledge", Map.of("confirmed", true))
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(call(RAVI, "GET", "/api/discharge/summaries/" + adm + "/summary.pdf", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(RAVI, "GET", "/api/discharge/summaries/" + adm, null).getResponse().getStatus()).isEqualTo(403);
        // A doctor outside the care team cannot open a closed admission's discharge record.
        assertThat(call(DR_KHAN, "GET", "/api/discharge/admissions/" + adm, null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void patientSeesReleasedSummaryAndAcknowledgesInstructionsOnce() throws Exception {
        UUID lakshmi = patientId("Lakshmi");
        JsonNode d = readJson(call(LAKSHMI, "GET", "/api/portal/patients/" + lakshmi + "/discharge", null)).get("discharge");
        assertThat(d.get("summary").get("warningSigns").asText()).contains("Weight gain");
        assertThat(d.get("medications").size()).isGreaterThan(0);
        assertThat(d.toString()).doesNotContain("clinicalNotes").doesNotContain("blockerReason");
        assertThat(d.get("steps").findValuesAsText("title")).doesNotContain("Bed moved into cleaning / turnover");
        MvcResult pdf = call(LAKSHMI, "GET", "/api/discharge/summaries/" + d.get("admissionId").asText() + "/summary.pdf", null);
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);

        if (!d.get("acknowledgement").get("acknowledged").asBoolean()) {
            assertThat(call(LAKSHMI, "POST", "/api/portal/patients/" + lakshmi + "/discharge/acknowledge", Map.of("confirmed", false))
                    .getResponse().getStatus()).isEqualTo(400);
            MvcResult ack = call(LAKSHMI, "POST", "/api/portal/patients/" + lakshmi + "/discharge/acknowledge",
                    Map.of("confirmed", true, "comment", "Understood, thank you"));
            assertThat(ack.getResponse().getStatus()).as(ack.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(readJson(ack).get("discharge").get("acknowledgement").get("acknowledged").asBoolean()).isTrue();
        }
        MvcResult again = call(LAKSHMI, "POST", "/api/portal/patients/" + lakshmi + "/discharge/acknowledge", Map.of("confirmed", true));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
    }
}
