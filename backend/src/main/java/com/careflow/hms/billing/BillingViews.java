package com.careflow.hms.billing;

import com.careflow.hms.billing.BillingDtos.AdjustmentView;
import com.careflow.hms.billing.BillingDtos.CategoryTotal;
import com.careflow.hms.billing.BillingDtos.ClaimView;
import com.careflow.hms.billing.BillingDtos.EstimateView;
import com.careflow.hms.billing.BillingDtos.InvoiceDetail;
import com.careflow.hms.billing.BillingDtos.LedgerView;
import com.careflow.hms.billing.BillingDtos.LineView;
import com.careflow.hms.billing.BillingDtos.PaymentView;
import com.careflow.hms.billing.BillingDtos.RequestView;
import com.careflow.hms.billing.BillingDtos.SettlementView;
import com.careflow.hms.common.Money;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds API views for billing records (batched name / invoice / patient lookups). */
@Component
@RequiredArgsConstructor
public class BillingViews {

    private final BillingSupport support;
    private final InvoiceRepository invoices;
    private final InvoiceLineItemRepository lines;
    private final EstimatedChargeRepository estimates;
    private final PaymentRepository payments;
    private final FinancialAdjustmentRepository adjustments;
    private final InsuranceClaimRepository claims;
    private final ClaimSettlementRepository settlements;
    private final LedgerEntryRepository ledgerEntries;
    private final BillingRequestRepository requests;
    private final ServiceTariffRepository tariffs;
    private final PatientRepository patients;
    private final LedgerService ledger;

