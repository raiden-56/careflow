package com.careflow.hms.clinical;

import com.careflow.hms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clinical module workflows: nurse medication administration and observations (workflow B), doctor
 * review / documentation / plan revision / results / expected discharge (workflow C), allergy checking,
 * role and ward boundaries, threshold alerts, MAR exception rules, release permissions and portal filtering.
 */
class ClinicalWorkflowIT extends IntegrationTestBase {

    private static final String ADMIN = "admin@careflow.local";
    private static final String DOCTOR = "dr.mehta@careflow.local";
    private static final String NURSE = "nurse.priya@careflow.local";
    private static final String OTHER_NURSE = "nurse.anita@careflow.local";
    private static final String BILLING = "billing@careflow.local";

    /** Admits a fresh patient to Medical Ward A under Dr. Mehta (nurse Priya's ward). */
    private UUID admitToMedicalWard(String first, String last) throws Exception {
        UUID patient = registerPatient(first, last);
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patient);
        body.put("admissionType", "EMERGENCY");
        body.put("admissionReason", "Clinical integration test admission");
        body.put("departmentId", departmentId("GEN_MED"));
        body.put("attendingDoctorId", userId(DOCTOR));
        body.put("requiresIsolation", false);
        body.put("bedId", availableBed("MWA"));
        MvcResult r = call(ADMIN, "POST", "/api/admissions", body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(readJson(r).get("summary").get("id").asText());
    }

    private UUID medication(String code) {
        return jdbc.queryForObject("SELECT id FROM medications WHERE code = ?", UUID.class, code);
    }

    private UUID tariff(String code) {
        return jdbc.queryForObject("SELECT id FROM service_tariffs WHERE code = ?", UUID.class, code);
    }

    private Map<String, Object> order(String medCode, String dose, String unit, String route, String frequency) {
        Map<String, Object> o = new HashMap<>();
        o.put("medicationId", medication(medCode));
        o.put("dose", dose);
        o.put("doseUnit", unit);
        o.put("route", route);
        o.put("frequency", frequency);
        o.put("indication", "Integration test");
        o.put("dischargeMedication", false);
        return o;
    }

    private JsonNode prescribe(UUID admissionId, Map<String, Object> order) throws Exception {
        MvcResult r = call(DOCTOR, "POST", "/api/admissions/" + admissionId + "/medication-orders", order);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return readJson(r);
    }

    private JsonNode scheduledDose(UUID admissionId, String orderId) throws Exception {
        JsonNode mar = readJson(call(NURSE, "GET", "/api/admissions/" + admissionId + "/mar", null));
        for (JsonNode d : mar) {
            if (d.get("orderId").asText().equals(orderId) && d.get("status").asText().equals("SCHEDULED")) {
                return d;
            }
        }
        throw new AssertionError("No scheduled dose for order " + orderId + ": " + mar);
    }

    private List<String> timelineTypes(UUID admissionId, String email) throws Exception {
        return readJson(call(email, "GET", "/api/admissions/" + admissionId + "/timeline?size=200", null))
                .get("content").findValuesAsText("eventType");
    }

    // ------------------------------------------------------------------ workflow B

