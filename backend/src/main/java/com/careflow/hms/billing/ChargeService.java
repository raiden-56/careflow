package com.careflow.hms.billing;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.Money;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.org.SequenceService;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Charge capture used by every module (room days, investigations, procedures, pharmacy,
 * consultations, manual charges). Posting is idempotent per clinical source and always writes a
 * matching CHARGE ledger entry in the same transaction.
 */
@Service
@RequiredArgsConstructor
public class ChargeService {

    private final InvoiceRepository invoices;
    private final InvoiceLineItemRepository lines;
    private final ServiceTariffRepository tariffs;
    private final AdmissionRepository admissions;
    private final LedgerService ledger;
    private final SequenceService sequences;
    private final TimelineService timeline;

    /**
     * @param tariffId     tariff to price from (optional when unitPrice is supplied)
     * @param unitPrice    explicit unit price (e.g. medication price); overrides the tariff price
     * @param sourceId     clinical source record (lab order, procedure, dispensation, bed...) for idempotency
     */
    @Builder
    public record ChargeRequest(UUID admissionId, UUID invoiceId, UUID tariffId, ServiceTariff.Category category,
                                String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal taxRate,
                                LocalDate serviceDate, InvoiceLineItem.SourceType sourceType, UUID sourceId,
                                String reason) {
    }

