package com.careflow.hms.medication;

import com.careflow.hms.clinical.ClinicalSupport;
import com.careflow.hms.clinical.ClinicalSupport.PatientRef;
import com.careflow.hms.medication.MedicationDtos.DoseView;
import com.careflow.hms.medication.MedicationDtos.OrderView;
import com.careflow.hms.org.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Batch mapping of orders and MAR rows to API views (names, patient refs, derived dose status). */
@Component
@RequiredArgsConstructor
public class MedicationViews {

    private final MedicationRepository medications;
    private final MedicationAdministrationRepository administrations;
    private final ClinicalSupport support;
    private final SettingsService settings;

    public Map<UUID, Medication> medications(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return medications.findByIdIn(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(Medication::getId, Function.identity()));
    }

    public List<OrderView> orders(List<MedicationOrder> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, Medication> meds = medications(list.stream().map(MedicationOrder::getMedicationId).toList());
        Map<UUID, PatientRef> refs = support.refs(list.stream().map(MedicationOrder::getAdmissionId).distinct().toList());
        Map<UUID, String> names = support.userNames(list.stream()
                .flatMap(o -> Stream.of(o.getPrescribedBy(), o.getVerifiedByPharmacist(), o.getDiscontinuedBy())).toList());
        List<UUID> ids = list.stream().map(MedicationOrder::getId).toList();
        Map<UUID, Instant> lastGiven = new HashMap<>();
        administrations.findByOrderIdInAndStatusOrderByAdministeredAtDesc(ids, MedicationAdministration.Status.ADMINISTERED)
                .forEach(m -> lastGiven.merge(m.getOrderId(), m.getAdministeredAt() == null ? m.getScheduledAt() : m.getAdministeredAt(),
                        (a, b) -> a.isAfter(b) ? a : b));
        Map<UUID, Instant> next = new HashMap<>();
        Instant cutoff = Instant.now().minus(Duration.ofHours(12));
        administrations.findByOrderIdInAndStatusAndScheduledAtAfterOrderByScheduledAtAsc(ids, MedicationAdministration.Status.SCHEDULED, cutoff)
                .forEach(m -> next.putIfAbsent(m.getOrderId(), m.getScheduledAt()));
        return list.stream().map(o -> {
            Medication m = meds.get(o.getMedicationId());
            return new OrderView(o.getId(), o.getAdmissionId(), refs.get(o.getAdmissionId()), o.getMedicationId(),
                    m == null ? "Unknown medication" : m.displayName(), m == null ? null : m.getGenericName(),
                    m == null ? null : m.getStrength(), m == null ? null : m.getForm().name(), m != null && m.isHighAlert(),
                    m != null && m.isControlled(), o.getDose(), o.getDoseUnit(), o.getRoute(), o.getFrequency(),
                    o.getScheduleTimes(), o.getStartAt(), o.getEndAt(), o.getDurationDays(), o.getIndication(), o.getInstructions(),
                    o.getPatientInstructions(), o.isDischargeMedication(), o.getStatus(), o.getAllergyOverrideReason(),
                    o.getPrescribedBy(), names.get(o.getPrescribedBy()), o.getPrescribedAt(), names.get(o.getVerifiedByPharmacist()),
                    o.getVerifiedAt(), names.get(o.getDiscontinuedBy()), o.getDiscontinuedAt(), o.getDiscontinueReason(),
                    o.getHoldReason(), o.getHeldAt(), o.getReplacesOrderId(), o.isReleasedToPatient(),
                    o.getStatus() == MedicationOrder.Status.ACTIVE ? next.get(o.getId()) : null, lastGiven.get(o.getId()),
                    o.getVersion());
        }).toList();
    }

    public List<DoseView> doses(List<MedicationAdministration> list, Map<UUID, MedicationOrder> ordersById) {
        if (list.isEmpty()) {
            return List.of();
        }
        UUID org = list.get(0).getOrganizationId();
        int window = settings.getInt(org, SettingsService.MED_DUE_WINDOW_MINUTES);
        int grace = settings.getInt(org, SettingsService.MED_OVERDUE_GRACE_MINUTES);
        Map<UUID, Medication> meds = medications(ordersById.values().stream().map(MedicationOrder::getMedicationId).toList());
        Map<UUID, PatientRef> refs = support.refs(list.stream().map(MedicationAdministration::getAdmissionId).distinct().toList());
        Map<UUID, String> names = support.userNames(list.stream()
                .flatMap(m -> Stream.of(m.getRecordedBy(), m.getFollowUpBy(), m.getWitnessUserId())).filter(Objects::nonNull).toList());
        Instant now = Instant.now();
        return list.stream().map(m -> {
            MedicationOrder o = ordersById.get(m.getOrderId());
            Medication med = o == null ? null : meds.get(o.getMedicationId());
            String display = displayStatus(m, now, window, grace);
            int late = (int) Math.max(0, Duration.between(m.getScheduledAt(), m.getAdministeredAt() != null ? m.getAdministeredAt() : now).toMinutes());
            return new DoseView(m.getId(), m.getOrderId(), m.getAdmissionId(), refs.get(m.getAdmissionId()),
                    med == null ? "Unknown medication" : med.displayName(), med == null ? null : med.getStrength(),
                    o == null ? null : o.getDose(), o == null ? null : o.getDoseUnit(), o == null ? null : o.getRoute(),
                    o == null ? null : o.getFrequency(), o == null ? null : o.getInstructions(), med != null && med.isHighAlert(),
                    med != null && med.isControlled(), o == null ? null : o.getStatus(), m.getScheduledAt(), m.getStatus(), display,
                    m.getAdministeredAt(), m.getDoseGiven(), m.getRouteGiven(), m.getSite(), m.getReason(), m.getNotes(),
                    names.get(m.getRecordedBy()), m.getRecordedAt(), names.get(m.getWitnessUserId()), m.getOverdueFlaggedAt(),
                    m.getFollowUpStatus(), m.getFollowUpNotes(), names.get(m.getFollowUpBy()), m.getFollowUpAt(),
                    m.getResponsibleTeam(), m.getStatus() == MedicationAdministration.Status.SCHEDULED
                    || m.getStatus() == MedicationAdministration.Status.ADMINISTERED ? late : 0, m.getVersion());
        }).toList();
    }

    /** SCHEDULED doses are shown as SCHEDULED (upcoming), DUE (within the due window) or OVERDUE (past the grace period). */
    public static String displayStatus(MedicationAdministration m, Instant now, int windowMinutes, int graceMinutes) {
        if (m.getStatus() != MedicationAdministration.Status.SCHEDULED) {
            return m.getStatus().name();
        }
        if (m.getScheduledAt().isBefore(now.minus(Duration.ofMinutes(graceMinutes)))) {
            return "OVERDUE";
        }
        if (!m.getScheduledAt().isAfter(now.plus(Duration.ofMinutes(windowMinutes)))) {
            return "DUE";
        }
        return "SCHEDULED";
    }

    public static Comparator<MedicationAdministration> bySchedule() {
        return Comparator.comparing(MedicationAdministration::getScheduledAt);
    }
}