    @Test
    void workflowB_nurseAdministersRecordsExceptionAndVitals_eventsOnTimeline() throws Exception {
        UUID admission = admitToMedicalWard("Bela", "Nursingflow");
        String stat = prescribe(admission, order("MED-PCM500", "1000", "mg", "ORAL", "STAT")).get("id").asText();
        String stat2 = prescribe(admission, order("MED-ONDAN4", "4", "mg", "IV", "STAT")).get("id").asText();

        // Nurse opens the assigned patient and reviews the active orders.
        assertThat(call(NURSE, "GET", "/api/admissions/" + admission + "/clinical-summary", null).getResponse().getStatus()).isEqualTo(200);
        JsonNode orders = readJson(call(NURSE, "GET", "/api/admissions/" + admission + "/medication-orders", null));
        assertThat(orders.findValuesAsText("id")).contains(stat, stat2);

        // The STAT dose appears in the nurse's medication round.
        JsonNode rounds = readJson(call(NURSE, "GET", "/api/mar/rounds?hours=4", null));
        assertThat(rounds.findValuesAsText("orderId")).contains(stat);

        JsonNode dose = scheduledDose(admission, stat);
        MvcResult given = call(NURSE, "POST", "/api/mar/" + dose.get("id").asText() + "/administer",
                Map.of("doseGiven", "1000 mg", "notes", "Given with water", "version", dose.get("version").asLong()));
        assertThat(given.getResponse().getStatus()).as(given.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(readJson(given).get("status").asText()).isEqualTo("ADMINISTERED");
        // Recording the same dose twice is rejected.
        assertThat(call(NURSE, "POST", "/api/mar/" + dose.get("id").asText() + "/administer", Map.of()).getResponse().getStatus()).isEqualTo(409);

        // An exception without a reason is rejected; with a reason it is recorded and queued for follow-up.
        JsonNode dose2 = scheduledDose(admission, stat2);
        MvcResult noReason = call(NURSE, "POST", "/api/mar/" + dose2.get("id").asText() + "/exception", Map.of("status", "REFUSED", "reason", ""));
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        MvcResult refused = call(NURSE, "POST", "/api/mar/" + dose2.get("id").asText() + "/exception",
                Map.of("status", "REFUSED", "reason", "Patient declined - nausea settled"));
        assertThat(refused.getResponse().getStatus()).as(refused.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(readJson(refused).get("followUpStatus").asText()).isEqualTo("OPEN");
        JsonNode queue = readJson(call(NURSE, "GET", "/api/mar/exceptions", null));
        assertThat(queue.get("content").findValuesAsText("id")).contains(dose2.get("id").asText());
        // The prescription itself is unchanged by the exception.
        assertThat(jdbc.queryForObject("SELECT status FROM medication_orders WHERE id = ?::uuid", String.class, stat2)).isEqualTo("ACTIVE");

        // Vital signs.
        MvcResult vitals = call(NURSE, "POST", "/api/admissions/" + admission + "/vitals", Map.of(
                "temperatureC", 37.2, "pulseBpm", 84, "respiratoryRate", 16, "systolicBp", 126, "diastolicBp", 78, "spo2", 98,
                "onOxygen", false, "painScore", 2));
        assertThat(vitals.getResponse().getStatus()).as(vitals.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(readJson(vitals).get("alertsRaised")).isEmpty();
        // Out-of-range values are rejected by validation.
        assertThat(call(NURSE, "POST", "/api/admissions/" + admission + "/vitals", Map.of("spo2", 140, "onOxygen", false))
                .getResponse().getStatus()).isEqualTo(400);

        assertThat(timelineTypes(admission, NURSE)).contains("MEDICATION_ADMINISTERED", "MEDICATION_REFUSED", "VITALS_RECORDED",
                "MEDICATION_PRESCRIBED");
    }

    // ------------------------------------------------------------------ workflow C

    @Test
    void workflowC_doctorDocumentsRevisesPlanReviewsResultsAndSetsDischargeDate() throws Exception {
        UUID admission = admitToMedicalWard("Chandra", "Doctorflow");
        UUID patient = jdbc.queryForObject("SELECT patient_id FROM admissions WHERE id = ?", UUID.class, admission);

        // Reviews history and allergies.
        assertThat(call(DOCTOR, "GET", "/api/patients/" + patient + "/allergies", null).getResponse().getStatus()).isEqualTo(200);
        MvcResult hist = call(DOCTOR, "POST", "/api/patients/" + patient + "/medical-history",
                Map.of("category", "MEDICAL", "conditionName", "Hypothyroidism", "details", "On levothyroxine"));
        assertThat(hist.getResponse().getStatus()).isEqualTo(201);
        assertThat(readJson(call(DOCTOR, "GET", "/api/patients/" + patient + "/medical-history", null)).findValuesAsText("conditionName"))
                .contains("Hypothyroidism");

        // Progress note.
        MvcResult note = call(DOCTOR, "POST", "/api/admissions/" + admission + "/notes", Map.of(
                "noteType", "PROGRESS", "title", "Day 1 review", "assessment", "Improving", "content", "Stable overnight."));
        assertThat(note.getResponse().getStatus()).as(note.getResponse().getContentAsString()).isEqualTo(201);
        String noteId = readJson(note).get("id").asText();
        MvcResult amend = call(DOCTOR, "POST", "/api/notes/" + noteId + "/amend", Map.of(
                "title", "Day 1 review", "content", "Stable overnight; tolerating diet.", "reason", "Added dietary status"));
        assertThat(amend.getResponse().getStatus()).isEqualTo(201);
        assertThat(readJson(amend).get("amendsNoteId").asText()).isEqualTo(noteId);
        assertThat(jdbc.queryForObject("SELECT status FROM clinical_notes WHERE id = ?::uuid", String.class, noteId)).isEqualTo("AMENDED");

        // Treatment plan: create, revise (supersedes), stale revision rejected.
        MvcResult p1 = call(DOCTOR, "POST", "/api/admissions/" + admission + "/treatment-plans",
                Map.of("goals", "Stabilise", "planDetails", "IV fluids and observation"));
        assertThat(p1.getResponse().getStatus()).as(p1.getResponse().getContentAsString()).isEqualTo(201);
        String plan1 = readJson(p1).get("id").asText();
        MvcResult p2 = call(DOCTOR, "POST", "/api/admissions/" + admission + "/treatment-plans",
                Map.of("goals", "Prepare for discharge", "planDetails", "Oral therapy", "revisionReason", "Clinically improved", "basedOnPlanId", plan1));
        assertThat(p2.getResponse().getStatus()).as(p2.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(readJson(p2).get("planVersion").asInt()).isEqualTo(2);
        MvcResult stale = call(DOCTOR, "POST", "/api/admissions/" + admission + "/treatment-plans",
                Map.of("goals", "x", "planDetails", "y", "revisionReason", "stale edit", "basedOnPlanId", plan1));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        JsonNode plans = readJson(call(DOCTOR, "GET", "/api/admissions/" + admission + "/treatment-plans", null));
        assertThat(plans.findValuesAsText("status")).containsExactly("ACTIVE", "SUPERSEDED");

        // Investigation: order (charge posted) -> nurse collects -> result -> doctor reviews.
        MvcResult ordered = call(DOCTOR, "POST", "/api/admissions/" + admission + "/investigations",
                Map.of("tariffId", tariff("LAB_CBC"), "priority", "URGENT", "clinicalInfo", "Baseline"));
        assertThat(ordered.getResponse().getStatus()).as(ordered.getResponse().getContentAsString()).isEqualTo(201);
        String labId = readJson(ordered).get("id").asText();
        assertThat(readJson(ordered).get("chargePosted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM care_tasks WHERE source_id = ?::uuid AND task_type = 'LAB_COLLECTION'", Integer.class, labId))
                .isEqualTo(1);
        assertThat(call(NURSE, "POST", "/api/investigations/" + labId + "/collect", Map.of()).getResponse().getStatus()).isEqualTo(200);
        MvcResult resulted = call(DOCTOR, "POST", "/api/investigations/" + labId + "/results", Map.of("components", List.of(
                Map.of("component", "Haemoglobin", "valueText", "12.9", "valueNumeric", 12.9, "unit", "g/dL", "referenceRange", "13-17", "flag", "LOW"))));
        assertThat(resulted.getResponse().getStatus()).as(resulted.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode toReview = readJson(call(DOCTOR, "GET", "/api/investigations/worklist?view=AWAITING_REVIEW&size=100", null));
        assertThat(toReview.get("content").findValuesAsText("id")).contains(labId);
        MvcResult reviewed = call(DOCTOR, "POST", "/api/investigations/" + labId + "/review", Map.of("comment", "Mild anaemia; recheck"));
        assertThat(readJson(reviewed).get("status").asText()).isEqualTo("REVIEWED");

        // Expected discharge date.
        String edd = LocalDate.now().plusDays(2).toString();
        MvcResult setEdd = call(DOCTOR, "PUT", "/api/admissions/" + admission + "/expected-discharge", Map.of("expectedDischargeDate", edd));
        assertThat(setEdd.getResponse().getStatus()).isEqualTo(200);
        assertThat(readJson(setEdd).get("summary").get("expectedDischargeDate").asText()).isEqualTo(edd);

        assertThat(timelineTypes(admission, DOCTOR)).contains("NOTE_PROGRESS", "NOTE_AMENDED", "TREATMENT_PLAN_REVISED",
                "INVESTIGATION_ORDERED", "SPECIMEN_COLLECTED", "RESULT_AVAILABLE", "RESULT_REVIEWED", "EXPECTED_DISCHARGE_UPDATED");
    }

    @Test
    void cancellingInvestigationBeforeCollectionReversesTheCharge() throws Exception {
        UUID admission = admitToMedicalWard("Divya", "Cancelflow");
        String labId = readJson(call(DOCTOR, "POST", "/api/admissions/" + admission + "/investigations",
                Map.of("tariffId", tariff("LAB_CRP")))).get("id").asText();
        MvcResult cancelled = call(DOCTOR, "POST", "/api/investigations/" + labId + "/cancel", Map.of("reason", "Ordered on wrong patient"));
        assertThat(cancelled.getResponse().getStatus()).as(cancelled.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM invoice_line_items WHERE source_id = ?::uuid AND source_type = 'LAB_ORDER'",
                String.class, labId)).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT status FROM care_tasks WHERE source_id = ?::uuid", String.class, labId)).isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------ allergy checking

    @Test
    void allergyConflictIsRejectedUnlessOverrideIsDocumented() throws Exception {
        UUID admission = admitToMedicalWard("Esha", "Allergyflow");
        UUID patient = jdbc.queryForObject("SELECT patient_id FROM admissions WHERE id = ?", UUID.class, admission);
        MvcResult allergy = call(NURSE, "POST", "/api/patients/" + patient + "/allergies",
                Map.of("allergen", "Penicillin", "allergyType", "DRUG", "reaction", "Rash", "severity", "MODERATE"));
        assertThat(allergy.getResponse().getStatus()).isEqualTo(201);

        Map<String, Object> amoxiclav = order("MED-AMXCLV625", "625", "mg", "ORAL", "TDS");
        MvcResult conflict = call(DOCTOR, "POST", "/api/admissions/" + admission + "/medication-orders", amoxiclav);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(conflict).get("code").asText()).isEqualTo("ALLERGY_CONFLICT");

        // The search annotates the conflict for the prescriber.
        JsonNode options = readJson(call(DOCTOR, "GET", "/api/prescribing/medications?q=amoxicillin&admissionId=" + admission, null));
        assertThat(options.get(0).get("allergyConflicts").get(0).asText()).contains("Penicillin");

        amoxiclav.put("allergyOverrideReason", "Tolerated amoxicillin in 2024 under observation; benefits outweigh risk");
        MvcResult overridden = call(DOCTOR, "POST", "/api/admissions/" + admission + "/medication-orders", amoxiclav);
        assertThat(overridden.getResponse().getStatus()).as(overridden.getResponse().getContentAsString()).isEqualTo(201);
        String orderId = readJson(overridden).get("id").asText();
        assertThat(readJson(overridden).get("allergyOverrideReason").asText()).startsWith("Tolerated amoxicillin");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinical_alerts WHERE source_id = ?::uuid AND alert_type = 'ALLERGY'",
                Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ?::uuid AND action = 'ALLERGY_OVERRIDE'",
                Integer.class, orderId)).isEqualTo(1);
        // MAR rows were generated for the scheduled order.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM medication_administrations WHERE order_id = ?::uuid AND status = 'SCHEDULED'",
                Integer.class, orderId)).isGreaterThan(0);
    }

