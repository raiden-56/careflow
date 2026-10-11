package com.careflow.hms.billing;

import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.BillingDtos.ClaimApproveRequest;
import com.careflow.hms.billing.BillingDtos.ClaimRequest;
import com.careflow.hms.billing.BillingDtos.ClaimSettleRequest;
import com.careflow.hms.billing.BillingDtos.ClaimView;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.Money;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
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
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Insurance / third-party claim lifecycle: DRAFT -> SUBMITTED -> UNDER_REVIEW -> APPROVED |
 * PARTIALLY_APPROVED | REJECTED -> SETTLED. Only settlements reduce the balance; each (partial)
 * settlement is its own INSURANCE_SETTLEMENT ledger entry with a unique reference.
 */
@Service
@RequiredArgsConstructor
public class ClaimService {

    private static final Map<String, String> SORTS = Map.of(
            "createdAt", "createdAt", "updatedAt", "updatedAt", "claimedAmount", "claimedAmount", "claimNumber", "claimNumber");

    private final InsuranceClaimRepository claims;
    private final ClaimSettlementRepository settlements;
    private final InvoiceRepository invoices;
    private final LedgerService ledger;
    private final SequenceService sequences;
    private final BillingSupport support;
    private final BillingViews views;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    @Transactional
    public ClaimView create(UUID invoiceId, ClaimRequest r) {
        Invoice inv = support.lockInvoice(invoiceId);
        BillingSupport.requireNotCancelled(inv);
        InsuranceClaim c = new InsuranceClaim();
        c.setInvoiceId(inv.getId());
        c.setPatientId(inv.getPatientId());
        c.setClaimNumber(sequences.claimNumber());
        c.setPayerName(r.payerName().trim());
        c.setPayerType(r.payerType());
        c.setPolicyNumber(r.policyNumber().trim());
        c.setPreAuthNumber(r.preAuthNumber() == null || r.preAuthNumber().isBlank() ? null : r.preAuthNumber().trim());
        c.setClaimedAmount(Money.scale(r.claimedAmount()));
        c.setNotes(r.notes() == null || r.notes().isBlank() ? null : r.notes().trim());
        c.setStatus(InsuranceClaim.Status.DRAFT);
        c.setCreatedBy(TenantContext.actorId());
        c.setCreatedAt(BillingClock.now());
        c = claims.saveAndFlush(c);
        if (inv.getPayerType() == Invoice.PayerType.SELF) {
            inv.setPayerType(switch (r.payerType()) {
                case INSURER, TPA -> Invoice.PayerType.INSURANCE;
                case CORPORATE -> Invoice.PayerType.CORPORATE;
                case GOVERNMENT -> Invoice.PayerType.GOVERNMENT;
            });
            invoices.save(inv);
        }
        record(inv, c, "CLAIM_CREATED", "Claim " + c.getClaimNumber() + " drafted with " + c.getPayerName(),
                "Claimed " + BillingSupport.rupees(c.getClaimedAmount()), TimelineEvent.Visibility.BILLING);
        audit.record("CLAIM_CREATED", "CLAIM", c.getId(), inv.getPatientId(),
                c.getClaimNumber() + " " + c.getPayerName() + " " + c.getClaimedAmount());
        return view(c);
    }

    @Transactional
    public ClaimView submit(UUID claimId) {
        InsuranceClaim c = find(claimId);
        Invoice inv = support.lockInvoice(c.getInvoiceId());
        requireStatus(c, EnumSet.of(InsuranceClaim.Status.DRAFT), "submitted");
        c.setStatus(InsuranceClaim.Status.SUBMITTED);
        c.setSubmittedAt(BillingClock.now());
        claims.saveAndFlush(c);
        record(inv, c, "CLAIM_SUBMITTED", "Insurance claim submitted to " + c.getPayerName(),
                "Claim " + c.getClaimNumber() + " for " + BillingSupport.rupees(c.getClaimedAmount()), TimelineEvent.Visibility.PATIENT);
        audit.record("CLAIM_SUBMITTED", "CLAIM", c.getId(), inv.getPatientId(), c.getClaimNumber());
        return view(c);
    }

    @Transactional
    public ClaimView markUnderReview(UUID claimId) {
        InsuranceClaim c = find(claimId);
        Invoice inv = support.lockInvoice(c.getInvoiceId());
        requireStatus(c, EnumSet.of(InsuranceClaim.Status.SUBMITTED), "marked under review");
        c.setStatus(InsuranceClaim.Status.UNDER_REVIEW);
        claims.saveAndFlush(c);
        record(inv, c, "CLAIM_UNDER_REVIEW", "Claim " + c.getClaimNumber() + " under review", c.getPayerName(),
                TimelineEvent.Visibility.BILLING);
        audit.record("CLAIM_UNDER_REVIEW", "CLAIM", c.getId(), inv.getPatientId(), c.getClaimNumber());
        return view(c);
    }

