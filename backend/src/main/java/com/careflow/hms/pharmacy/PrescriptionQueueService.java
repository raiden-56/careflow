package com.careflow.hms.pharmacy;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.admission.AdmissionService;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.medication.Medication;
import com.careflow.hms.medication.MedicationOrder;
import com.careflow.hms.medication.MedicationOrderRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.patient.Allergy;
import com.careflow.hms.patient.AllergyRepository;
import com.careflow.hms.pharmacy.PharmacyDtos.Availability;
import com.careflow.hms.pharmacy.PharmacyDtos.BatchOption;
import com.careflow.hms.pharmacy.PharmacyDtos.OrderDetail;
import com.careflow.hms.pharmacy.PharmacyDtos.OrderQueryView;
import com.careflow.hms.pharmacy.PharmacyDtos.QueueFilter;
import com.careflow.hms.pharmacy.PharmacyDtos.QueueItem;
import com.careflow.hms.pharmacy.PharmacyDtos.SupplyStatus;
import com.careflow.hms.pharmacy.PharmacyDtos.VerifyRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.Warning;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pharmacist review of active prescriptions: the work queue, order detail with safety warnings,
 * verification and clarification queries to the prescriber. Pharmacy never creates or modifies a
 * prescription - verification only records who checked it and when.
 */
@Service
@RequiredArgsConstructor
public class PrescriptionQueueService {

    static final String INTERACTION_NOTE = "CareFlow does not perform automated drug-interaction screening. "
            + "Review the patient's other active medications listed here against your reference sources.";

