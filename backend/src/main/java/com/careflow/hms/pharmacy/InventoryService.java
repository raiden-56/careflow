package com.careflow.hms.pharmacy;

import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.Money;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.medication.Medication;
import com.careflow.hms.medication.MedicationRepository;
import com.careflow.hms.pharmacy.PharmacyDtos.AdjustReason;
import com.careflow.hms.pharmacy.PharmacyDtos.AdjustRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.AlertsView;
import com.careflow.hms.pharmacy.PharmacyDtos.Availability;
import com.careflow.hms.pharmacy.PharmacyDtos.BatchDetail;
import com.careflow.hms.pharmacy.PharmacyDtos.BatchView;
import com.careflow.hms.pharmacy.PharmacyDtos.MovementView;
import com.careflow.hms.pharmacy.PharmacyDtos.ReceiveStockRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.StockLevel;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pharmacy stock: batch receipt, quarantine/release, expiry write-off and adjustments. Every quantity
 * change writes an append-only {@link StockMovement} (DB-enforced) and batch rows are locked while
 * their quantity changes, so stock can never go negative.
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final Map<String, String> BATCH_SORTS = Map.of(
            "expiryDate", "expiryDate", "receivedAt", "receivedAt", "quantityOnHand", "quantityOnHand",
            "batchNumber", "batchNumber", "status", "status");

    private final InventoryBatchRepository batches;
    private final StockMovementRepository movements;
    private final MedicationRepository medications;
    private final PharmacySupport support;
    private final AuditService audit;

    // ------------------------------------------------------------------ commands

    @Transactional
    public BatchView receive(ReceiveStockRequest r) {
        UUID orgId = TenantContext.requireOrganizationId();
        Medication m = medications.findByIdAndOrganizationId(r.medicationId(), orgId)
                .orElseThrow(() -> ApiException.notFound("Medication"));
        if (!m.isActive()) {
            throw ApiException.rule("MEDICATION_INACTIVE", m.getCode() + " is inactive; reactivate it before receiving stock.");
        }
        LocalDate today = PharmacyClock.today();
        if (!r.expiryDate().isAfter(today)) {
            throw ApiException.badRequest("INVALID_EXPIRY", "Expiry date must be after today; expired stock cannot be received.");
        }
        String batchNumber = r.batchNumber().trim().toUpperCase(Locale.ROOT);
        if (batches.existsByOrganizationIdAndMedicationIdAndBatchNumberIgnoreCase(orgId, m.getId(), batchNumber)) {
            throw ApiException.conflict("DUPLICATE_BATCH", "Batch " + batchNumber + " already exists for " + m.getCode()
                    + ". Use a stock adjustment to correct its quantity.");
        }
        Instant now = PharmacyClock.now();
        InventoryBatch b = new InventoryBatch();
        b.setMedicationId(m.getId());
        b.setBatchNumber(batchNumber);
        b.setExpiryDate(r.expiryDate());
        b.setQuantityReceived(r.quantity());
        b.setQuantityOnHand(r.quantity());
        b.setUnitCost(Money.scale(r.unitCost()));
        b.setSupplier(r.supplier() == null || r.supplier().isBlank() ? null : r.supplier().trim());
        b.setReceivedAt(now);
        b.setReceivedBy(TenantContext.actorId());
        b.setStatus(InventoryBatch.Status.ACTIVE);
        b = batches.saveAndFlush(b);
        movement(b, StockMovement.MovementType.RECEIPT, r.quantity(), "GOODS_RECEIPT", null,
                b.getSupplier() == null ? "Stock received" : "Received from " + b.getSupplier());
        audit.record("STOCK_RECEIVED", "INVENTORY_BATCH", b.getId(), null,
                r.quantity() + " x " + m.getCode() + " batch " + batchNumber + " exp " + r.expiryDate());
        return view(b);
    }

    @Transactional
    public BatchView quarantine(UUID batchId, String reason) {
        InventoryBatch b = lock(batchId);
        if (b.getStatus() != InventoryBatch.Status.ACTIVE) {
            throw ApiException.rule("INVALID_BATCH_STATE", "Only active batches can be quarantined (batch is " + b.getStatus() + ").");
        }
        if (b.getQuantityOnHand() <= 0) {
            throw ApiException.rule("BATCH_EMPTY", "This batch has no stock on hand.");
        }
        b.setStatus(InventoryBatch.Status.QUARANTINED);
        audit.record("BATCH_QUARANTINED", "INVENTORY_BATCH", b.getId(), null, b.getBatchNumber() + ": " + reason);
        return view(batches.saveAndFlush(b));
    }

    @Transactional
    public BatchView release(UUID batchId, String reason) {
        InventoryBatch b = lock(batchId);
        if (b.getStatus() != InventoryBatch.Status.QUARANTINED) {
            throw ApiException.rule("INVALID_BATCH_STATE", "Only quarantined batches can be released.");
        }
        if (b.getExpiryDate().isBefore(PharmacyClock.today())) {
            throw ApiException.rule("BATCH_EXPIRED", "An expired batch cannot be released to use; write it off instead.");
        }
        b.setStatus(b.getQuantityOnHand() > 0 ? InventoryBatch.Status.ACTIVE : InventoryBatch.Status.DEPLETED);
        audit.record("BATCH_RELEASED", "INVENTORY_BATCH", b.getId(), null, b.getBatchNumber() + ": " + reason);
        return view(batches.saveAndFlush(b));
    }

    /** Removes all remaining stock of an expired batch (EXPIRY_WRITE_OFF movement) and closes it. */
    @Transactional
    public BatchView writeOff(UUID batchId, String reason) {
        InventoryBatch b = lock(batchId);
        int qty = b.getQuantityOnHand();
        if (qty <= 0) {
            throw b.getStatus() == InventoryBatch.Status.EXPIRED
                    ? ApiException.rule("ALREADY_WRITTEN_OFF", "This batch has already been written off.")
                    : ApiException.rule("BATCH_EMPTY", "This batch has no stock on hand to write off.");
        }
        if (!b.getExpiryDate().isBefore(PharmacyClock.today())) {
            throw ApiException.rule("NOT_EXPIRED", "Only expired batches can be written off. Use a stock adjustment"
                    + " (damage) or quarantine for unexpired stock.");
        }
        b.setQuantityOnHand(0);
        b.setStatus(InventoryBatch.Status.EXPIRED);
        batches.saveAndFlush(b);
        movement(b, StockMovement.MovementType.EXPIRY_WRITE_OFF, -qty, "WRITE_OFF", null, reason);
        audit.record("BATCH_WRITTEN_OFF", "INVENTORY_BATCH", b.getId(), null,
                qty + " units of batch " + b.getBatchNumber() + " written off: " + reason);
        return view(b);
    }

    @Transactional
    public BatchView adjust(UUID batchId, AdjustRequest r) {
        InventoryBatch b = lock(batchId);
        if (b.getStatus() == InventoryBatch.Status.EXPIRED) {
            throw ApiException.rule("INVALID_BATCH_STATE", "Written-off batches cannot be adjusted.");
        }
        boolean in = r.direction() == PharmacyDtos.AdjustDirection.IN;
        if (in && r.reasonCode() == AdjustReason.DAMAGE) {
            throw ApiException.badRequest("INVALID_REASON", "Damage can only reduce stock.");
        }
        int delta = in ? r.quantity() : -r.quantity();
        int after = b.getQuantityOnHand() + delta;
        if (after < 0) {
            throw ApiException.rule("INSUFFICIENT_STOCK", "Cannot remove " + r.quantity() + "; only "
                    + b.getQuantityOnHand() + " on hand in batch " + b.getBatchNumber() + ".");
        }
        b.setQuantityOnHand(after);
        if (b.getStatus() == InventoryBatch.Status.ACTIVE && after == 0) {
            b.setStatus(InventoryBatch.Status.DEPLETED);
        } else if (b.getStatus() == InventoryBatch.Status.DEPLETED && after > 0) {
            b.setStatus(InventoryBatch.Status.ACTIVE);
        }
        batches.saveAndFlush(b);
        StockMovement.MovementType type = !in && r.reasonCode() == AdjustReason.DAMAGE
                ? StockMovement.MovementType.DAMAGE
                : in ? StockMovement.MovementType.ADJUSTMENT_IN : StockMovement.MovementType.ADJUSTMENT_OUT;
        String reason = humanReason(r.reasonCode()) + ": " + r.reason().trim();
        movement(b, type, delta, "ADJUSTMENT", null, reason);
        audit.record("STOCK_ADJUSTED", "INVENTORY_BATCH", b.getId(), null,
                (delta > 0 ? "+" : "") + delta + " on batch " + b.getBatchNumber() + " (" + reason + ")");
        return view(b);
    }

    /**
     * Applies a quantity change to an already locked batch and records the movement. Used by dispensing
     * and returns. Never lets stock go negative.
     */
    StockMovement applyMovement(InventoryBatch locked, StockMovement.MovementType type, int delta, String refType,
                                UUID refId, String reason) {
        int after = locked.getQuantityOnHand() + delta;
        if (after < 0) {
            throw ApiException.rule("INSUFFICIENT_STOCK", "Only " + locked.getQuantityOnHand() + " units on hand in batch "
                    + locked.getBatchNumber() + ".");
        }
        locked.setQuantityOnHand(after);
        if (locked.getStatus() == InventoryBatch.Status.ACTIVE && after == 0) {
            locked.setStatus(InventoryBatch.Status.DEPLETED);
        } else if (locked.getStatus() == InventoryBatch.Status.DEPLETED && after > 0) {
            locked.setStatus(InventoryBatch.Status.ACTIVE);
        }
        batches.saveAndFlush(locked);
        return movement(locked, type, delta, refType, refId, reason);
    }

    InventoryBatch lock(UUID batchId) {
        return batches.lockById(batchId, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Batch"));
    }

    private StockMovement movement(InventoryBatch b, StockMovement.MovementType type, int qty, String refType, UUID refId,
                                   String reason) {
        StockMovement mv = new StockMovement();
        mv.setBatchId(b.getId());
        mv.setMedicationId(b.getMedicationId());
        mv.setMovementType(type);
        mv.setQuantity(qty);
        mv.setBalanceAfter(b.getQuantityOnHand());
        mv.setReferenceType(refType);
        mv.setReferenceId(refId);
        mv.setReason(reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason);
        mv.setPerformedBy(TenantContext.actorId());
        mv.setPerformedAt(PharmacyClock.now());
        return movements.save(mv);
    }

    private static String humanReason(AdjustReason r) {
        return switch (r) {
            case COUNT_CORRECTION -> "Count correction";
            case DAMAGE -> "Damage";
            case OTHER -> "Adjustment";
        };
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<BatchView> searchBatches(UUID medicationId, Collection<InventoryBatch.Status> statuses,
                                                 Integer expiringWithinDays, boolean includeEmpty, String q,
                                                 int page, int size, String sort) {
        UUID orgId = TenantContext.requireOrganizationId();
        Collection<InventoryBatch.Status> st = statuses == null || statuses.isEmpty()
                ? EnumSet.allOf(InventoryBatch.Status.class) : statuses;
        LocalDate expiringBy = expiringWithinDays == null ? null : PharmacyClock.today().plusDays(Math.max(expiringWithinDays, 0));
        var pageable = Paging.of(page, size, sort, BATCH_SORTS, Sort.by(Sort.Direction.ASC, "expiryDate"));
        Page<InventoryBatch> result = batches.search(orgId, medicationId, st, expiringBy, includeEmpty, Paging.likePattern(q), pageable);
        List<BatchView> views = support.toBatchViews(result.getContent());
        return new PageResponse<>(views, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public BatchDetail batchDetail(UUID batchId) {
        InventoryBatch b = batches.findByIdAndOrganizationId(batchId, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Batch"));
        return new BatchDetail(view(b), support.toMovementViews(movements.findByBatchIdOrderByPerformedAtDesc(b.getId())));
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementView> movements(UUID batchId, UUID medicationId, Collection<StockMovement.MovementType> types,
                                                LocalDate from, LocalDate to, int page, int size) {
        UUID orgId = TenantContext.requireOrganizationId();
        Collection<StockMovement.MovementType> t = types == null || types.isEmpty()
                ? EnumSet.allOf(StockMovement.MovementType.class) : types;
        Instant fromI = from == null ? PharmacySupport.MIN_INSTANT : TimeUtil.startOfDay(from);
        Instant toI = to == null ? PharmacySupport.MAX_INSTANT : TimeUtil.startOfDay(to.plusDays(1));
        var pageable = org.springframework.data.domain.PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), Paging.MAX_SIZE),
                Sort.by(Sort.Direction.DESC, "performedAt"));
        Page<StockMovement> result = movements.search(orgId, batchId, medicationId, t, fromI, toI, pageable);
        return new PageResponse<>(support.toMovementViews(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    /** Stock position per catalogue medication (usable = ACTIVE and not expired). */
    @Transactional(readOnly = true)
    public List<StockLevel> stockLevels(int nearExpiryDays) {
        UUID orgId = TenantContext.requireOrganizationId();
        LocalDate today = PharmacyClock.today();
        LocalDate nearBy = today.plusDays(nearExpiryDays);
        Map<UUID, List<InventoryBatch>> byMed = new HashMap<>();
        for (InventoryBatch b : batches.findByOrganizationIdAndQuantityOnHandGreaterThan(orgId, 0)) {
            byMed.computeIfAbsent(b.getMedicationId(), k -> new ArrayList<>()).add(b);
        }
        List<StockLevel> result = new ArrayList<>();
        for (Medication m : medications.findByOrganizationIdOrderByGenericNameAsc(orgId)) {
            long usable = 0;
            long quarantined = 0;
            long expired = 0;
            long near = 0;
            LocalDate nearest = null;
            BigDecimal value = Money.ZERO;
            List<InventoryBatch> list = byMed.getOrDefault(m.getId(), List.of());
            for (InventoryBatch b : list) {
                boolean isExpired = b.getExpiryDate().isBefore(today);
                if (isExpired) {
                    expired += b.getQuantityOnHand();
                } else if (b.getStatus() == InventoryBatch.Status.QUARANTINED) {
                    quarantined += b.getQuantityOnHand();
                } else if (b.getStatus() == InventoryBatch.Status.ACTIVE) {
                    usable += b.getQuantityOnHand();
                    value = value.add(Money.multiply(Money.nz(b.getUnitCost()), BigDecimal.valueOf(b.getQuantityOnHand())));
                    if (!b.getExpiryDate().isAfter(nearBy)) {
                        near += b.getQuantityOnHand();
                    }
                    if (nearest == null || b.getExpiryDate().isBefore(nearest)) {
                        nearest = b.getExpiryDate();
                    }
                }
            }
            result.add(new StockLevel(m.getId(), m.getCode(), m.displayName(), m.getForm(), m.getStrength(),
                    m.getDispenseUnit(), m.isActive(), m.isHighAlert(), m.isControlled(), m.getReorderLevel(), usable,
                    quarantined, expired, near, list.size(), nearest, Money.scale(value), Money.scale(m.getUnitPrice()),
                    PharmacySupport.availability(usable, m.getReorderLevel())));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PageResponse<StockLevel> stockLevelPage(String q, Availability availability, Boolean active, int nearExpiryDays,
                                                   int page, int size, String sort) {
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        List<StockLevel> all = stockLevels(nearExpiryDays).stream()
                .filter(s -> needle == null || s.name().toLowerCase(Locale.ROOT).contains(needle)
                        || s.code().toLowerCase(Locale.ROOT).contains(needle))
                .filter(s -> availability == null || s.availability() == availability)
                .filter(s -> active == null || s.active() == active)
                .sorted(stockComparator(sort))
                .toList();
        return PageResponse.ofList(all, Math.max(page, 0), Math.min(Math.max(size, 1), Paging.MAX_SIZE));
    }

    private static Comparator<StockLevel> stockComparator(String sort) {
        String field = "name";
        boolean asc = true;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            field = parts[0].trim();
            asc = parts.length < 2 || parts[1].trim().equalsIgnoreCase("asc");
        }
        Comparator<StockLevel> c = switch (field) {
            case "name" -> Comparator.comparing(StockLevel::name, String.CASE_INSENSITIVE_ORDER);
            case "code" -> Comparator.comparing(StockLevel::code);
            case "usable" -> Comparator.comparingLong(StockLevel::usable);
            case "stockValue" -> Comparator.comparing(StockLevel::stockValue);
            case "nearestExpiry" -> Comparator.comparing(StockLevel::nearestExpiry, Comparator.nullsLast(Comparator.naturalOrder()));
            case "availability" -> Comparator.comparing(StockLevel::availability).reversed();
            default -> throw ApiException.badRequest("INVALID_SORT", "Sorting by '" + field + "' is not supported");
        };
        return asc ? c : c.reversed();
    }

    @Transactional(readOnly = true)
    public AlertsView alerts(int nearExpiryDays) {
        UUID orgId = TenantContext.requireOrganizationId();
        LocalDate today = PharmacyClock.today();
        LocalDate nearBy = today.plusDays(nearExpiryDays);
        List<StockLevel> low = stockLevels(nearExpiryDays).stream()
                .filter(s -> s.active() && s.availability() != Availability.IN_STOCK)
                .sorted(Comparator.comparingLong(StockLevel::usable))
                .toList();
        List<InventoryBatch> onHand = new ArrayList<>(batches.findByOrganizationIdAndQuantityOnHandGreaterThan(orgId, 0));
        onHand.sort(Comparator.comparing(InventoryBatch::getExpiryDate));
        List<InventoryBatch> near = onHand.stream()
                .filter(b -> b.getStatus() == InventoryBatch.Status.ACTIVE && !b.getExpiryDate().isBefore(today)
                        && !b.getExpiryDate().isAfter(nearBy)).toList();
        List<InventoryBatch> expired = onHand.stream().filter(b -> b.getExpiryDate().isBefore(today)).toList();
        List<InventoryBatch> quarantined = onHand.stream()
                .filter(b -> b.getStatus() == InventoryBatch.Status.QUARANTINED && !b.getExpiryDate().isBefore(today)).toList();
        return new AlertsView(nearExpiryDays, low, support.toBatchViews(near), support.toBatchViews(expired),
                support.toBatchViews(quarantined));
    }

    @Transactional(readOnly = true)
    public String stockCsv(int nearExpiryDays) {
        StringBuilder sb = new StringBuilder();
        sb.append("Code,Medication,Form,Strength,Dispense unit,Active,Reorder level,Usable stock,Quarantined,")
                .append("Expired on hand,Expiring within ").append(nearExpiryDays).append(" days,Batches,Nearest expiry,")
                .append("Usable stock value (INR),Unit price (INR),Availability\n");
        for (StockLevel s : stockLevels(nearExpiryDays)) {
            sb.append(String.join(",",
                    PharmacySupport.csv(s.code()), PharmacySupport.csv(s.name()), PharmacySupport.csv(s.form()),
                    PharmacySupport.csv(s.strength()), PharmacySupport.csv(s.dispenseUnit()),
                    s.active() ? "Yes" : "No", String.valueOf(s.reorderLevel()), String.valueOf(s.usable()),
                    String.valueOf(s.quarantined()), String.valueOf(s.expiredOnHand()), String.valueOf(s.nearExpiry()),
                    String.valueOf(s.batchCount()), s.nearestExpiry() == null ? "" : s.nearestExpiry().toString(),
                    s.stockValue().toPlainString(), s.unitPrice().toPlainString(), s.availability().name()))
                    .append('\n');
        }
        audit.record("STOCK_REPORT_EXPORTED", "INVENTORY", null, null, "Stock report CSV exported");
        return sb.toString();
    }

    private BatchView view(InventoryBatch b) {
        return support.toBatchViews(List.of(b)).get(0);
    }
}
