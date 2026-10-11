package com.careflow.hms.billing;

import com.careflow.hms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared helpers for billing integration tests. */
abstract class BillingTestSupport extends IntegrationTestBase {

    static final String BILLING = "billing@careflow.local";
    static final String ACCOUNTS = "accounts@careflow.local";
    static final String ADMIN = "admin@careflow.local";

    protected String newInvoice(String firstName) throws Exception {
        UUID patient = registerPatient(firstName, "Billing" + (int) (Math.random() * 100000));
        MvcResult adm = call(ADMIN, "POST", "/api/admissions", Map.of(
                "patientId", patient, "admissionType", "ELECTIVE", "admissionReason", "Billing integration test",
                "departmentId", departmentId("GEN_MED"), "attendingDoctorId", userId("dr.mehta@careflow.local"),
                "requiresIsolation", false));
        assertThat(adm.getResponse().getStatus()).as(adm.getResponse().getContentAsString()).isEqualTo(201);
        String admissionId = readJson(adm).at("/summary/id").asText();
        return readJson(ok(call(BILLING, "GET", "/api/invoices/by-admission/" + admissionId, null))).get("id").asText();
    }

    protected JsonNode charge(String invoiceId, String tariffCode, String qty) throws Exception {
        return readJson(ok(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/charges",
                Map.of("tariffId", tariff(tariffCode), "quantity", qty))));
    }

    protected static String lastLineId(JsonNode detail) {
        JsonNode lines = detail.get("lines");
        String id = null;
        String latest = "";
        for (JsonNode l : lines) {
            if (l.get("postedAt").asText().compareTo(latest) >= 0) {
                latest = l.get("postedAt").asText();
                id = l.get("id").asText();
            }
        }
        return id;
    }

    protected MvcResult pay(String invoiceId, String amount, String method, String ref, String key, boolean allowAdvance) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        body.put("method", method);
        body.put("methodReference", ref);
        body.put("payerName", "Test Payer");
        body.put("idempotencyKey", key);
        body.put("allowAdvance", allowAdvance);
        return call(BILLING, "POST", "/api/invoices/" + invoiceId + "/payments", body);
    }

    protected JsonNode summary(String invoiceId) throws Exception {
        return readJson(ok(call(BILLING, "GET", "/api/invoices/" + invoiceId, null))).get("summary");
    }

    protected String invoiceNumber(String invoiceId) {
        return jdbc.queryForObject("SELECT invoice_number FROM invoices WHERE id = ?::uuid", String.class, invoiceId);
    }

    protected String tariff(String code) {
        return jdbc.queryForObject("SELECT id FROM service_tariffs WHERE code = ? LIMIT 1", String.class, code);
    }

    protected static String key() {
        return "test-" + UUID.randomUUID();
    }

    protected static Map<String, Object> refundBody(String paymentId, String amount) {
        return Map.of("type", "REFUND", "amount", amount, "reason", "Refund of excess", "relatedPaymentId", paymentId,
                "refundMethod", "UPI");
    }

    protected static MvcResult ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 201);
        return r;
    }

    protected static MvcResult created(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return r;
    }
}
