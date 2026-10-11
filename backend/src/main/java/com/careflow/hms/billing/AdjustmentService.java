package com.careflow.hms.billing;

import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.BillingDtos.AdjustmentRequest;
import com.careflow.hms.billing.BillingDtos.AdjustmentView;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.Money;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.org.SequenceService;
import com.careflow.hms.security.Permissions;
import com.careflow.hms.security.RolePermissionService;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Discounts, write-offs, credit/debit notes and refunds. Every adjustment is requested first and
 * only an APPROVED adjustment posts to the ledger; the approver must be a different person from the
 * requester (segregation of duties).
 */
@Service
@RequiredArgsConstructor
public class AdjustmentService {

    private static final Map<String, String> SORTS = Map.of(
            "requestedAt", "requestedAt", "amount", "amount", "decidedAt", "decidedAt", "adjustmentNumber", "adjustmentNumber");

    private final FinancialAdjustmentRepository adjustments;
    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final SequenceService sequences;
    private final BillingSupport support;
    private final BillingViews views;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;
    private final UserRepository users;
    private final RolePermissionService rolePermissions;

    @Transactional
    public AdjustmentView request(UUID invoiceId, AdjustmentRequest r) {
        Invoice inv = support.lockInvoice(invoiceId);
        BillingSupport.requireNotCancelled(inv);
        BigDecimal amount = Money.scale(r.amount());
        FinancialAdjustment a = new FinancialAdjustment();
        a.setInvoiceId(inv.getId());
        a.setAdjustmentType(r.type());
        a.setAmount(amount);
        a.setReason(r.reason().trim());
        if (r.type() == FinancialAdjustment.Type.REFUND) {
            if (r.relatedPaymentId() == null) {
                throw ApiException.badRequest("PAYMENT_REQUIRED", "Select the payment being refunded.");
            }
            if (r.refundMethod() == null) {
                throw ApiException.badRequest("REFUND_METHOD_REQUIRED", "Select how the refund will be paid out.");
            }
            a.setRelatedPaymentId(r.relatedPaymentId());
            a.setRefundMethod(r.refundMethod().name());
        } else if (r.relatedPaymentId() != null || r.refundMethod() != null) {
            throw ApiException.badRequest("NOT_A_REFUND", "Only refunds reference a payment and a refund method.");
        }
        validateCeilings(inv, a, null);
        a.setAdjustmentNumber(sequences.adjustmentNumber());
        a.setRequestedBy(TenantContext.actorId());
        a.setRequestedAt(BillingClock.now());
        a = adjustments.saveAndFlush(a);
        timeline.record(TimelineService.Entry.builder()
                .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                .eventType("ADJUSTMENT_REQUESTED").category(TimelineEvent.Category.BILLING)
                .title(BillingSupport.label(a.getAdjustmentType()) + " requested: " + BillingSupport.rupees(amount))
                .detail(a.getAdjustmentNumber() + " - " + a.getReason()).status("PENDING")
                .visibility(TimelineEvent.Visibility.BILLING).entityType("ADJUSTMENT").entityId(a.getId()).occurredAt(BillingClock.now()).build());
        notifications.notifyUsers(approvers(inv.getOrganizationId(), a.getRequestedBy()), NotificationRequest.builder()
                .category("BILLING").priority(Notification.Priority.HIGH)
                .title(BillingSupport.label(a.getAdjustmentType()) + " awaiting approval")
                .message(a.getAdjustmentNumber() + " for " + BillingSupport.rupees(amount) + " on " + inv.getInvoiceNumber()
                        + " requested by " + support.name(a.getRequestedBy()) + ".")
                .link("/billing/adjustments").entityType("ADJUSTMENT").entityId(a.getId())
                .dedupeKey("ADJUSTMENT_REQUESTED:" + a.getId()).build());
        audit.record("ADJUSTMENT_REQUESTED", "ADJUSTMENT", a.getId(), inv.getPatientId(),
                a.getAdjustmentNumber() + " " + a.getAdjustmentType() + " " + amount + " on " + inv.getInvoiceNumber() + ": " + a.getReason());
        return views.adjustmentViews(List.of(a)).get(0);
    }

