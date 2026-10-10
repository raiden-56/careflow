package com.careflow.hms.pharmacy;

import com.careflow.hms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Pharmacy: catalogue availability, verification, dispensing, returns, stock integrity and access control. */
class PharmacyWorkflowIT extends IntegrationTestBase {

    private static final String PHARMACIST = "pharmacy@careflow.local";
    private static final String ADMIN = "admin@careflow.local";
    private static final String DOCTOR = "dr.mehta@careflow.local";
    private static final String NURSE = "nurse.priya@careflow.local";

    // ------------------------------------------------------------------ fixtures

    private String uniqueCode(String prefix) {
        return prefix + "-" + Integer.toString(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE), 36).toUpperCase();
    }

    private UUID createMedication(String code, String name, String price, int reorderLevel, boolean highAlert,
                                  String allergenGroup) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("genericName", name);
        body.put("form", "TABLET");
        body.put("strength", "500 mg");
        body.put("defaultRoute", "ORAL");
        body.put("drugClass", "Test class");
        body.put("allergenGroup", allergenGroup);
        body.put("isHighAlert", highAlert);
        body.put("isControlled", false);
        body.put("unitPrice", price);
        body.put("dispenseUnit", "TAB/CAP");
        body.put("reorderLevel", reorderLevel);
        MvcResult r = call(PHARMACIST, "POST", "/api/medications", body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(readJson(r).get("id").asText());
    }

    private UUID receive(UUID medicationId, String batch, LocalDate expiry, int qty) throws Exception {
        MvcResult r = call(PHARMACIST, "POST", "/api/pharmacy/batches", Map.of("medicationId", medicationId,
                "batchNumber", batch, "expiryDate", expiry.toString(), "quantity", qty, "unitCost", "5.00",
                "supplier", "Test supplier"));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(readJson(r).get("id").asText());
    }

    /** A fresh patient with an open admission (and its own open invoice). */
    private UUID[] admitFreshPatient(String first) throws Exception {
        UUID patient = registerPatient(first, "Pharmacy");
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patient);
        body.put("admissionType", "EMERGENCY");
        body.put("admissionReason", "Pharmacy integration test");
        body.put("departmentId", departmentId("GEN_MED"));
        body.put("attendingDoctorId", userId(DOCTOR));
        body.put("requiresIsolation", false);
        MvcResult r = call(ADMIN, "POST", "/api/admissions", body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        UUID admission = UUID.fromString(readJson(r).get("summary").get("id").asText());
        return new UUID[]{patient, admission};
    }

    /** Prescriptions are owned by the clinical module; tests insert the order row directly. */
    private UUID insertOrder(UUID[] pa, UUID medicationId, String frequency) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO medication_orders (id, organization_id, admission_id, patient_id, medication_id, dose, dose_unit,
                    route, frequency, start_at, status, prescribed_by, prescribed_at)
                SELECT ?, a.organization_id, a.id, a.patient_id, ?, '500', 'mg', 'ORAL', ?, now(), 'ACTIVE', ?, now()
                FROM admissions a WHERE a.id = ?
                """, id, medicationId, frequency, userId(DOCTOR), pa[1]);
        return id;
    }

    private void verify(UUID orderId) throws Exception {
        MvcResult r = call(PHARMACIST, "POST", "/api/pharmacy/orders/" + orderId + "/verify", Map.of("allergyAcknowledged", false));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    }

    private MvcResult dispense(UUID orderId, UUID batchId, int qty) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("orderId", orderId);
        body.put("batchId", batchId);
        body.put("quantity", qty);
        body.put("safetyChecksConfirmed", true);
        return call(PHARMACIST, "POST", "/api/pharmacy/dispensations", body);
    }

    private int onHand(UUID batchId) {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory_batches WHERE id = ?", Integer.class, batchId);
    }

    private BigDecimal ledgerBalance(UUID admissionId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(l.amount), 0) FROM ledger_entries l JOIN invoices i ON i.id = l.invoice_id
                WHERE i.admission_id = ? AND i.status <> 'CANCELLED'
                """, BigDecimal.class, admissionId);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void catalogueSearchReturnsStockAvailabilityForPrescribers() throws Exception {
        String code = uniqueCode("AVL");
        UUID med = createMedication(code, "Availabilitol", "4.00", 50, false, null);
        JsonNode out = readJson(call(DOCTOR, "GET", "/api/medications?activeOnly=true&q=" + code, null)).get("content").get(0);
        assertThat(out.get("stockOnHand").asLong()).isZero();
        assertThat(out.get("availability").asText()).isEqualTo("OUT_OF_STOCK");

        receive(med, "AVL-1", LocalDate.now().plusYears(1), 20);
        out = readJson(call(DOCTOR, "GET", "/api/medications?activeOnly=true&q=" + code, null)).get("content").get(0);
        assertThat(out.get("stockOnHand").asLong()).isEqualTo(20);
        assertThat(out.get("availability").asText()).isEqualTo("LOW_STOCK");
        for (String field : List.of("id", "code", "genericName", "form", "strength", "defaultRoute", "drugClass",
                "isHighAlert", "isControlled", "unitPrice", "dispenseUnit", "reorderLevel", "active")) {
            assertThat(out.has(field)).as(field).isTrue();
        }
        assertThat(out.get("unitPrice").asText()).isEqualTo("4.00");

        receive(med, "AVL-2", LocalDate.now().plusYears(2), 40);
        out = readJson(call(DOCTOR, "GET", "/api/medications/" + med, null));
        assertThat(out.get("availability").asText()).isEqualTo("IN_STOCK");

        // Duplicate codes are rejected; catalogue writes are pharmacy-only.
        Map<String, Object> dup = new HashMap<>(Map.of("code", code, "genericName", "Dup", "form", "TABLET", "strength", "1 mg",
                "defaultRoute", "ORAL", "unitPrice", "1.00", "dispenseUnit", "TAB", "reorderLevel", 1));
        assertThat(call(PHARMACIST, "POST", "/api/medications", dup).getResponse().getStatus()).isEqualTo(409);
        assertThat(call(DOCTOR, "POST", "/api/medications", dup).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void dispensingDecrementsStockWritesMovementPostsChargeAndNeverTouchesMar() throws Exception {
        UUID med = createMedication(uniqueCode("DSP"), "Dispensamol", "12.50", 10, false, null);
        UUID batch = receive(med, "DSP-1", LocalDate.now().plusMonths(18), 50);
        UUID[] pa = admitFreshPatient("Dina");
        UUID order = insertOrder(pa, med, "TDS");
        String orderBefore = jdbc.queryForObject(
                "SELECT dose || '|' || dose_unit || '|' || route || '|' || frequency || '|' || status FROM medication_orders WHERE id = ?",
                String.class, order);
        int marBefore = jdbc.queryForObject("SELECT count(*) FROM medication_administrations", Integer.class);
        String marStatusesBefore = jdbc.queryForObject(
                "SELECT coalesce(string_agg(id::text || status, ',' ORDER BY id), '') FROM medication_administrations", String.class);
        BigDecimal balanceBefore = ledgerBalance(pa[1]);

        // Unverified orders cannot be dispensed.
        assertThat(dispense(order, batch, 4).getResponse().getStatus()).isEqualTo(422);
        verify(order);
        assertThat(jdbc.queryForObject("SELECT verified_by_pharmacist FROM medication_orders WHERE id = ?", UUID.class, order))
                .isEqualTo(userId(PHARMACIST));

        MvcResult r = dispense(order, batch, 4);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode d = readJson(r);
        UUID dispensationId = UUID.fromString(d.get("id").asText());
        assertThat(d.get("chargedAmount").asText()).isEqualTo("50.00");
        assertThat(onHand(batch)).isEqualTo(46);

        Map<String, Object> mv = jdbc.queryForMap("SELECT movement_type, quantity, balance_after, reference_type FROM stock_movements"
                + " WHERE reference_id = ?", dispensationId);
        assertThat(mv.get("movement_type")).isEqualTo("DISPENSE");
        assertThat(mv.get("quantity")).isEqualTo(-4);
        assertThat(mv.get("balance_after")).isEqualTo(46);

        Map<String, Object> line = jdbc.queryForMap("SELECT id, category, status, total_amount, quantity FROM invoice_line_items"
                + " WHERE source_type = 'DISPENSATION' AND source_id = ?", dispensationId);
        assertThat(line.get("category")).isEqualTo("PHARMACY");
        assertThat(line.get("status")).isEqualTo("POSTED");
        assertThat((BigDecimal) line.get("total_amount")).isEqualByComparingTo("50.00");
        assertThat(jdbc.queryForObject("SELECT charge_line_id FROM dispensations WHERE id = ?", UUID.class, dispensationId))
                .isEqualTo(line.get("id"));
        assertThat(ledgerBalance(pa[1])).isEqualByComparingTo(balanceBefore.add(new BigDecimal("50.00")));

        // Dispensing is a supply event only: no MAR rows created or modified, prescription unchanged.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM medication_administrations", Integer.class)).isEqualTo(marBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM medication_administrations WHERE order_id = ?", Integer.class, order)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT coalesce(string_agg(id::text || status, ',' ORDER BY id), '') FROM medication_administrations", String.class))
                .isEqualTo(marStatusesBefore);
        assertThat(jdbc.queryForObject(
                "SELECT dose || '|' || dose_unit || '|' || route || '|' || frequency || '|' || status FROM medication_orders WHERE id = ?",
                String.class, order)).isEqualTo(orderBefore);

        // Timeline + history.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM timeline_events WHERE entity_id = ? AND event_type = 'MEDICATION_DISPENSED'"
                + " AND visibility = 'CARE_TEAM'", Integer.class, dispensationId)).isEqualTo(1);
        JsonNode history = readJson(call(PHARMACIST, "GET", "/api/pharmacy/dispensations?admissionId=" + pa[1], null));
        assertThat(history.get("totalElements").asInt()).isEqualTo(1);
        JsonNode detail = readJson(call(PHARMACIST, "GET", "/api/pharmacy/dispensations/" + dispensationId, null));
        assertThat(detail.get("movements").size()).isEqualTo(1);
        assertThat(detail.get("charges").size()).isEqualTo(1);
    }

    @Test
    void cannotDispenseBeyondStockFromExpiredOrQuarantinedBatchOrAgainstDiscontinuedOrder() throws Exception {
        UUID med = createMedication(uniqueCode("REJ"), "Rejectamol", "3.00", 5, false, null);
        UUID good = receive(med, "REJ-OK", LocalDate.now().plusMonths(10), 10);
        UUID expired = receive(med, "REJ-EXP", LocalDate.now().plusMonths(2), 10);
        jdbc.update("UPDATE inventory_batches SET expiry_date = current_date - 3 WHERE id = ?", expired);
        UUID quarantined = receive(med, "REJ-QUA", LocalDate.now().plusMonths(6), 10);
        assertThat(call(PHARMACIST, "POST", "/api/pharmacy/batches/" + quarantined + "/quarantine",
                Map.of("reason", "Temperature excursion")).getResponse().getStatus()).isEqualTo(200);
        UUID[] pa = admitFreshPatient("Reena");
        UUID order = insertOrder(pa, med, "BD");
        verify(order);

        MvcResult tooMany = dispense(order, good, 11);
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(tooMany).get("code").asText()).isEqualTo("INSUFFICIENT_STOCK");
        MvcResult exp = dispense(order, expired, 1);
        assertThat(exp.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(exp).get("code").asText()).isEqualTo("BATCH_EXPIRED");
        MvcResult q = dispense(order, quarantined, 1);
        assertThat(readJson(q).get("code").asText()).isEqualTo("BATCH_QUARANTINED");
        assertThat(onHand(good)).isEqualTo(10);
        assertThat(onHand(expired)).isEqualTo(10);

        // Expired stock can be written off; unexpired cannot.
        assertThat(call(PHARMACIST, "POST", "/api/pharmacy/batches/" + good + "/write-off", Map.of("reason", "attempt"))
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(call(PHARMACIST, "POST", "/api/pharmacy/batches/" + expired + "/write-off", Map.of("reason", "Expired"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(onHand(expired)).isZero();

        // Adjustments require a reason and never go negative.
        assertThat(call(PHARMACIST, "POST", "/api/pharmacy/batches/" + good + "/adjust", Map.of("direction", "OUT",
                "quantity", 11, "reasonCode", "DAMAGE", "reason", "Broken strips")).getResponse().getStatus()).isEqualTo(422);
        assertThat(call(PHARMACIST, "POST", "/api/pharmacy/batches/" + good + "/adjust", Map.of("direction", "OUT",
                "quantity", 1, "reasonCode", "DAMAGE", "reason", "")).getResponse().getStatus()).isEqualTo(400);

        jdbc.update("UPDATE medication_orders SET status = 'DISCONTINUED', discontinued_at = now() WHERE id = ?", order);
        MvcResult disc = dispense(order, good, 1);
        assertThat(disc.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(disc).get("code").asText()).isEqualTo("ORDER_NOT_ACTIVE");
        assertThat(onHand(good)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispensations WHERE order_id = ?", Integer.class, order)).isZero();
    }

    @Test
    void concurrentDispensesOnASmallBatchNeverGoNegative() throws Exception {
        UUID med = createMedication(uniqueCode("CON"), "Concurrentol", "2.00", 1, false, null);
        UUID batch = receive(med, "CON-1", LocalDate.now().plusMonths(12), 5);
        UUID[] pa = admitFreshPatient("Chitra");
        UUID order = insertOrder(pa, med, "QID");
        verify(order);
        token(PHARMACIST);

        int attempts = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            Callable<Integer> task = () -> {
                start.await();
                return dispense(order, batch, 1).getResponse().getStatus();
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            int status = f.get();
            if (status == 201) {
                ok++;
            } else {
                assertThat(status).isIn(409, 422);
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(5);
        assertThat(onHand(batch)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_batches WHERE id = ?", String.class, batch)).isEqualTo("DEPLETED");
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(quantity), 0) FROM stock_movements WHERE batch_id = ?", Integer.class, batch)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE batch_id = ? AND balance_after < 0", Integer.class, batch)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispensations WHERE batch_id = ?", Integer.class, batch)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoice_line_items l JOIN dispensations d ON d.id = l.source_id"
                + " WHERE d.batch_id = ? AND l.status = 'POSTED'", Integer.class, batch)).isEqualTo(5);
    }

    @Test
    void returnsRestockAndCorrectBillingThroughReversals() throws Exception {
        UUID med = createMedication(uniqueCode("RET"), "Returnamol", "10.00", 5, false, null);
        UUID batch = receive(med, "RET-1", LocalDate.now().plusMonths(12), 30);
        UUID[] pa = admitFreshPatient("Rohini");
        UUID order = insertOrder(pa, med, "OD");
        verify(order);
        BigDecimal before = ledgerBalance(pa[1]);

        UUID dispensation = UUID.fromString(readJson(dispense(order, batch, 6)).get("id").asText());
        assertThat(ledgerBalance(pa[1])).isEqualByComparingTo(before.add(new BigDecimal("60.00")));
        assertThat(onHand(batch)).isEqualTo(24);

        // Partial return (restocked): original line reversed, remaining quantity re-posted.
        MvcResult r1 = call(PHARMACIST, "POST", "/api/pharmacy/dispensations/" + dispensation + "/returns",
                Map.of("quantity", 2, "reason", "Dose reduced", "damaged", false));
        assertThat(r1.getResponse().getStatus()).as(r1.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode v1 = readJson(r1);
        assertThat(v1.get("status").asText()).isEqualTo("PARTIALLY_RETURNED");
        assertThat(v1.get("returnedQuantity").asInt()).isEqualTo(2);
        assertThat(v1.get("chargedAmount").asText()).isEqualTo("40.00");
        assertThat(onHand(batch)).isEqualTo(26);
        assertThat(ledgerBalance(pa[1])).isEqualByComparingTo(before.add(new BigDecimal("40.00")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoice_line_items WHERE source_type = 'DISPENSATION' AND source_id = ?"
                + " AND status = 'REVERSED'", Integer.class, dispensation)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT total_amount FROM invoice_line_items WHERE source_type = 'DISPENSATION'"
                + " AND source_id = ? AND status = 'POSTED'", BigDecimal.class, dispensation)).isEqualByComparingTo("40.00");

        // Cannot return more than remains.
        assertThat(call(PHARMACIST, "POST", "/api/pharmacy/dispensations/" + dispensation + "/returns",
                Map.of("quantity", 5, "reason", "Too many", "damaged", false)).getResponse().getStatus()).isEqualTo(422);

        // Remaining 4 returned damaged: not restocked, fully credited.
        JsonNode v2 = readJson(call(PHARMACIST, "POST", "/api/pharmacy/dispensations/" + dispensation + "/returns",
                Map.of("quantity", 4, "reason", "Strip contaminated on ward", "damaged", true)));
        assertThat(v2.get("status").asText()).isEqualTo("RETURNED");
        assertThat(v2.get("chargedAmount").asText()).isEqualTo("0.00");
        assertThat(onHand(batch)).isEqualTo(26);
        assertThat(ledgerBalance(pa[1])).isEqualByComparingTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoice_line_items WHERE source_type = 'DISPENSATION' AND source_id = ?"
                + " AND status = 'POSTED'", Integer.class, dispensation)).isZero();
        assertThat(jdbc.queryForObject("SELECT string_agg(movement_type, ',' ORDER BY performed_at, movement_type DESC) FROM stock_movements"
                + " WHERE reference_id = ?", String.class, dispensation)).isEqualTo("DISPENSE,RETURN,RETURN,DAMAGE");
    }

    @Test
    void verificationRequiresAllergyAcknowledgementAndQueriesNotifyPrescriber() throws Exception {
        UUID med = createMedication(uniqueCode("ALG"), "Testicillin", "7.00", 5, false, "PENICILLIN");
        UUID batch = receive(med, "ALG-1", LocalDate.now().plusMonths(12), 20);
        UUID[] pa = admitFreshPatient("Alka");
        jdbc.update("INSERT INTO allergies (organization_id, patient_id, allergen, allergy_type, severity, status)"
                + " SELECT organization_id, id, 'Penicillin', 'DRUG', 'SEVERE', 'ACTIVE' FROM patients WHERE id = ?", pa[0]);
        UUID order = insertOrder(pa, med, "BD");

        JsonNode detail = readJson(call(PHARMACIST, "GET", "/api/pharmacy/orders/" + order, null));
        assertThat(detail.get("item").get("warnings").toString()).contains("ALLERGY");
        assertThat(detail.get("admission").get("allergies").toString()).contains("Penicillin");

        MvcResult noAck = call(PHARMACIST, "POST", "/api/pharmacy/orders/" + order + "/verify", Map.of("allergyAcknowledged", false));
        assertThat(noAck.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(noAck).get("code").asText()).isEqualTo("ALLERGY_ACK_REQUIRED");

        MvcResult q = call(PHARMACIST, "POST", "/api/pharmacy/orders/" + order + "/queries",
                Map.of("message", "Documented severe penicillin allergy - please confirm or change the antibiotic."));
        assertThat(q.getResponse().getStatus()).isEqualTo(201);
        UUID queryId = UUID.fromString(readJson(q).get("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND dedupe_key = ?",
                Integer.class, userId(DOCTOR), "PHARM_QUERY:" + queryId)).isEqualTo(1);
        assertThat(call(PHARMACIST, "POST", "/api/pharmacy/orders/" + order + "/queries", Map.of("message", "Second query"))
                .getResponse().getStatus()).isEqualTo(409);
        JsonNode queued = readJson(call(PHARMACIST, "GET", "/api/pharmacy/queue?filter=QUERIED&admissionId=" + pa[1], null));
        assertThat(queued.get("totalElements").asInt()).isEqualTo(1);

        MvcResult ok = call(PHARMACIST, "POST", "/api/pharmacy/orders/" + order + "/verify",
                Map.of("allergyAcknowledged", true, "note", "Prescriber confirmed tolerance history"));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM pharmacy_order_queries WHERE id = ?", String.class, queryId)).isEqualTo("RESOLVED");
        assertThat(call(PHARMACIST, "POST", "/api/pharmacy/orders/" + order + "/verify", Map.of("allergyAcknowledged", true))
                .getResponse().getStatus()).isEqualTo(409);

        // Allergy-flagged dispensing requires explicit safety confirmation.
        Map<String, Object> body = new HashMap<>(Map.of("orderId", order, "batchId", batch, "quantity", 2));
        body.put("safetyChecksConfirmed", false);
        assertThat(readJson(call(PHARMACIST, "POST", "/api/pharmacy/dispensations", body)).get("code").asText())
                .isEqualTo("SAFETY_CHECK_REQUIRED");
        assertThat(dispense(order, batch, 2).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void nonPharmacyRolesCannotDispenseOrManageInventory() throws Exception {
        UUID med = createMedication(uniqueCode("SEC"), "Securamol", "1.00", 1, false, null);
        UUID batch = receive(med, "SEC-1", LocalDate.now().plusMonths(12), 10);
        UUID[] pa = admitFreshPatient("Sejal");
        UUID order = insertOrder(pa, med, "OD");
        verify(order);
        Map<String, Object> dispenseBody = Map.of("orderId", order, "batchId", batch, "quantity", 1, "safetyChecksConfirmed", true);
        Map<String, Object> adjustBody = Map.of("direction", "IN", "quantity", 5, "reasonCode", "COUNT_CORRECTION", "reason", "Bypass");
        Map<String, Object> receiveBody = Map.of("medicationId", med, "batchNumber", "SEC-X", "expiryDate",
                LocalDate.now().plusYears(1).toString(), "quantity", 5, "unitCost", "1.00");

        for (String user : List.of(NURSE, DOCTOR, "billing@careflow.local", ADMIN, "ravi.sharma@demo.careflow.local")) {
            assertThat(call(user, "POST", "/api/pharmacy/dispensations", dispenseBody).getResponse().getStatus()).as(user).isEqualTo(403);
            assertThat(call(user, "POST", "/api/pharmacy/batches/" + batch + "/adjust", adjustBody).getResponse().getStatus()).as(user).isEqualTo(403);
            assertThat(call(user, "POST", "/api/pharmacy/batches", receiveBody).getResponse().getStatus()).as(user).isEqualTo(403);
            assertThat(call(user, "POST", "/api/pharmacy/orders/" + order + "/verify", Map.of("allergyAcknowledged", true))
                    .getResponse().getStatus()).as(user).isEqualTo(403);
        }
        // Nurses cannot reach inventory management or the pharmacy queue at all.
        for (String path : List.of("/api/pharmacy/batches", "/api/pharmacy/stock", "/api/pharmacy/alerts", "/api/pharmacy/queue",
                "/api/pharmacy/movements", "/api/dashboard/pharmacy", "/api/pharmacy/stock/export", "/api/pharmacy/batches/" + batch)) {
            assertThat(call(NURSE, "GET", path, null).getResponse().getStatus()).as(path).isEqualTo(403);
        }
        assertThat(call(NURSE, "POST", "/api/pharmacy/batches/" + batch + "/quarantine", Map.of("reason", "nope"))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(onHand(batch)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispensations WHERE order_id = ?", Integer.class, order)).isZero();
    }

    @Test
    void stockReportAndAlertsReflectBatches() throws Exception {
        String code = uniqueCode("RPT");
        UUID med = createMedication(code, "Reportamol", "8.00", 100, false, null);
        UUID batch = receive(med, "RPT-1", LocalDate.now().plusDays(20), 12);
        JsonNode alerts = readJson(call(PHARMACIST, "GET", "/api/pharmacy/alerts?nearExpiryDays=30", null));
        assertThat(alerts.get("lowStock").toString()).contains(code);
        assertThat(alerts.get("nearExpiry").toString()).contains(batch.toString());
        MvcResult csv = call(PHARMACIST, "GET", "/api/pharmacy/stock/export", null);
        assertThat(csv.getResponse().getStatus()).isEqualTo(200);
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getContentAsString()).contains(code).contains("LOW_STOCK");
        JsonNode stock = readJson(call(PHARMACIST, "GET", "/api/pharmacy/stock?q=" + code, null));
        assertThat(stock.get("content").get(0).get("usable").asInt()).isEqualTo(12);
        assertThat(stock.get("content").get(0).get("nearExpiry").asInt()).isEqualTo(12);
        JsonNode dash = readJson(call(PHARMACIST, "GET", "/api/dashboard/pharmacy", null));
        assertThat(dash.get("lowStockCount").asInt()).isGreaterThan(0);
        assertThat(dash.get("dispensedLast7Days").size()).isEqualTo(7);
    }
}