    @Transactional
    public ClaimView approve(UUID claimId, ClaimApproveRequest r) {
        InsuranceClaim c = find(claimId);
        Invoice inv = support.lockInvoice(c.getInvoiceId());
        requireStatus(c, EnumSet.of(InsuranceClaim.Status.SUBMITTED, InsuranceClaim.Status.UNDER_REVIEW), "approved");
        BigDecimal approved = Money.scale(r.approvedAmount());
        if (approved.compareTo(c.getClaimedAmount()) > 0) {
            throw ApiException.rule("APPROVED_EXCEEDS_CLAIMED", "The approved amount cannot exceed the claimed amount of "
                    + BillingSupport.rupees(c.getClaimedAmount()) + ".");
        }
        c.setApprovedAmount(approved);
        c.setStatus(approved.compareTo(c.getClaimedAmount()) == 0 ? InsuranceClaim.Status.APPROVED : InsuranceClaim.Status.PARTIALLY_APPROVED);
        c.setDecidedAt(BillingClock.now());
        if (r.notes() != null && !r.notes().isBlank()) {
            c.setNotes(r.notes().trim());
        }
        claims.saveAndFlush(c);
        String title = (c.getStatus() == InsuranceClaim.Status.APPROVED ? "Insurance claim approved" : "Insurance claim partially approved")
                + ": " + BillingSupport.rupees(approved);
        record(inv, c, "CLAIM_APPROVED", title, c.getPayerName() + " approved " + BillingSupport.rupees(approved) + " of "
                + BillingSupport.rupees(c.getClaimedAmount()) + "; awaiting settlement.", TimelineEvent.Visibility.PATIENT);
        notifications.notifyPatient(inv.getPatientId(), NotificationRequest.builder()
                .category("BILLING").title(title)
                .message(c.getPayerName() + " approved " + BillingSupport.rupees(approved) + " for invoice " + inv.getInvoiceNumber()
                        + ". The amount will reduce your balance once the insurer pays the hospital.")
                .link("/patient/bills/" + inv.getId()).entityType("CLAIM").entityId(c.getId())
                .dedupeKey("CLAIM_APPROVED:" + c.getId()).build());
        audit.record("CLAIM_APPROVED", "CLAIM", c.getId(), inv.getPatientId(), c.getClaimNumber() + " approved " + approved);
        return view(c);
    }

    @Transactional
    public ClaimView reject(UUID claimId, String reason) {
        InsuranceClaim c = find(claimId);
        Invoice inv = support.lockInvoice(c.getInvoiceId());
        requireStatus(c, EnumSet.of(InsuranceClaim.Status.SUBMITTED, InsuranceClaim.Status.UNDER_REVIEW), "rejected");
        c.setStatus(InsuranceClaim.Status.REJECTED);
        c.setRejectionReason(reason.trim());
        c.setDecidedAt(BillingClock.now());
        claims.saveAndFlush(c);
        record(inv, c, "CLAIM_REJECTED", "Insurance claim rejected by " + c.getPayerName(), reason.trim(),
                TimelineEvent.Visibility.PATIENT);
        notifications.notifyPatient(inv.getPatientId(), NotificationRequest.builder()
                .category("BILLING").title("Insurance claim not approved")
                .message(c.getPayerName() + " did not approve claim " + c.getClaimNumber() + ": " + reason.trim()
                        + ". Please contact the billing desk to discuss payment options.")
                .link("/patient/bills/" + inv.getId()).entityType("CLAIM").entityId(c.getId())
                .dedupeKey("CLAIM_REJECTED:" + c.getId()).build());
        audit.record("CLAIM_REJECTED", "CLAIM", c.getId(), inv.getPatientId(), c.getClaimNumber() + ": " + reason.trim());
        return view(c);
    }