    public InvoiceDetail detail(Invoice inv) {
        List<InvoiceLineItem> lineItems = lines.findByInvoiceIdOrderByServiceDateAscPostedAtAsc(inv.getId());
        List<EstimatedCharge> est = estimates.findByInvoiceIdOrderByCreatedAtAsc(inv.getId());
        List<Payment> pays = payments.findByInvoiceIdOrderByReceivedAtAsc(inv.getId());
        List<FinancialAdjustment> adjs = adjustments.findByInvoiceIdOrderByRequestedAtAsc(inv.getId());
        List<InsuranceClaim> cls = claims.findByInvoiceIdOrderByCreatedAtAsc(inv.getId());
        List<LedgerEntry> entries = ledgerEntries.findByInvoiceIdOrderByCreatedAtAsc(inv.getId());
        List<BillingRequest> reqs = requests.findByInvoiceIdOrderByRaisedAtDesc(inv.getId());

        Set<UUID> userIds = new HashSet<>();
        userIds.add(inv.getCreatedBy());
        userIds.add(inv.getFinalizedBy());
        userIds.add(inv.getReviewedBy());
        userIds.add(inv.getReopenedBy());
        lineItems.forEach(l -> userIds.add(l.getPostedBy()));
        est.forEach(e -> userIds.add(e.getCreatedBy()));
        entries.forEach(e -> userIds.add(e.getCreatedBy()));
        Map<UUID, String> names = support.names(userIds);

        Map<UUID, UUID> reversedBy = new HashMap<>();
        lineItems.stream().filter(l -> l.getReversesLineId() != null).forEach(l -> reversedBy.put(l.getReversesLineId(), l.getId()));
        Map<UUID, String> tariffCodes = new HashMap<>();
        tariffs.findAllById(lineItems.stream().map(InvoiceLineItem::getTariffId).filter(Objects::nonNull).distinct().toList())
                .forEach(t -> tariffCodes.put(t.getId(), t.getCode()));
        List<LineView> lineViews = lineItems.stream().map(l -> new LineView(l.getId(), l.getTariffId(),
                tariffCodes.get(l.getTariffId()), l.getCategory().name(), l.getDescription(), l.getServiceDate(),
                l.getQuantity(), l.getUnitPrice(), l.getTaxRate(), l.getNetAmount(), l.getTaxAmount(), l.getTotalAmount(),
                l.getSourceType(), l.getStatus(), l.getReversesLineId(), reversedBy.get(l.getId()), l.getReason(),
                names.get(l.getPostedBy()), l.getPostedAt())).toList();

        Map<String, BigDecimal> catAmount = new LinkedHashMap<>();
        Map<String, Integer> catCount = new HashMap<>();
        for (InvoiceLineItem l : lineItems) {
            catAmount.merge(l.getCategory().name(), l.getTotalAmount(), BigDecimal::add);
            if (l.getStatus() == InvoiceLineItem.Status.POSTED) {
                catCount.merge(l.getCategory().name(), 1, Integer::sum);
            }
        }
        List<CategoryTotal> categoryTotals = catAmount.entrySet().stream()
                .map(e -> new CategoryTotal(e.getKey(), Money.scale(e.getValue()), catCount.getOrDefault(e.getKey(), 0)))
                .sorted((x, y) -> y.amount().compareTo(x.amount()))
                .toList();

        List<EstimateView> estViews = est.stream().map(e -> new EstimateView(e.getId(), e.getCategory(), e.getDescription(),
                e.getEstimatedAmount(), e.getBasis(), e.isActive(), names.get(e.getCreatedBy()), e.getCreatedAt())).toList();

        List<LedgerView> ledgerViews = new ArrayList<>();
        BigDecimal running = Money.ZERO;
        for (LedgerEntry e : entries) {
            running = running.add(e.getAmount());
            ledgerViews.add(new LedgerView(e.getId(), e.getEntryType(), e.getAmount(), Money.scale(running),
                    e.getReferenceType(), e.getReferenceId(), e.getReferenceNumber(), e.getDescription(),
                    names.get(e.getCreatedBy()), e.getCreatedAt()));
        }

        LedgerService.BalanceSummary summary = ledger.summarize(inv.getId());
        BigDecimal refundsCommitted = adjs.stream()
                .filter(x -> x.getAdjustmentType() == FinancialAdjustment.Type.REFUND && x.getStatus() != FinancialAdjustment.Status.REJECTED)
                .map(FinancialAdjustment::getAmount).reduce(Money.ZERO, BigDecimal::add);
        BigDecimal refundable = Money.scale(summary.netPayments().subtract(refundsCommitted).max(BigDecimal.ZERO));
        BigDecimal pending = adjs.stream().filter(x -> x.getStatus() == FinancialAdjustment.Status.PENDING)
                .map(x -> x.getAmount().multiply(BigDecimal.valueOf(x.getAdjustmentType().balanceSign())))
                .reduce(Money.ZERO, BigDecimal::add);

        Patient p = support.patient(inv.getPatientId());
        return new InvoiceDetail(inv.getId(), inv.getInvoiceNumber(), inv.getStatus(), inv.getPayerType(), inv.getNotes(),
                inv.getCreatedAt(), names.get(inv.getCreatedBy()), inv.getFinalizedAt(), names.get(inv.getFinalizedBy()),
                inv.getReviewedAt(), names.get(inv.getReviewedBy()), inv.getReopenedAt(), names.get(inv.getReopenedBy()),
                inv.getReopenReason(), inv.getRoomChargesPostedUntil(), inv.getVersion(), support.patientRef(p),
                support.admissionRef(inv.getAdmissionId()), summary, categoryTotals, lineViews, estViews,
                paymentViews(pays), adjustmentViews(adjs), claimViews(cls), ledgerViews, requestViews(reqs),
                refundable, Money.scale(pending));
    }

    // ------------------------------------------------------------------ batched views

