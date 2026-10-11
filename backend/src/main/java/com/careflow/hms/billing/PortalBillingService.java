package com.careflow.hms.billing;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.billing.BillingDtos.AdmissionRef;
import com.careflow.hms.billing.BillingDtos.ExplanationLine;
import com.careflow.hms.billing.BillingDtos.PortalAdjustment;
import com.careflow.hms.billing.BillingDtos.PortalBillCard;
import com.careflow.hms.billing.BillingDtos.PortalCategory;
import com.careflow.hms.billing.BillingDtos.PortalClaim;
import com.careflow.hms.billing.BillingDtos.PortalEstimate;
import com.careflow.hms.billing.BillingDtos.PortalInvoice;
import com.careflow.hms.billing.BillingDtos.PortalLine;
import com.careflow.hms.billing.BillingDtos.PortalPayment;
import com.careflow.hms.billing.BillingDtos.PortalRequest;
import com.careflow.hms.common.Money;
import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only patient / caregiver view of bills. Only posted charges, completed or reversed payments,
 * APPROVED adjustments, submitted claims and clearly labelled estimates are exposed; nothing here
 * lets a patient change an invoice or payment.
 */
@Service
@RequiredArgsConstructor
public class PortalBillingService {

    private final InvoiceRepository invoices;
    private final InvoiceLineItemRepository lines;
    private final PaymentRepository payments;
    private final FinancialAdjustmentRepository adjustments;
    private final InsuranceClaimRepository claims;
    private final EstimatedChargeRepository estimates;
    private final BillingRequestRepository requests;
    private final AdmissionRepository admissions;
    private final DepartmentRepository departments;
    private final OrganizationRepository organizations;
    private final LedgerService ledger;
    private final AccessPolicy access;
    private final BillingSupport support;

