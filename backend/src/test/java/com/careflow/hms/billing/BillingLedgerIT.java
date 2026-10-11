package com.careflow.hms.billing;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Ledger math, balance summary, payment status, adjustments (segregation of duties, refund ceiling) and claims. */
class BillingLedgerIT extends BillingTestSupport {

    @Test
    void balanceSummaryFollowsTheLedger() throws Exception {
        String invoiceId = newInvoice("Ledgermath");
        charge(invoiceId, "LAB_CBC", "2");          // 900.00
        String echoLine = lastLineId(charge(invoiceId, "IMG_ECHO", "1")); // 2500.00
        charge(invoiceId, "CONSUMABLE_IV_SET", "1"); // 180.00 + 12% tax = 201.60
        ok(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/charges/" + echoLine + "/reverse",
                Map.of("reason", "Ordered in error")));
        JsonNode pay = readJson(ok(pay(invoiceId, "500.00", "CASH", null, key(), false)));
        String paymentId = pay.at("/payment/id").asText();

        // Discount: requested by one person, approved by another.
        String discount = readJson(created(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/adjustments",
                Map.of("type", "DISCOUNT", "amount", "100.00", "reason", "Staff family concession")))).get("id").asText();
        // Pending adjustments never touch the balance.
        assertThat(summary(invoiceId).get("balance").asText()).isEqualTo("601.60");
        ok(call(ACCOUNTS, "POST", "/api/adjustments/" + discount + "/approve", Map.of("notes", "OK")));

        // Insurance: approved 300, settled 200 -> 100 pending settlement (not deducted).
        String claim = readJson(created(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/claims", Map.of(
                "payerName", "Test Insurer (fictional)", "payerType", "INSURER", "policyNumber", "POL-1",
                "claimedAmount", "300.00")))).get("id").asText();
        ok(call(BILLING, "POST", "/api/claims/" + claim + "/submit", null));
        JsonNode approved = readJson(ok(call(BILLING, "POST", "/api/claims/" + claim + "/approve", Map.of("approvedAmount", "300.00"))));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        ok(call(BILLING, "POST", "/api/claims/" + claim + "/settle", Map.of("amount", "200.00", "reference", "UTR-" + UUID.randomUUID())));

        // Refund 50 against the payment.
        String refund = readJson(created(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/adjustments", Map.of(
                "type", "REFUND", "amount", "50.00", "reason", "Refund overcharged consumable", "relatedPaymentId", paymentId,
                "refundMethod", "CASH")))).get("id").asText();
        ok(call(ACCOUNTS, "POST", "/api/adjustments/" + refund + "/approve", Map.of()));

        JsonNode s = summary(invoiceId);
        assertThat(s.get("grossCharges").asText()).isEqualTo("3601.60");
        assertThat(s.get("chargeReversals").asText()).isEqualTo("2500.00");
        assertThat(s.get("netCharges").asText()).isEqualTo("1101.60");
        assertThat(s.get("netPayments").asText()).isEqualTo("500.00");
        assertThat(s.get("discounts").asText()).isEqualTo("100.00");
        assertThat(s.get("insuranceSettled").asText()).isEqualTo("200.00");
        assertThat(s.get("insuranceApprovedPending").asText()).isEqualTo("100.00");
        assertThat(s.get("refunds").asText()).isEqualTo("50.00");
        // 1101.60 - 500 - 100 - 200 + 50
        assertThat(s.get("balance").asText()).isEqualTo("351.60");
        assertThat(s.get("paymentStatus").asText()).isEqualTo("PARTIALLY_PAID");
        BigDecimal ledgerSum = jdbc.queryForObject("SELECT SUM(amount) FROM ledger_entries WHERE invoice_id = ?::uuid",
                BigDecimal.class, invoiceId);
        assertThat(ledgerSum).isEqualByComparingTo("351.60");
        // The invoice list (SQL aggregation) agrees with the service summary.
        JsonNode list = readJson(ok(call(BILLING, "GET", "/api/invoices?q=" + invoiceNumber(invoiceId), null)));
        assertThat(list.at("/content/0/balance").asText()).isEqualTo("351.60");
        assertThat(list.at("/content/0/paymentStatus").asText()).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    void paidOnlyWhenBalanceIsExactlyZero() throws Exception {
        String invoiceId = newInvoice("Paidzero");
        assertThat(summary(invoiceId).get("paymentStatus").asText()).isEqualTo("NO_CHARGES");
        charge(invoiceId, "LAB_CBC", "1"); // 450.00
        assertThat(summary(invoiceId).get("paymentStatus").asText()).isEqualTo("UNPAID");
        ok(pay(invoiceId, "449.99", "UPI", "UPI-REF-1", key(), false));
        JsonNode s = summary(invoiceId);
        assertThat(s.get("balance").asText()).isEqualTo("0.01");
        assertThat(s.get("paymentStatus").asText()).isEqualTo("PARTIALLY_PAID");
        ok(pay(invoiceId, "0.01", "CASH", null, key(), false));
        s = summary(invoiceId);
        assertThat(s.get("balance").asText()).isEqualTo("0.00");
        assertThat(s.get("paymentStatus").asText()).isEqualTo("PAID");
    }

    @Test
    void overpaymentRequiresExplicitAdvance() throws Exception {
        String invoiceId = newInvoice("Overpay");
        charge(invoiceId, "LAB_CBC", "1"); // 450
        MvcResult r = pay(invoiceId, "500.00", "CASH", null, key(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(r).get("code").asText()).isEqualTo("OVERPAYMENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE invoice_id = ?::uuid", Integer.class, invoiceId)).isZero();
        JsonNode ok = readJson(ok(pay(invoiceId, "500.00", "CASH", null, key(), true)));
        assertThat(ok.at("/payment/advance").asBoolean()).isTrue();
        assertThat(ok.at("/summary/balance").asText()).isEqualTo("-50.00");
        assertThat(ok.at("/summary/paymentStatus").asText()).isEqualTo("CREDIT_BALANCE");
        // Non-cash payments need a reference.
        assertThat(pay(invoiceId, "10.00", "CARD", " ", key(), true).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void approverMustDifferFromRequesterAndOnlyApprovedAdjustmentsPost() throws Exception {
        String invoiceId = newInvoice("Segregation");
        charge(invoiceId, "IMG_ECHO", "1"); // 2500
        String adj = readJson(created(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/adjustments",
                Map.of("type", "WRITE_OFF", "amount", "500.00", "reason", "Charity care")))).get("id").asText();
        MvcResult self = call(BILLING, "POST", "/api/adjustments/" + adj + "/approve", Map.of());
        assertThat(self.getResponse().getStatus()).isEqualTo(403);
        assertThat(readJson(self).get("code").asText()).isEqualTo("SELF_APPROVAL_NOT_ALLOWED");
        assertThat(summary(invoiceId).get("balance").asText()).isEqualTo("2500.00");
        // A nurse has no approval permission at all.
        assertThat(call("nurse.priya@careflow.local", "POST", "/api/adjustments/" + adj + "/approve", Map.of())
                .getResponse().getStatus()).isEqualTo(403);
        JsonNode approved = readJson(ok(call(ACCOUNTS, "POST", "/api/adjustments/" + adj + "/approve", Map.of())));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(summary(invoiceId).get("balance").asText()).isEqualTo("2000.00");
        assertThat(summary(invoiceId).get("writeOffs").asText()).isEqualTo("500.00");
        // A decided adjustment cannot be decided again.
        assertThat(call(ACCOUNTS, "POST", "/api/adjustments/" + adj + "/approve", Map.of()).getResponse().getStatus()).isEqualTo(422);
        // A discount larger than the balance is refused.
        assertThat(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/adjustments",
                Map.of("type", "DISCOUNT", "amount", "2000.01", "reason", "Too much")).getResponse().getStatus()).isEqualTo(422);
        // Rejected adjustments never post.
        String rejected = readJson(created(call(ACCOUNTS, "POST", "/api/invoices/" + invoiceId + "/adjustments",
                Map.of("type", "DISCOUNT", "amount", "100.00", "reason", "Requested by family")))).get("id").asText();
        ok(call(BILLING, "POST", "/api/adjustments/" + rejected + "/reject", Map.of("notes", "Not eligible under policy")));
        assertThat(summary(invoiceId).get("balance").asText()).isEqualTo("2000.00");
    }

    @Test
    void refundCannotExceedNetPaymentsLessPriorRefunds() throws Exception {
        String invoiceId = newInvoice("Refundceiling");
        charge(invoiceId, "LAB_CBC", "1"); // 450
        String paymentId = readJson(ok(pay(invoiceId, "1000.00", "CARD", "POS-1", key(), true))).at("/payment/id").asText();
        Map<String, Object> tooMuch = refundBody(paymentId, "1000.01");
        assertThat(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/adjustments", tooMuch).getResponse().getStatus()).isEqualTo(422);
        String first = readJson(created(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/adjustments",
                refundBody(paymentId, "550.00")))).get("id").asText();
        // Pending refunds count toward the ceiling too: only 450 remains.
        MvcResult second = call(BILLING, "POST", "/api/invoices/" + invoiceId + "/adjustments", refundBody(paymentId, "450.01"));
        assertThat(second.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(second).get("code").asText()).isEqualTo("REFUND_EXCEEDS_PAYMENTS");
        ok(call(ACCOUNTS, "POST", "/api/adjustments/" + first + "/approve", Map.of()));
        JsonNode s = summary(invoiceId);
        assertThat(s.get("refunds").asText()).isEqualTo("550.00");
        assertThat(s.get("balance").asText()).isEqualTo("0.00");
        assertThat(s.get("paymentStatus").asText()).isEqualTo("PAID");
        // Refunds need a payment and a refund method.
        assertThat(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/adjustments",
                Map.of("type", "REFUND", "amount", "10.00", "reason", "No payment")).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void claimLifecycleEnforcesAmountsAndPartialSettlements() throws Exception {
        String invoiceId = newInvoice("Claimflow");
        charge(invoiceId, "PROC_ANGIO", "1"); // 18000
        String claim = readJson(created(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/claims", Map.of(
                "payerName", "Fictional TPA", "payerType", "TPA", "policyNumber", "P-9", "claimedAmount", "15000.00")))).get("id").asText();
        // Cannot approve a draft.
        assertThat(call(BILLING, "POST", "/api/claims/" + claim + "/approve", Map.of("approvedAmount", "100.00")).getResponse().getStatus()).isEqualTo(422);
        ok(call(BILLING, "POST", "/api/claims/" + claim + "/submit", null));
        ok(call(BILLING, "POST", "/api/claims/" + claim + "/review", null));
        assertThat(call(BILLING, "POST", "/api/claims/" + claim + "/approve", Map.of("approvedAmount", "15000.01")).getResponse().getStatus()).isEqualTo(422);
        JsonNode c = readJson(ok(call(BILLING, "POST", "/api/claims/" + claim + "/approve", Map.of("approvedAmount", "12000.00"))));
        assertThat(c.get("status").asText()).isEqualTo("PARTIALLY_APPROVED");
        assertThat(summary(invoiceId).get("insuranceApprovedPending").asText()).isEqualTo("12000.00");
        ok(call(BILLING, "POST", "/api/claims/" + claim + "/settle", Map.of("amount", "7000.00", "reference", "UTR-A")));
        // Same reference twice is a duplicate; more than the remaining approved amount is refused.
        assertThat(call(BILLING, "POST", "/api/claims/" + claim + "/settle", Map.of("amount", "1.00", "reference", "UTR-A")).getResponse().getStatus()).isEqualTo(409);
        assertThat(call(BILLING, "POST", "/api/claims/" + claim + "/settle", Map.of("amount", "5000.01", "reference", "UTR-B")).getResponse().getStatus()).isEqualTo(422);
        c = readJson(ok(call(BILLING, "POST", "/api/claims/" + claim + "/settle", Map.of("amount", "5000.00", "reference", "UTR-B"))));
        assertThat(c.get("status").asText()).isEqualTo("SETTLED");
        assertThat(c.get("settlements").size()).isEqualTo(2);
        JsonNode s = summary(invoiceId);
        assertThat(s.get("insuranceSettled").asText()).isEqualTo("12000.00");
        assertThat(s.get("insuranceApprovedPending").asText()).isEqualTo("0.00");
        assertThat(s.get("balance").asText()).isEqualTo("6000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE invoice_id = ?::uuid AND entry_type = 'INSURANCE_SETTLEMENT'",
                Integer.class, invoiceId)).isEqualTo(2);
    }

    @Test
    void finalizedInvoiceRejectsChargesButAcceptsPaymentsAndCanBeReopened() throws Exception {
        String invoiceId = newInvoice("Finalize");
        charge(invoiceId, "LAB_CBC", "1");
        ok(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/estimates", Map.of(
                "category", "ROOM", "description", "One more day", "estimatedAmount", "2100.00", "basis", "Expected stay")));
        assertThat(summary(invoiceId).get("estimatedFutureCharges").asText()).isEqualTo("2100.00");
        assertThat(summary(invoiceId).get("balance").asText()).isEqualTo("450.00");
        JsonNode fin = readJson(ok(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/finalize", Map.of())));
        assertThat(fin.get("status").asText()).isEqualTo("FINALIZED");
        assertThat(fin.get("reviewedByName").asText()).isNotBlank();
        assertThat(fin.at("/summary/estimatedFutureCharges").asText()).isEqualTo("0.00");
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?::uuid", String.class, invoiceId)).isEqualTo("FINALIZED");
        MvcResult blocked = call(BILLING, "POST", "/api/invoices/" + invoiceId + "/charges",
                Map.of("tariffId", tariff("LAB_CRP"), "quantity", "1"));
        assertThat(blocked.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(blocked).get("code").asText()).isEqualTo("INVOICE_NOT_OPEN");
        ok(pay(invoiceId, "450.00", "CASH", null, key(), false));
        assertThat(summary(invoiceId).get("paymentStatus").asText()).isEqualTo("PAID");
        assertThat(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/reopen", Map.of()).getResponse().getStatus()).isEqualTo(400);
        JsonNode reopened = readJson(ok(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/reopen", Map.of("reason", "Late lab charge"))));
        assertThat(reopened.get("status").asText()).isEqualTo("OPEN");
        assertThat(reopened.get("reopenReason").asText()).isEqualTo("Late lab charge");
        charge(invoiceId, "LAB_CRP", "1");
        assertThat(summary(invoiceId).get("balance").asText()).isEqualTo("550.00");
    }

    @Test
    void priceOverrideRequiresReasonAndFutureServiceDatesAreRejected() throws Exception {
        String invoiceId = newInvoice("Override");
        Map<String, Object> body = new HashMap<>(Map.of("tariffId", tariff("LAB_CBC"), "quantity", "1", "unitPriceOverride", "300.00"));
        assertThat(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/charges", body).getResponse().getStatus()).isEqualTo(400);
        body.put("overrideReason", "Package rate agreed with the family");
        JsonNode d = readJson(ok(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/charges", body)));
        assertThat(d.at("/summary/netCharges").asText()).isEqualTo("300.00");
        Map<String, Object> future = Map.of("tariffId", tariff("LAB_CBC"), "quantity", "1",
                "serviceDate", java.time.LocalDate.now().plusDays(3).toString());
        assertThat(call(BILLING, "POST", "/api/invoices/" + invoiceId + "/charges", future).getResponse().getStatus()).isEqualTo(400);
    }
}