    @Transactional
    public AdjustmentView approve(UUID adjustmentId, String notes) {
        FinancialAdjustment a = find(adjustmentId);
        Invoice inv = support.lockInvoice(a.getInvoiceId());
        requirePending(a);
        UUID actor = TenantContext.actorId();
        if (actor.equals(a.getRequestedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_NOT_ALLOWED",
                    "You requested this adjustment, so a different authorized person must approve it.");
        }
        BillingSupport.requireNotCancelled(inv);
        validateCeilings(inv, a, a.getId());
        a.setStatus(FinancialAdjustment.Status.APPROVED);
        a.setDecidedBy(actor);
        a.setDecidedAt(BillingClock.now());
        a.setDecisionNotes(blankToNull(notes));
        adjustments.saveAndFlush(a);
        BigDecimal signed = a.getAmount().multiply(BigDecimal.valueOf(a.getAdjustmentType().balanceSign()));
        ledger.post(inv, LedgerEntry.EntryType.valueOf(a.getAdjustmentType().name()), signed, "ADJUSTMENT", a.getId(),
                a.getAdjustmentNumber(), BillingSupport.label(a.getAdjustmentType()) + " - " + a.getReason()
                        + (a.getRefundMethod() == null ? "" : " (paid out by " + a.getRefundMethod().toLowerCase().replace('_', ' ') + ")"));
        BigDecimal balance = ledger.balance(inv.getId());
        timeline.record(TimelineService.Entry.builder()
                .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                .eventType("ADJUSTMENT_APPROVED").category(TimelineEvent.Category.BILLING)
                .title(patientLabel(a.getAdjustmentType()) + ": " + BillingSupport.rupees(a.getAmount()))
                .detail("Reference " + a.getAdjustmentNumber() + ". Outstanding balance " + BillingSupport.rupees(balance))
                .status("APPROVED").visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("ADJUSTMENT").entityId(a.getId()).occurredAt(BillingClock.now()).build());
        notifications.notifyUsers(List.of(a.getRequestedBy()), NotificationRequest.builder()
                .category("BILLING").title("Adjustment " + a.getAdjustmentNumber() + " approved")
                .message(BillingSupport.label(a.getAdjustmentType()) + " of " + BillingSupport.rupees(a.getAmount()) + " on "
                        + inv.getInvoiceNumber() + " was approved by " + support.name(actor) + ".")
                .link("/billing/invoices/" + inv.getId()).entityType("ADJUSTMENT").entityId(a.getId())
                .dedupeKey("ADJUSTMENT_DECIDED:" + a.getId()).build());
        notifications.notifyPatient(inv.getPatientId(), NotificationRequest.builder()
                .category("BILLING").title("Your bill was updated")
                .message(patientLabel(a.getAdjustmentType()) + " of " + BillingSupport.rupees(a.getAmount()) + " on invoice "
                        + inv.getInvoiceNumber() + ". Outstanding balance: " + BillingSupport.rupees(balance.max(BigDecimal.ZERO)) + ".")
                .link("/patient/bills/" + inv.getId()).entityType("INVOICE").entityId(inv.getId())
                .dedupeKey("ADJUSTMENT_PATIENT:" + a.getId()).build());
        audit.record("ADJUSTMENT_APPROVED", "ADJUSTMENT", a.getId(), inv.getPatientId(),
                a.getAdjustmentNumber() + " " + a.getAdjustmentType() + " " + a.getAmount() + " approved");
        return views.adjustmentViews(List.of(a)).get(0);
    }

