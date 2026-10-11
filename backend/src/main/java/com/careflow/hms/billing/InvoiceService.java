package com.careflow.hms.billing;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.BillingDtos.AddChargeRequest;
import com.careflow.hms.billing.BillingDtos.EstimateRequest;
import com.careflow.hms.billing.BillingDtos.InvoiceDetail;
import com.careflow.hms.billing.BillingDtos.InvoiceSummary;
import com.careflow.hms.billing.BillingDtos.RoomChargeResult;
import com.careflow.hms.billing.BillingDtos.TariffOption;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.Money;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Invoice workspace: search, detail, charges, estimates, room charges, finalize / reopen. */
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final InvoiceLineItemRepository lines;
    private final EstimatedChargeRepository estimates;
    private final ServiceTariffRepository tariffs;
    private final ChargeService charges;
    private final RoomChargeService roomCharges;
    private final LedgerService ledger;
    private final InvoiceQueries queries;
    private final BillingViews views;
    private final BillingSupport support;
    private final AccessPolicy access;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<InvoiceSummary> search(Collection<Invoice.Status> statuses,
                                               Collection<LedgerService.PaymentStatus> paymentStatuses,
                                               LocalDate from, LocalDate to, UUID departmentId, String q,
                                               int page, int size, String sort) {
        ZoneId zone = support.zone();
        InvoiceQueries.Filter f = InvoiceQueries.Filter.builder()
                .orgId(TenantContext.requireOrganizationId())
                .statuses(statuses).paymentStatuses(paymentStatuses)
                .from(from == null ? null : from.atStartOfDay(zone).toInstant())
                .to(to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant())
                .departmentId(departmentId).q(q).build();
        PageResponse<InvoiceQueries.Row> rows = queries.page(f, zone, page, size, sort, "createdAt,desc");
        return new PageResponse<>(rows.content().stream().map(InvoiceQueries.Row::summary).toList(), rows.page(),
                rows.size(), rows.totalElements(), rows.totalPages());
    }

    @Transactional(readOnly = true)
    public InvoiceDetail detail(UUID invoiceId) {
        Invoice inv = support.requireInvoice(invoiceId);
        return views.detail(inv);
    }

    @Transactional(readOnly = true)
    public InvoiceDetail byAdmission(UUID admissionId) {
        access.requireAdmission(admissionId, AccessPolicy.Scope.FINANCIAL);
        Invoice inv = invoices.findActiveByAdmission(admissionId).orElseThrow(() -> ApiException.notFound("Invoice"));
        return views.detail(inv);
    }

    @Transactional(readOnly = true)
    public List<TariffOption> tariffs(String q, ServiceTariff.Category category) {
        return tariffs.search(TenantContext.requireOrganizationId(), category, true, Paging.likePattern(q),
                        PageRequest.of(0, 50, Sort.by("category", "name"))).stream()
                .map(t -> new TariffOption(t.getId(), t.getCode(), t.getName(), t.getCategory(), t.getUnitPrice(),
                        t.getTaxRate(), t.getBillingUnit(), t.getRoomType()))
                .toList();
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public InvoiceDetail createForAdmission(UUID admissionId) {
        Admission a = access.requireAdmission(admissionId, AccessPolicy.Scope.FINANCIAL);
        if (a.getStatus() == Admission.Status.CANCELLED) {
            throw ApiException.rule("ADMISSION_CANCELLED", "A cancelled admission cannot be billed.");
        }
        if (invoices.findActiveByAdmission(admissionId).isPresent()) {
            throw ApiException.conflict("INVOICE_EXISTS", "An active invoice already exists for this admission.");
        }
        Invoice inv = charges.openInvoiceForAdmission(admissionId);
        audit.record("INVOICE_CREATED", "INVOICE", inv.getId(), inv.getPatientId(), inv.getInvoiceNumber());
        return views.detail(inv);
    }

    @Transactional
    public InvoiceDetail addCharge(UUID invoiceId, AddChargeRequest r) {
        Invoice inv = support.lockInvoice(invoiceId);
        BillingSupport.requireOpen(inv);
        ServiceTariff tariff = tariffs.findByIdAndOrganizationId(r.tariffId(), inv.getOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Tariff"));
        if (!tariff.isActive()) {
            throw ApiException.rule("TARIFF_INACTIVE", "Tariff " + tariff.getCode() + " is not active.");
        }
        LocalDate today = support.today();
        LocalDate serviceDate = r.serviceDate() == null ? today : r.serviceDate();
        if (serviceDate.isAfter(today)) {
            throw ApiException.badRequest("FUTURE_SERVICE_DATE", "The service date cannot be in the future. Use an estimate for planned services.");
        }
        Admission a = inv.getAdmissionId() == null ? null : access.requireAdmission(inv.getAdmissionId(), AccessPolicy.Scope.FINANCIAL);
        if (a != null) {
            LocalDate admitted = a.getAdmittedAt().atZone(support.zone()).toLocalDate();
            if (serviceDate.isBefore(admitted)) {
                throw ApiException.badRequest("SERVICE_DATE_BEFORE_ADMISSION", "The service date is before the admission date (" + admitted + ").");
            }
        }
        boolean override = r.unitPriceOverride() != null && r.unitPriceOverride().compareTo(tariff.getUnitPrice()) != 0;
        if (override && (r.overrideReason() == null || r.overrideReason().trim().length() < 3)) {
            throw ApiException.badRequest("OVERRIDE_REASON_REQUIRED", "A reason is required when overriding the tariff price.");
        }
        String description = tariff.getName() + (r.note() == null || r.note().isBlank() ? "" : " - " + r.note().trim());
        String reason = override ? "Price override from " + BillingSupport.rupees(tariff.getUnitPrice()) + ": " + r.overrideReason().trim() : null;
        InvoiceLineItem line = charges.postCharge(ChargeService.ChargeRequest.builder()
                .invoiceId(inv.getId()).tariffId(tariff.getId()).category(tariff.getCategory()).description(description)
                .quantity(r.quantity()).unitPrice(override ? Money.scale(r.unitPriceOverride()) : null)
                .serviceDate(serviceDate).sourceType(InvoiceLineItem.SourceType.MANUAL).reason(reason).build());
        timeline.record(TimelineService.Entry.builder()
                .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                .eventType("CHARGE_POSTED").category(TimelineEvent.Category.BILLING)
                .title("Charge posted: " + line.getDescription())
                .detail(BillingSupport.rupees(line.getTotalAmount()) + " on " + inv.getInvoiceNumber() + (override ? " (price override)" : ""))
                .visibility(TimelineEvent.Visibility.BILLING).entityType("INVOICE").entityId(inv.getId()).occurredAt(BillingClock.now()).build());
        audit.record(override ? "CHARGE_POSTED_OVERRIDE" : "CHARGE_POSTED", "INVOICE", inv.getId(), inv.getPatientId(),
                line.getDescription() + " x" + line.getQuantity() + " = " + line.getTotalAmount() + (reason == null ? "" : " | " + reason));
        return views.detail(inv);
    }

    @Transactional
    public InvoiceDetail reverseCharge(UUID invoiceId, UUID lineId, String reason) {
        Invoice inv = support.lockInvoice(invoiceId);
        BillingSupport.requireOpen(inv);
        InvoiceLineItem original = lines.findById(lineId)
                .filter(l -> l.getInvoiceId().equals(inv.getId()))
                .orElseThrow(() -> ApiException.notFound("Charge"));
        InvoiceLineItem rev = charges.reverseCharge(original.getId(), reason.trim());
        timeline.record(TimelineService.Entry.builder()
                .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                .eventType("CHARGE_REVERSED").category(TimelineEvent.Category.BILLING)
                .title("Charge reversed: " + original.getDescription())
                .detail(BillingSupport.rupees(original.getTotalAmount()) + " - " + reason.trim())
                .visibility(TimelineEvent.Visibility.BILLING).entityType("INVOICE").entityId(inv.getId()).occurredAt(BillingClock.now()).build());
        audit.record("CHARGE_REVERSED", "INVOICE", inv.getId(), inv.getPatientId(),
                original.getDescription() + " " + original.getTotalAmount() + " reversed by line " + rev.getId() + ": " + reason);
        return views.detail(inv);
    }

    @Transactional
    public RoomChargeResult postRoomCharges(UUID invoiceId) {
        Invoice inv = support.lockInvoice(invoiceId);
        BillingSupport.requireOpen(inv);
        if (inv.getAdmissionId() == null) {
            throw ApiException.rule("NO_ADMISSION", "Room charges apply only to admission invoices.");
        }
        RoomChargeResult r = roomCharges.post(inv, false, false);
        if (r.roomDaysPosted() + r.nursingDaysPosted() > 0) {
            timeline.record(TimelineService.Entry.builder()
                    .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                    .eventType("ROOM_CHARGES_POSTED").category(TimelineEvent.Category.BILLING)
                    .title("Room charges posted through " + r.postedThrough())
                    .detail(r.roomDaysPosted() + " room day(s), " + r.nursingDaysPosted() + " nursing day(s): "
                            + BillingSupport.rupees(r.amountPosted()))
                    .visibility(TimelineEvent.Visibility.BILLING).entityType("INVOICE").entityId(inv.getId()).occurredAt(BillingClock.now()).build());
            audit.record("ROOM_CHARGES_POSTED", "INVOICE", inv.getId(), inv.getPatientId(),
                    r.roomDaysPosted() + " room / " + r.nursingDaysPosted() + " nursing days through " + r.postedThrough());
        }
        return r;
    }

    @Transactional
    public InvoiceDetail addEstimate(UUID invoiceId, EstimateRequest r) {
        Invoice inv = support.lockInvoice(invoiceId);
        BillingSupport.requireOpen(inv);
        EstimatedCharge e = new EstimatedCharge();
        e.setInvoiceId(inv.getId());
        e.setCategory(r.category().name());
        e.setDescription(r.description().trim());
        e.setEstimatedAmount(Money.scale(r.estimatedAmount()));
        e.setBasis(r.basis().trim());
        e.setCreatedBy(TenantContext.actorId());
        e.setCreatedAt(BillingClock.now());
        estimates.save(e);
        audit.record("ESTIMATE_ADDED", "INVOICE", inv.getId(), inv.getPatientId(),
                e.getDescription() + " " + e.getEstimatedAmount() + " (" + e.getBasis() + ")");
        return views.detail(inv);
    }

    @Transactional
    public InvoiceDetail deactivateEstimate(UUID invoiceId, UUID estimateId) {
        Invoice inv = support.lockInvoice(invoiceId);
        EstimatedCharge e = estimates.findById(estimateId)
                .filter(x -> x.getInvoiceId().equals(inv.getId()))
                .orElseThrow(() -> ApiException.notFound("Estimate"));
        if (!e.isActive()) {
            throw ApiException.rule("ESTIMATE_INACTIVE", "This estimate is already inactive.");
        }
        e.setActive(false);
        audit.record("ESTIMATE_REMOVED", "INVOICE", inv.getId(), inv.getPatientId(), e.getDescription());
        return views.detail(inv);
    }

    /**
     * Posts outstanding room charges up to the discharge date (or today), then marks the invoice
     * FINALIZED and reviewed by the current user. Active estimates are retired (the final bill has no
     * future charges). Finalized invoices accept payments, adjustments and claims but no new charges.
     */
    @Transactional
    public InvoiceDetail finalizeInvoice(UUID invoiceId, String notes) {
        Invoice inv = support.lockInvoice(invoiceId);
        BillingSupport.requireOpen(inv);
        RoomChargeResult rooms = roomCharges.post(inv, true, false);
        if (!lines.existsByInvoiceIdAndStatus(inv.getId(), InvoiceLineItem.Status.POSTED)) {
            throw ApiException.rule("NO_CHARGES", "The invoice has no posted charges and cannot be finalized.");
        }
        UUID actor = TenantContext.actorId();
        Instant now = BillingClock.now();
        estimates.findByInvoiceIdAndActiveTrueOrderByCreatedAtAsc(inv.getId()).forEach(e -> e.setActive(false));
        inv.setStatus(Invoice.Status.FINALIZED);
        inv.setFinalizedBy(actor);
        inv.setFinalizedAt(now);
        inv.setReviewedBy(actor);
        inv.setReviewedAt(now);
        if (notes != null && !notes.isBlank()) {
            inv.setNotes(notes.trim());
        }
        invoices.saveAndFlush(inv);
        BigDecimal balance = ledger.balance(inv.getId());
        timeline.record(TimelineService.Entry.builder()
                .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                .eventType("INVOICE_FINALIZED").category(TimelineEvent.Category.BILLING)
                .title("Final bill " + inv.getInvoiceNumber() + " generated")
                .detail("Outstanding balance " + BillingSupport.rupees(balance))
                .status("FINALIZED").visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("INVOICE").entityId(inv.getId()).occurredAt(BillingClock.now()).build());
        notifications.notifyPatient(inv.getPatientId(), NotificationRequest.builder()
                .category("BILLING").title("Your final bill is ready")
                .message("Invoice " + inv.getInvoiceNumber() + " has been finalized. Outstanding balance: "
                        + BillingSupport.rupees(balance.max(BigDecimal.ZERO)) + ".")
                .link("/patient/bills/" + inv.getId()).entityType("INVOICE").entityId(inv.getId())
                .dedupeKey("INVOICE_FINALIZED:" + inv.getId() + ":" + now.getEpochSecond()).build());
        audit.record("INVOICE_FINALIZED", "INVOICE", inv.getId(), inv.getPatientId(),
                inv.getInvoiceNumber() + " finalized; balance " + balance + "; room days posted " + rooms.roomDaysPosted());
        return views.detail(inv);
    }

    @Transactional
    public InvoiceDetail reopen(UUID invoiceId, String reason) {
        Invoice inv = support.lockInvoice(invoiceId);
        if (inv.getStatus() != Invoice.Status.FINALIZED) {
            throw ApiException.rule("INVOICE_NOT_FINALIZED", "Only a finalized invoice can be reopened.");
        }
        inv.setStatus(Invoice.Status.OPEN);
        inv.setFinalizedBy(null);
        inv.setFinalizedAt(null);
        inv.setReviewedBy(null);
        inv.setReviewedAt(null);
        inv.setReopenedBy(TenantContext.actorId());
        inv.setReopenedAt(BillingClock.now());
        inv.setReopenReason(reason.trim());
        invoices.saveAndFlush(inv);
        timeline.record(TimelineService.Entry.builder()
                .patientId(inv.getPatientId()).admissionId(inv.getAdmissionId())
                .eventType("INVOICE_REOPENED").category(TimelineEvent.Category.BILLING)
                .title("Invoice " + inv.getInvoiceNumber() + " reopened").detail(reason.trim())
                .status("OPEN").visibility(TimelineEvent.Visibility.BILLING)
                .entityType("INVOICE").entityId(inv.getId()).occurredAt(BillingClock.now()).build());
        audit.record("INVOICE_REOPENED", "INVOICE", inv.getId(), inv.getPatientId(), reason.trim());
        return views.detail(inv);
    }
}
