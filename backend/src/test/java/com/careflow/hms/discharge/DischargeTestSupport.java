package com.careflow.hms.discharge;

import com.careflow.hms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared helpers for discharge integration tests (fresh admissions so seeded demo data stays intact). */
abstract class DischargeTestSupport extends IntegrationTestBase {

    static final String ADMIN = "admin@careflow.local";
    static final String DR_MEHTA = "dr.mehta@careflow.local";
    static final String DR_KHAN = "dr.khan@careflow.local";
    static final String DR_IYER = "dr.iyer@careflow.local";
    static final String NURSE_PRIYA = "nurse.priya@careflow.local";
    static final String NURSE_RAHUL = "nurse.rahul@careflow.local";
    static final String BILLING = "billing@careflow.local";
    static final String PHARMACIST = "pharmacy@careflow.local";
    static final String LAKSHMI = "lakshmi.pillai@demo.careflow.local";
    static final String RAVI = "ravi.sharma@demo.careflow.local";

    /** Registers and admits a patient to Medical Ward A under Dr Mehta. */
    UUID admitFresh(String first, String last) throws Exception {
        UUID patient = registerPatient(first, last);
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patient);
        body.put("admissionType", "EMERGENCY");
        body.put("admissionReason", "Discharge workflow integration test");
        body.put("departmentId", departmentId("GEN_MED"));
        body.put("attendingDoctorId", userId(DR_MEHTA));
        body.put("requiresIsolation", false);
        body.put("bedId", availableBed("MWA"));
        MvcResult r = call(ADMIN, "POST", "/api/admissions", body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(readJson(r).get("summary").get("id").asText());
    }

    UUID orgId() {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE code = 'CFDEMO'", UUID.class);
    }

    UUID patientOf(UUID admissionId) {
        return jdbc.queryForObject("SELECT patient_id FROM admissions WHERE id = ?", UUID.class, admissionId);
    }

    JsonNode initiate(String email, UUID admissionId) throws Exception {
        MvcResult r = call(email, "POST", "/api/discharge/admissions/" + admissionId + "/workflow",
                Map.of("plannedDischargeAt", Instant.now().plusSeconds(86400).toString()));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return readJson(r);
    }

    JsonNode detail(String email, UUID admissionId) throws Exception {
        MvcResult r = call(email, "GET", "/api/discharge/admissions/" + admissionId, null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return readJson(r);
    }

    static JsonNode step(JsonNode detail, String code) {
        for (JsonNode s : detail.get("workflow").get("steps")) {
            if (s.get("code").asText().equals(code)) {
                return s;
            }
        }
        throw new AssertionError("No step " + code);
    }

    MvcResult patchStep(String email, UUID admissionId, String code, Map<String, Object> changes) throws Exception {
        // Read as the attending doctor (has access before and after discharge), then act as the given user.
        JsonNode d = detail(DR_MEHTA, admissionId);
        JsonNode s = step(d, code);
        Map<String, Object> body = new HashMap<>(changes);
        body.putIfAbsent("version", s.get("version").asLong());
        return call(email, "PATCH", "/api/discharge/workflows/" + d.get("workflow").get("id").asText() + "/steps/" + s.get("id").asText(), body);
    }

    /** Active discharge medication + an inpatient order with a future scheduled dose. Returns the scheduled dose id. */
    UUID addMedications(UUID admissionId) {
        UUID org = orgId();
        UUID patient = patientOf(admissionId);
        UUID med = jdbc.queryForObject("SELECT id FROM medications WHERE code = 'MED-CEPHAL500' AND organization_id = ?", UUID.class, org);
        UUID dr = userId(DR_MEHTA);
        jdbc.update("""
                INSERT INTO medication_orders (organization_id, admission_id, patient_id, medication_id, dose, dose_unit, route, frequency,
                    start_at, duration_days, patient_instructions, is_discharge_medication, status, prescribed_by)
                VALUES (?, ?, ?, ?, '500', 'mg', 'ORAL', 'QID', now(), 7, 'Take one capsule four times a day for 7 days.', true, 'ACTIVE', ?)
                """, org, admissionId, patient, med, dr);
        UUID inpatient = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO medication_orders (id, organization_id, admission_id, patient_id, medication_id, dose, dose_unit, route, frequency,
                    start_at, is_discharge_medication, status, prescribed_by)
                VALUES (?, ?, ?, ?, ?, '500', 'mg', 'ORAL', 'QID', now() - interval '1 day', false, 'ACTIVE', ?)
                """, inpatient, org, admissionId, patient, med, dr);
        UUID dose = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO medication_administrations (id, organization_id, order_id, admission_id, patient_id, scheduled_at, status)
                VALUES (?, ?, ?, ?, ?, now() + interval '3 hours', 'SCHEDULED')
                """, dose, org, inpatient, admissionId, patient);
        return dose;
    }

    /** Posts a charge straight to the authoritative ledger (simulates the billing module). */
    void postLedgerCharge(UUID admissionId, String amount) {
        UUID invoice = jdbc.queryForObject("SELECT id FROM invoices WHERE admission_id = ? AND status <> 'CANCELLED'", UUID.class, admissionId);
        jdbc.update("""
                INSERT INTO ledger_entries (organization_id, invoice_id, patient_id, entry_type, amount, reference_type, reference_id, description, created_by)
                VALUES (?, ?, ?, 'CHARGE', ?::numeric, 'INVOICE_LINE', ?, 'Integration test charge', ?)
                """, orgId(), invoice, patientOf(admissionId), amount, UUID.randomUUID(), userId(BILLING));
    }

    void finalizeInvoice(UUID admissionId) {
        jdbc.update("UPDATE invoices SET status = 'FINALIZED', finalized_by = ?, finalized_at = now() WHERE admission_id = ? AND status = 'OPEN'",
                userId(BILLING), admissionId);
    }

    UUID addLabOrder(UUID admissionId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO lab_orders (id, organization_id, admission_id, patient_id, order_number, test_code, test_name, status, ordered_by)
                VALUES (?, ?, ?, ?, ?, 'LAB_CRP', 'C-reactive protein', ?, ?)
                """, id, orgId(), admissionId, patientOf(admissionId), "LAB-IT-" + id.toString().substring(0, 8), status, userId(DR_MEHTA));
        return id;
    }
}
