package com.careflow.hms.billing;

import com.careflow.hms.common.ApiException;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.RolePermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payment idempotency (sequential and concurrent), bed-day room charges across a transfer, Workflow D
 * (charges -> partial payment -> receipt -> patient portal) and role / object-level access rules.
 */
class BillingWorkflowIT extends BillingTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired
    AccessPolicy accessPolicy;

    @Autowired
    RolePermissionService rolePermissions;

    @Test
    void paymentRetryWithSameKeyReturnsOriginalPayment() throws Exception {
        String invoiceId = newInvoice("Idempotent");
        charge(invoiceId, "IMG_ECHO", "1"); // 2500
        String k = key();
        MvcResult first = pay(invoiceId, "1000.00", "UPI", "UPI-777", k, false);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        MvcResult retry = pay(invoiceId, "1000.00", "UPI", "UPI-777", k, false);
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        JsonNode a = readJson(first);
        JsonNode b = readJson(retry);
        assertThat(b.get("replayed").asBoolean()).isTrue();
        assertThat(b.at("/payment/id").asText()).isEqualTo(a.at("/payment/id").asText());
        assertThat(b.at("/payment/receiptNumber").asText()).isEqualTo(a.at("/payment/receiptNumber").asText());
        assertThat(paymentLedgerCount(invoiceId)).isEqualTo(1);
        assertThat(summary(invoiceId).get("balance").asText()).isEqualTo("1500.00");
        // Reusing a key for another invoice is a conflict, never a silent replay.
        String other = newInvoice("Idemother");
        charge(other, "LAB_CBC", "1");
        assertThat(pay(other, "100.00", "CASH", null, k, false).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void concurrentDuplicateSubmitsCreateExactlyOnePayment() throws Exception {
        String invoiceId = newInvoice("Concurrent");
        charge(invoiceId, "IMG_ECHO", "1"); // 2500
        String k = key();
        token(BILLING);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<MvcResult> task = () -> {
                    start.await();
                    return pay(invoiceId, "700.00", "CARD", "POS-CONC", k, false);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            Set<String> ids = new java.util.HashSet<>();
            int createdCount = 0;
            for (Future<MvcResult> f : futures) {
                MvcResult r = f.get();
                assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isIn(200, 201);
                if (r.getResponse().getStatus() == 201) {
                    createdCount++;
                }
                ids.add(readJson(r).at("/payment/id").asText());
            }
            assertThat(ids).hasSize(1);
            assertThat(createdCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(paymentLedgerCount(invoiceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE invoice_id = ?::uuid", Integer.class, invoiceId)).isEqualTo(1);
        assertThat(summary(invoiceId).get("balance").asText()).isEqualTo("1800.00");
    }

    @Test
    void roomChargesAreDayAccurateAcrossATransferAndIdempotent() throws Exception {
        UUID patient = registerPatient("Roomday", "Transfer" + (int) (Math.random() * 100000));
        UUID generalBed = availableBed("MWA");
        String admissionId = admit(patient, generalBed);
        UUID privateBed = availableBed("PVT");
        LocalDate today = LocalDate.now(IST);
        LocalDate d3 = today.minusDays(3);
        LocalDate d1 = today.minusDays(1);
        // Admitted 3 days ago to a general bed; moved to a private room yesterday afternoon.
        jdbc.update("UPDATE admissions SET admitted_at = ? WHERE id = ?::uuid", ts(d3, 10, 0), admissionId);
        jdbc.update("UPDATE bed_assignments SET assigned_at = ?, released_at = ?, release_reason = 'TRANSFER' WHERE admission_id = ?::uuid",
                ts(d3, 10, 30), ts(d1, 14, 0), admissionId);
        jdbc.update("""
                INSERT INTO bed_assignments (organization_id, admission_id, bed_id, assigned_at)
                SELECT organization_id, id, ?, ? FROM admissions WHERE id = ?::uuid
                """, privateBed, ts(d1, 14, 0), admissionId);
        jdbc.update("UPDATE beds SET status = 'OCCUPIED' WHERE id = ?", privateBed);
        jdbc.update("UPDATE beds SET status = 'AVAILABLE' WHERE id = ?", generalBed);
        String invoiceId = readJson(ok(call(BILLING, "GET", "/api/invoices/by-admission/" + admissionId, null))).get("id").asText();

        JsonNode r = readJson(ok(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/room-charges", null)));
        assertThat(r.get("roomDaysPosted").asInt()).isEqualTo(3);
        assertThat(r.get("nursingDaysPosted").asInt()).isEqualTo(3);
        // 2 x general 1500 + 1 x private 5500 (+5% tax) + 3 x nursing 600
        assertThat(r.get("amountPosted").asText()).isEqualTo("10575.00");
        assertThat(r.get("postedThrough").asText()).isEqualTo(d1.toString());

        List<Map<String, Object>> rooms = jdbc.queryForList("""
                SELECT service_date, source_id, total_amount FROM invoice_line_items
                WHERE invoice_id = ?::uuid AND category = 'ROOM' AND status = 'POSTED' ORDER BY service_date
                """, invoiceId);
        assertThat(rooms).hasSize(3);
        assertThat(rooms.get(0).get("service_date").toString()).isEqualTo(d3.toString());
        assertThat(rooms.get(0).get("source_id")).isEqualTo(generalBed);
        assertThat(rooms.get(1).get("source_id")).isEqualTo(generalBed);
        assertThat(rooms.get(2).get("service_date").toString()).isEqualTo(d1.toString());
        assertThat(rooms.get(2).get("source_id")).isEqualTo(privateBed);
        assertThat(new java.math.BigDecimal(rooms.get(2).get("total_amount").toString())).isEqualByComparingTo("5775.00");

        // Second run posts nothing new.
        JsonNode again = readJson(ok(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/room-charges", null)));
        assertThat(again.get("roomDaysPosted").asInt()).isZero();
        assertThat(again.get("nursingDaysPosted").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoice_line_items WHERE invoice_id = ?::uuid AND source_type = 'BED_DAY'",
                Integer.class, invoiceId)).isEqualTo(6);
        // Release the private bed for other tests.
        jdbc.update("UPDATE bed_assignments SET released_at = now(), release_reason = 'CORRECTION' WHERE admission_id = ?::uuid AND released_at IS NULL", admissionId);
        jdbc.update("UPDATE beds SET status = 'AVAILABLE' WHERE id = ?", privateBed);
    }

    @Test
    void sameDayAdmissionAndDischargeIsChargedOneDay() throws Exception {
        UUID patient = registerPatient("Sameday", "Stay" + (int) (Math.random() * 100000));
        UUID bed = availableBed("MWA");
        String admissionId = admit(patient, bed);
        LocalDate today = LocalDate.now(IST);
        jdbc.update("UPDATE admissions SET admitted_at = ?, status = 'DISCHARGED', discharged_at = ? WHERE id = ?::uuid",
                ts(today, 0, 5), ts(today, 0, 50), admissionId);
        jdbc.update("UPDATE bed_assignments SET assigned_at = ?, released_at = ?, release_reason = 'DISCHARGE' WHERE admission_id = ?::uuid",
                ts(today, 0, 10), ts(today, 0, 45), admissionId);
        jdbc.update("UPDATE beds SET status = 'AVAILABLE' WHERE id = ?", bed);
        String invoiceId = readJson(ok(call(BILLING, "GET", "/api/invoices/by-admission/" + admissionId, null))).get("id").asText();
        JsonNode r = readJson(ok(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/room-charges", null)));
        assertThat(r.get("roomDaysPosted").asInt()).isEqualTo(1);
        assertThat(r.get("postedThrough").asText()).isEqualTo(today.toString());
    }

    @Test
    void workflowD_chargesPartialPaymentReceiptAndPatientPortal() throws Exception {
        String ravi = "ravi.sharma@demo.careflow.local";
        String sunita = "sunita.sharma@demo.careflow.local";
        UUID raviPatient = patientId("Ravi");
        String invoiceId = readJson(ok(call(BILLING, "GET", "/api/invoices/by-admission/" + openAdmissionId("Ravi"), null))).get("id").asText();
        // An invoice already exists for the admission: creating another is refused.
        assertThat(call(BILLING, "POST", "/api/invoices", Map.of("admissionId", openAdmissionId("Ravi"))).getResponse().getStatus()).isEqualTo(409);

        JsonNode before = summary(invoiceId);
        charge(invoiceId, "LAB_LFT", "1");     // 900
        charge(invoiceId, "IMG_ECG", "1");     // 300
        JsonNode afterCharges = summary(invoiceId);
        assertThat(new java.math.BigDecimal(afterCharges.get("netCharges").asText()))
                .isEqualByComparingTo(new java.math.BigDecimal(before.get("netCharges").asText()).add(new java.math.BigDecimal("1200.00")));
        java.math.BigDecimal balance = new java.math.BigDecimal(afterCharges.get("balance").asText());
        java.math.BigDecimal part = balance.divide(java.math.BigDecimal.valueOf(2), 2, java.math.RoundingMode.DOWN);
        JsonNode payment = readJson(ok(pay(invoiceId, part.toPlainString(), "UPI", "UPI-WFD", key(), false)));
        String paymentId = payment.at("/payment/id").asText();
        String receipt = payment.at("/payment/receiptNumber").asText();
        assertThat(receipt).startsWith("RCP-");
        assertThat(payment.at("/summary/paymentStatus").asText()).isEqualTo("PARTIALLY_PAID");

        // Receipt PDF for staff.
        MvcResult pdf = call(BILLING, "GET", "/api/payments/" + paymentId + "/receipt.pdf", null);
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
        assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 4)).isEqualTo("%PDF");

        // The patient sees the same paid / pending amounts in the portal, computed by the server.
        JsonNode portal = readJson(ok(call(ravi, "GET", "/api/portal/invoices/" + invoiceId, null)));
        JsonNode staffSummary = summary(invoiceId);
        assertThat(portal.at("/summary/balance").asText()).isEqualTo(staffSummary.get("balance").asText());
        assertThat(portal.at("/summary/netPayments").asText()).isEqualTo(staffSummary.get("netPayments").asText());
        assertThat(new java.math.BigDecimal(portal.at("/summary/balance").asText())).isEqualByComparingTo(balance.subtract(part));
        assertThat(portal.get("payments").findValuesAsText("receiptNumber")).contains(receipt);
        assertThat(portal.get("explanation").get(portal.get("explanation").size() - 1).get("operator").asText()).isEqualTo("=");
        JsonNode bills = readJson(ok(call(ravi, "GET", "/api/portal/patients/" + raviPatient + "/bills", null)));
        assertThat(bills.findValuesAsText("invoiceId")).contains(invoiceId);
        JsonNode history = readJson(ok(call(sunita, "GET", "/api/portal/patients/" + raviPatient + "/payments", null)));
        assertThat(history.findValuesAsText("receiptNumber")).contains(receipt);
        // Patient and caregiver can download their own receipt and statement.
        assertThat(call(ravi, "GET", "/api/payments/" + paymentId + "/receipt.pdf", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(sunita, "GET", "/api/invoices/" + invoiceId + "/invoice.pdf", null).getResponse().getStatus()).isEqualTo(200);
        // Portal users raise a query; they can never change invoices or payments.
        assertThat(call(ravi, "POST", "/api/portal/invoices/" + invoiceId + "/requests", Map.of(
                "requestType", "QUERY", "subject", "ECG charge", "description", "Was the ECG part of the package?")).getResponse().getStatus()).isEqualTo(201);
        assertThat(call(ravi, "POST", "/api/invoices/" + invoiceId + "/payments", Map.of("amount", "1.00", "method", "CASH",
                "payerName", "x", "idempotencyKey", key())).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(ravi, "POST", "/api/invoices/" + invoiceId + "/charges/" + UUID.randomUUID() + "/reverse",
                Map.of("reason", "not mine")).getResponse().getStatus()).isEqualTo(403);
        // Staff see and answer the query.
        JsonNode queue = readJson(ok(call(BILLING, "GET", "/api/billing-requests?status=OPEN&q=ECG", null)));
        String requestId = queue.at("/content/0/id").asText();
        ok(call(BILLING, "POST", "/api/billing-requests/" + requestId + "/respond", Map.of("status", "RESOLVED",
                "response", "Yes, ECG is billed separately at the standard tariff.")));
    }

    @Test
    void patientCannotSeeAnotherPatientsInvoice() throws Exception {
        String ravi = "ravi.sharma@demo.careflow.local";
        String lakshmiInvoice = jdbc.queryForObject("""
                SELECT i.id FROM invoices i JOIN patients p ON p.id = i.patient_id WHERE p.first_name = 'Lakshmi' LIMIT 1
                """, String.class);
        String lakshmiPayment = jdbc.queryForObject("SELECT id FROM payments WHERE invoice_id = ?::uuid LIMIT 1", String.class, lakshmiInvoice);
        assertThat(call(ravi, "GET", "/api/portal/invoices/" + lakshmiInvoice, null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(ravi, "GET", "/api/portal/patients/" + patientId("Lakshmi") + "/bills", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(ravi, "GET", "/api/portal/patients/" + patientId("Lakshmi") + "/payments", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(ravi, "GET", "/api/payments/" + lakshmiPayment + "/receipt.pdf", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(ravi, "GET", "/api/invoices/" + lakshmiInvoice + "/invoice.pdf", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(ravi, "POST", "/api/portal/invoices/" + lakshmiInvoice + "/requests", Map.of(
                "requestType", "QUERY", "subject", "x", "description", "y")).getResponse().getStatus()).isEqualTo(404);
        // Staff endpoints are not available to patients at all.
        assertThat(call(ravi, "GET", "/api/invoices/" + lakshmiInvoice, null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(ravi, "GET", "/api/invoices", null).getResponse().getStatus()).isEqualTo(403);
        // Kiran (Aarav's mother) cannot see Ravi's bills.
        assertThat(call("kiran.reddy@demo.careflow.local", "GET", "/api/portal/patients/" + patientId("Ravi") + "/bills", null)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void nurseCannotRecordPaymentsOrReadInvoices() throws Exception {
        String invoiceId = readJson(ok(call(BILLING, "GET", "/api/invoices/by-admission/" + openAdmissionId("Deepa"), null))).get("id").asText();
        Map<String, Object> body = new HashMap<>(Map.of("amount", "100.00", "method", "CASH", "payerName", "Nurse",
                "idempotencyKey", key(), "allowAdvance", false));
        assertThat(call("nurse.priya@careflow.local", "POST", "/api/invoices/" + invoiceId + "/payments", body).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("nurse.priya@careflow.local", "GET", "/api/invoices", null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("dr.mehta@careflow.local", "GET", "/api/invoices/" + invoiceId, null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("pharmacy@careflow.local", "POST", "/api/invoices/" + invoiceId + "/charges",
                Map.of("tariffId", tariff("LAB_CBC"), "quantity", "1")).getResponse().getStatus()).isEqualTo(403);
        assertThat(call("nurse.priya@careflow.local", "GET", "/api/reports/billing/collections", null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void billingStaffHaveNoClinicalAccess() throws Exception {
        UUID raviAdmission = openAdmissionId("Ravi");
        // Clinical worklists and clinical-scope object access are refused for billing staff.
        assertThat(call(BILLING, "GET", "/api/admissions/my", null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(BILLING, "POST", "/api/admissions/" + raviAdmission + "/care-team",
                Map.of("userId", userId("dr.iyer@careflow.local"), "careRole", "CONSULTANT")).getResponse().getStatus()).isEqualTo(403);
        UUID billingUser = userId(BILLING);
        UUID org = jdbc.queryForObject("SELECT organization_id FROM users WHERE id = ?", UUID.class, billingUser);
        Set<String> perms = rolePermissions.permissionsFor(org, Role.BILLING);
        assertThat(perms).doesNotContain("CLINICAL_READ", "CLINICAL_NOTE_WRITE");
        CurrentUser cu = new CurrentUser(billingUser, org, Role.BILLING, "Suresh Kumar", BILLING, perms, null, UUID.randomUUID(), IST);
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(cu, null, List.of()));
        try {
            assertThatThrownBy(() -> accessPolicy.requireAdmission(raviAdmission, AccessPolicy.Scope.CLINICAL))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(403));
            assertThat(accessPolicy.requireAdmission(raviAdmission, AccessPolicy.Scope.FINANCIAL).getId()).isEqualTo(raviAdmission);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
        // The billing view of the timeline never includes clinical events.
        JsonNode tl = readJson(ok(call(BILLING, "GET", "/api/admissions/" + raviAdmission + "/timeline?size=200", null)));
        assertThat(tl.get("content").findValuesAsText("category")).doesNotContain("CLINICAL", "MEDICATION", "OBSERVATION", "INVESTIGATION", "NURSING");
    }

    @Test
    void reportsDashboardAndOutstandingAreAvailableToFinance() throws Exception {
        JsonNode dash = readJson(ok(call(BILLING, "GET", "/api/dashboard/billing", null)));
        assertThat(dash.has("outstandingTotal")).isTrue();
        assertThat(dash.get("pendingApprovals").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode out = readJson(ok(call(BILLING, "GET", "/api/billing/outstanding?admissionStatus=DISCHARGED", null)));
        assertThat(out.get("buckets").size()).isEqualTo(4);
        JsonNode rohan = null;
        for (JsonNode r : out.at("/rows/content")) {
            if (r.at("/invoice/patientName").asText().startsWith("Rohan")) {
                rohan = r;
            }
        }
        assertThat(rohan).isNotNull();
        assertThat(rohan.get("overdue").asBoolean()).isTrue();
        for (String report : List.of("collections", "charges-vs-payments", "adjustments", "claims", "revenue", "receivables")) {
            assertThat(call(ADMIN, "GET", "/api/reports/billing/" + report, null).getResponse().getStatus()).as(report).isEqualTo(200);
            MvcResult csv = call(BILLING, "GET", "/api/reports/billing/" + report + "/export", null);
            assertThat(csv.getResponse().getStatus()).as(report).isEqualTo(200);
            assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        }
        JsonNode revenue = readJson(ok(call(BILLING, "GET", "/api/reports/billing/revenue", null)));
        assertThat(revenue.get("byCategory").findValuesAsText("key")).contains("ROOM");
    }

    // ------------------------------------------------------------------ helpers

    private String admit(UUID patient, UUID bed) throws Exception {
        MvcResult adm = call(ADMIN, "POST", "/api/admissions", Map.of(
                "patientId", patient, "admissionType", "EMERGENCY", "admissionReason", "Room charge test",
                "departmentId", departmentId("GEN_MED"), "attendingDoctorId", userId("dr.mehta@careflow.local"),
                "requiresIsolation", false, "bedId", bed));
        assertThat(adm.getResponse().getStatus()).as(adm.getResponse().getContentAsString()).isEqualTo(201);
        return readJson(adm).at("/summary/id").asText();
    }

    private static Timestamp ts(LocalDate d, int h, int m) {
        return Timestamp.from(d.atTime(LocalTime.of(h, m)).atZone(IST).toInstant());
    }

    private int paymentLedgerCount(String invoiceId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE invoice_id = ?::uuid AND entry_type = 'PAYMENT'",
                Integer.class, invoiceId);
        return n == null ? 0 : n;
    }
}
