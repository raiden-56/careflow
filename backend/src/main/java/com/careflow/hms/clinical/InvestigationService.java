package com.careflow.hms.clinical;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.ChargeService;
import com.careflow.hms.billing.Invoice;
import com.careflow.hms.billing.InvoiceLineItem;
import com.careflow.hms.billing.InvoiceLineItemRepository;
import com.careflow.hms.billing.InvoiceRepository;
import com.careflow.hms.billing.ServiceTariff;
import com.careflow.hms.billing.ServiceTariffRepository;
import com.careflow.hms.clinical.ClinicalDtos.CatalogueItem;
import com.careflow.hms.clinical.ClinicalDtos.CollectRequest;
import com.careflow.hms.clinical.ClinicalDtos.LabOrderRequest;
import com.careflow.hms.clinical.ClinicalDtos.LabOrderView;
import com.careflow.hms.clinical.ClinicalDtos.LabResultView;
import com.careflow.hms.clinical.ClinicalDtos.LisResultPayload;
import com.careflow.hms.clinical.ClinicalDtos.ResultComponent;
import com.careflow.hms.clinical.ClinicalDtos.ResultEntryRequest;
import com.careflow.hms.clinical.ClinicalSupport.PatientRef;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.org.SequenceService;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Investigations (lab and imaging): order from the tariff catalogue (charge captured through
 * ChargeService), specimen collection, result entry (manual or development LIS adapter),
 * clinician review and release to the patient portal.
 */
@Service
@RequiredArgsConstructor
public class InvestigationService {

    public enum Worklist { PENDING_COLLECTION, AWAITING_RESULT, AWAITING_REVIEW, OUTSTANDING, ALL }

    private final LabOrderRepository orders;
    private final LabResultRepository results;
    private final ServiceTariffRepository tariffs;
    private final InvoiceRepository invoices;
    private final InvoiceLineItemRepository lineItems;
    private final AdmissionRepository admissions;
    private final ChargeService charges;
    private final SequenceService sequences;
    private final CareTaskService careTasks;
    private final AlertService alerts;
    private final ClinicalAlertRepository alertRepository;
    private final ClinicalSupport support;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    // ------------------------------------------------------------------ catalogue

    @Transactional(readOnly = true)
    public List<CatalogueItem> catalogue(String category, String q) {
        UUID org = TenantContext.requireOrganizationId();
        Set<ServiceTariff.Category> cats = category == null || category.isBlank()
                ? EnumSet.of(ServiceTariff.Category.LAB, ServiceTariff.Category.IMAGING)
                : EnumSet.of(parseCategory(category));
        String needle = q == null ? null : q.trim().toLowerCase();
        List<CatalogueItem> out = new ArrayList<>();
        for (ServiceTariff.Category c : cats) {
            tariffs.findByOrganizationIdAndCategoryAndActiveTrueOrderByName(org, c).stream()
                    .filter(t -> needle == null || needle.isEmpty() || t.getName().toLowerCase().contains(needle)
                            || t.getCode().toLowerCase().contains(needle))
                    .forEach(t -> out.add(new CatalogueItem(t.getId(), t.getCode(), t.getName(), t.getCategory().name(),
                            t.getUnitPrice(), t.getDescription())));
        }
        return out;
    }

    private static ServiceTariff.Category parseCategory(String c) {
        try {
            ServiceTariff.Category cat = ServiceTariff.Category.valueOf(c.trim().toUpperCase());
            if (cat != ServiceTariff.Category.LAB && cat != ServiceTariff.Category.IMAGING && cat != ServiceTariff.Category.PROCEDURE) {
                throw new IllegalArgumentException();
            }
            return cat;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_CATEGORY", "Category must be LAB, IMAGING or PROCEDURE.");
        }
    }

    // ------------------------------------------------------------------ order