    /** The open invoice for an admission, created on first use. Locks the invoice row. */
    @Transactional
    public Invoice openInvoiceForAdmission(UUID admissionId) {
        UUID orgId = TenantContext.requireOrganizationId();
        Admission a = admissions.findByIdAndOrganizationId(admissionId, orgId)
                .orElseThrow(() -> ApiException.notFound("Admission"));
        Optional<Invoice> existing = invoices.findActiveByAdmission(admissionId);
        if (existing.isPresent()) {
            return invoices.lockById(existing.get().getId(), orgId).orElseThrow();
        }
        Invoice inv = new Invoice();
        inv.setPatientId(a.getPatientId());
        inv.setAdmissionId(a.getId());
        inv.setInvoiceNumber(sequences.invoiceNumber());
        inv.setStatus(Invoice.Status.OPEN);
        inv.setCreatedBy(TenantContext.actorId());
        inv = invoices.saveAndFlush(inv);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("INVOICE_CREATED").category(TimelineEvent.Category.BILLING)
                .title("Invoice " + inv.getInvoiceNumber() + " opened")
                .visibility(TimelineEvent.Visibility.BILLING)
                .entityType("INVOICE").entityId(inv.getId()).build());
        return inv;
    }

    @Transactional
    public InvoiceLineItem postCharge(ChargeRequest req) {
        UUID orgId = TenantContext.requireOrganizationId();
        Invoice invoice;
        if (req.invoiceId() != null) {
            invoice = invoices.lockById(req.invoiceId(), orgId).orElseThrow(() -> ApiException.notFound("Invoice"));
        } else if (req.admissionId() != null) {
            invoice = openInvoiceForAdmission(req.admissionId());
        } else {
            throw ApiException.badRequest("INVOICE_REQUIRED", "An admission or invoice is required to post a charge.");
        }
        if (invoice.getStatus() != Invoice.Status.OPEN) {
            throw ApiException.rule("INVOICE_NOT_OPEN",
                    "Invoice " + invoice.getInvoiceNumber() + " is " + invoice.getStatus() + "; charges cannot be added.");
        }
        LocalDate serviceDate = req.serviceDate() != null ? req.serviceDate() : TimeUtil.today();
        if (req.sourceId() != null && lines.existsByInvoiceIdAndSourceTypeAndSourceIdAndServiceDateAndStatus(
                invoice.getId(), req.sourceType(), req.sourceId(), serviceDate, InvoiceLineItem.Status.POSTED)) {
            throw ApiException.conflict("CHARGE_ALREADY_POSTED", "This charge has already been posted.");
        }
        ServiceTariff tariff = null;
        if (req.tariffId() != null) {
            tariff = tariffs.findByIdAndOrganizationId(req.tariffId(), orgId)
                    .orElseThrow(() -> ApiException.notFound("Tariff"));
            if (!tariff.isActive()) {
                throw ApiException.rule("TARIFF_INACTIVE", "Tariff " + tariff.getCode() + " is not active.");
            }
        }
        BigDecimal unitPrice = req.unitPrice() != null ? req.unitPrice() : tariff != null ? tariff.getUnitPrice() : null;
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw ApiException.badRequest("PRICE_REQUIRED", "A valid unit price or tariff is required.");
        }
        BigDecimal qty = req.quantity() == null ? BigDecimal.ONE : req.quantity();
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("INVALID_QUANTITY", "Quantity must be positive.");
        }
        BigDecimal taxRate = req.taxRate() != null ? req.taxRate() : tariff != null ? tariff.getTaxRate() : BigDecimal.ZERO;
        BigDecimal net = Money.multiply(unitPrice, qty);
        BigDecimal tax = Money.tax(net, taxRate);

        InvoiceLineItem line = new InvoiceLineItem();
        line.setInvoiceId(invoice.getId());
        line.setTariffId(tariff == null ? null : tariff.getId());
        line.setCategory(req.category() != null ? req.category() : tariff != null ? tariff.getCategory() : ServiceTariff.Category.OTHER);
        line.setDescription(req.description() != null ? req.description() : tariff != null ? tariff.getName() : "Charge");
        line.setServiceDate(serviceDate);
        line.setQuantity(qty);
        line.setUnitPrice(Money.scale(unitPrice));
        line.setTaxRate(taxRate);
        line.setNetAmount(net);
        line.setTaxAmount(tax);
        line.setTotalAmount(Money.sum(net, tax));
        line.setSourceType(req.sourceType() == null ? InvoiceLineItem.SourceType.MANUAL : req.sourceType());
        line.setSourceId(req.sourceId());
        line.setStatus(InvoiceLineItem.Status.POSTED);
        line.setReason(req.reason());
        line.setPostedBy(TenantContext.actorId());
        line.setPostedAt(BillingClock.now());
        line = lines.saveAndFlush(line);
        ledger.post(invoice, LedgerEntry.EntryType.CHARGE, line.getTotalAmount(), "INVOICE_LINE", line.getId(),
                invoice.getInvoiceNumber(), line.getDescription());
        return line;
    }

    /** Reverses a posted charge with a negative REVERSAL line and ledger entry. Never deletes. */
    @Transactional
    public InvoiceLineItem reverseCharge(UUID lineId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("REASON_REQUIRED", "A reason is required to reverse a charge.");
        }
        UUID orgId = TenantContext.requireOrganizationId();
        InvoiceLineItem original = lines.findById(lineId)
                .filter(l -> l.getOrganizationId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("Charge"));
        Invoice invoice = invoices.lockById(original.getInvoiceId(), orgId).orElseThrow();
        if (invoice.getStatus() == Invoice.Status.CANCELLED) {
            throw ApiException.rule("INVOICE_CANCELLED", "Charges on a cancelled invoice cannot be reversed.");
        }
        if (original.getStatus() != InvoiceLineItem.Status.POSTED) {
            throw ApiException.rule("NOT_REVERSIBLE", "Only posted charges can be reversed.");
        }
        InvoiceLineItem rev = new InvoiceLineItem();
        rev.setInvoiceId(invoice.getId());
        rev.setTariffId(original.getTariffId());
        rev.setCategory(original.getCategory());
        rev.setDescription("Reversal: " + original.getDescription());
        rev.setServiceDate(original.getServiceDate());
        rev.setQuantity(original.getQuantity().negate());
        rev.setUnitPrice(original.getUnitPrice());
        rev.setTaxRate(original.getTaxRate());
        rev.setNetAmount(original.getNetAmount().negate());
        rev.setTaxAmount(original.getTaxAmount().negate());
        rev.setTotalAmount(original.getTotalAmount().negate());
        rev.setSourceType(InvoiceLineItem.SourceType.REVERSAL);
        rev.setSourceId(original.getId());
        rev.setStatus(InvoiceLineItem.Status.REVERSAL);
        rev.setReversesLineId(original.getId());
        rev.setReason(reason);
        rev.setPostedBy(TenantContext.actorId());
        rev.setPostedAt(BillingClock.now());
        rev = lines.save(rev);
        original.setStatus(InvoiceLineItem.Status.REVERSED);
        ledger.post(invoice, LedgerEntry.EntryType.CHARGE_REVERSAL, original.getTotalAmount().negate(),
                "INVOICE_LINE", rev.getId(), invoice.getInvoiceNumber(), "Reversal: " + original.getDescription() + " - " + reason);
        return rev;
    }
}
