package com.careflow.hms.admission;

import com.careflow.hms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Workflow A (admission) and bed-allocation concurrency guarantees. */
class AdmissionWorkflowIT extends IntegrationTestBase {

    private static final String ADMIN = "admin@careflow.local";

    private Map<String, Object> admitBody(UUID patientId, UUID bedId) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("admissionType", "EMERGENCY");
        body.put("admissionReason", "Integration test admission");
        body.put("departmentId", departmentId("GEN_MED"));
        body.put("attendingDoctorId", userId("dr.mehta@careflow.local"));
        body.put("requiresIsolation", false);
        body.put("bedId", bedId);
        return body;
    }

    @Test
    void admitsPatientAssignsBedAndShowsInDoctorAndNurseWorklists() throws Exception {
        UUID patient = registerPatient("Asha", "Workflow");
        UUID bed = availableBed("MWA");
        MvcResult r = call(ADMIN, "POST", "/api/admissions", admitBody(patient, bed));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JsonNode summary = readJson(r).get("summary");
        assertThat(summary.get("status").asText()).isEqualTo("ADMITTED");
        assertThat(summary.get("location").get("bedId").asText()).isEqualTo(bed.toString());
        String admissionId = summary.get("id").asText();

        String bedStatus = jdbc.queryForObject("SELECT status FROM beds WHERE id = ?", String.class, bed);
        assertThat(bedStatus).isEqualTo("OCCUPIED");

        JsonNode doctorList = readJson(call("dr.mehta@careflow.local", "GET", "/api/admissions/my", null));
        assertThat(doctorList.findValuesAsText("id")).contains(admissionId);
        // Nurse Priya is assigned to Medical Ward A.
        JsonNode nurseList = readJson(call("nurse.priya@careflow.local", "GET", "/api/admissions/my", null));
        assertThat(nurseList.findValuesAsText("id")).contains(admissionId);
        // A nurse on another ward does not see it.
        JsonNode otherNurse = readJson(call("nurse.rahul@careflow.local", "GET", "/api/admissions/my", null));
        assertThat(otherNurse.findValuesAsText("id")).doesNotContain(admissionId);
        // The admission opened an invoice and recorded a patient-visible timeline event.
        Integer invoices = jdbc.queryForObject("SELECT count(*) FROM invoices WHERE admission_id = ?::uuid", Integer.class, admissionId);
        assertThat(invoices).isEqualTo(1);
        JsonNode timeline = readJson(call(ADMIN, "GET", "/api/admissions/" + admissionId + "/timeline", null));
        assertThat(timeline.get("content").findValuesAsText("eventType")).contains("PATIENT_ADMITTED", "BED_ASSIGNED");
    }

    @Test
    void rejectsSecondOpenAdmissionForSamePatient() throws Exception {
        UUID patient = registerPatient("Bina", "Duplicate");
        assertThat(call(ADMIN, "POST", "/api/admissions", admitBody(patient, null)).getResponse().getStatus()).isEqualTo(201);
        MvcResult second = call(ADMIN, "POST", "/api/admissions", admitBody(patient, null));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(readJson(second).get("code").asText()).isEqualTo("PATIENT_ALREADY_ADMITTED");
    }

    @Test
    void occupiedBedCannotBeAssignedAgain() throws Exception {
        UUID bed = availableBed("SWB");
        UUID p1 = registerPatient("Chitra", "First");
        UUID p2 = registerPatient("Dev", "Second");
        assertThat(call(ADMIN, "POST", "/api/admissions", admitBody(p1, bed)).getResponse().getStatus()).isEqualTo(201);
        MvcResult r = call(ADMIN, "POST", "/api/admissions", admitBody(p2, bed));
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        // The failed admission is rolled back entirely (no orphan PENDING_BED admission).
        Integer open = jdbc.queryForObject("SELECT count(*) FROM admissions WHERE patient_id = ? AND status <> 'CANCELLED'", Integer.class, p2);
        assertThat(open).isZero();
    }

    @Test
    void concurrentAssignmentsOfTheSameBedYieldExactlyOneWinner() throws Exception {
        UUID bed = availableBed("PAE");
        int contenders = 6;
        List<UUID> admissions = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            UUID p = registerPatient("Race" + i, "Contender");
            MvcResult r = call(ADMIN, "POST", "/api/admissions", admitBody(p, null));
            admissions.add(UUID.fromString(readJson(r).get("summary").get("id").asText()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (UUID admissionId : admissions) {
            Callable<Integer> task = () -> {
                start.await();
                return call(ADMIN, "POST", "/api/admissions/" + admissionId + "/assign-bed", Map.of("bedId", bed))
                        .getResponse().getStatus();
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int ok = 0;
        int conflicts = 0;
        for (Future<Integer> f : results) {
            int status = f.get();
            if (status == 200) {
                ok++;
            } else if (status == 409) {
                conflicts++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflicts).isEqualTo(contenders - 1);
        Integer openAssignments = jdbc.queryForObject(
                "SELECT count(*) FROM bed_assignments WHERE bed_id = ? AND released_at IS NULL", Integer.class, bed);
        assertThat(openAssignments).isEqualTo(1);
    }

    @Test
    void transferMovesPatientAtomicallyAndReleasesOldBedToCleaning() throws Exception {
        UUID fromBed = availableBed("MWA");
        UUID patient = registerPatient("Esha", "Transfer");
        JsonNode adm = readJson(call(ADMIN, "POST", "/api/admissions", admitBody(patient, fromBed))).get("summary");
        UUID toBed = availableBed("CCU");
        JsonNode t = readJson(call(ADMIN, "POST", "/api/admissions/" + adm.get("id").asText() + "/transfers",
                Map.of("toBedId", toBed, "reason", "Needs cardiac monitoring", "priority", "URGENT")));
        String tid = t.get("id").asText();
        assertThat(call(ADMIN, "POST", "/api/transfers/" + tid + "/approve", Map.of("toBedId", toBed)).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(ADMIN, "POST", "/api/transfers/" + tid + "/complete", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM beds WHERE id = ?", String.class, fromBed)).isEqualTo("CLEANING");
        assertThat(jdbc.queryForObject("SELECT status FROM beds WHERE id = ?", String.class, toBed)).isEqualTo("OCCUPIED");
        Integer open = jdbc.queryForObject("SELECT count(*) FROM bed_assignments WHERE admission_id = ?::uuid AND released_at IS NULL",
                Integer.class, adm.get("id").asText());
        assertThat(open).isEqualTo(1);
    }

    @Test
    void occupiedBedCannotBeManuallyMadeAvailable() throws Exception {
        UUID bed = jdbc.queryForObject("SELECT id FROM beds WHERE status = 'OCCUPIED' LIMIT 1", UUID.class);
        MvcResult r = call(ADMIN, "PATCH", "/api/beds/" + bed + "/status", Map.of("status", "AVAILABLE"));
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
    }
}
