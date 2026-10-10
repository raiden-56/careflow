package com.careflow.hms.pharmacy;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.admission.AdmissionService;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.ChargeService;
import com.careflow.hms.billing.InvoiceLineItem;
import com.careflow.hms.billing.InvoiceLineItemRepository;
import com.careflow.hms.billing.ServiceTariff;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.medication.Medication;
import com.careflow.hms.medication.MedicationOrder;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.patient.Allergy;
import com.careflow.hms.pharmacy.PharmacyDtos.DispenseRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.DispensationDetail;
import com.careflow.hms.pharmacy.PharmacyDtos.DispensationView;
import com.careflow.hms.pharmacy.PharmacyDtos.ReturnRequest;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Role;
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
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues stock against verified prescriptions and handles returns. Dispensing is a pharmacy supply
 * event only: it never creates or changes medication administration (MAR) records.
 */
@Service
@RequiredArgsConstructor
public class DispensingService {

    private static final Map<String, String> SORTS = Map.of("dispensedAt", "dispensedAt", "quantity", "quantity");

    private final PrescriptionQueueService queue;
    private final InventoryService inventory;
    private final InventoryBatchRepository batches;
    private final DispensationRepository dispensations;
    private final StockMovementRepository movements;
    private final InvoiceLineItemRepository lines;
    private final ChargeService charges;
    private final AdmissionService admissionService;
    private final com.careflow.hms.admission.AdmissionRepository admissions;
    private final PharmacyOrderQueryRepository queries;
    private final PharmacySupport support;
    private final DispensationMapper mapper;
    private final AccessPolicy access;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    @Transactional
    public DispensationView dispense(DispenseRequest r) {
        MedicationOrder o = queue.requireOrder(r.orderId());
        Admission a = access.requireAdmission(o.getAdmissionId(), AccessPolicy.Scope.MEDICATION);
        PrescriptionQueueService.requireActive(o, a);
        if (o.getVerifiedAt() == null) {
            throw ApiException.rule("ORDER_NOT_VERIFIED", "Verify the prescription before dispensing.");
        }
        if (queries.findByOrderIdAndStatus(o.getId(), PharmacyOrderQuery.Status.OPEN).isPresent()) {
            throw ApiException.rule("QUERY_OPEN", "A query to the prescriber is still open for this prescription.");
        }
        Medication m = support.medicationsById(List.of(o.getMedicationId())).get(o.getMedicationId());
        List<Allergy> matched = queue.allergyMatches(o.getPatientId(), m);
        if ((m.isHighAlert() || m.isControlled() || !matched.isEmpty()) && !r.safetyChecksConfirmed()) {
            throw ApiException.rule("SAFETY_CHECK_REQUIRED", "Confirm the safety checks for this "
                    + (!matched.isEmpty() ? "allergy-flagged" : m.isControlled() ? "controlled" : "high-alert")
                    + " medication before dispensing.");
        }
        int qty = r.quantity();
        LocalDate today = PharmacyClock.today();
        UUID batchId = r.batchId() != null ? r.batchId() : fefoBatch(m, qty, today);
        InventoryBatch b = inventory.lock(batchId);
        if (!b.getMedicationId().equals(m.getId())) {
            throw ApiException.badRequest("BATCH_MISMATCH", "Batch " + b.getBatchNumber() + " is not " + m.getGenericName() + ".");
        }
        if (b.getExpiryDate().isBefore(today)) {
            throw ApiException.rule("BATCH_EXPIRED", "Batch " + b.getBatchNumber() + " expired on " + b.getExpiryDate()
                    + " and cannot be dispensed.");
        }
        if (b.getStatus() == InventoryBatch.Status.QUARANTINED) {
            throw ApiException.rule("BATCH_QUARANTINED", "Batch " + b.getBatchNumber() + " is quarantined.");
        }
        if (b.getStatus() != InventoryBatch.Status.ACTIVE || b.getQuantityOnHand() <= 0) {
            throw ApiException.rule("OUT_OF_STOCK", "Batch " + b.getBatchNumber() + " has no usable stock.");
        }
        if (b.getQuantityOnHand() < qty) {
            throw ApiException.rule("INSUFFICIENT_STOCK", "Only " + b.getQuantityOnHand() + " " + m.getDispenseUnit()
                    + " available in batch " + b.getBatchNumber() + ".");
        }

        Instant now = PharmacyClock.now();
        CurrentUser u = TenantContext.requireUser();
        Dispensation d = new Dispensation();
        d.setOrderId(o.getId());
        d.setAdmissionId(a.getId());
        d.setPatientId(o.getPatientId());
        d.setMedicationId(m.getId());
        d.setBatchId(b.getId());
        d.setQuantity(qty);
        d.setReturnedQuantity(0);
        d.setStatus(Dispensation.Status.DISPENSED);
        d.setDispensedBy(u.userId());
        d.setDispensedAt(now);
        d.setNotes(PrescriptionQueueService.blank(r.notes()) ? null : r.notes().trim());
        d = dispensations.saveAndFlush(d);
        inventory.applyMovement(b, StockMovement.MovementType.DISPENSE, -qty, "DISPENSATION", d.getId(),
                "Dispensed for " + a.getAdmissionNumber());

        InvoiceLineItem line = charges.postCharge(ChargeService.ChargeRequest.builder()
                .admissionId(a.getId())
                .category(ServiceTariff.Category.PHARMACY)
                .description("Pharmacy: " + m.displayName() + " × " + qty + " " + m.getDispenseUnit() + " (batch " + b.getBatchNumber() + ")")
                .quantity(BigDecimal.valueOf(qty))
                .unitPrice(m.getUnitPrice())
                .serviceDate(today)
                .sourceType(InvoiceLineItem.SourceType.DISPENSATION)
                .sourceId(d.getId())
                .build());
        d.setChargeLineId(line.getId());
        d = dispensations.saveAndFlush(d);

        AdmissionSummary s = admissionService.summaries(List.of(a), false).get(0);
        String what = m.displayName() + " × " + qty + " " + m.getDispenseUnit();
        timeline.record(TimelineService.Entry.builder()
                .patientId(o.getPatientId()).admissionId(a.getId())
                .eventType("MEDICATION_DISPENSED").category(TimelineEvent.Category.MEDICATION)
                .title("Dispensed " + what)
                .detail("Batch " + b.getBatchNumber() + " (exp " + b.getExpiryDate() + ") by " + u.fullName()
                        + ". Supplied to the ward; administration is recorded separately on the MAR.")
                .status("DISPENSED").visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("DISPENSATION").entityId(d.getId()).occurredAt(now).build());
        if (!PharmacyClock.isBackdated()) {
            notifications.notifyWardNurses(a.getId(), NotificationRequest.builder()
                    .category("MEDICATION").priority(m.isHighAlert() ? Notification.Priority.HIGH : Notification.Priority.NORMAL)
                    .title("Medication ready: " + m.displayName())
                    .message(qty + " " + m.getDispenseUnit() + " for " + s.patient().fullName() + " ("
                            + PharmacySupport.location(s) + ") dispensed by pharmacy.")
                    .link("/nurse/patients/" + a.getId())
                    .entityType("DISPENSATION").entityId(d.getId())
                    .dedupeKey("DISPENSED:" + d.getId()).build());
            long remaining = support.usableStock(m.getId());
            if (remaining < m.getReorderLevel()) {
                notifications.notifyRole(Role.PHARMACIST, NotificationRequest.builder()
                        .category("INVENTORY").priority(remaining == 0 ? Notification.Priority.HIGH : Notification.Priority.NORMAL)
                        .title((remaining == 0 ? "Out of stock: " : "Low stock: ") + m.displayName())
                        .message(remaining + " " + m.getDispenseUnit() + " usable; reorder level " + m.getReorderLevel() + ".")
                        .link("/pharmacy/alerts").entityType("MEDICATION").entityId(m.getId())
                        .dedupeKey("LOW_STOCK:" + m.getId() + ":" + today).build());
            }
        }
        audit.record("MEDICATION_DISPENSED", "DISPENSATION", d.getId(), o.getPatientId(),
                what + " from batch " + b.getBatchNumber() + " for " + a.getAdmissionNumber());
        return mapper.toViews(List.of(d)).get(0);
    }