    @Transactional(readOnly = true)
    public List<PortalBillCard> bills(UUID patientId) {
        access.requirePatient(patientId, AccessPolicy.Scope.FINANCIAL);
        List<PortalBillCard> result = new ArrayList<>();
        for (Invoice inv : invoices.findByPatientIdOrderByCreatedAtDesc(patientId)) {
            if (inv.getStatus() == Invoice.Status.CANCELLED) {
                continue;
            }
            LedgerService.BalanceSummary s = ledger.summarize(inv.getId());
            Admission a = inv.getAdmissionId() == null ? null : admissions.findById(inv.getAdmissionId()).orElse(null);
            String dept = a == null ? null : departments.findById(a.getDepartmentId()).map(d -> d.getName()).orElse(null);
            BigDecimal netAdjustments = Money.scale(s.discounts().add(s.writeOffs()).add(s.creditNotes()).subtract(s.debitNotes()));
            result.add(new PortalBillCard(inv.getId(), inv.getInvoiceNumber(), inv.getStatus(),
                    a == null ? null : a.getAdmissionNumber(), a == null ? null : a.getStatus(),
                    a == null ? null : a.getAdmittedAt(), a == null ? null : a.getDischargedAt(), dept,
                    s.netCharges(), s.netPayments(), s.insuranceSettled(), s.insuranceApprovedPending(), netAdjustments,
                    s.refunds(), s.balance(), s.paymentStatus(), s.estimatedFutureCharges(),
                    Money.scale(s.balance().max(BigDecimal.ZERO).add(s.estimatedFutureCharges())), inv.getFinalizedAt()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PortalInvoice invoice(UUID invoiceId) {
        Invoice inv = support.requireInvoice(invoiceId);
        if (inv.getStatus() == Invoice.Status.CANCELLED) {
            throw com.careflow.hms.common.ApiException.notFound("Invoice");
        }
        LedgerService.BalanceSummary s = ledger.summarize(inv.getId());
        Patient p = support.patient(inv.getPatientId());
        AdmissionRef admission = support.admissionRef(inv.getAdmissionId());

        // Charges by category: reversed charges are shown (marked) but excluded from totals; reversal lines are hidden.
        Map<String, List<PortalLine>> byCat = new LinkedHashMap<>();
        Map<String, BigDecimal> catTotals = new HashMap<>();
        for (InvoiceLineItem l : lines.findByInvoiceIdOrderByServiceDateAscPostedAtAsc(inv.getId())) {
            if (l.getStatus() == InvoiceLineItem.Status.REVERSAL) {
                continue;
            }
            boolean reversed = l.getStatus() == InvoiceLineItem.Status.REVERSED;
            byCat.computeIfAbsent(l.getCategory().name(), k -> new ArrayList<>()).add(new PortalLine(l.getId(), l.getDescription(),
                    l.getServiceDate(), l.getQuantity(), l.getUnitPrice(), l.getTaxAmount(), l.getTotalAmount(), reversed));
            if (!reversed) {
                catTotals.merge(l.getCategory().name(), l.getTotalAmount(), BigDecimal::add);
            }
        }
        List<PortalCategory> categories = byCat.entrySet().stream()
                .map(e -> new PortalCategory(e.getKey(), Money.scale(catTotals.getOrDefault(e.getKey(), Money.ZERO)), e.getValue()))
                .sorted((x, y) -> y.total().compareTo(x.total())).toList();

        String patientName = p.fullName();
        List<PortalPayment> pays = payments.findByInvoiceIdOrderByReceivedAtAsc(inv.getId()).stream()
                .map(x -> new PortalPayment(x.getId(), inv.getId(), inv.getInvoiceNumber(), patientName, x.getReceiptNumber(),
                        x.getAmount(), x.getMethod(), x.getPayerName(), x.getStatus(), x.isAdvance(), x.getReceivedAt(), x.getReversedAt()))
                .toList();
        List<PortalAdjustment> adjs = adjustments.findByInvoiceIdOrderByRequestedAtAsc(inv.getId()).stream()
                .filter(a -> a.getStatus() == FinancialAdjustment.Status.APPROVED)
                .map(a -> new PortalAdjustment(a.getAdjustmentNumber(), a.getAdjustmentType(),
                        AdjustmentService.patientLabel(a.getAdjustmentType()), a.getAmount(),
                        a.getAdjustmentType().balanceSign(), a.getDecidedAt()))
                .toList();
        List<PortalClaim> insurance = claims.findByInvoiceIdOrderByCreatedAtAsc(inv.getId()).stream()
                .filter(c -> c.getStatus() != InsuranceClaim.Status.DRAFT)
                .map(c -> new PortalClaim(c.getClaimNumber(), c.getPayerName(), c.getStatus(), c.getClaimedAmount(),
                        c.getApprovedAmount(), Money.scale(c.getSettledAmount()), BillingViews.pendingSettlement(c),
                        c.getSubmittedAt(), c.getRejectionReason()))
                .toList();
        List<PortalEstimate> est = estimates.findByInvoiceIdAndActiveTrueOrderByCreatedAtAsc(inv.getId()).stream()
                .map(e -> new PortalEstimate(e.getCategory(), e.getDescription(), e.getEstimatedAmount(), e.getBasis())).toList();

        List<BillingRequest> reqs = requests.findByInvoiceIdOrderByRaisedAtDesc(inv.getId());
        Map<UUID, String> names = support.names(reqs.stream().map(BillingRequest::getRaisedBy).toList());
        Map<UUID, String> lineDesc = new HashMap<>();
        lines.findAllById(reqs.stream().map(BillingRequest::getLineItemId).filter(Objects::nonNull).distinct().toList())
                .forEach(l -> lineDesc.put(l.getId(), l.getDescription()));
        List<PortalRequest> portalRequests = reqs.stream().map(r -> new PortalRequest(r.getId(), r.getRequestType(), r.getSubject(),
                r.getDescription(), lineDesc.get(r.getLineItemId()), r.getStatus(), r.getRaisedAt(), names.get(r.getRaisedBy()),
                r.getResponse(), r.getRespondedAt())).toList();

        String hospital = organizations.findById(TenantContext.requireOrganizationId()).map(o -> o.getName()).orElse("");
        return new PortalInvoice(inv.getId(), inv.getInvoiceNumber(), inv.getStatus(), inv.getCreatedAt(), inv.getFinalizedAt(),
                hospital, support.patientRef(p), admission, s, explanation(s), explanationNotes(s, inv), categories, pays, adjs,
                insurance, est, portalRequests);
    }

    @Transactional(readOnly = true)
    public List<PortalPayment> payments(UUID patientId) {
        Patient p = access.requirePatient(patientId, AccessPolicy.Scope.FINANCIAL);
        Map<UUID, String> invoiceNumbers = new HashMap<>();
        invoices.findByPatientIdOrderByCreatedAtDesc(patientId).forEach(i -> invoiceNumbers.put(i.getId(), i.getInvoiceNumber()));
        return payments.findByPatientIdInOrderByReceivedAtDesc(List.of(patientId)).stream()
                .map(x -> new PortalPayment(x.getId(), x.getInvoiceId(), invoiceNumbers.get(x.getInvoiceId()), p.fullName(),
                        x.getReceiptNumber(), x.getAmount(), x.getMethod(), x.getPayerName(), x.getStatus(), x.isAdvance(),
                        x.getReceivedAt(), x.getReversedAt()))
                .toList();
    }

    /** Step-by-step balance calculation in the order a patient reads it. Zero lines are omitted. */
    static List<ExplanationLine> explanation(LedgerService.BalanceSummary s) {
        List<ExplanationLine> out = new ArrayList<>();
        out.add(new ExplanationLine("Charges posted to your bill", s.grossCharges(), "+",
                "Services already provided and billed (room, nursing, tests, procedures, medicines)."));
        add(out, "Charges cancelled or corrected", s.chargeReversals(), "-", "Charges the hospital reversed after review.");
        add(out, "Additional amounts (debit notes)", s.debitNotes(), "+", "Approved additions to the bill.");
        add(out, "Payments you made", s.payments(), "-", "Every receipt issued for this bill.");
        add(out, "Payments reversed", s.paymentReversals(), "+", "For example a cheque that was not honoured.");
        add(out, "Refunds paid back to you", s.refunds(), "+", "Money returned to you; it is added back to the balance.");
        add(out, "Discounts", s.discounts(), "-", "Approved concessions.");
        add(out, "Amounts waived", s.writeOffs(), "-", "Approved write-offs.");
        add(out, "Credit notes", s.creditNotes(), "-", "Approved credits.");
        add(out, "Paid by your insurer", s.insuranceSettled(), "-", "Insurance or third-party payments received by the hospital.");
        out.add(new ExplanationLine(s.balance().signum() < 0 ? "Credit in your favour" : "Outstanding balance",
                s.balance().abs(), "=", s.balance().signum() < 0
                ? "You have paid more than the posted charges; the hospital will adjust or refund this credit."
                : "The amount still due today, calculated by the hospital's ledger."));
        return out;
    }

    private static void add(List<ExplanationLine> out, String label, BigDecimal amount, String op, String hint) {
        if (amount != null && amount.signum() != 0) {
            out.add(new ExplanationLine(label, amount, op, hint));
        }
    }

    static List<String> explanationNotes(LedgerService.BalanceSummary s, Invoice inv) {
        List<String> notes = new ArrayList<>();
        if (s.insuranceApprovedPending().signum() > 0) {
            notes.add("Your insurer has approved " + BillingSupport.rupees(s.insuranceApprovedPending())
                    + " that has not been paid to the hospital yet. It will reduce your balance once received, so it is not deducted above.");
        }
        if (s.estimatedFutureCharges().signum() > 0) {
            notes.add("Estimated upcoming charges of about " + BillingSupport.rupees(s.estimatedFutureCharges())
                    + " are shown separately for planning. Estimates are not part of your balance and may change.");
        }
        if (inv.getStatus() == Invoice.Status.OPEN) {
            notes.add("This bill is still open: charges for your ongoing stay (such as each day's room and nursing care) are added as services are provided.");
        } else {
            notes.add("This is your final bill. No new charges will be added unless the hospital reopens it and informs you.");
        }
        notes.add("If something looks wrong, raise a query on this page and the billing team will respond.");
        return notes;
    }
}