    @Transactional
    public AdjustmentView reject(UUID adjustmentId, String notes) {
        if (notes == null || notes.trim().length() < 3) {
            throw ApiException.badRequest("REASON_REQUIRED", "Document why the adjustment is rejected.");
        }
        FinancialAdjustment a = find(adjustmentId);
        Invoice inv = support.lockInvoice(a.getInvoiceId());
        requirePending(a);
        UUID actor = TenantContext.actorId();
        if (actor.equals(a.getRequestedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_NOT_ALLOWED",
                    "You requested this adjustment, so a different authorized person must decide it.");
        }
        a.setStatus(FinancialAdjustment.Status.REJECTED);
        a.setDecidedBy(actor);
        a.setDecidedAt(BillingClock.now());
        a.setDecisionNotes(notes.trim());
        adjustments.saveAndFlush(a);
        timeline.record(TimelineService.Entry.builder()
                .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                .eventType("ADJUSTMENT_REJECTED").category(TimelineEvent.Category.BILLING)
                .title(BillingSupport.label(a.getAdjustmentType()) + " rejected: " + BillingSupport.rupees(a.getAmount()))
                .detail(a.getAdjustmentNumber() + " - " + notes.trim()).status("REJECTED")
                .visibility(TimelineEvent.Visibility.BILLING).entityType("ADJUSTMENT").entityId(a.getId()).occurredAt(BillingClock.now()).build());
        notifications.notifyUsers(List.of(a.getRequestedBy()), NotificationRequest.builder()
                .category("BILLING").title("Adjustment " + a.getAdjustmentNumber() + " rejected")
                .message(notes.trim()).link("/billing/invoices/" + inv.getId())
                .entityType("ADJUSTMENT").entityId(a.getId()).dedupeKey("ADJUSTMENT_DECIDED:" + a.getId()).build());
        audit.record("ADJUSTMENT_REJECTED", "ADJUSTMENT", a.getId(), inv.getPatientId(), a.getAdjustmentNumber() + ": " + notes.trim());
        return views.adjustmentViews(List.of(a)).get(0);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdjustmentView> search(Collection<FinancialAdjustment.Status> statuses,
                                               Collection<FinancialAdjustment.Type> types, String q,
                                               int page, int size, String sort) {
        var pageable = Paging.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "requestedAt"));
        Page<FinancialAdjustment> result = adjustments.search(TenantContext.requireOrganizationId(),
                statuses == null || statuses.isEmpty() ? EnumSet.allOf(FinancialAdjustment.Status.class) : statuses,
                types == null || types.isEmpty() ? EnumSet.allOf(FinancialAdjustment.Type.class) : types,
                Paging.likePattern(q), pageable);
        return new PageResponse<>(views.adjustmentViews(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    // ------------------------------------------------------------------ rules

    /**
     * Reductions (discount, write-off, credit note) may not exceed the outstanding balance; a refund may
     * not exceed the refunded payment nor the invoice's net payments, minus refunds already issued or
     * pending. Re-checked at approval time under the invoice lock.
     */
    private void validateCeilings(Invoice inv, FinancialAdjustment a, UUID excludeId) {
        LedgerService.BalanceSummary s = ledger.summarize(inv.getId());
        switch (a.getAdjustmentType()) {
            case DISCOUNT, WRITE_OFF, CREDIT_NOTE -> {
                if (a.getAmount().compareTo(s.balance()) > 0) {
                    throw ApiException.rule("EXCEEDS_BALANCE", BillingSupport.label(a.getAdjustmentType())
                            + " cannot exceed the outstanding balance of " + BillingSupport.rupees(s.balance().max(BigDecimal.ZERO)) + ".");
                }
            }
            case REFUND -> {
                Payment p = payments.findByIdAndOrganizationId(a.getRelatedPaymentId(), inv.getOrganizationId())
                        .filter(x -> x.getInvoiceId().equals(inv.getId()))
                        .orElseThrow(() -> ApiException.notFound("Payment"));
                if (p.getStatus() != Payment.Status.COMPLETED) {
                    throw ApiException.rule("PAYMENT_REVERSED", "Receipt " + p.getReceiptNumber() + " was reversed and cannot be refunded.");
                }
                List<FinancialAdjustment> refunds = new ArrayList<>(adjustments.findByInvoiceIdOrderByRequestedAtAsc(inv.getId()).stream()
                        .filter(x -> x.getAdjustmentType() == FinancialAdjustment.Type.REFUND
                                && x.getStatus() != FinancialAdjustment.Status.REJECTED
                                && (excludeId == null || !x.getId().equals(excludeId)))
                        .toList());
                BigDecimal invoiceRefunds = refunds.stream().map(FinancialAdjustment::getAmount).reduce(Money.ZERO, BigDecimal::add);
                BigDecimal paymentRefunds = refunds.stream().filter(x -> p.getId().equals(x.getRelatedPaymentId()))
                        .map(FinancialAdjustment::getAmount).reduce(Money.ZERO, BigDecimal::add);
                BigDecimal invoiceCeiling = s.netPayments().subtract(invoiceRefunds);
                BigDecimal paymentCeiling = p.getAmount().subtract(paymentRefunds);
                BigDecimal ceiling = invoiceCeiling.min(paymentCeiling).max(BigDecimal.ZERO);
                if (a.getAmount().compareTo(ceiling) > 0) {
                    throw ApiException.rule("REFUND_EXCEEDS_PAYMENTS", "The refund cannot exceed "
                            + BillingSupport.rupees(ceiling) + " (net payments received less refunds already issued or pending).");
                }
            }
            case DEBIT_NOTE -> {
                // Increases what is owed; no ceiling beyond approval.
            }
        }
    }

    private FinancialAdjustment find(UUID id) {
        return adjustments.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Adjustment"));
    }

    private static void requirePending(FinancialAdjustment a) {
        if (a.getStatus() != FinancialAdjustment.Status.PENDING) {
            throw ApiException.rule("ADJUSTMENT_DECIDED", "Adjustment " + a.getAdjustmentNumber() + " was already "
                    + a.getStatus().name().toLowerCase() + ".");
        }
    }

    /** Active users whose role holds ADJUSTMENT_APPROVE, excluding the requester. */
    private List<UUID> approvers(UUID orgId, UUID requester) {
        List<UUID> ids = new ArrayList<>();
        for (Role role : Role.values()) {
            if (role == Role.PATIENT || role == Role.SUPER_ADMIN) {
                continue;
            }
            if (rolePermissions.permissionsFor(orgId, role).contains(Permissions.ADJUSTMENT_APPROVE)) {
                users.findByOrganizationIdAndRoleAndStatus(orgId, role, User.Status.ACTIVE).stream()
                        .map(User::getId).filter(id -> !id.equals(requester)).forEach(ids::add);
            }
        }
        return ids;
    }

    static String patientLabel(FinancialAdjustment.Type t) {
        return switch (t) {
            case DISCOUNT -> "Discount applied";
            case WRITE_OFF -> "Amount waived";
            case CREDIT_NOTE -> "Credit note applied";
            case DEBIT_NOTE -> "Additional amount added (debit note)";
            case REFUND -> "Refund issued";
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