    @Transactional
    public LabOrderView order(UUID admissionId, LabOrderRequest r) {
        Admission a = support.requireOpenClinical(admissionId);
        ServiceTariff t = tariffs.findByIdAndOrganizationId(r.tariffId(), a.getOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Investigation"));
        if (!t.isActive() || (t.getCategory() != ServiceTariff.Category.LAB && t.getCategory() != ServiceTariff.Category.IMAGING)) {
            throw ApiException.rule("NOT_ORDERABLE", t.getName() + " is not an orderable investigation.");
        }
        LabOrder.Priority priority = r.priority() == null ? LabOrder.Priority.ROUTINE : r.priority();
        Instant now = Instant.now();
        LabOrder o = new LabOrder();
        o.setAdmissionId(a.getId());
        o.setPatientId(a.getPatientId());
        o.setOrderNumber(sequences.labOrderNumber());
        o.setTariffId(t.getId());
        o.setTestCode(t.getCode());
        o.setTestName(t.getName());
        o.setCategory(t.getCategory() == ServiceTariff.Category.IMAGING ? LabOrder.Category.IMAGING : LabOrder.Category.LAB);
        o.setSpecimen(ClinicalSupport.blankToNull(r.specimen()) != null ? r.specimen().trim()
                : o.getCategory() == LabOrder.Category.LAB ? defaultSpecimen(t.getCode()) : null);
        o.setPriority(priority);
        o.setStatus(LabOrder.Status.ORDERED);
        o.setClinicalInfo(ClinicalSupport.blankToNull(r.clinicalInfo()));
        o.setOrderedBy(TenantContext.actorId());
        o.setOrderedAt(now);
        o.setDueAt(r.dueAt() != null ? r.dueAt() : now.plus(switch (priority) {
            case STAT -> Duration.ofMinutes(30);
            case URGENT -> Duration.ofHours(2);
            case ROUTINE -> Duration.ofHours(8);
        }));
        o = orders.saveAndFlush(o);

        // Charge capture (idempotent per lab order) on the admission's open invoice.
        Optional<Invoice> inv = invoices.findActiveByAdmission(a.getId());
        if (inv.isEmpty() || inv.get().getStatus() == Invoice.Status.OPEN) {
            charges.postCharge(ChargeService.ChargeRequest.builder()
                    .admissionId(a.getId()).tariffId(t.getId()).category(t.getCategory())
                    .description(t.getName() + " (" + o.getOrderNumber() + ")")
                    .serviceDate(TimeUtil.today()).sourceType(InvoiceLineItem.SourceType.LAB_ORDER).sourceId(o.getId())
                    .build());
            o.setChargePosted(true);
        }

        boolean lab = o.getCategory() == LabOrder.Category.LAB;
        careTasks.createTask(a, lab ? CareTask.TaskType.LAB_COLLECTION : CareTask.TaskType.OTHER,
                (lab ? "Collect specimen: " : "Prepare for imaging: ") + o.getTestName(),
                (o.getSpecimen() != null ? "Specimen: " + o.getSpecimen() + ". " : "") + "Order " + o.getOrderNumber()
                        + (o.getClinicalInfo() != null ? ". Clinical info: " + o.getClinicalInfo() : ""),
                priority == LabOrder.Priority.STAT ? CareTask.Priority.URGENT
                        : priority == LabOrder.Priority.URGENT ? CareTask.Priority.HIGH : CareTask.Priority.NORMAL,
                o.getDueAt(), null, Role.NURSE, "LAB_ORDER", o.getId());
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("INVESTIGATION_ORDERED").category(TimelineEvent.Category.INVESTIGATION)
                .title("Investigation ordered: " + o.getTestName())
                .detail(priority.name().toLowerCase() + " · " + o.getOrderNumber() + (o.getClinicalInfo() != null ? " · " + o.getClinicalInfo() : ""))
                .status("ORDERED").visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("LAB_ORDER").entityId(o.getId()).build());
        audit.record("INVESTIGATION_ORDERED", "LAB_ORDER", o.getId(), a.getPatientId(), o.getOrderNumber() + " " + o.getTestName());
        return views(List.of(o)).get(0);
    }