    public List<PaymentView> paymentViews(List<Payment> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> userIds = new HashSet<>();
        list.forEach(p -> {
            userIds.add(p.getReceivedBy());
            userIds.add(p.getReversedBy());
        });
        Map<UUID, String> names = support.names(userIds);
        Map<UUID, Invoice> invs = invoiceMap(list.stream().map(Payment::getInvoiceId).toList());
        Map<UUID, Patient> pts = patientMap(list.stream().map(Payment::getPatientId).toList());
        Map<UUID, BigDecimal> refunded = new HashMap<>();
        for (UUID invoiceId : invs.keySet()) {
            adjustments.findByInvoiceIdOrderByRequestedAtAsc(invoiceId).stream()
                    .filter(a -> a.getAdjustmentType() == FinancialAdjustment.Type.REFUND
                            && a.getStatus() == FinancialAdjustment.Status.APPROVED && a.getRelatedPaymentId() != null)
                    .forEach(a -> refunded.merge(a.getRelatedPaymentId(), a.getAmount(), BigDecimal::add));
        }
        return list.stream().map(p -> {
            Invoice inv = invs.get(p.getInvoiceId());
            Patient pt = pts.get(p.getPatientId());
            return new PaymentView(p.getId(), p.getInvoiceId(), inv == null ? null : inv.getInvoiceNumber(), p.getPatientId(),
                    pt == null ? null : pt.fullName(), pt == null ? null : pt.getMrn(), p.getReceiptNumber(), p.getAmount(),
                    p.getMethod(), p.getMethodReference(), p.getPayerName(), p.getStatus(), p.isAdvance(), p.getNotes(),
                    names.get(p.getReceivedBy()), p.getReceivedAt(), names.get(p.getReversedBy()), p.getReversedAt(),
                    p.getReversalReason(), Money.scale(refunded.getOrDefault(p.getId(), Money.ZERO)));
        }).toList();
    }

    public PaymentView paymentView(Payment p) {
        return paymentViews(List.of(p)).get(0);
    }

    public List<AdjustmentView> adjustmentViews(List<FinancialAdjustment> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> userIds = new HashSet<>();
        list.forEach(a -> {
            userIds.add(a.getRequestedBy());
            userIds.add(a.getDecidedBy());
        });
        Map<UUID, String> names = support.names(userIds);
        Map<UUID, Invoice> invs = invoiceMap(list.stream().map(FinancialAdjustment::getInvoiceId).toList());
        Map<UUID, Patient> pts = patientMap(invs.values().stream().map(Invoice::getPatientId).toList());
        Map<UUID, String> receipts = new HashMap<>();
        payments.findAllById(list.stream().map(FinancialAdjustment::getRelatedPaymentId).filter(Objects::nonNull).distinct().toList())
                .forEach(p -> receipts.put(p.getId(), p.getReceiptNumber()));
        return list.stream().map(a -> {
            Invoice inv = invs.get(a.getInvoiceId());
            Patient pt = inv == null ? null : pts.get(inv.getPatientId());
            return new AdjustmentView(a.getId(), a.getInvoiceId(), inv == null ? null : inv.getInvoiceNumber(),
                    inv == null ? null : inv.getPatientId(), pt == null ? null : pt.fullName(), pt == null ? null : pt.getMrn(),
                    a.getAdjustmentNumber(), a.getAdjustmentType(), a.getAmount(), a.getAdjustmentType().balanceSign(),
                    a.getReason(), a.getRelatedPaymentId(), receipts.get(a.getRelatedPaymentId()), a.getRefundMethod(),
                    a.getStatus(), a.getRequestedBy(), names.get(a.getRequestedBy()), a.getRequestedAt(),
                    names.get(a.getDecidedBy()), a.getDecidedAt(), a.getDecisionNotes(), a.getVersion());
        }).toList();
    }