    private final MedicationOrderRepository orders;
    private final AdmissionRepository admissions;
    private final AdmissionService admissionService;
    private final AllergyRepository allergies;
    private final DispensationRepository dispensations;
    private final PharmacyOrderQueryRepository queries;
    private final InventoryBatchRepository batches;
    private final PharmacySupport support;
    private final DispensationMapper dispensationMapper;
    private final AccessPolicy access;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<QueueItem> queue(QueueFilter filter, String q, UUID admissionId, int page, int size) {
        QueueFilter f = filter == null ? QueueFilter.OUTSTANDING : filter;
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        List<QueueItem> items = allActiveItems().stream()
                .filter(i -> admissionId == null || i.admissionId().equals(admissionId))
                .filter(i -> matches(f, i.supplyStatus()))
                .filter(i -> needle == null || i.patient().fullName().toLowerCase(Locale.ROOT).contains(needle)
                        || i.patient().mrn().toLowerCase(Locale.ROOT).contains(needle)
                        || i.medicationName().toLowerCase(Locale.ROOT).contains(needle)
                        || i.medicationCode().toLowerCase(Locale.ROOT).contains(needle)
                        || i.admissionNumber().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(QUEUE_ORDER)
                .toList();
        return PageResponse.ofList(items, Math.max(page, 0), Math.min(Math.max(size, 1), Paging.MAX_SIZE));
    }

    private static boolean matches(QueueFilter f, SupplyStatus s) {
        return switch (f) {
            case OUTSTANDING -> s == SupplyStatus.AWAITING_VERIFICATION || s == SupplyStatus.QUERIED
                    || s == SupplyStatus.READY_TO_DISPENSE;
            case AWAITING_VERIFICATION -> s == SupplyStatus.AWAITING_VERIFICATION;
            case QUERIED -> s == SupplyStatus.QUERIED;
            case READY_TO_DISPENSE -> s == SupplyStatus.READY_TO_DISPENSE;
            case ALL -> true;
        };
    }

    private static final Comparator<QueueItem> QUEUE_ORDER = Comparator
            .comparingInt((QueueItem i) -> switch (i.supplyStatus()) {
                case AWAITING_VERIFICATION -> 0;
                case READY_TO_DISPENSE -> 1;
                case QUERIED -> 2;
                case SUPPLIED -> 3;
                case COURSE_ENDED -> 4;
            })
            .thenComparing(i -> !i.isHighAlert())
            .thenComparing(QueueItem::prescribedAt);

    /** Every ACTIVE order on an open admission the caller may access in MEDICATION scope. */
    @Transactional(readOnly = true)
    public List<QueueItem> allActiveItems() {
        UUID orgId = TenantContext.requireOrganizationId();
        return buildItems(orders.findByOrganizationIdAndStatus(orgId, MedicationOrder.Status.ACTIVE));
    }

    @Transactional(readOnly = true)
    public OrderDetail detail(UUID orderId) {
        MedicationOrder o = requireOrder(orderId);
        Admission a = access.requireAdmission(o.getAdmissionId(), AccessPolicy.Scope.MEDICATION);
        List<QueueItem> built = buildItems(List.of(o), true);
        QueueItem item = built.get(0);
        AdmissionSummary summary = admissionService.summaries(List.of(a), true).get(0);
        Map<UUID, Medication> meds = new HashMap<>();
        List<MedicationOrder> others = orders.findByAdmissionIdAndStatusIn(a.getId(), List.of(MedicationOrder.Status.ACTIVE))
                .stream().filter(x -> !x.getId().equals(o.getId())).toList();
        meds.putAll(support.medicationsById(others.stream().map(MedicationOrder::getMedicationId).toList()));
        List<String> otherNames = others.stream().map(x -> {
            Medication m = meds.get(x.getMedicationId());
            return (m == null ? "?" : m.displayName()) + " — " + x.getDose() + " " + x.getDoseUnit() + " "
                    + x.getRoute() + " " + x.getFrequency();
        }).sorted().toList();
        LocalDate today = PharmacyClock.today();
        List<InventoryBatch> usable = batches.findUsableFefo(o.getMedicationId(), today);
        Integer need = item.suggestedQuantity();
        UUID suggested = usable.stream().filter(b -> need == null || b.getQuantityOnHand() >= need)
                .map(InventoryBatch::getId).findFirst()
                .orElse(usable.isEmpty() ? null : usable.get(0).getId());
        List<BatchOption> options = usable.stream().map(b -> new BatchOption(b.getId(), b.getBatchNumber(), b.getExpiryDate(),
                ChronoUnit.DAYS.between(today, b.getExpiryDate()), b.getQuantityOnHand(), b.getId().equals(suggested))).toList();
        List<PharmacyOrderQuery> qs = queries.findByOrderIdOrderByRaisedAtDesc(o.getId());
        Map<UUID, String> names = support.userNames(qs.stream().flatMap(x -> java.util.stream.Stream.of(x.getRaisedBy(), x.getResolvedBy())).toList());
        return new OrderDetail(item, summary, otherNames, INTERACTION_NOTE, PharmacySupport.SUPPLY_CYCLE_HOURS, options,
                dispensationMapper.toViews(dispensations.findByOrderIdOrderByDispensedAtDesc(o.getId())),
                qs.stream().map(x -> queryView(x, names)).toList());
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public QueueItem verify(UUID orderId, VerifyRequest r) {
        MedicationOrder o = requireOrder(orderId);
        Admission a = access.requireAdmission(o.getAdmissionId(), AccessPolicy.Scope.MEDICATION);
        requireActive(o, a);
        if (o.getVerifiedAt() != null) {
            throw ApiException.conflict("ALREADY_VERIFIED", "This prescription has already been verified.");
        }
        Medication m = support.medicationsById(List.of(o.getMedicationId())).get(o.getMedicationId());
        List<Allergy> matched = allergyMatches(o.getPatientId(), m);
        if (!matched.isEmpty() && !r.allergyAcknowledged()) {
            throw ApiException.rule("ALLERGY_ACK_REQUIRED", "The patient has a documented allergy that may apply ("
                    + matched.stream().map(PharmacySupport::allergyLabel).collect(Collectors.joining(", "))
                    + "). Confirm you have reviewed it with the prescriber before verifying.");
        }
        Instant now = PharmacyClock.now();
        o.setVerifiedByPharmacist(TenantContext.actorId());
        o.setVerifiedAt(now);
        orders.saveAndFlush(o);
        queries.findByOrderIdAndStatus(o.getId(), PharmacyOrderQuery.Status.OPEN).ifPresent(q -> {
            q.setStatus(PharmacyOrderQuery.Status.RESOLVED);
            q.setResolvedBy(TenantContext.actorId());
            q.setResolvedAt(now);
            q.setResolution("Resolved on verification" + (blank(r.note()) ? "" : ": " + r.note().trim()));
            queries.save(q);
        });
        String med = m.displayName();
        timeline.record(TimelineService.Entry.builder()
                .patientId(o.getPatientId()).admissionId(a.getId())
                .eventType("PHARMACY_VERIFIED").category(TimelineEvent.Category.MEDICATION)
                .title("Pharmacist verified " + med)
                .detail(o.getDose() + " " + o.getDoseUnit() + " " + o.getRoute() + " " + o.getFrequency()
                        + (matched.isEmpty() ? "" : ". Allergy alert reviewed: "
                        + matched.stream().map(PharmacySupport::allergyLabel).collect(Collectors.joining(", ")))
                        + (blank(r.note()) ? "" : ". Note: " + r.note().trim()))
                .status("VERIFIED").visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("MEDICATION_ORDER").entityId(o.getId()).occurredAt(now).build());
        audit.record("ORDER_VERIFIED", "MEDICATION_ORDER", o.getId(), o.getPatientId(),
                med + (matched.isEmpty() ? "" : " (allergy alert acknowledged)"));
        return buildItems(List.of(o)).get(0);
    }

    @Transactional
    public OrderQueryView raiseQuery(UUID orderId, String message) {
        MedicationOrder o = requireOrder(orderId);
        Admission a = access.requireAdmission(o.getAdmissionId(), AccessPolicy.Scope.MEDICATION);
        requireActive(o, a);
        if (queries.findByOrderIdAndStatus(o.getId(), PharmacyOrderQuery.Status.OPEN).isPresent()) {
            throw ApiException.conflict("QUERY_ALREADY_OPEN", "A query is already open for this prescription.");
        }
        Medication m = support.medicationsById(List.of(o.getMedicationId())).get(o.getMedicationId());
        CurrentUser u = TenantContext.requireUser();
        PharmacyOrderQuery q = new PharmacyOrderQuery();
        q.setOrderId(o.getId());
        q.setAdmissionId(a.getId());
        q.setPatientId(o.getPatientId());
        q.setMessage(message.trim());
        q.setRaisedBy(u.userId());
        q.setRaisedAt(PharmacyClock.now());
        q = queries.saveAndFlush(q);
        String patientName = admissionService.summaries(List.of(a), false).get(0).patient().fullName();
        timeline.record(TimelineService.Entry.builder()
                .patientId(o.getPatientId()).admissionId(a.getId())
                .eventType("PHARMACY_QUERY").category(TimelineEvent.Category.MEDICATION)
                .title("Pharmacy query on " + m.displayName())
                .detail(q.getMessage()).status("OPEN").visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("MEDICATION_ORDER").entityId(o.getId()).occurredAt(q.getRaisedAt()).build());
        Set<UUID> recipients = new LinkedHashSet<>();
        recipients.add(o.getPrescribedBy());
        if (!PharmacyClock.isBackdated()) {
            notifications.notifyUsers(recipients, NotificationRequest.builder()
                    .category("MEDICATION").priority(Notification.Priority.HIGH)
                    .title("Pharmacy query: " + m.displayName() + " for " + patientName)
                    .message(truncate(q.getMessage(), 400) + " — " + u.fullName() + ", Pharmacy")
                    .link("/doctor/patients/" + a.getId())
                    .entityType("MEDICATION_ORDER").entityId(o.getId())
                    .dedupeKey("PHARM_QUERY:" + q.getId()).requiresAck(true).build());
        }
        audit.record("PHARMACY_QUERY_RAISED", "MEDICATION_ORDER", o.getId(), o.getPatientId(), truncate(q.getMessage(), 300));
        return queryView(q, support.userNames(List.of(q.getRaisedBy())));
    }

    @Transactional
    public OrderQueryView resolveQuery(UUID queryId, String resolution) {
        PharmacyOrderQuery q = queries.findByIdAndOrganizationId(queryId, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Query"));
        access.requireAdmission(q.getAdmissionId(), AccessPolicy.Scope.MEDICATION);
        if (q.getStatus() != PharmacyOrderQuery.Status.OPEN) {
            throw ApiException.rule("QUERY_NOT_OPEN", "This query has already been resolved.");
        }
        q.setStatus(PharmacyOrderQuery.Status.RESOLVED);
        q.setResolvedBy(TenantContext.actorId());
        q.setResolvedAt(PharmacyClock.now());
        q.setResolution(resolution.trim());
        q = queries.saveAndFlush(q);
        MedicationOrder o = requireOrder(q.getOrderId());
        Medication m = support.medicationsById(List.of(o.getMedicationId())).get(o.getMedicationId());
        timeline.record(TimelineService.Entry.builder()
                .patientId(q.getPatientId()).admissionId(q.getAdmissionId())
                .eventType("PHARMACY_QUERY_RESOLVED").category(TimelineEvent.Category.MEDICATION)
                .title("Pharmacy query resolved: " + m.displayName())
                .detail(q.getResolution()).status("RESOLVED").visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("MEDICATION_ORDER").entityId(o.getId()).occurredAt(q.getResolvedAt()).build());
        audit.record("PHARMACY_QUERY_RESOLVED", "MEDICATION_ORDER", o.getId(), q.getPatientId(), truncate(q.getResolution(), 300));
        return queryView(q, support.userNames(List.of(q.getRaisedBy(), q.getResolvedBy())));
    }

    // ------------------------------------------------------------------ helpers

    MedicationOrder requireOrder(UUID orderId) {
        UUID orgId = TenantContext.requireOrganizationId();
        return orders.findById(orderId).filter(o -> o.getOrganizationId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("Prescription"));
    }

    static void requireActive(MedicationOrder o, Admission a) {
        if (o.getStatus() != MedicationOrder.Status.ACTIVE) {
            throw ApiException.rule("ORDER_NOT_ACTIVE", "This prescription is " + o.getStatus().name().toLowerCase(Locale.ROOT)
                    .replace('_', ' ') + "; pharmacy actions are only possible on active prescriptions.");
        }
        if (!a.getStatus().isOpen()) {
            throw ApiException.rule("ADMISSION_CLOSED", "The admission is closed.");
        }
    }

    List<Allergy> allergyMatches(UUID patientId, Medication m) {
        return allergies.findByPatientIdAndStatus(patientId, Allergy.Status.ACTIVE).stream()
                .filter(al -> PharmacySupport.allergyMatches(al, m)).toList();
    }

    List<QueueItem> buildItems(List<MedicationOrder> list) {
        return buildItems(list, false);
    }

    /**
     * Maps orders to queue items. Orders on closed admissions or outside the caller's MEDICATION scope are
     * skipped unless {@code includeAll} (used after an explicit object-level check).
     */
    List<QueueItem> buildItems(List<MedicationOrder> list, boolean includeAll) {
        if (list.isEmpty()) {
            return List.of();
        }
        CurrentUser u = TenantContext.requireUser();
        Map<UUID, Admission> adms = admissions.findByIdIn(list.stream().map(MedicationOrder::getAdmissionId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Admission::getId, Function.identity()));
        Map<UUID, Boolean> allowed = new HashMap<>();
        List<MedicationOrder> visible = list.stream().filter(o -> {
            Admission a = adms.get(o.getAdmissionId());
            if (a == null) {
                return false;
            }
            if (includeAll) {
                return true;
            }
            return a.getStatus().isOpen() && allowed.computeIfAbsent(a.getId(),
                    k -> access.canAccessAdmission(u, a, AccessPolicy.Scope.MEDICATION));
        }).toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        List<Admission> admList = visible.stream().map(o -> adms.get(o.getAdmissionId())).distinct().toList();
        Map<UUID, AdmissionSummary> summaries = admissionService.summaries(admList, false).stream()
                .collect(Collectors.toMap(AdmissionSummary::id, Function.identity()));
        Map<UUID, List<Allergy>> allergyMap = allergies.findByPatientIdInAndStatus(
                        visible.stream().map(MedicationOrder::getPatientId).collect(Collectors.toSet()), Allergy.Status.ACTIVE)
                .stream().collect(Collectors.groupingBy(Allergy::getPatientId));
        Map<UUID, Medication> meds = support.medicationsById(visible.stream().map(MedicationOrder::getMedicationId).toList());
        Map<UUID, Long> stock = support.usableStock(meds.keySet());
        List<UUID> orderIds = visible.stream().map(MedicationOrder::getId).toList();
        Map<UUID, List<Dispensation>> disp = dispensations.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(Dispensation::getOrderId));
        Map<UUID, PharmacyOrderQuery> openQueries = queries.findByOrderIdInAndStatus(orderIds, PharmacyOrderQuery.Status.OPEN)
                .stream().collect(Collectors.toMap(PharmacyOrderQuery::getOrderId, Function.identity(), (x, y) -> x));
        List<UUID> people = new ArrayList<>();
        visible.forEach(o -> {
            people.add(o.getPrescribedBy());
            people.add(o.getVerifiedByPharmacist());
        });
        openQueries.values().forEach(q -> people.add(q.getRaisedBy()));
        Map<UUID, String> names = support.userNames(people);
        Instant now = PharmacyClock.now();
        Instant cycleStart = now.minus(Duration.ofHours(PharmacySupport.SUPPLY_CYCLE_HOURS));

        List<QueueItem> result = new ArrayList<>();
        for (MedicationOrder o : visible) {
            Admission a = adms.get(o.getAdmissionId());
            AdmissionSummary s = summaries.get(a.getId());
            Medication m = meds.get(o.getMedicationId());
            long onHand = stock.getOrDefault(m.getId(), 0L);
            Availability availability = PharmacySupport.availability(onHand, m.getReorderLevel());
            List<Allergy> patientAllergies = allergyMap.getOrDefault(o.getPatientId(), List.of());
            List<Allergy> matched = patientAllergies.stream().filter(al -> PharmacySupport.allergyMatches(al, m)).toList();
            List<Dispensation> ds = disp.getOrDefault(o.getId(), List.of());
            int netTotal = ds.stream().mapToInt(d -> d.getQuantity() - d.getReturnedQuantity()).sum();
            int netCycle = ds.stream().filter(d -> !d.getDispensedAt().isBefore(cycleStart))
                    .mapToInt(d -> d.getQuantity() - d.getReturnedQuantity()).sum();
            Instant last = ds.stream().filter(d -> d.getQuantity() > d.getReturnedQuantity())
                    .map(Dispensation::getDispensedAt).max(Comparator.naturalOrder()).orElse(null);
            PharmacyOrderQuery openQuery = openQueries.get(o.getId());
            SupplyCalculator.Suggestion suggestion = SupplyCalculator.suggest(o, m);

            SupplyStatus status;
            if (openQuery != null) {
                status = SupplyStatus.QUERIED;
            } else if (o.getVerifiedAt() == null) {
                status = SupplyStatus.AWAITING_VERIFICATION;
            } else if (o.getEndAt() != null && !o.getEndAt().isAfter(now)) {
                status = SupplyStatus.COURSE_ENDED;
            } else {
                boolean scheduled = o.getFrequency().isScheduled();
                boolean outstanding = scheduled ? netCycle <= 0 : netTotal <= 0;
                status = outstanding ? SupplyStatus.READY_TO_DISPENSE : SupplyStatus.SUPPLIED;
            }

            List<Warning> warnings = new ArrayList<>();
            for (Allergy al : matched) {
                warnings.add(new Warning("ALLERGY", "error", "Documented allergy: " + PharmacySupport.allergyLabel(al)
                        + (al.getReaction() == null ? "" : " — " + al.getReaction()) + ". May apply to " + m.getGenericName()
                        + (m.getAllergenGroup() == null ? "" : " (allergen group " + m.getAllergenGroup() + ")") + "."));
            }
            if (o.getAllergyOverrideReason() != null && !o.getAllergyOverrideReason().isBlank()) {
                warnings.add(new Warning("ALLERGY_OVERRIDE", "info", "Prescriber documented allergy override: "
                        + o.getAllergyOverrideReason()));
            }
            if (m.isHighAlert()) {
                warnings.add(new Warning("HIGH_ALERT", "warning", "High-alert medication: independent double check required before release."));
            }
            if (m.isControlled()) {
                warnings.add(new Warning("CONTROLLED", "warning", "Controlled drug: record batch and quantity in the controlled-drug register."));
            }
            if (availability == Availability.OUT_OF_STOCK) {
                warnings.add(new Warning("OUT_OF_STOCK", "error", "No usable stock on hand."));
            } else if (availability == Availability.LOW_STOCK) {
                warnings.add(new Warning("LOW_STOCK", "warning", "Low stock: " + onHand + " " + m.getDispenseUnit()
                        + " usable (reorder level " + m.getReorderLevel() + ")."));
            }
            if (!m.isActive()) {
                warnings.add(new Warning("MEDICATION_INACTIVE", "warning", "This medication is inactive in the catalogue."));
            }
            if (openQuery != null) {
                warnings.add(new Warning("QUERY_OPEN", "warning", "Awaiting prescriber response: " + truncate(openQuery.getMessage(), 160)));
            }

            result.add(new QueueItem(o.getId(), a.getId(), a.getAdmissionNumber(), a.getStatus(), s.patient(),
                    PharmacySupport.location(s), m.getId(), m.getCode(), m.displayName(), m.getForm(), m.getStrength(),
                    m.getDispenseUnit(), m.isHighAlert(), m.isControlled(), m.getUnitPrice(), o.getDose(), o.getDoseUnit(),
                    o.getRoute(), o.getFrequency().name(), o.getScheduleTimes(), o.getStartAt(), o.getEndAt(),
                    o.getIndication(), o.getInstructions(), o.getAllergyOverrideReason(), o.isDischargeMedication(),
                    o.getPrescribedBy(), names.get(o.getPrescribedBy()), o.getPrescribedAt(), o.getVerifiedAt() != null,
                    names.get(o.getVerifiedByPharmacist()), o.getVerifiedAt(),
                    openQuery == null ? null : queryView(openQuery, names),
                    patientAllergies.stream().map(PharmacySupport::allergyLabel).toList(), warnings, onHand, availability,
                    last, netTotal, netCycle, suggestion.quantity(), suggestion.basis(), status, o.getVersion()));
        }
        return result;
    }

    private static OrderQueryView queryView(PharmacyOrderQuery q, Map<UUID, String> names) {
        return new OrderQueryView(q.getId(), q.getOrderId(), q.getMessage(), q.getStatus(), names.get(q.getRaisedBy()),
                q.getRaisedAt(), q.getResolvedBy() == null ? null : names.get(q.getResolvedBy()), q.getResolvedAt(),
                q.getResolution());
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