    private static String defaultSpecimen(String code) {
        return switch (code) {
            case "LAB_URINE", "LAB_UCX" -> "Urine";
            case "LAB_BCX" -> "Blood culture bottles";
            default -> "Venous blood";
        };
    }

    @Transactional
    public LabOrderView collect(UUID orderId, CollectRequest r) {
        LabOrder o = support.requireOrg(orders.findById(orderId), "Investigation");
        Admission a = support.requireOpenClinical(o.getAdmissionId());
        if (o.getStatus() != LabOrder.Status.ORDERED) {
            throw ApiException.rule("NOT_AWAITING_COLLECTION", "This investigation is " + o.getStatus().name().toLowerCase() + ", not awaiting collection.");
        }
        Instant at = r == null || r.collectedAt() == null ? Instant.now() : r.collectedAt();
        if (at.isAfter(Instant.now().plus(Duration.ofMinutes(5))) || at.isBefore(o.getOrderedAt().minus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("INVALID_TIME", "Collection time must be between the order time and now.");
        }
        o.transitionTo(LabOrder.Status.COLLECTED);
        o.setCollectedBy(TenantContext.actorId());
        o.setCollectedAt(at);
        if (r != null && ClinicalSupport.blankToNull(r.specimen()) != null) {
            o.setSpecimen(r.specimen().trim());
        }
        careTasks.closeForSource("LAB_ORDER", o.getId(), a, false, r == null ? "Specimen collected" : ClinicalSupport.blankToNull(r.notes()));
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("SPECIMEN_COLLECTED").category(TimelineEvent.Category.INVESTIGATION)
                .title("Specimen collected: " + o.getTestName()).detail(o.getSpecimen()).status("COLLECTED")
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("LAB_ORDER").entityId(o.getId()).occurredAt(at).build());
        audit.record("SPECIMEN_COLLECTED", "LAB_ORDER", o.getId(), a.getPatientId(), o.getOrderNumber());
        orders.flush();
        return views(List.of(o)).get(0);
    }

