package com.careflow.hms.medication;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.clinical.AlertService;
import com.careflow.hms.clinical.ClinicalAlert;
import com.careflow.hms.clinical.ClinicalSupport;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.medication.MedicationDtos.MedicationOption;
import com.careflow.hms.medication.MedicationDtos.OrderRequest;
import com.careflow.hms.medication.MedicationDtos.OrderView;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.patient.Allergy;
import com.careflow.hms.patient.AllergyRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Prescribing: medication orders with allergy checking (documented override), high-alert flags,
 * modify (= discontinue + replacement order), hold / resume / discontinue and release to the patient.
 * Only users with PRESCRIBE reach these commands; pharmacist verification belongs to the pharmacy module.
 */
@Service
@RequiredArgsConstructor
public class MedicationOrderService {

    private final MedicationOrderRepository orders;
    private final MedicationRepository medications;
    private final AllergyRepository allergies;
    private final MarService mar;
    private final MedicationViews views;
    private final AlertService alerts;
    private final ClinicalSupport support;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    // ------------------------------------------------------------------ catalogue for prescribing

    @Transactional(readOnly = true)
    public List<MedicationOption> search(String q, UUID admissionId) {
        UUID org = TenantContext.requireOrganizationId();
        List<Allergy> drugAllergies = List.of();
        if (admissionId != null) {
            Admission a = support.requireClinical(admissionId);
            drugAllergies = activeDrugAllergies(a.getPatientId());
        }
        Page<Medication> page = medications.search(org, true, Paging.likePattern(q),
                PageRequest.of(0, 30, Sort.by("genericName", "strength")));
        List<Allergy> al = drugAllergies;
        return page.getContent().stream().map(m -> new MedicationOption(m.getId(), m.getCode(), m.displayName(), m.getGenericName(),
                m.getBrandName(), m.getForm().name(), m.getStrength(), m.getDefaultRoute(), m.getDrugClass(), m.getAllergenGroup(),
                m.isHighAlert(), m.isControlled(), conflicts(m, al).stream().map(MedicationOrderService::describe).toList())).toList();
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<OrderView> forAdmission(UUID admissionId, Collection<MedicationOrder.Status> statuses) {
        support.requireClinical(admissionId);
        List<MedicationOrder> list = orders.findByAdmissionIdOrderByPrescribedAtDesc(admissionId).stream()
                .filter(o -> statuses == null || statuses.isEmpty() || statuses.contains(o.getStatus())).toList();
        return views.orders(list);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderView> mine(Collection<MedicationOrder.Status> statuses, int page, int size) {
        List<UUID> ids = support.myAdmissionIds();
        if (ids.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0, 0);
        }
        Collection<MedicationOrder.Status> st = statuses == null || statuses.isEmpty()
                ? EnumSet.of(MedicationOrder.Status.ACTIVE, MedicationOrder.Status.ON_HOLD) : statuses;
        Page<MedicationOrder> p = orders.findForAdmissions(ids, st,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "prescribedAt")));
        return new PageResponse<>(views.orders(p.getContent()), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @Transactional(readOnly = true)
    public OrderView get(UUID orderId) {
        MedicationOrder o = support.requireOrg(orders.findById(orderId), "Medication order");
        support.requireClinical(o.getAdmissionId());
        return views.orders(List.of(o)).get(0);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public OrderView prescribe(UUID admissionId, OrderRequest r) {
        Admission a = support.requireOpenClinical(admissionId);
        MedicationOrder o = create(a, r, null);
        return views.orders(List.of(o)).get(0);
    }

    MedicationOrder create(Admission a, OrderRequest r, UUID replacesOrderId) {
        Medication med = medications.findByIdAndOrganizationId(r.medicationId(), a.getOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Medication"));
        if (!med.isActive()) {
            throw ApiException.rule("MEDICATION_INACTIVE", med.displayName() + " is not available for prescribing.");
        }
        Instant now = Instant.now();
        Instant start = (r.startAt() == null ? now : r.startAt()).truncatedTo(ChronoUnit.MINUTES);
        if (start.isBefore(now.minus(Duration.ofHours(24)))) {
            throw ApiException.badRequest("INVALID_START", "The start time cannot be more than 24 hours in the past.");
        }
        if (start.isAfter(now.plus(Duration.ofDays(30)))) {
            throw ApiException.badRequest("INVALID_START", "The start time cannot be more than 30 days ahead.");
        }
        Instant end = r.endAt();
        if (r.durationDays() != null) {
            end = start.plus(Duration.ofDays(r.durationDays()));
        }
        if (end != null && !end.isAfter(start)) {
            throw ApiException.badRequest("INVALID_END", "The end time must be after the start time.");
        }
        String times = null;
        if (r.frequency().isScheduled()) {
            times = r.scheduleTimes() == null || r.scheduleTimes().isBlank() ? r.frequency().defaultTimes() : normalizeTimes(r.scheduleTimes());
            int expected = expectedSlots(r.frequency());
            int given = times.split(",").length;
            if (expected > 0 && given != expected) {
                throw ApiException.badRequest("SCHEDULE_MISMATCH",
                        r.frequency() + " requires " + expected + " administration time(s); " + given + " given.");
            }
        } else if (r.scheduleTimes() != null && !r.scheduleTimes().isBlank()) {
            throw ApiException.badRequest("SCHEDULE_NOT_ALLOWED", r.frequency() + " orders do not have fixed administration times.");
        }

        // ---- allergy check (documented DRUG allergies vs allergen group / generic name)
        List<Allergy> conflicts = conflicts(med, activeDrugAllergies(a.getPatientId()));
        String override = ClinicalSupport.blankToNull(r.allergyOverrideReason());
        if (!conflicts.isEmpty() && (override == null || override.length() < 10)) {
            throw ApiException.rule("ALLERGY_CONFLICT", "Allergy conflict: " + med.displayName() + " matches documented allergy "
                    + String.join("; ", conflicts.stream().map(MedicationOrderService::describe).toList())
                    + ". To proceed, document a clinical override reason (at least 10 characters).");
        }

        MedicationOrder o = new MedicationOrder();
        o.setAdmissionId(a.getId());
        o.setPatientId(a.getPatientId());
        o.setMedicationId(med.getId());
        o.setDose(r.dose().trim());
        o.setDoseUnit(r.doseUnit().trim());
        o.setRoute(r.route());
        o.setFrequency(r.frequency());
        o.setScheduleTimes(times);
        o.setStartAt(start);
        o.setEndAt(end);
        o.setDurationDays(r.durationDays());
        o.setIndication(ClinicalSupport.blankToNull(r.indication()));
        o.setInstructions(ClinicalSupport.blankToNull(r.instructions()));
        o.setPatientInstructions(ClinicalSupport.blankToNull(r.patientInstructions()));
        o.setDischargeMedication(r.dischargeMedication());
        o.setStatus(MedicationOrder.Status.ACTIVE);
        o.setAllergyOverrideReason(conflicts.isEmpty() ? null : override);
        o.setPrescribedBy(TenantContext.actorId());
        o.setPrescribedAt(now);
        o.setReplacesOrderId(replacesOrderId);
        o = orders.saveAndFlush(o);

        int doses = mar.generate(o, start.isBefore(now) ? now.minus(Duration.ofMinutes(1)) : start, mar.horizonEnd(a.getOrganizationId()));
        String desc = med.displayName() + " " + o.getDose() + " " + o.getDoseUnit() + " " + o.getRoute() + " " + o.getFrequency();
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType(replacesOrderId == null ? "MEDICATION_PRESCRIBED" : "MEDICATION_MODIFIED").category(TimelineEvent.Category.MEDICATION)
                .title((replacesOrderId == null ? "Prescribed: " : "Prescription modified: ") + desc)
                .detail((o.getIndication() != null ? "Indication: " + o.getIndication() : "")
                        + (med.isHighAlert() ? " · HIGH-ALERT medication" : "")
                        + (o.getAllergyOverrideReason() != null ? " · Allergy override documented" : ""))
                .status("ACTIVE").visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("MEDICATION_ORDER").entityId(o.getId()).build());
        if (!conflicts.isEmpty()) {
            String allergyText = String.join("; ", conflicts.stream().map(MedicationOrderService::describe).toList());
            alerts.raise(a, ClinicalAlert.AlertType.ALLERGY, ClinicalAlert.Severity.WARNING,
                    "Allergy override: " + med.displayName(),
                    "Prescribed despite documented allergy (" + allergyText + "). Override reason: " + override
                            + ". Monitor for hypersensitivity reactions.",
                    "MEDICATION_ORDER", o.getId(), null, null, null, null, a.getAttendingDoctorId(), now);
            notifications.notifyWardNurses(a.getId(), NotificationRequest.builder()
                    .category("MEDICATION").priority(Notification.Priority.HIGH)
                    .title("Allergy override prescribed: " + med.displayName())
                    .message("Documented allergy: " + allergyText + ". Monitor closely. " + a.getAdmissionNumber())
                    .link("/nurse/patients/" + a.getId()).entityType("MEDICATION_ORDER").entityId(o.getId())
                    .dedupeKey("ALLERGY_OVERRIDE:" + o.getId()).build());
            audit.record("ALLERGY_OVERRIDE", "MEDICATION_ORDER", o.getId(), a.getPatientId(), AuditEvent.Outcome.SUCCESS,
                    med.displayName() + " prescribed despite " + allergyText + ". Reason: " + override,
                    Map.of("allergies", allergyText, "reason", override));
        } else if (med.isHighAlert()) {
            notifications.notifyWardNurses(a.getId(), NotificationRequest.builder()
                    .category("MEDICATION").priority(Notification.Priority.HIGH)
                    .title("High-alert medication prescribed: " + med.displayName())
                    .message(desc + " · " + a.getAdmissionNumber() + ". Independent double-check required before administration.")
                    .link("/nurse/patients/" + a.getId()).entityType("MEDICATION_ORDER").entityId(o.getId())
                    .dedupeKey("HIGH_ALERT_RX:" + o.getId()).build());
        }
        if (o.getFrequency() == MedicationOrder.Frequency.STAT) {
            notifications.notifyWardNurses(a.getId(), NotificationRequest.builder()
                    .category("MEDICATION_DUE").priority(Notification.Priority.HIGH)
                    .title("STAT dose ordered: " + med.displayName()).message(desc + " · " + a.getAdmissionNumber())
                    .link("/nurse/medications").entityType("MEDICATION_ORDER").entityId(o.getId())
                    .dedupeKey("STAT:" + o.getId()).build());
        }
        audit.record(replacesOrderId == null ? "MEDICATION_PRESCRIBED" : "MEDICATION_MODIFIED", "MEDICATION_ORDER", o.getId(),
                a.getPatientId(), desc + " (" + doses + " dose(s) scheduled)");
        return o;
    }

    /** Modification = discontinue the current order and create a replacement that references it. */
    @Transactional
    public OrderView modify(UUID orderId, OrderRequest r, String reason) {
        MedicationOrder old = support.requireOrg(orders.findById(orderId), "Medication order");
        Admission a = support.requireOpenClinical(old.getAdmissionId());
        if (old.getStatus() != MedicationOrder.Status.ACTIVE && old.getStatus() != MedicationOrder.Status.ON_HOLD) {
            throw ApiException.rule("ORDER_NOT_ACTIVE", "Only active or held orders can be modified.");
        }
        doDiscontinue(old, a, "Modified: " + reason.trim(), false);
        MedicationOrder replacement = create(a, r, old.getId());
        return views.orders(List.of(replacement)).get(0);
    }

    @Transactional
    public OrderView hold(UUID orderId, String reason) {
        MedicationOrder o = support.requireOrg(orders.findById(orderId), "Medication order");
        Admission a = support.requireOpenClinical(o.getAdmissionId());
        if (o.getStatus() != MedicationOrder.Status.ACTIVE) {
            throw ApiException.rule("ORDER_NOT_ACTIVE", "Only active orders can be put on hold.");
        }
        Instant now = Instant.now();
        o.setStatus(MedicationOrder.Status.ON_HOLD);
        o.setHoldReason(reason.trim());
        o.setHeldBy(TenantContext.actorId());
        o.setHeldAt(now);
        int cancelled = mar.cancelFuture(o, now, "Order on hold: " + reason.trim());
        recordStatusEvent(o, a, "MEDICATION_HELD", "Prescription on hold", reason, "ON_HOLD");
        audit.record("MEDICATION_HELD", "MEDICATION_ORDER", o.getId(), a.getPatientId(), reason + " (" + cancelled + " dose(s) cancelled)");
        orders.flush();
        return views.orders(List.of(o)).get(0);
    }

    @Transactional
    public OrderView resume(UUID orderId) {
        MedicationOrder o = support.requireOrg(orders.findById(orderId), "Medication order");
        Admission a = support.requireOpenClinical(o.getAdmissionId());
        if (o.getStatus() != MedicationOrder.Status.ON_HOLD) {
            throw ApiException.rule("ORDER_NOT_ON_HOLD", "Only orders on hold can be resumed.");
        }
        if (o.getEndAt() != null && o.getEndAt().isBefore(Instant.now())) {
            throw ApiException.rule("ORDER_EXPIRED", "The course has ended; prescribe a new order instead.");
        }
        o.setStatus(MedicationOrder.Status.ACTIVE);
        o.setHoldReason(null);
        o.setHeldBy(null);
        o.setHeldAt(null);
        orders.saveAndFlush(o);
        int doses = mar.generate(o, Instant.now(), mar.horizonEnd(a.getOrganizationId()));
        recordStatusEvent(o, a, "MEDICATION_RESUMED", "Prescription resumed", null, "ACTIVE");
        audit.record("MEDICATION_RESUMED", "MEDICATION_ORDER", o.getId(), a.getPatientId(), doses + " dose(s) scheduled");
        return views.orders(List.of(o)).get(0);
    }

    @Transactional
    public OrderView discontinue(UUID orderId, String reason) {
        MedicationOrder o = support.requireOrg(orders.findById(orderId), "Medication order");
        Admission a = support.requireClinical(o.getAdmissionId());
        if (o.getStatus() != MedicationOrder.Status.ACTIVE && o.getStatus() != MedicationOrder.Status.ON_HOLD) {
            throw ApiException.rule("ORDER_NOT_ACTIVE", "This order is already " + o.getStatus().name().toLowerCase() + ".");
        }
        doDiscontinue(o, a, reason.trim(), true);
        return views.orders(List.of(o)).get(0);
    }

    private void doDiscontinue(MedicationOrder o, Admission a, String reason, boolean notifyNurses) {
        Instant now = Instant.now();
        o.setStatus(MedicationOrder.Status.DISCONTINUED);
        o.setDiscontinuedBy(TenantContext.actorId());
        o.setDiscontinuedAt(now);
        o.setDiscontinueReason(reason);
        int cancelled = mar.cancelFuture(o, now, "Order discontinued");
        recordStatusEvent(o, a, "MEDICATION_DISCONTINUED", "Prescription discontinued", reason, "DISCONTINUED");
        if (notifyNurses) {
            String name = medications.findById(o.getMedicationId()).map(Medication::displayName).orElse("medication");
            notifications.notifyWardNurses(a.getId(), NotificationRequest.builder()
                    .category("MEDICATION").title("Medication discontinued: " + name).message(reason + " · " + a.getAdmissionNumber())
                    .link("/nurse/patients/" + a.getId()).entityType("MEDICATION_ORDER").entityId(o.getId())
                    .dedupeKey("MED_DC:" + o.getId()).build());
        }
        audit.record("MEDICATION_DISCONTINUED", "MEDICATION_ORDER", o.getId(), a.getPatientId(), reason + " (" + cancelled + " dose(s) cancelled)");
        orders.flush();
    }

    @Transactional
    public OrderView release(UUID orderId, String patientInstructions) {
        MedicationOrder o = support.requireOrg(orders.findById(orderId), "Medication order");
        Admission a = support.requireClinical(o.getAdmissionId());
        if (o.getStatus() == MedicationOrder.Status.DISCONTINUED && !o.isDischargeMedication()) {
            throw ApiException.rule("ORDER_DISCONTINUED", "Discontinued orders are not released to the patient.");
        }
        String text = ClinicalSupport.blankToNull(patientInstructions) != null ? patientInstructions.trim() : o.getPatientInstructions();
        if (text == null) {
            throw ApiException.badRequest("PATIENT_INSTRUCTIONS_REQUIRED", "Add plain-language instructions before releasing to the patient.");
        }
        o.setPatientInstructions(text);
        o.setReleasedToPatient(true);
        String name = medications.findById(o.getMedicationId()).map(Medication::displayName).orElse("medication");
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("MEDICATION_RELEASED").category(TimelineEvent.Category.MEDICATION)
                .title("Medicine information shared: " + name).visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("MEDICATION_ORDER").entityId(o.getId()).build());
        notifications.notifyPatient(a.getPatientId(), NotificationRequest.builder()
                .category("MEDICATION").title("Medicine information available").message(name)
                .link("/patient/medications").entityType("MEDICATION_ORDER").entityId(o.getId())
                .dedupeKey("MED_RELEASED:" + o.getId()).build());
        audit.record("RELEASED_TO_PATIENT", "MEDICATION_ORDER", o.getId(), a.getPatientId(), name);
        orders.flush();
        return views.orders(List.of(o)).get(0);
    }

    private void recordStatusEvent(MedicationOrder o, Admission a, String type, String title, String reason, String status) {
        String name = medications.findById(o.getMedicationId()).map(Medication::displayName).orElse("medication");
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType(type).category(TimelineEvent.Category.MEDICATION)
                .title(title + ": " + name).detail(reason).status(status).visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("MEDICATION_ORDER").entityId(o.getId()).build());
    }