    // ------------------------------------------------------------------ role & ward boundaries

    @Test
    void nurseCannotPrescribeOrModifyOrders() throws Exception {
        UUID admission = admitToMedicalWard("Farah", "Rolecheck");
        String orderId = prescribe(admission, order("MED-PANTO40", "40", "mg", "ORAL", "OD")).get("id").asText();
        assertThat(call(NURSE, "POST", "/api/admissions/" + admission + "/medication-orders", order("MED-PCM500", "500", "mg", "ORAL", "PRN"))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(call(NURSE, "POST", "/api/medication-orders/" + orderId + "/modify",
                Map.of("order", order("MED-PANTO40", "20", "mg", "ORAL", "OD"), "reason", "dose change")).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(NURSE, "POST", "/api/medication-orders/" + orderId + "/hold", Map.of("reason", "test")).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(NURSE, "POST", "/api/medication-orders/" + orderId + "/discontinue", Map.of("reason", "test")).getResponse().getStatus()).isEqualTo(403);
        // The doctor can modify: the original is discontinued and the replacement references it.
        MvcResult modified = call(DOCTOR, "POST", "/api/medication-orders/" + orderId + "/modify",
                Map.of("order", order("MED-PANTO40", "20", "mg", "ORAL", "OD"), "reason", "Dose reduced"));
        assertThat(modified.getResponse().getStatus()).as(modified.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(readJson(modified).get("replacesOrderId").asText()).isEqualTo(orderId);
        assertThat(jdbc.queryForObject("SELECT status FROM medication_orders WHERE id = ?::uuid", String.class, orderId)).isEqualTo("DISCONTINUED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM medication_administrations WHERE order_id = ?::uuid AND status = 'SCHEDULED'",
                Integer.class, orderId)).isZero();
    }

    @Test
    void billingUserCannotWriteClinicalNotes() throws Exception {
        UUID ravi = openAdmissionId("Ravi");
        MvcResult r = call(BILLING, "POST", "/api/admissions/" + ravi + "/notes",
                Map.of("noteType", "PROGRESS", "title", "Not allowed", "content", "Billing should not write this"));
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(call(BILLING, "GET", "/api/admissions/" + ravi + "/clinical-summary", null).getResponse().getStatus()).isEqualTo(403);
        // A nurse cannot write a doctor-only note type either.
        MvcResult nurseDoctorNote = call(NURSE, "POST", "/api/admissions/" + ravi + "/notes",
                Map.of("noteType", "PROGRESS", "title", "Progress", "content", "Doctor note type"));
        assertThat(nurseDoctorNote.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void nurseOutsideTheWardIsDenied() throws Exception {
        UUID ravi = openAdmissionId("Ravi");
        assertThat(call(OTHER_NURSE, "GET", "/api/admissions/" + ravi + "/clinical-summary", null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(OTHER_NURSE, "POST", "/api/admissions/" + ravi + "/vitals", Map.of("pulseBpm", 80, "onOxygen", false))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(call(OTHER_NURSE, "GET", "/api/admissions/" + ravi + "/mar", null).getResponse().getStatus()).isEqualTo(403);
        Integer denials = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'ACCESS_DENIED' AND entity_id = ?",
                Integer.class, ravi);
        assertThat(denials).isGreaterThan(0);
        // The ward nurse is allowed.
        assertThat(call(NURSE, "GET", "/api/admissions/" + ravi + "/clinical-summary", null).getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ thresholds

    @Test
    void thresholdBreachCreatesAlertAndNotifiesTheAttendingDoctor() throws Exception {
        UUID admission = admitToMedicalWard("Gita", "Thresholdflow");
        MvcResult r = call(NURSE, "POST", "/api/admissions/" + admission + "/vitals",
                Map.of("spo2", 85, "pulseBpm", 98, "onOxygen", false, "recordedAt", Instant.now().minusSeconds(60).toString()));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode raised = readJson(r).get("alertsRaised");
        assertThat(raised).hasSize(1);
        JsonNode alert = raised.get(0);
        assertThat(alert.get("alertType").asText()).isEqualTo("VITAL_THRESHOLD");
        assertThat(alert.get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(alert.get("parameter").asText()).isEqualTo("SPO2");
        assertThat(alert.get("thresholdRuleId").isNull()).isFalse();
        String alertId = alert.get("id").asText();
        Integer doctorNotifications = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE entity_id = ?::uuid AND recipient_user_id = ? AND priority = 'CRITICAL'",
                Integer.class, alertId, userId(DOCTOR));
        assertThat(doctorNotifications).isEqualTo(1);

        // A repeated breach updates the open alert instead of raising (and notifying) again.
        MvcResult again = call(NURSE, "POST", "/api/admissions/" + admission + "/vitals", Map.of("spo2", 86, "onOxygen", true));
        assertThat(readJson(again).get("alertsRaised")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinical_alerts WHERE admission_id = ? AND parameter = 'SPO2'", Integer.class, admission))
                .isEqualTo(1);

        // The doctor sees and acknowledges it; resolving requires notes.
        JsonNode mine = readJson(call(DOCTOR, "GET", "/api/alerts?status=OPEN&size=100", null));
        assertThat(mine.get("content").findValuesAsText("id")).contains(alertId);
        assertThat(call(DOCTOR, "POST", "/api/alerts/" + alertId + "/acknowledge", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(DOCTOR, "POST", "/api/alerts/" + alertId + "/resolve", Map.of("notes", "")).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(DOCTOR, "POST", "/api/alerts/" + alertId + "/resolve", Map.of("notes", "Oxygen started, SpO2 95%")).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void nurseEscalationCreatesAlertForAttendingDoctor() throws Exception {
        UUID admission = admitToMedicalWard("Hema", "Escalationflow");
        MvcResult r = call(NURSE, "POST", "/api/admissions/" + admission + "/escalations",
                Map.of("concern", "New confusion", "observations", "GCS 13, RR 26", "urgency", "EMERGENCY"));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode alert = readJson(r);
        assertThat(alert.get("alertType").asText()).isEqualTo("ESCALATION");
        assertThat(alert.get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(alert.get("assignedTo").asText()).isEqualTo(userId(DOCTOR).toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE entity_id = ?::uuid AND recipient_user_id = ?",
                Integer.class, UUID.fromString(alert.get("id").asText()), userId(DOCTOR))).isEqualTo(1);
    }

    // ------------------------------------------------------------------ release & portal

    @Test
    void rolesWithoutReleasePermissionCannotRelease() throws Exception {
        UUID ravi = openAdmissionId("Ravi");
        String noteId = jdbc.queryForObject("SELECT id::text FROM clinical_notes WHERE admission_id = ? AND status = 'FINAL' LIMIT 1", String.class, ravi);
        String orderId = jdbc.queryForObject("SELECT id::text FROM medication_orders WHERE admission_id = ? LIMIT 1", String.class, ravi);
        String labId = jdbc.queryForObject("SELECT id::text FROM lab_orders WHERE admission_id = ? AND status = 'REVIEWED' LIMIT 1", String.class, ravi);
        assertThat(call(NURSE, "POST", "/api/notes/" + noteId + "/release", Map.of("patientSummary", "Shared by nurse")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(call(NURSE, "POST", "/api/medication-orders/" + orderId + "/release", Map.of("patientInstructions", "x")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(call(NURSE, "POST", "/api/investigations/" + labId + "/release", null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(BILLING, "POST", "/api/notes/" + noteId + "/release", Map.of("patientSummary", "x")).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void portalReturnsOnlyReleasedContent() throws Exception {
        UUID ravi = openAdmissionId("Ravi");
        UUID patient = patientId("Ravi");
        JsonNode before = readJson(call("ravi.sharma@demo.careflow.local", "GET", "/api/portal/patients/" + patient + "/records", null));
        Integer releasedNotes = jdbc.queryForObject(
                "SELECT count(*) FROM clinical_notes WHERE patient_id = ? AND released_to_patient AND status = 'FINAL'", Integer.class, patient);
        assertThat(before.get("notes")).hasSize(releasedNotes);
        before.get("notes").forEach(n -> assertThat(n.get("patientSummary").asText()).isNotBlank());
        before.get("results").forEach(res -> res.get("components").forEach(c -> assertThat(c.get("releasedToPatient").asBoolean()).isTrue()));
        assertThat(before.toString()).doesNotContain("Penicillin allergy (urticaria) noted");

        // A new, unreleased note is not visible; once released, only its patient summary is.
        String noteId = readJson(call(DOCTOR, "POST", "/api/admissions/" + ravi + "/notes", Map.of(
                "noteType", "DOCTOR_REVIEW", "title", "Portal check review", "content", "Internal clinical reasoning PORTAL-SECRET"))).get("id").asText();
        JsonNode mid = readJson(call("ravi.sharma@demo.careflow.local", "GET", "/api/portal/patients/" + patient + "/records", null));
        assertThat(mid.get("notes").findValuesAsText("id")).doesNotContain(noteId);
        assertThat(call(DOCTOR, "POST", "/api/notes/" + noteId + "/release", Map.of("patientSummary", "You are doing well.")).getResponse().getStatus())
                .isEqualTo(200);
        JsonNode after = readJson(call("sunita.sharma@demo.careflow.local", "GET", "/api/portal/patients/" + patient + "/records", null));
        assertThat(after.get("notes").findValuesAsText("id")).contains(noteId);
        assertThat(after.toString()).doesNotContain("PORTAL-SECRET");

        // Medications: only released orders.
        JsonNode meds = readJson(call("ravi.sharma@demo.careflow.local", "GET", "/api/portal/patients/" + patient + "/medications", null));
        Integer releasedOrders = jdbc.queryForObject("SELECT count(*) FROM medication_orders WHERE patient_id = ? AND released_to_patient", Integer.class, patient);
        assertThat(meds).hasSize(releasedOrders);

        // Another patient cannot read Ravi's records (404, no existence leak).
        assertThat(call("lakshmi.pillai@demo.careflow.local", "GET", "/api/portal/patients/" + patient + "/records", null)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void handoverIsPrefilledSubmittedAndAcknowledgedByReceivingNurse() throws Exception {
        UUID mwa = jdbc.queryForObject("SELECT id FROM wards WHERE code = 'MWA'", UUID.class);
        MvcResult created = call(NURSE, "POST", "/api/handovers", Map.of("wardId", mwa, "shiftDate", LocalDate.now().toString(), "shift", "EVENING"));
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode h = readJson(created);
        assertThat(h.get("items").size()).isGreaterThan(0);
        assertThat(h.get("items").findValuesAsText("patientName")).contains("Ravi Sharma");
        String id = h.get("id").asText();
        UUID leela = userId("nurse.leela@careflow.local");
        // Only the receiving nurse may acknowledge.
        assertThat(call(NURSE, "POST", "/api/handovers/" + id + "/submit", Map.of("toUserId", leela)).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(NURSE, "POST", "/api/handovers/" + id + "/acknowledge", Map.of()).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(OTHER_NURSE, "GET", "/api/handovers/" + id, null).getResponse().getStatus()).isEqualTo(403);
        MvcResult ack = call("nurse.leela@careflow.local", "POST", "/api/handovers/" + id + "/acknowledge", Map.of("comment", "Received"));
        assertThat(readJson(ack).get("status").asText()).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void ruleBasedDraftReferencesSourcesAndBecomesNoteOnlyWhenAccepted() throws Exception {
        UUID ravi = openAdmissionId("Ravi");
        MvcResult d = call(DOCTOR, "POST", "/api/admissions/" + ravi + "/drafts", Map.of("draftType", "PROGRESS_SUMMARY"));
        assertThat(d.getResponse().getStatus()).as(d.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode draft = readJson(d);
        assertThat(draft.get("generator").asText()).isEqualTo("rule-based-summarizer-v1");
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        draft.get("sourceRefs").forEach(r -> assertThat(r.get("sources").size()).isGreaterThan(0));
        Integer notesBefore = jdbc.queryForObject("SELECT count(*) FROM clinical_notes WHERE admission_id = ?", Integer.class, ravi);
        MvcResult accepted = call(DOCTOR, "POST", "/api/drafts/" + draft.get("id").asText() + "/accept", Map.of());
        assertThat(readJson(accepted).get("status").asText()).isEqualTo("ACCEPTED");
        Integer notesAfter = jdbc.queryForObject("SELECT count(*) FROM clinical_notes WHERE admission_id = ?", Integer.class, ravi);
        assertThat(notesAfter).isEqualTo(notesBefore + 1);
        assertThat(jdbc.queryForObject("SELECT author_id FROM clinical_notes WHERE id = ?::uuid", UUID.class,
                readJson(accepted).get("publishedNoteId").asText())).isEqualTo(userId(DOCTOR));
    }
}