    /** Cancels an investigation; the charge is reversed when no specimen has been collected yet. */
    @Transactional
    public LabOrderView cancel(UUID orderId, String reason) {
        LabOrder o = support.requireOrg(orders.findById(orderId), "Investigation");
        Admission a = support.requireClinical(o.getAdmissionId());
        if (o.getStatus() != LabOrder.Status.ORDERED && o.getStatus() != LabOrder.Status.COLLECTED
                && o.getStatus() != LabOrder.Status.IN_PROGRESS) {
            throw ApiException.rule("CANNOT_CANCEL", "Investigations with results cannot be cancelled.");
        }
        boolean beforeCollection = o.getStatus() == LabOrder.Status.ORDERED;
        o.transitionTo(LabOrder.Status.CANCELLED);
        o.setCancelledBy(TenantContext.actorId());
        o.setCancelledAt(Instant.now());
        o.setCancelReason(reason.trim());
        if (beforeCollection && o.isChargePosted()) {
            for (InvoiceLineItem line : lineItems.findBySourceTypeAndSourceIdAndStatus(InvoiceLineItem.SourceType.LAB_ORDER, o.getId(),
                    InvoiceLineItem.Status.POSTED)) {
                Invoice inv = invoices.findById(line.getInvoiceId()).orElse(null);
                if (inv != null && inv.getStatus() == Invoice.Status.OPEN) {
                    charges.reverseCharge(line.getId(), "Investigation " + o.getOrderNumber() + " cancelled before collection: " + reason.trim());
                }
            }
        }
        careTasks.closeForSource("LAB_ORDER", o.getId(), a, true, "Investigation cancelled: " + reason.trim());
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("INVESTIGATION_CANCELLED").category(TimelineEvent.Category.INVESTIGATION)
                .title("Investigation cancelled: " + o.getTestName()).detail(reason).status("CANCELLED")
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("LAB_ORDER").entityId(o.getId()).build());
        audit.record("INVESTIGATION_CANCELLED", "LAB_ORDER", o.getId(), a.getPatientId(),
                o.getOrderNumber() + ": " + reason + (beforeCollection ? " (charge reversed)" : ""));
        orders.flush();
        return views(List.of(o)).get(0);
    }

    // ------------------------------------------------------------------ results

    @Transactional
    public LabOrderView enterResults(UUID orderId, ResultEntryRequest r) {
        LabOrder o = support.requireOrg(orders.findById(orderId), "Investigation");
        Admission a = support.requireClinical(o.getAdmissionId());
        return doEnterResults(o, a, r.components(), r.status() == null ? LabResult.Status.FINAL : r.status(), r.resultedAt(),
                LabResult.Source.MANUAL);
    }

    /** DEVELOPMENT LIS adapter: a mock integration boundary that ingests a result payload by order number. */
    @Transactional
    public LabOrderView ingestLisResult(LisResultPayload p) {
        UUID org = TenantContext.requireOrganizationId();
        LabOrder o = orders.findByOrganizationIdAndOrderNumber(org, p.orderNumber().trim())
                .orElseThrow(() -> ApiException.notFound("Investigation " + p.orderNumber()));
        Admission a = support.requireClinical(o.getAdmissionId());
        return doEnterResults(o, a, p.components(), LabResult.Status.FINAL, p.performedAt(), LabResult.Source.LIS_ADAPTER);
    }

    LabOrderView doEnterResults(LabOrder o, Admission a, List<ResultComponent> components, LabResult.Status status,
                                Instant resultedAt, LabResult.Source source) {
        if (o.getStatus() == LabOrder.Status.CANCELLED) {
            throw ApiException.rule("ORDER_CANCELLED", "Results cannot be entered for a cancelled investigation.");
        }
        if (o.getCategory() == LabOrder.Category.LAB && o.getStatus() == LabOrder.Status.ORDERED) {
            throw ApiException.rule("NOT_COLLECTED", "Record specimen collection before entering results.");
        }
        if (status == LabResult.Status.ENTERED_IN_ERROR || status == LabResult.Status.CORRECTED) {
            throw ApiException.badRequest("INVALID_STATUS", "Results are entered as PRELIMINARY or FINAL.");
        }
        Instant at = resultedAt == null ? Instant.now() : resultedAt;
        if (at.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("INVALID_TIME", "Result time cannot be in the future.");
        }
        boolean wasReviewed = o.getStatus() == LabOrder.Status.REVIEWED;
        o.transitionTo(LabOrder.Status.RESULTED);
        List<LabResult> saved = new ArrayList<>();
        for (ResultComponent c : components) {
            LabResult lr = new LabResult();
            lr.setLabOrderId(o.getId());
            lr.setAdmissionId(o.getAdmissionId());
            lr.setPatientId(o.getPatientId());
            lr.setComponent(c.component().trim());
            lr.setValueText(c.valueText().trim());
            lr.setValueNumeric(c.valueNumeric());
            lr.setUnit(ClinicalSupport.blankToNull(c.unit()));
            lr.setReferenceRange(ClinicalSupport.blankToNull(c.referenceRange()));
            lr.setFlag(c.flag());
            lr.setInterpretation(ClinicalSupport.blankToNull(c.interpretation()));
            lr.setResultedBy(TenantContext.actorId());
            lr.setResultedAt(at);
            lr.setSource(source);
            lr.setStatus(status);
            saved.add(results.save(lr));
        }
        results.flush();
        if (o.getCategory() == LabOrder.Category.IMAGING) {
            careTasks.closeForSource("LAB_ORDER", o.getId(), a, false, "Study performed and reported");
        }
        List<LabResult> critical = saved.stream().filter(x -> x.getFlag().isCritical()).toList();
        boolean abnormal = saved.stream().anyMatch(x -> x.getFlag() != LabResult.Flag.NORMAL);
        String summary = saved.stream().filter(x -> x.getFlag() != LabResult.Flag.NORMAL)
                .map(x -> x.getComponent() + " " + x.getValueText() + (x.getUnit() != null ? " " + x.getUnit() : "") + " (" + x.getFlag().name().toLowerCase().replace('_', ' ') + ")")
                .collect(Collectors.joining("; "));
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("RESULT_AVAILABLE").category(TimelineEvent.Category.INVESTIGATION)
                .title((wasReviewed ? "Additional result: " : "Result available: ") + o.getTestName()
                        + (source == LabResult.Source.LIS_ADAPTER ? " (LIS dev adapter)" : ""))
                .detail(summary.isEmpty() ? "All components within reference range" : ClinicalSupport.truncate(summary, 1000))
                .status(critical.isEmpty() ? (abnormal ? "ABNORMAL" : "RESULTED") : "CRITICAL")
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("LAB_ORDER").entityId(o.getId()).occurredAt(at).build());
        if (!critical.isEmpty()) {
            String values = critical.stream().map(x -> x.getComponent() + " " + x.getValueText() + (x.getUnit() != null ? " " + x.getUnit() : ""))
                    .collect(Collectors.joining(", "));
            ClinicalAlert al = alerts.raise(a, ClinicalAlert.AlertType.LAB_CRITICAL, ClinicalAlert.Severity.CRITICAL,
                    "Critical result: " + o.getTestName() + " - " + ClinicalSupport.truncate(values, 200),
                    "Critical value(s) reported for " + o.getTestName() + " (" + o.getOrderNumber() + "): " + values
                            + ". Review the patient and the result urgently.",
                    "LAB_ORDER", o.getId(), null, ClinicalSupport.truncate(values, 50), null, null, o.getOrderedBy(), at);
            notifications.notifyUsers(List.of(o.getOrderedBy(), a.getAttendingDoctorId()), NotificationRequest.builder()
                    .category("LAB_RESULT").priority(Notification.Priority.CRITICAL).requiresAck(true)
                    .title("CRITICAL result: " + o.getTestName()).message(values + " · " + a.getAdmissionNumber())
                    .link("/doctor/patients/" + a.getId()).entityType("LAB_ORDER").entityId(o.getId())
                    .dedupeKey("LAB_CRITICAL:" + al.getId()).build());
        } else {
            notifications.notifyUsers(List.of(o.getOrderedBy()), NotificationRequest.builder()
                    .category("LAB_RESULT").priority(abnormal ? Notification.Priority.HIGH : Notification.Priority.NORMAL)
                    .title("New result: " + o.getTestName()).message((abnormal ? "Abnormal values · " : "") + a.getAdmissionNumber())
                    .link("/doctor/investigations").entityType("LAB_ORDER").entityId(o.getId())
                    .dedupeKey("LAB_RESULT:" + o.getId() + ":" + saved.get(0).getId()).build());
        }
        audit.record("RESULT_ENTERED", "LAB_ORDER", o.getId(), a.getPatientId(),
                o.getOrderNumber() + " (" + saved.size() + " component(s), source " + source + ")");
        orders.flush();
        return views(List.of(o)).get(0);
    }

    @Transactional
    public LabOrderView review(UUID orderId, String comment) {
        LabOrder o = support.requireOrg(orders.findById(orderId), "Investigation");
        Admission a = support.requireClinical(o.getAdmissionId());
        if (o.getStatus() != LabOrder.Status.RESULTED) {
            throw ApiException.rule("NOT_RESULTED", "Only resulted investigations awaiting review can be reviewed.");
        }
        Instant now = Instant.now();
        UUID me = TenantContext.actorId();
        for (LabResult lr : results.findByLabOrderIdOrderByComponent(o.getId())) {
            if (lr.getReviewedAt() == null && lr.getStatus() != LabResult.Status.ENTERED_IN_ERROR) {
                lr.setReviewedBy(me);
                lr.setReviewedAt(now);
                lr.setReviewComment(ClinicalSupport.blankToNull(comment));
            }
        }
        o.transitionTo(LabOrder.Status.REVIEWED);
        alertRepository.findBySourceTypeAndSourceIdAndStatusIn("LAB_ORDER", o.getId(), AlertService.UNRESOLVED)
                .forEach(al -> alerts.doResolve(al, "Result reviewed" + (comment != null && !comment.isBlank() ? ": " + comment.trim() : "")));
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("RESULT_REVIEWED").category(TimelineEvent.Category.INVESTIGATION)
                .title("Result reviewed: " + o.getTestName()).detail(ClinicalSupport.blankToNull(comment)).status("REVIEWED")
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("LAB_ORDER").entityId(o.getId()).build());
        audit.record("RESULT_REVIEWED", "LAB_ORDER", o.getId(), a.getPatientId(), o.getOrderNumber());
        orders.flush();
        return views(List.of(o)).get(0);
    }

    @Transactional
    public LabOrderView release(UUID orderId) {
        LabOrder o = support.requireOrg(orders.findById(orderId), "Investigation");
        Admission a = support.requireClinical(o.getAdmissionId());
        if (o.getStatus() != LabOrder.Status.REVIEWED) {
            throw ApiException.rule("NOT_REVIEWED", "Results must be reviewed by a clinician before release to the patient.");
        }
        Instant now = Instant.now();
        int released = 0;
        for (LabResult lr : results.findByLabOrderIdOrderByComponent(o.getId())) {
            if (!lr.isReleasedToPatient() && lr.getStatus() != LabResult.Status.ENTERED_IN_ERROR) {
                lr.setReleasedToPatient(true);
                lr.setReleasedAt(now);
                lr.setReleasedBy(TenantContext.actorId());
                released++;
            }
        }
        if (released == 0) {
            throw ApiException.rule("ALREADY_RELEASED", "These results are already available to the patient.");
        }
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("RESULT_RELEASED").category(TimelineEvent.Category.INVESTIGATION)
                .title("Test result available: " + o.getTestName()).visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("LAB_ORDER").entityId(o.getId()).build());
        notifications.notifyPatient(a.getPatientId(), NotificationRequest.builder()
                .category("LAB_RESULT").title("A test result is available").message(o.getTestName())
                .link("/patient/records").entityType("LAB_ORDER").entityId(o.getId())
                .dedupeKey("RESULT_RELEASED:" + o.getId()).build());
        audit.record("RELEASED_TO_PATIENT", "LAB_ORDER", o.getId(), a.getPatientId(), o.getOrderNumber() + " " + o.getTestName());
        results.flush();
        return views(List.of(o)).get(0);
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<LabOrderView> forAdmission(UUID admissionId) {
        support.requireClinical(admissionId);
        return views(orders.findByAdmissionIdOrderByOrderedAtDesc(admissionId));
    }

    @Transactional(readOnly = true)
    public LabOrderView get(UUID orderId) {
        LabOrder o = support.requireOrg(orders.findById(orderId), "Investigation");
        support.requireClinical(o.getAdmissionId());
        return views(List.of(o)).get(0);
    }

    @Transactional(readOnly = true)
    public PageResponse<LabOrderView> worklist(Worklist view, int page, int size) {
        List<UUID> ids = support.myAdmissionIds();
        if (ids.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0, 0);
        }
        Collection<LabOrder.Status> st = switch (view) {
            case PENDING_COLLECTION -> EnumSet.of(LabOrder.Status.ORDERED);
            case AWAITING_RESULT -> EnumSet.of(LabOrder.Status.ORDERED, LabOrder.Status.COLLECTED, LabOrder.Status.IN_PROGRESS);
            case AWAITING_REVIEW -> EnumSet.of(LabOrder.Status.RESULTED);
            case OUTSTANDING -> EnumSet.of(LabOrder.Status.ORDERED, LabOrder.Status.COLLECTED, LabOrder.Status.IN_PROGRESS, LabOrder.Status.RESULTED);
            case ALL -> EnumSet.allOf(LabOrder.Status.class);
        };
        Sort sort = view == Worklist.ALL ? Sort.by(Sort.Direction.DESC, "orderedAt") : Sort.by(Sort.Direction.ASC, "orderedAt");
        Page<LabOrder> p = orders.findForAdmissions(ids, st, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), sort));
        List<LabOrder> content = p.getContent();
        if (view == Worklist.PENDING_COLLECTION) {
            content = content.stream().filter(o -> o.getCategory() == LabOrder.Category.LAB).toList();
        }
        return new PageResponse<>(views(content), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    public List<LabOrderView> views(List<LabOrder> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<LabResult>> byOrder = results.findByLabOrderIdIn(list.stream().map(LabOrder::getId).toList()).stream()
                .collect(Collectors.groupingBy(LabResult::getLabOrderId));
        Map<UUID, PatientRef> refs = support.refs(list.stream().map(LabOrder::getAdmissionId).distinct().toList());
        Map<UUID, String> names = support.userNames(Stream.concat(
                list.stream().flatMap(o -> Stream.of(o.getOrderedBy(), o.getCollectedBy(), o.getCancelledBy())),
                byOrder.values().stream().flatMap(List::stream).flatMap(r -> Stream.of(r.getResultedBy(), r.getReviewedBy()))).toList());
        return list.stream().map(o -> {
            List<LabResult> rs = byOrder.getOrDefault(o.getId(), List.of()).stream()
                    .sorted(java.util.Comparator.comparing(LabResult::getResultedAt).thenComparing(LabResult::getComponent)).toList();
            List<LabResultView> rv = rs.stream().map(r -> resultView(r, names)).toList();
            return new LabOrderView(o.getId(), o.getAdmissionId(), refs.get(o.getAdmissionId()), o.getOrderNumber(), o.getTariffId(),
                    o.getTestCode(), o.getTestName(), o.getCategory(), o.getSpecimen(), o.getPriority(), o.getStatus(),
                    o.getClinicalInfo(), o.getOrderedBy(), names.get(o.getOrderedBy()), o.getOrderedAt(), o.getDueAt(),
                    names.get(o.getCollectedBy()), o.getCollectedAt(), names.get(o.getCancelledBy()), o.getCancelledAt(),
                    o.getCancelReason(), o.isChargePosted(), rs.stream().anyMatch(r -> r.getFlag().isCritical()),
                    rs.stream().anyMatch(r -> r.getFlag() != LabResult.Flag.NORMAL),
                    !rs.isEmpty() && rs.stream().allMatch(LabResult::isReleasedToPatient), rv, o.getVersion());
        }).toList();
    }

    static LabResultView resultView(LabResult r, Map<UUID, String> names) {
        return new LabResultView(r.getId(), r.getComponent(), r.getValueText(), r.getValueNumeric(), r.getUnit(), r.getReferenceRange(),
                r.getFlag(), r.getInterpretation(), names.get(r.getResultedBy()), r.getResultedAt(), r.getSource(),
                names.get(r.getReviewedBy()), r.getReviewedAt(), r.getReviewComment(), r.isReleasedToPatient(), r.getReleasedAt(),
                r.getStatus());
    }

    /** Admission of an order (for jobs that run without a user). */
    Optional<Admission> admissionOf(LabOrder o) {
        return admissions.findById(o.getAdmissionId());
    }
}