    @Transactional
    public ClaimView settle(UUID claimId, ClaimSettleRequest r) {
        InsuranceClaim c = find(claimId);
        Invoice inv = support.lockInvoice(c.getInvoiceId());
        BillingSupport.requireNotCancelled(inv);
        requireStatus(c, EnumSet.of(InsuranceClaim.Status.APPROVED, InsuranceClaim.Status.PARTIALLY_APPROVED), "settled");
        BigDecimal amount = Money.scale(r.amount());
        BigDecimal remaining = BillingViews.pendingSettlement(c);
        if (amount.compareTo(remaining) > 0) {
            throw ApiException.rule("SETTLEMENT_EXCEEDS_APPROVED", "The settlement cannot exceed the approved amount still pending ("
                    + BillingSupport.rupees(remaining) + ").");
        }
        String reference = r.reference().trim();
        if (settlements.existsByClaimIdAndSettlementReferenceIgnoreCase(c.getId(), reference)) {
            throw ApiException.conflict("DUPLICATE_SETTLEMENT", "A settlement with reference " + reference + " is already recorded for this claim.");
        }
        Instant now = BillingClock.now();
        Instant settledAt = r.settledAt() == null ? now : r.settledAt();
        if (settledAt.isAfter(now.plusSeconds(300))) {
            throw ApiException.badRequest("FUTURE_SETTLEMENT", "The settlement date cannot be in the future.");
        }
        ClaimSettlement s = new ClaimSettlement();
        s.setClaimId(c.getId());
        s.setInvoiceId(inv.getId());
        s.setAmount(amount);
        s.setSettlementReference(reference);
        s.setNotes(r.notes() == null || r.notes().isBlank() ? null : r.notes().trim());
        s.setSettledAt(settledAt);
        s.setRecordedBy(TenantContext.actorId());
        s.setRecordedAt(now);
        s = settlements.saveAndFlush(s);
        c.setSettledAmount(Money.scale(Money.nz(c.getSettledAmount()).add(amount)));
        c.setSettledAt(settledAt);
        if (c.getSettledAmount().compareTo(c.getApprovedAmount()) >= 0) {
            c.setStatus(InsuranceClaim.Status.SETTLED);
        }
        claims.saveAndFlush(c);
        ledger.post(inv, LedgerEntry.EntryType.INSURANCE_SETTLEMENT, amount.negate(), "CLAIM_SETTLEMENT", s.getId(), reference,
                "Insurance settlement from " + c.getPayerName() + " (claim " + c.getClaimNumber() + ")");
        BigDecimal balance = ledger.balance(inv.getId());
        record(inv, c, "CLAIM_SETTLED", "Insurance payment received: " + BillingSupport.rupees(amount),
                c.getPayerName() + ", claim " + c.getClaimNumber() + ". Outstanding balance " + BillingSupport.rupees(balance),
                TimelineEvent.Visibility.PATIENT);
        notifications.notifyPatient(inv.getPatientId(), NotificationRequest.builder()
                .category("BILLING").title("Insurance payment received")
                .message(c.getPayerName() + " paid " + BillingSupport.rupees(amount) + " towards invoice " + inv.getInvoiceNumber()
                        + ". Outstanding balance: " + BillingSupport.rupees(balance.max(BigDecimal.ZERO)) + ".")
                .link("/patient/bills/" + inv.getId()).entityType("CLAIM").entityId(c.getId())
                .dedupeKey("CLAIM_SETTLED:" + s.getId()).build());
        audit.record("CLAIM_SETTLED", "CLAIM", c.getId(), inv.getPatientId(),
                c.getClaimNumber() + " settlement " + amount + " ref " + reference);
        return view(c);
    }

    @Transactional(readOnly = true)
    public ClaimView get(UUID claimId) {
        InsuranceClaim c = find(claimId);
        support.requireInvoice(c.getInvoiceId());
        return view(c);
    }

    @Transactional(readOnly = true)
    public PageResponse<ClaimView> search(Collection<InsuranceClaim.Status> statuses, String q, int page, int size, String sort) {
        var pageable = Paging.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<InsuranceClaim> result = claims.search(TenantContext.requireOrganizationId(),
                statuses == null || statuses.isEmpty() ? EnumSet.allOf(InsuranceClaim.Status.class) : statuses,
                Paging.likePattern(q), pageable);
        return new PageResponse<>(views.claimViews(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    private void record(Invoice inv, InsuranceClaim c, String type, String title, String detail, TimelineEvent.Visibility v) {
        timeline.record(TimelineService.Entry.builder()
                .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                .eventType(type).category(TimelineEvent.Category.BILLING).title(title).detail(detail)
                .status(c.getStatus().name()).visibility(v).entityType("CLAIM").entityId(c.getId()).occurredAt(BillingClock.now()).build());
    }

    private InsuranceClaim find(UUID id) {
        return claims.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Claim"));
    }

    private static void requireStatus(InsuranceClaim c, Set<InsuranceClaim.Status> allowed, String action) {
        if (!allowed.contains(c.getStatus())) {
            throw ApiException.rule("INVALID_CLAIM_TRANSITION", "Claim " + c.getClaimNumber() + " is "
                    + c.getStatus().name().toLowerCase().replace('_', ' ') + " and cannot be " + action + ".");
        }
    }

    private ClaimView view(InsuranceClaim c) {
        return views.claimViews(List.of(c)).get(0);
    }
}