    /** FEFO: the earliest-expiring usable batch that can supply the whole quantity. */
    private UUID fefoBatch(Medication m, int qty, LocalDate today) {
        List<InventoryBatch> usable = batches.findUsableFefo(m.getId(), today);
        if (usable.isEmpty()) {
            throw ApiException.rule("OUT_OF_STOCK", m.displayName() + " is out of stock.");
        }
        return usable.stream().filter(b -> b.getQuantityOnHand() >= qty).map(InventoryBatch::getId).findFirst()
                .orElseThrow(() -> ApiException.rule("INSUFFICIENT_STOCK", "No single batch holds " + qty + " "
                        + m.getDispenseUnit() + "; dispense in smaller quantities from separate batches."));
    }

    /**
     * Partial or full return. Restocks the original batch unless damaged, then corrects billing by reversing
     * the posted charge and re-posting the remaining quantity (posted charges are never edited).
     */
    @Transactional
    public DispensationView returnStock(UUID dispensationId, ReturnRequest r) {
        UUID orgId = TenantContext.requireOrganizationId();
        Dispensation d = dispensations.lockById(dispensationId, orgId).orElseThrow(() -> ApiException.notFound("Dispensation"));
        Admission a = access.requireAdmission(d.getAdmissionId(), AccessPolicy.Scope.MEDICATION);
        int remaining = d.getQuantity() - d.getReturnedQuantity();
        if (remaining <= 0) {
            throw ApiException.rule("ALREADY_RETURNED", "Everything from this dispensation has already been returned.");
        }
        int qty = r.quantity();
        if (qty > remaining) {
            throw ApiException.rule("RETURN_EXCEEDS_DISPENSED", "Only " + remaining + " can still be returned.");
        }
        Medication m = support.medicationsById(List.of(d.getMedicationId())).get(d.getMedicationId());
        InventoryBatch b = inventory.lock(d.getBatchId());
        String reason = r.reason().trim();
        if (r.damaged()) {
            inventory.applyMovement(b, StockMovement.MovementType.RETURN, qty, "DISPENSATION", d.getId(), "Returned (damaged): " + reason);
            inventory.applyMovement(b, StockMovement.MovementType.DAMAGE, -qty, "DISPENSATION", d.getId(), "Damaged return not restocked: " + reason);
        } else {
            inventory.applyMovement(b, StockMovement.MovementType.RETURN, qty, "DISPENSATION", d.getId(), "Returned to stock: " + reason);
        }
        d.setReturnedQuantity(d.getReturnedQuantity() + qty);
        d.setStatus(d.getReturnedQuantity() >= d.getQuantity() ? Dispensation.Status.RETURNED : Dispensation.Status.PARTIALLY_RETURNED);

        if (d.getChargeLineId() != null) {
            InvoiceLineItem current = lines.findById(d.getChargeLineId()).orElse(null);
            if (current != null && current.getStatus() == InvoiceLineItem.Status.POSTED) {
                charges.reverseCharge(current.getId(), "Pharmacy return of " + qty + " " + m.getDispenseUnit() + ": " + reason);
                int net = d.getQuantity() - d.getReturnedQuantity();
                if (net > 0) {
                    InvoiceLineItem repost = charges.postCharge(ChargeService.ChargeRequest.builder()
                            .invoiceId(current.getInvoiceId())
                            .category(ServiceTariff.Category.PHARMACY)
                            .description("Pharmacy: " + m.displayName() + " × " + net + " " + m.getDispenseUnit()
                                    + " (batch " + b.getBatchNumber() + ", after return)")
                            .quantity(BigDecimal.valueOf(net))
                            .unitPrice(current.getUnitPrice())
                            .taxRate(current.getTaxRate())
                            .serviceDate(current.getServiceDate())
                            .sourceType(InvoiceLineItem.SourceType.DISPENSATION)
                            .sourceId(d.getId())
                            .reason("Re-posted after return of " + qty)
                            .build());
                    d.setChargeLineId(repost.getId());
                }
            }
        }
        d = dispensations.saveAndFlush(d);
        String what = m.displayName() + " × " + qty + " " + m.getDispenseUnit();
        timeline.record(TimelineService.Entry.builder()
                .patientId(d.getPatientId()).admissionId(a.getId())
                .eventType("MEDICATION_RETURNED").category(TimelineEvent.Category.MEDICATION)
                .title("Returned to pharmacy: " + what)
                .detail(reason + (r.damaged() ? " (damaged, not restocked)" : " (restocked to batch " + b.getBatchNumber() + ")"))
                .status(d.getStatus().name()).visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("DISPENSATION").entityId(d.getId()).occurredAt(PharmacyClock.now()).build());
        audit.record("DISPENSATION_RETURNED", "DISPENSATION", d.getId(), d.getPatientId(),
                what + (r.damaged() ? " damaged" : " restocked") + ": " + reason);
        return mapper.toViews(List.of(d)).get(0);
    }