    // ------------------------------------------------------------------ allergy matching

    List<Allergy> activeDrugAllergies(UUID patientId) {
        return allergies.findByPatientIdAndStatus(patientId, Allergy.Status.ACTIVE).stream()
                .filter(x -> x.getAllergyType() == Allergy.AllergyType.DRUG).toList();
    }

    /** Matches documented DRUG allergies against the medication's allergen group and generic name. */
    static List<Allergy> conflicts(Medication m, List<Allergy> drugAllergies) {
        List<Allergy> out = new ArrayList<>();
        String group = m.getAllergenGroup() == null ? null : m.getAllergenGroup().toLowerCase(Locale.ROOT);
        String generic = m.getGenericName().toLowerCase(Locale.ROOT);
        for (Allergy al : drugAllergies) {
            String allergen = al.getAllergen().toLowerCase(Locale.ROOT);
            List<String> tokens = Arrays.stream(allergen.split("[^a-z0-9]+")).filter(t -> t.length() >= 4).toList();
            boolean match = false;
            if (group != null) {
                match = allergen.contains(group) || tokens.stream().anyMatch(t -> group.contains(stem(t)));
            }
            if (!match) {
                match = tokens.stream().anyMatch(t -> generic.contains(stem(t)));
            }
            if (match) {
                out.add(al);
            }
        }
        return out;
    }

    private static String stem(String token) {
        return token.endsWith("s") && token.length() > 4 ? token.substring(0, token.length() - 1) : token;
    }

    static String describe(Allergy a) {
        return a.getAllergen() + " (" + a.getSeverity().name().toLowerCase().replace('_', '-')
                + (a.getReaction() != null ? ": " + a.getReaction() : "") + ")";
    }

    private static String normalizeTimes(String csv) {
        return String.join(",", MarService.parseTimes(csv).stream().map(t -> t.toString()).toList());
    }

    private static int expectedSlots(MedicationOrder.Frequency f) {
        return switch (f) {
            case OD, HS, WEEKLY -> 1;
            case BD, Q12H -> 2;
            case TDS, Q8H -> 3;
            case QID, Q6H -> 4;
            case Q4H -> 6;
            default -> 0;
        };
    }
}