    public List<ClaimView> claimViews(List<InsuranceClaim> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ClaimSettlement>> byClaim = settlements.findByClaimIdInOrderBySettledAtAsc(
                list.stream().map(InsuranceClaim::getId).toList()).stream()
                .collect(Collectors.groupingBy(ClaimSettlement::getClaimId, LinkedHashMap::new, Collectors.toList()));
        Set<UUID> userIds = new HashSet<>();
        list.forEach(c -> userIds.add(c.getCreatedBy()));
        byClaim.values().forEach(l -> l.forEach(s -> userIds.add(s.getRecordedBy())));
        Map<UUID, String> names = support.names(userIds);
        Map<UUID, Invoice> invs = invoiceMap(list.stream().map(InsuranceClaim::getInvoiceId).toList());
        Map<UUID, Patient> pts = patientMap(list.stream().map(InsuranceClaim::getPatientId).toList());
        return list.stream().map(c -> {
            Invoice inv = invs.get(c.getInvoiceId());
            Patient pt = pts.get(c.getPatientId());
            List<SettlementView> sv = byClaim.getOrDefault(c.getId(), List.of()).stream()
                    .map(s -> new SettlementView(s.getId(), s.getAmount(), s.getSettlementReference(), s.getNotes(),
                            s.getSettledAt(), names.get(s.getRecordedBy()))).toList();
            return new ClaimView(c.getId(), c.getInvoiceId(), inv == null ? null : inv.getInvoiceNumber(), c.getPatientId(),
                    pt == null ? null : pt.fullName(), pt == null ? null : pt.getMrn(), c.getClaimNumber(), c.getPayerName(),
                    c.getPayerType(), c.getPolicyNumber(), c.getPreAuthNumber(), c.getClaimedAmount(), c.getApprovedAmount(),
                    Money.scale(c.getSettledAmount()), pendingSettlement(c), c.getStatus(), c.getSubmittedAt(),
                    c.getDecidedAt(), c.getSettledAt(), c.getRejectionReason(), c.getNotes(), names.get(c.getCreatedBy()),
                    c.getCreatedAt(), c.getUpdatedAt(), sv, c.getVersion());
        }).toList();
    }

    public static BigDecimal pendingSettlement(InsuranceClaim c) {
        if (c.getStatus() != InsuranceClaim.Status.APPROVED && c.getStatus() != InsuranceClaim.Status.PARTIALLY_APPROVED) {
            return Money.ZERO;
        }
        return Money.scale(Money.nz(c.getApprovedAmount()).subtract(Money.nz(c.getSettledAmount())).max(BigDecimal.ZERO));
    }

    public List<RequestView> requestViews(List<BillingRequest> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> userIds = new HashSet<>();
        list.forEach(r -> {
            userIds.add(r.getRaisedBy());
            userIds.add(r.getRespondedBy());
        });
        Map<UUID, String> names = support.names(userIds);
        Map<UUID, Invoice> invs = invoiceMap(list.stream().map(BillingRequest::getInvoiceId).toList());
        Map<UUID, Patient> pts = patientMap(list.stream().map(BillingRequest::getPatientId).toList());
        Map<UUID, String> lineDesc = new HashMap<>();
        lines.findAllById(list.stream().map(BillingRequest::getLineItemId).filter(Objects::nonNull).distinct().toList())
                .forEach(l -> lineDesc.put(l.getId(), l.getDescription() + " (" + l.getServiceDate() + ")"));
        return list.stream().map(r -> {
            Invoice inv = invs.get(r.getInvoiceId());
            Patient pt = pts.get(r.getPatientId());
            return new RequestView(r.getId(), r.getInvoiceId(), inv == null ? null : inv.getInvoiceNumber(), r.getPatientId(),
                    pt == null ? null : pt.fullName(), pt == null ? null : pt.getMrn(), r.getLineItemId(),
                    lineDesc.get(r.getLineItemId()), r.getRequestType(), r.getSubject(), r.getDescription(), r.getStatus(),
                    names.get(r.getRaisedBy()), r.getRaisedAt(), r.getResponse(), names.get(r.getRespondedBy()),
                    r.getRespondedAt(), r.getVersion());
        }).toList();
    }

    private Map<UUID, Invoice> invoiceMap(Collection<UUID> ids) {
        return invoices.findAllById(ids.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Invoice::getId, Function.identity()));
    }

    private Map<UUID, Patient> patientMap(Collection<UUID> ids) {
        return patients.findByIdIn(ids.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Patient::getId, Function.identity()));
    }
}