    // ------------------------------------------------------------------ history

    @Transactional(readOnly = true)
    public PageResponse<DispensationView> history(LocalDate from, LocalDate to, UUID patientId, UUID medicationId,
                                                  UUID admissionId, Collection<Dispensation.Status> statuses, String q,
                                                  int page, int size, String sort) {
        CurrentUser u = TenantContext.requireUser();
        Instant fromI = from == null ? PharmacySupport.MIN_INSTANT : TimeUtil.startOfDay(from);
        Instant toI = to == null ? PharmacySupport.MAX_INSTANT : TimeUtil.startOfDay(to.plusDays(1));
        Collection<Dispensation.Status> st = statuses == null || statuses.isEmpty() ? EnumSet.allOf(Dispensation.Status.class) : statuses;
        var pageable = Paging.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "dispensedAt"));
        Page<Dispensation> result = dispensations.search(u.organizationId(), fromI, toI, patientId, medicationId, admissionId,
                st, Paging.likePattern(q), pageable);
        List<Dispensation> content = result.getContent();
        if (u.role() != Role.PHARMACIST) {
            // Non-pharmacy roles holding DISPENSE still need MEDICATION scope per admission.
            Map<UUID, Boolean> allowed = new java.util.HashMap<>();
            content = content.stream().filter(d -> allowed.computeIfAbsent(d.getAdmissionId(), id ->
                    admissions.findById(id).map(adm -> access.canAccessAdmission(u, adm, AccessPolicy.Scope.MEDICATION)).orElse(false))).toList();
        }
        return new PageResponse<>(mapper.toViews(content), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public DispensationDetail detail(UUID id) {
        Dispensation d = dispensations.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Dispensation"));
        access.requireAdmission(d.getAdmissionId(), AccessPolicy.Scope.MEDICATION);
        return new DispensationDetail(mapper.toViews(List.of(d)).get(0),
                support.toMovementViews(movements.findByReferenceTypeAndReferenceIdOrderByPerformedAtAsc("DISPENSATION", d.getId())),
                mapper.chargeLines(d.getId()));
    }
}
