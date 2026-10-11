package com.careflow.hms.billing;

import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.BillingDtos.PaymentResult;
import com.careflow.hms.billing.BillingDtos.PaymentView;
import com.careflow.hms.billing.BillingDtos.RecordPaymentRequest;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.Money;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.org.SequenceService;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Payment capture. Idempotent per client-supplied key: a retry (or a concurrent duplicate submit)
 * with the same key returns the original payment and never posts a second ledger entry. The invoice
 * row is locked for the duration of the payment so balance checks are race-free.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Map<String, String> SORTS = Map.of(
            "receivedAt", "receivedAt", "amount", "amount", "receiptNumber", "receiptNumber", "method", "method");

    private final PaymentRepository payments;
    private final FinancialAdjustmentRepository adjustments;
    private final LedgerService ledger;
    private final SequenceService sequences;
    private final BillingSupport support;
    private final BillingViews views;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    @Transactional
    public PaymentResult record(UUID invoiceId, RecordPaymentRequest r) {
        UUID orgId = TenantContext.requireOrganizationId();
        String key = r.idempotencyKey().trim();
        Optional<Payment> replay = payments.findByOrganizationIdAndIdempotencyKey(orgId, key);
        if (replay.isPresent()) {
            return replay(replay.get(), invoiceId);
        }
        Invoice inv = support.lockInvoice(invoiceId);
        // A concurrent submit with the same key may have committed while we waited for the lock.
        replay = payments.findByOrganizationIdAndIdempotencyKey(orgId, key);
        if (replay.isPresent()) {
            return replay(replay.get(), invoiceId);
        }
        BillingSupport.requireNotCancelled(inv);
        if (r.method() != Payment.Method.CASH && (r.methodReference() == null || r.methodReference().isBlank())) {
            throw ApiException.badRequest("METHOD_REFERENCE_REQUIRED",
                    "A transaction / instrument reference is required for " + BillingSupport.method(r.method()) + " payments.");
        }
        BigDecimal amount = Money.scale(r.amount());
        BigDecimal balance = ledger.balance(inv.getId());
        boolean advance = amount.compareTo(balance) > 0;
        if (advance && !r.allowAdvance()) {
            throw ApiException.rule("OVERPAYMENT", "The amount exceeds the outstanding balance of "
                    + BillingSupport.rupees(balance.max(BigDecimal.ZERO))
                    + ". Confirm it as an advance deposit to keep the excess as a credit balance.");
        }
        Payment p = new Payment();
        p.setInvoiceId(inv.getId());
        p.setPatientId(inv.getPatientId());
        p.setReceiptNumber(sequences.receiptNumber());
        p.setAmount(amount);
        p.setMethod(r.method());
        p.setMethodReference(blankToNull(r.methodReference()));
        p.setPayerName(r.payerName().trim());
        p.setIdempotencyKey(key);
        p.setNotes(blankToNull(r.notes()));
        p.setAdvance(advance);
        p.setReceivedBy(TenantContext.actorId());
        p.setReceivedAt(BillingClock.now());
        p = payments.saveAndFlush(p);
        ledger.post(inv, LedgerEntry.EntryType.PAYMENT, amount.negate(), "PAYMENT", p.getId(), p.getReceiptNumber(),
                "Payment received - " + BillingSupport.method(p.getMethod()) + (p.getMethodReference() == null ? "" : " " + p.getMethodReference())
                        + (advance ? " (includes advance deposit)" : ""));
        BigDecimal newBalance = ledger.balance(inv.getId());
        timeline.record(TimelineService.Entry.builder()
                .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                .eventType("PAYMENT_RECEIVED").category(TimelineEvent.Category.BILLING)
                .title("Payment received: " + BillingSupport.rupees(amount))
                .detail("Receipt " + p.getReceiptNumber() + " via " + BillingSupport.method(p.getMethod())
                        + ". Outstanding balance " + BillingSupport.rupees(newBalance))
                .status("COMPLETED").visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("PAYMENT").entityId(p.getId()).occurredAt(p.getReceivedAt()).build());
        notifications.notifyPatient(inv.getPatientId(), NotificationRequest.builder()
                .category("BILLING").title("Payment received")
                .message("We received " + BillingSupport.rupees(amount) + " towards invoice " + inv.getInvoiceNumber()
                        + " (receipt " + p.getReceiptNumber() + "). "
                        + (newBalance.signum() > 0 ? "Outstanding balance: " + BillingSupport.rupees(newBalance) + "."
                        : newBalance.signum() < 0 ? "Credit balance: " + BillingSupport.rupees(newBalance.negate()) + "."
                        : "Your bill is fully paid. Thank you."))
                .link("/patient/bills/" + inv.getId()).entityType("PAYMENT").entityId(p.getId())
                .dedupeKey("PAYMENT:" + p.getId()).build());
        audit.record("PAYMENT_RECORDED", "PAYMENT", p.getId(), inv.getPatientId(),
                p.getReceiptNumber() + " " + amount + " " + p.getMethod() + " on " + inv.getInvoiceNumber() + (advance ? " (advance)" : ""));
        return new PaymentResult(views.paymentView(p), false, ledger.summarize(inv.getId()));
    }

    /** Used after a unique-key race outside the transaction: returns the payment that won. */
    @Transactional(readOnly = true)
    public Optional<PaymentResult> findReplay(UUID invoiceId, String idempotencyKey) {
        return payments.findByOrganizationIdAndIdempotencyKey(TenantContext.requireOrganizationId(), idempotencyKey.trim())
                .map(p -> replay(p, invoiceId));
    }

    private PaymentResult replay(Payment p, UUID invoiceId) {
        if (!p.getInvoiceId().equals(invoiceId)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "This payment key was already used for a different invoice. Start a new payment.");
        }
        support.requireInvoice(invoiceId);
        return new PaymentResult(views.paymentView(p), true, ledger.summarize(invoiceId));
    }

    /** Reverses a payment (e.g. bounced cheque, card chargeback) with a PAYMENT_REVERSAL ledger entry. */
    @Transactional
    public PaymentView reverse(UUID paymentId, String reason) {
        UUID orgId = TenantContext.requireOrganizationId();
        Payment p = payments.findByIdAndOrganizationId(paymentId, orgId).orElseThrow(() -> ApiException.notFound("Payment"));
        Invoice inv = support.lockInvoice(p.getInvoiceId());
        if (p.getStatus() != Payment.Status.COMPLETED) {
            throw ApiException.rule("PAYMENT_ALREADY_REVERSED", "Receipt " + p.getReceiptNumber() + " has already been reversed.");
        }
        boolean refunded = adjustments.findByInvoiceIdOrderByRequestedAtAsc(inv.getId()).stream()
                .anyMatch(a -> p.getId().equals(a.getRelatedPaymentId()) && a.getStatus() != FinancialAdjustment.Status.REJECTED);
        if (refunded) {
            throw ApiException.rule("PAYMENT_HAS_REFUND", "A refund has been requested or issued against this payment; it cannot be reversed.");
        }
        p.setStatus(Payment.Status.REVERSED);
        p.setReversedBy(TenantContext.actorId());
        p.setReversedAt(BillingClock.now());
        p.setReversalReason(reason.trim());
        payments.saveAndFlush(p);
        ledger.post(inv, LedgerEntry.EntryType.PAYMENT_REVERSAL, p.getAmount(), "PAYMENT", p.getId(), p.getReceiptNumber(),
                "Payment reversed - " + reason.trim());
        BigDecimal newBalance = ledger.balance(inv.getId());
        timeline.record(TimelineService.Entry.builder()
                .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                .eventType("PAYMENT_REVERSED").category(TimelineEvent.Category.BILLING)
                .title("Payment reversed: " + BillingSupport.rupees(p.getAmount()))
                .detail("Receipt " + p.getReceiptNumber() + " - " + reason.trim())
                .status("REVERSED").visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("PAYMENT").entityId(p.getId()).occurredAt(BillingClock.now()).build());
        notifications.notifyPatient(inv.getPatientId(), NotificationRequest.builder()
                .category("BILLING").priority(Notification.Priority.HIGH).title("Payment reversed")
                .message("Receipt " + p.getReceiptNumber() + " for " + BillingSupport.rupees(p.getAmount())
                        + " was reversed (" + reason.trim() + "). Outstanding balance: " + BillingSupport.rupees(newBalance) + ".")
                .link("/patient/bills/" + inv.getId()).entityType("PAYMENT").entityId(p.getId())
                .dedupeKey("PAYMENT_REVERSED:" + p.getId()).build());
        audit.record("PAYMENT_REVERSED", "PAYMENT", p.getId(), inv.getPatientId(), p.getReceiptNumber() + ": " + reason.trim());
        return views.paymentView(p);
    }

    @Transactional(readOnly = true)
    public PaymentView get(UUID paymentId) {
        Payment p = payments.findByIdAndOrganizationId(paymentId, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Payment"));
        support.requireInvoice(p.getInvoiceId());
        return views.paymentView(p);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentView> search(LocalDate from, LocalDate to, Collection<Payment.Method> methods,
                                            Collection<Payment.Status> statuses, String q, int page, int size, String sort) {
        ZoneId zone = support.zone();
        var pageable = Paging.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "receivedAt"));
        Page<Payment> result = payments.search(TenantContext.requireOrganizationId(),
                from == null ? java.time.Instant.parse("2000-01-01T00:00:00Z") : from.atStartOfDay(zone).toInstant(),
                to == null ? java.time.Instant.now().plusSeconds(86_400) : to.plusDays(1).atStartOfDay(zone).toInstant(),
                methods == null || methods.isEmpty() ? EnumSet.allOf(Payment.Method.class) : methods,
                statuses == null || statuses.isEmpty() ? EnumSet.allOf(Payment.Status.class) : statuses,
                Paging.likePattern(q), pageable);
        return new PageResponse<>(views.paymentViews(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
