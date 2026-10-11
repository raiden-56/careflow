package com.careflow.hms.medication;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.clinical.ClinicalSupport;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.medication.MedicationDtos.AdministerRequest;
import com.careflow.hms.medication.MedicationDtos.DoseView;
import com.careflow.hms.medication.MedicationDtos.ExceptionRequest;
import com.careflow.hms.medication.MedicationDtos.FollowUpRequest;
import com.careflow.hms.medication.MedicationDtos.OrderView;
import com.careflow.hms.medication.MedicationDtos.PrnDoseRequest;
import com.careflow.hms.medication.MedicationDtos.RoundGroup;
import com.careflow.hms.medication.MedicationDtos.RoundsView;
import com.careflow.hms.notification.EscalationRule;
import com.careflow.hms.notification.EscalationRuleRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.org.SettingsService;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Medication administration record (MAR): dose schedule generation from orders, medication rounds,
 * administration and exception recording (never changes the prescription), PRN/STAT doses, the
 * exception queue with follow-up, and the overdue escalation job.
 */
@Service
@RequiredArgsConstructor
public class MarService {

    private static final Set<MedicationAdministration.Status> PENDING = EnumSet.of(MedicationAdministration.Status.SCHEDULED,
            MedicationAdministration.Status.DELAYED);

    private final MedicationAdministrationRepository administrations;
    private final MedicationOrderRepository orders;
    private final MedicationRepository medications;
    private final AdmissionRepository admissions;
    private final UserRepository users;
    private final EscalationRuleRepository escalationRules;
    private final MedicationViews views;
    private final ClinicalSupport support;
    private final SettingsService settings;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;
    private final com.careflow.hms.org.OrganizationRepository organizations;

    // ================================================================== schedule generation

    /**
     * Creates SCHEDULED rows for an order's hospital-local administration times between {@code from}
     * and {@code to} (unique per order + time). STAT/ONCE orders get a single dose at the start time;
     * PRN orders have no schedule (doses are recorded ad hoc).
     */
    @Transactional
    public int generate(MedicationOrder o, Instant from, Instant to) {
        if (o.getStatus() != MedicationOrder.Status.ACTIVE) {
            return 0;
        }
        Set<Instant> existing = new HashSet<>(administrations.scheduledTimes(o.getId()));
        List<Instant> times = new ArrayList<>();
        MedicationOrder.Frequency f = o.getFrequency();
        if (f == MedicationOrder.Frequency.STAT || f == MedicationOrder.Frequency.ONCE) {
            if (existing.isEmpty()) {
                times.add(o.getStartAt().truncatedTo(ChronoUnit.MINUTES));
            }
        } else if (f.isScheduled()) {
            ZoneId zone = zoneOf(o.getOrganizationId());
            Instant start = from.isBefore(o.getStartAt()) ? o.getStartAt() : from;
            Instant end = o.getEndAt() != null && o.getEndAt().isBefore(to) ? o.getEndAt() : to;
            if (!start.isBefore(end)) {
                return 0;
            }
            List<LocalTime> slots = parseTimes(o.getScheduleTimes() == null || o.getScheduleTimes().isBlank()
                    ? f.defaultTimes() : o.getScheduleTimes());
            LocalDate startDay = start.atZone(zone).toLocalDate();
            LocalDate weeklyDay = o.getStartAt().atZone(zone).toLocalDate();
            for (LocalDate d = startDay; !d.atStartOfDay(zone).toInstant().isAfter(end); d = d.plusDays(1)) {
                if (f == MedicationOrder.Frequency.WEEKLY && d.getDayOfWeek() != weeklyDay.getDayOfWeek()) {
                    continue;
                }
                for (LocalTime t : slots) {
                    Instant at = d.atTime(t).atZone(zone).toInstant();
                    if (!at.isBefore(start) && at.isBefore(end)) {
                        times.add(at);
                    }
                }
            }
        }
        int created = 0;
        for (Instant at : times) {
            if (existing.add(at)) {
                MedicationAdministration m = new MedicationAdministration();
                m.setOrganizationId(o.getOrganizationId());
                m.setOrderId(o.getId());
                m.setAdmissionId(o.getAdmissionId());
                m.setPatientId(o.getPatientId());
                m.setScheduledAt(at);
                m.setStatus(MedicationAdministration.Status.SCHEDULED);
                administrations.save(m);
                created++;
            }
        }
        return created;
    }

    /** Cancels SCHEDULED doses of an order from {@code after} onwards (hold / discontinue / completion). */
    @Transactional
    public int cancelFuture(MedicationOrder o, Instant after, String reason) {
        int n = 0;
        for (MedicationAdministration m : administrations.findByOrderIdAndStatusAndScheduledAtAfter(o.getId(),
                MedicationAdministration.Status.SCHEDULED, after)) {
            m.setStatus(MedicationAdministration.Status.CANCELLED);
            m.setReason(reason);
            m.setRecordedAt(Instant.now());
            n++;
        }
        return n;
    }

    static List<LocalTime> parseTimes(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(LocalTime::parse).distinct().sorted().toList();
    }

    private ZoneId zoneOf(UUID orgId) {
        return organizations.findById(orgId).map(org -> org.zone()).orElse(TimeUtil.DEFAULT_ZONE);
    }

    private int horizonHours(UUID orgId) {
        return settings.getInt(orgId, SettingsService.MED_SCHEDULE_HORIZON_HOURS);
    }

    public Instant horizonEnd(UUID orgId) {
        return Instant.now().plus(Duration.ofHours(horizonHours(orgId)));
    }

    // ================================================================== rounds & lists

    /** Medication round for the caller's patients: overdue/due/upcoming doses grouped by scheduled time. */
    @Transactional(readOnly = true)
    public RoundsView rounds(int hoursAhead, UUID wardId) {
        CurrentUser u = TenantContext.requireUser();
        List<AdmissionSummary> mine = support.myAdmissions().stream()
                .filter(s -> wardId == null || s.location() != null && wardId.equals(s.location().wardId()))
                .toList();
        UUID org = u.organizationId();
        int window = settings.getInt(org, SettingsService.MED_DUE_WINDOW_MINUTES);
        int grace = settings.getInt(org, SettingsService.MED_OVERDUE_GRACE_MINUTES);
        boolean mayHold = Boolean.parseBoolean(settings.get(org, SettingsService.NURSE_MAY_HOLD_DOSE));
        Instant now = Instant.now();
        if (mine.isEmpty()) {
            return new RoundsView(now, window, grace, mayHold, 0, 0, 0, List.of(), List.of());
        }
        List<UUID> ids = mine.stream().map(AdmissionSummary::id).toList();
        int ahead = Math.min(Math.max(hoursAhead, 1), 24);
        List<MedicationAdministration> list = administrations.findByAdmissionIdInAndStatusInAndScheduledAtBetweenOrderByScheduledAtAsc(
                ids, PENDING, now.minus(Duration.ofHours(24)), now.plus(Duration.ofHours(ahead)));
        Map<UUID, MedicationOrder> byId = ordersById(list);
        List<DoseView> doses = views.doses(list, byId);
        Map<Instant, List<DoseView>> grouped = new LinkedHashMap<>();
        for (DoseView d : doses) {
            grouped.computeIfAbsent(d.scheduledAt().truncatedTo(ChronoUnit.HOURS), k -> new ArrayList<>()).add(d);
        }
        List<RoundGroup> groups = grouped.entrySet().stream().map(e -> new RoundGroup(e.getKey(), e.getValue())).toList();
        int due = (int) doses.stream().filter(d -> "DUE".equals(d.displayStatus())).count();
        int overdue = (int) doses.stream().filter(d -> "OVERDUE".equals(d.displayStatus())).count();
        int upcoming = (int) doses.stream().filter(d -> "SCHEDULED".equals(d.displayStatus())).count();
        List<MedicationOrder> prn = orders.findByAdmissionIdInAndStatusIn(ids, EnumSet.of(MedicationOrder.Status.ACTIVE)).stream()
                .filter(o -> o.getFrequency() == MedicationOrder.Frequency.PRN)
                .toList();
        List<OrderView> prnViews = views.orders(prn);
        return new RoundsView(now, window, grace, mayHold, due, overdue, upcoming, groups, prnViews);
    }

    @Transactional(readOnly = true)
    public List<DoseView> forAdmission(UUID admissionId, Instant from, Instant to) {
        Admission a = support.requireClinical(admissionId);
        Instant f = from != null ? from : a.getAdmittedAt().minus(Duration.ofHours(1));
        Instant t = to != null ? to : Instant.now().plus(Duration.ofHours(48));
        List<MedicationAdministration> list = administrations.findByAdmissionIdAndScheduledAtBetweenOrderByScheduledAtAsc(a.getId(), f, t)
                .stream().filter(m -> m.getStatus() != MedicationAdministration.Status.CANCELLED).toList();
        return views.doses(list, ordersById(list));
    }

    /** Administration history across the caller's patients (or one admission), newest first. */
    @Transactional(readOnly = true)
    public PageResponse<DoseView> history(UUID admissionId, Collection<MedicationAdministration.Status> statuses, Instant from,
                                          Instant to, int page, int size) {
        List<UUID> ids;
        if (admissionId != null) {
            support.requireClinical(admissionId);
            ids = List.of(admissionId);
        } else {
            ids = support.myAdmissionIds();
        }
        if (ids.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0, 0);
        }
        Collection<MedicationAdministration.Status> st = statuses == null || statuses.isEmpty()
                ? EnumSet.of(MedicationAdministration.Status.ADMINISTERED, MedicationAdministration.Status.DELAYED,
                MedicationAdministration.Status.REFUSED, MedicationAdministration.Status.MISSED, MedicationAdministration.Status.HELD)
                : statuses;
        Instant t = to != null ? to : Instant.now().plus(Duration.ofHours(1));
        Instant f = from != null ? from : t.minus(Duration.ofDays(14));
        Page<MedicationAdministration> p = administrations.findHistory(ids, st, f, t,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "scheduledAt")));
        return new PageResponse<>(views.doses(p.getContent(), ordersById(p.getContent())), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    @Transactional(readOnly = true)
    public PageResponse<DoseView> exceptionQueue(Collection<MedicationAdministration.FollowUpStatus> followUps, int page, int size) {
        List<UUID> ids = support.myAdmissionIds();
        if (ids.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0, 0);
        }
        Collection<MedicationAdministration.FollowUpStatus> fu = followUps == null || followUps.isEmpty()
                ? EnumSet.of(MedicationAdministration.FollowUpStatus.OPEN, MedicationAdministration.FollowUpStatus.IN_PROGRESS)
                : followUps;
        Page<MedicationAdministration> p = administrations.findExceptionQueue(ids, fu,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "scheduledAt")));
        return new PageResponse<>(views.doses(p.getContent(), ordersById(p.getContent())), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    private Map<UUID, MedicationOrder> ordersById(List<MedicationAdministration> list) {
        return orders.findAllById(list.stream().map(MedicationAdministration::getOrderId).distinct().toList()).stream()
                .collect(Collectors.toMap(MedicationOrder::getId, Function.identity()));
    }

    // ================================================================== recording

    @Transactional
    public DoseView administer(UUID administrationId, AdministerRequest r) {
        MedicationAdministration m = support.requireOrg(administrations.findById(administrationId), "Dose");
        Admission a = support.requireOpenClinical(m.getAdmissionId());
        checkVersion(m, r.version());
        if (!PENDING.contains(m.getStatus())) {
            throw ApiException.conflict("DOSE_ALREADY_RECORDED", "This dose is already recorded as " + m.getStatus().name().toLowerCase() + ".");
        }
        MedicationOrder o = orders.findById(m.getOrderId()).orElseThrow();
        if (o.getStatus() != MedicationOrder.Status.ACTIVE) {
            throw ApiException.rule("ORDER_NOT_ACTIVE", "The prescription is " + o.getStatus().name().toLowerCase().replace('_', ' ')
                    + "; this dose cannot be given. Record an exception or contact the prescriber.");
        }
        Instant now = Instant.now();
        Instant at = r.administeredAt() == null ? now : r.administeredAt();
        if (at.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("FUTURE_TIME", "Administration time cannot be in the future.");
        }
        if (at.isBefore(m.getScheduledAt().minus(Duration.ofHours(2)))) {
            throw ApiException.rule("TOO_EARLY", "This dose is scheduled for " + java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(zoneOf(m.getOrganizationId())).format(m.getScheduledAt())
                    + "; administration more than 2 hours early is not allowed.");
        }
        Medication med = medications.findById(o.getMedicationId()).orElseThrow();
        m.setStatus(MedicationAdministration.Status.ADMINISTERED);
        m.setAdministeredAt(at);
        m.setDoseGiven(ClinicalSupport.blankToNull(r.doseGiven()) != null ? r.doseGiven().trim() : o.getDose() + " " + o.getDoseUnit());
        m.setRouteGiven((r.routeGiven() != null ? r.routeGiven() : o.getRoute()).name());
        m.setSite(ClinicalSupport.blankToNull(r.site()));
        m.setNotes(ClinicalSupport.blankToNull(r.notes()));
        m.setWitnessUserId(validWitness(r.witnessUserId()));
        m.setRecordedBy(TenantContext.actorId());
        m.setRecordedAt(now);
        if (m.getFollowUpStatus() == MedicationAdministration.FollowUpStatus.OPEN && m.getOverdueFlaggedAt() != null) {
            m.setFollowUpStatus(MedicationAdministration.FollowUpStatus.RESOLVED);
            m.setFollowUpNotes("Administered late at " + at);
            m.setFollowUpBy(TenantContext.actorId());
            m.setFollowUpAt(now);
        }
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("MEDICATION_ADMINISTERED").category(TimelineEvent.Category.MEDICATION)
                .title("Administered: " + med.displayName() + " " + m.getDoseGiven() + " " + m.getRouteGiven())
                .detail(lateness(m.getScheduledAt(), at) + (m.getSite() != null ? " · site " + m.getSite() : "")
                        + (m.getNotes() != null ? " · " + m.getNotes() : ""))
                .status("ADMINISTERED").visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("MEDICATION_ADMINISTRATION").entityId(m.getId()).occurredAt(at).build());
        audit.record("MEDICATION_ADMINISTERED", "MEDICATION_ADMINISTRATION", m.getId(), a.getPatientId(),
                med.displayName() + " " + m.getDoseGiven());
        administrations.flush();
        return views.doses(List.of(m), Map.of(o.getId(), o)).get(0);
    }

    /** Records DELAYED / REFUSED / MISSED / HELD with a mandatory reason. The prescription itself is unchanged. */
    @Transactional
    public DoseView recordException(UUID administrationId, ExceptionRequest r) {
        MedicationAdministration m = support.requireOrg(administrations.findById(administrationId), "Dose");
        Admission a = support.requireOpenClinical(m.getAdmissionId());
        checkVersion(m, r.version());
        if (!m.getStatus().equals(MedicationAdministration.Status.SCHEDULED) && m.getStatus() != MedicationAdministration.Status.DELAYED) {
            throw ApiException.conflict("DOSE_ALREADY_RECORDED", "This dose is already recorded as " + m.getStatus().name().toLowerCase() + ".");
        }
        if (!r.status().isException()) {
            throw ApiException.badRequest("INVALID_EXCEPTION", "Exceptions are DELAYED, REFUSED, MISSED or HELD.");
        }
        if (r.reason() == null || r.reason().trim().length() < 3) {
            throw ApiException.badRequest("REASON_REQUIRED", "A reason is required for this exception.");
        }
        if (r.status() == MedicationAdministration.Status.HELD && TenantContext.requireUser().role() == Role.NURSE
                && !Boolean.parseBoolean(settings.get(a.getOrganizationId(), SettingsService.NURSE_MAY_HOLD_DOSE))) {
            throw ApiException.rule("HOLD_NOT_PERMITTED", "Hospital policy does not allow nurses to hold a dose. Contact the prescriber.");
        }
        if (m.getStatus() == MedicationAdministration.Status.DELAYED && r.status() == MedicationAdministration.Status.DELAYED) {
            throw ApiException.rule("ALREADY_DELAYED", "This dose is already recorded as delayed.");
        }
        MedicationOrder o = orders.findById(m.getOrderId()).orElseThrow();
        Medication med = medications.findById(o.getMedicationId()).orElseThrow();
        Instant now = Instant.now();
        m.setStatus(r.status());
        m.setReason(r.reason().trim());
        m.setNotes(ClinicalSupport.blankToNull(r.notes()));
        m.setRecordedBy(TenantContext.actorId());
        m.setRecordedAt(now);
        m.setFollowUpStatus(MedicationAdministration.FollowUpStatus.OPEN);
        String wardName = support.currentWardId(a.getId()).flatMap(support::ward).map(w -> w.getName()).orElse("Ward");
        m.setResponsibleTeam(r.status() == MedicationAdministration.Status.DELAYED ? wardName + " nursing"
                : wardName + " nursing · " + support.userName(a.getAttendingDoctorId()));
        String label = switch (r.status()) {
            case DELAYED -> "Dose delayed";
            case REFUSED -> "Dose refused by patient";
            case MISSED -> "Dose missed";
            case HELD -> "Dose held";
            default -> "Dose exception";
        };
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("MEDICATION_" + r.status().name()).category(TimelineEvent.Category.MEDICATION)
                .title(label + ": " + med.displayName()).detail("Reason: " + m.getReason())
                .status(r.status().name()).visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("MEDICATION_ADMINISTRATION").entityId(m.getId()).build());
        if (r.status() != MedicationAdministration.Status.DELAYED) {
            notifications.notifyUsers(List.of(a.getAttendingDoctorId()), NotificationRequest.builder()
                    .category("MEDICATION_EXCEPTION")
                    .priority(med.isHighAlert() ? Notification.Priority.HIGH : Notification.Priority.NORMAL)
                    .title(label + ": " + med.displayName())
                    .message(m.getReason() + " · " + a.getAdmissionNumber())
                    .link("/doctor/patients/" + a.getId()).entityType("MEDICATION_ADMINISTRATION").entityId(m.getId())
                    .dedupeKey("MED_EXCEPTION:" + m.getId() + ":" + r.status()).build());
        }
        audit.record("MEDICATION_" + r.status().name(), "MEDICATION_ADMINISTRATION", m.getId(), a.getPatientId(),
                med.displayName() + ": " + m.getReason());
        administrations.flush();
        return views.doses(List.of(m), Map.of(o.getId(), o)).get(0);
    }

    /** Ad hoc dose against an active PRN / STAT / ONCE order. */
    @Transactional
    public DoseView recordAdHoc(UUID orderId, PrnDoseRequest r) {
        MedicationOrder o = support.requireOrg(orders.findById(orderId), "Medication order");
        Admission a = support.requireOpenClinical(o.getAdmissionId());
        if (o.getStatus() != MedicationOrder.Status.ACTIVE) {
            throw ApiException.rule("ORDER_NOT_ACTIVE", "The prescription is not active.");
        }
        if (o.getFrequency() != MedicationOrder.Frequency.PRN) {
            throw ApiException.rule("NOT_PRN", "Ad hoc doses can only be recorded against PRN orders; use the scheduled dose instead.");
        }
        Instant now = Instant.now();
        Instant at = (r.administeredAt() == null ? now : r.administeredAt()).truncatedTo(ChronoUnit.SECONDS);
        if (at.isAfter(now.plus(Duration.ofMinutes(5))) || at.isBefore(o.getStartAt().minus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("INVALID_TIME", "Administration time must be after the order start and not in the future.");
        }
        if (administrations.existsByOrderIdAndScheduledAt(o.getId(), at)) {
            throw ApiException.conflict("DUPLICATE_DOSE", "A dose of this order is already recorded at that exact time.");
        }
        Medication med = medications.findById(o.getMedicationId()).orElseThrow();
        MedicationAdministration m = new MedicationAdministration();
        m.setOrderId(o.getId());
        m.setAdmissionId(a.getId());
        m.setPatientId(a.getPatientId());
        m.setScheduledAt(at);
        m.setStatus(MedicationAdministration.Status.ADMINISTERED);
        m.setAdministeredAt(at);
        m.setDoseGiven(r.doseGiven().trim());
        m.setRouteGiven((r.routeGiven() != null ? r.routeGiven() : o.getRoute()).name());
        m.setSite(ClinicalSupport.blankToNull(r.site()));
        m.setReason(r.reason().trim());
        m.setNotes(ClinicalSupport.blankToNull(r.notes()));
        m.setWitnessUserId(validWitness(r.witnessUserId()));
        m.setRecordedBy(TenantContext.actorId());
        m.setRecordedAt(now);
        m = administrations.saveAndFlush(m);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("MEDICATION_PRN_ADMINISTERED").category(TimelineEvent.Category.MEDICATION)
                .title("PRN dose given: " + med.displayName() + " " + m.getDoseGiven() + " " + m.getRouteGiven())
                .detail("Indication: " + m.getReason()).status("ADMINISTERED").visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("MEDICATION_ADMINISTRATION").entityId(m.getId()).occurredAt(at).build());
        audit.record("MEDICATION_PRN_ADMINISTERED", "MEDICATION_ADMINISTRATION", m.getId(), a.getPatientId(),
                med.displayName() + " " + m.getDoseGiven() + ": " + m.getReason());
        return views.doses(List.of(m), Map.of(o.getId(), o)).get(0);
    }

    @Transactional
    public DoseView updateFollowUp(UUID administrationId, FollowUpRequest r) {
        MedicationAdministration m = support.requireOrg(administrations.findById(administrationId), "Dose");
        Admission a = support.requireClinical(m.getAdmissionId());
        boolean isException = m.getStatus().isException()
                || m.getStatus() == MedicationAdministration.Status.SCHEDULED && m.getOverdueFlaggedAt() != null;
        if (!isException) {
            throw ApiException.rule("NOT_AN_EXCEPTION", "Follow-up applies to delayed, refused, missed, held or overdue doses.");
        }
        if (r.status() == MedicationAdministration.FollowUpStatus.NONE) {
            throw ApiException.badRequest("INVALID_STATUS", "Choose OPEN, IN_PROGRESS or RESOLVED.");
        }
        if (r.status() == MedicationAdministration.FollowUpStatus.RESOLVED && (r.notes() == null || r.notes().trim().length() < 3)) {
            throw ApiException.badRequest("NOTES_REQUIRED", "Document how the exception was resolved.");
        }
        m.setFollowUpStatus(r.status());
        if (ClinicalSupport.blankToNull(r.notes()) != null) {
            m.setFollowUpNotes(r.notes().trim());
        }
        m.setFollowUpBy(TenantContext.actorId());
        m.setFollowUpAt(Instant.now());
        if (r.status() == MedicationAdministration.FollowUpStatus.RESOLVED) {
            timeline.record(TimelineService.Entry.builder()
                    .patientId(a.getPatientId()).admissionId(a.getId())
                    .eventType("MEDICATION_EXCEPTION_RESOLVED").category(TimelineEvent.Category.MEDICATION)
                    .title("Medication exception followed up").detail(m.getFollowUpNotes()).status("RESOLVED")
                    .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("MEDICATION_ADMINISTRATION").entityId(m.getId()).build());
        }
        audit.record("MEDICATION_FOLLOW_UP", "MEDICATION_ADMINISTRATION", m.getId(), a.getPatientId(), r.status() + ": " + r.notes());
        administrations.flush();
        MedicationOrder o = orders.findById(m.getOrderId()).orElseThrow();
        return views.doses(List.of(m), Map.of(o.getId(), o)).get(0);
    }

    private static void checkVersion(MedicationAdministration m, Long version) {
        if (version != null && !version.equals(m.getVersion())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This dose was updated by someone else. Reload and check before recording.");
        }
    }

    private UUID validWitness(UUID witnessId) {
        if (witnessId == null) {
            return null;
        }
        if (witnessId.equals(TenantContext.actorId())) {
            throw ApiException.rule("INVALID_WITNESS", "The witness must be a different staff member.");
        }
        return users.findByIdAndOrganizationId(witnessId, TenantContext.requireOrganizationId())
                .filter(u -> u.getStatus() == User.Status.ACTIVE && (u.getRole() == Role.NURSE || u.getRole() == Role.DOCTOR
                        || u.getRole() == Role.PHARMACIST))
                .map(User::getId)
                .orElseThrow(() -> ApiException.rule("INVALID_WITNESS", "The witness must be an active clinician."));
    }

    private static String lateness(Instant scheduled, Instant given) {
        long min = Duration.between(scheduled, given).toMinutes();
        if (Math.abs(min) <= 15) {
            return "On time";
        }
        return min > 0 ? min + " min after scheduled time" : -min + " min before scheduled time";
    }

    // ================================================================== jobs

    /** Keeps the schedule horizon filled, completes expired orders and cancels doses of inactive orders. */
    @Transactional
    public int maintainSchedules(UUID orgId) {
        Instant now = Instant.now();
        int changed = 0;
        for (MedicationOrder o : orders.findExpired(orgId, now)) {
            o.setStatus(MedicationOrder.Status.COMPLETED);
            changed += cancelFuture(o, o.getEndAt(), "Order completed (end of course)");
        }
        for (MedicationAdministration m : administrations.findScheduledForInactiveOrders(orgId, now)) {
            m.setStatus(MedicationAdministration.Status.CANCELLED);
            m.setReason("Order no longer active");
            m.setRecordedAt(now);
            changed++;
        }
        Instant to = now.plus(Duration.ofHours(horizonHours(orgId)));
        for (MedicationOrder o : orders.findByOrganizationIdAndStatus(orgId, MedicationOrder.Status.ACTIVE)) {
            Admission a = admissions.findById(o.getAdmissionId()).orElse(null);
            if (a == null || !a.getStatus().isOpen()) {
                continue;
            }
            changed += generate(o, now, to);
        }
        return changed;
    }

    /**
     * Flags SCHEDULED doses past the grace period as overdue and escalates per MEDICATION_OVERDUE rules:
     * level 1 notifies the ward nurses, level 2 the attending doctor (each level at most once per dose).
     */
    @Transactional
    public int escalateOverdue(UUID orgId) {
        int grace = settings.getInt(orgId, SettingsService.MED_OVERDUE_GRACE_MINUTES);
        List<EscalationRule> rules = escalationRules.findByOrganizationIdAndEventTypeAndActiveTrueOrderByEscalationLevel(orgId,
                EscalationRule.EventType.MEDICATION_OVERDUE);
        int maxLevel = rules.stream().mapToInt(EscalationRule::getEscalationLevel).max().orElse(0);
        Instant now = Instant.now();
        int changed = 0;
        for (MedicationAdministration m : administrations.findNewlyOverdue(orgId, now.minus(Duration.ofMinutes(grace)))) {
            m.setOverdueFlaggedAt(now);
            if (m.getFollowUpStatus() == MedicationAdministration.FollowUpStatus.NONE) {
                m.setFollowUpStatus(MedicationAdministration.FollowUpStatus.OPEN);
            }
            changed++;
        }
        if (maxLevel == 0) {
            return changed;
        }
        int minThreshold = rules.stream().mapToInt(EscalationRule::getThresholdMinutes).min().orElse(grace);
        List<MedicationAdministration> candidates = administrations.findOverdueBelowLevel(orgId,
                now.minus(Duration.ofMinutes(Math.max(minThreshold, grace))), maxLevel);
        if (candidates.isEmpty()) {
            return changed;
        }
        Map<UUID, MedicationOrder> byId = ordersById(candidates);
        Map<UUID, Medication> meds = views.medications(byId.values().stream().map(MedicationOrder::getMedicationId).toList());
        for (MedicationAdministration m : candidates) {
            long minutesLate = Duration.between(m.getScheduledAt(), now).toMinutes();
            Admission a = admissions.findById(m.getAdmissionId()).orElse(null);
            MedicationOrder o = byId.get(m.getOrderId());
            if (a == null || o == null || !a.getStatus().isOpen()) {
                continue;
            }
            Medication med = meds.get(o.getMedicationId());
            String medName = med == null ? "medication" : med.displayName();
            for (EscalationRule rule : rules) {
                if (rule.getEscalationLevel() <= m.getOverdueEscalationLevel() || minutesLate < rule.getThresholdMinutes()) {
                    continue;
                }
                if (m.getOverdueFlaggedAt() == null) {
                    m.setOverdueFlaggedAt(now);
                }
                NotificationRequest.NotificationRequestBuilder req = NotificationRequest.builder()
                        .category("MEDICATION_OVERDUE")
                        .priority(parsePriority(rule.getPriority()))
                        .title("Overdue dose: " + medName)
                        .message(minutesLate + " min overdue · " + a.getAdmissionNumber() + " · " + o.getDose() + " " + o.getDoseUnit()
                                + " " + o.getRoute())
                        .entityType("MEDICATION_ADMINISTRATION").entityId(m.getId())
                        .dedupeKey("MED_OVERDUE:" + m.getId() + ":L" + rule.getEscalationLevel());
                if (rule.getEscalateToRole() == Role.DOCTOR) {
                    notifications.notifyUsers(List.of(a.getAttendingDoctorId()), req.link("/doctor/patients/" + a.getId()).build());
                } else if (rule.getEscalateToRole() == Role.NURSE) {
                    notifications.notifyWardNurses(a.getId(), req.link("/nurse/medications").build());
                } else {
                    notifications.notifyRole(rule.getEscalateToRole(), req.build());
                }
                m.setOverdueEscalationLevel(rule.getEscalationLevel());
                if (rule.getEscalationLevel() == 1) {
                    timeline.record(TimelineService.Entry.builder()
                            .patientId(a.getPatientId()).admissionId(a.getId())
                            .eventType("MEDICATION_OVERDUE").category(TimelineEvent.Category.MEDICATION)
                            .title("Dose overdue: " + medName).detail("Not recorded " + minutesLate + " min after scheduled time")
                            .status("OVERDUE").visibility(TimelineEvent.Visibility.CARE_TEAM)
                            .entityType("MEDICATION_ADMINISTRATION").entityId(m.getId()).build());
                }
                changed++;
            }
        }
        return changed;
    }

    private static Notification.Priority parsePriority(String p) {
        try {
            return Notification.Priority.valueOf(p);
        } catch (RuntimeException e) {
            return Notification.Priority.HIGH;
        }
    }

    /** Counts used by dashboards: due / overdue doses for a set of admissions. */
    @Transactional(readOnly = true)
    public Map<UUID, int[]> dueOverdueCounts(Collection<UUID> admissionIds) {
        Map<UUID, int[]> out = new LinkedHashMap<>();
        if (admissionIds.isEmpty()) {
            return out;
        }
        Instant now = Instant.now();
        List<MedicationAdministration> list = administrations.findByAdmissionIdInAndStatusInAndScheduledAtBetweenOrderByScheduledAtAsc(
                admissionIds, EnumSet.of(MedicationAdministration.Status.SCHEDULED), now.minus(Duration.ofHours(24)), now.plus(Duration.ofHours(2)));
        if (list.isEmpty()) {
            return out;
        }
        UUID org = list.get(0).getOrganizationId();
        int window = settings.getInt(org, SettingsService.MED_DUE_WINDOW_MINUTES);
        int grace = settings.getInt(org, SettingsService.MED_OVERDUE_GRACE_MINUTES);
        for (MedicationAdministration m : list) {
            String s = MedicationViews.displayStatus(m, now, window, grace);
            int[] c = out.computeIfAbsent(m.getAdmissionId(), k -> new int[2]);
            if ("DUE".equals(s)) {
                c[0]++;
            } else if ("OVERDUE".equals(s)) {
                c[1]++;
            }
        }
        return out;
    }

    /** Pending doses (SCHEDULED/DELAYED) for an admission in a window - used by handover prefill. */
    public List<DoseView> pendingDoses(UUID admissionId, Instant from, Instant to) {
        List<MedicationAdministration> list = administrations.findByAdmissionIdInAndStatusInAndScheduledAtBetweenOrderByScheduledAtAsc(
                List.of(admissionId), PENDING, from, to);
        return views.doses(list, ordersById(list));
    }

    /** Open medication exceptions for admissions (dashboards). */
    public long openExceptionCount(Collection<UUID> admissionIds) {
        if (admissionIds.isEmpty()) {
            return 0;
        }
        return administrations.findExceptionQueue(admissionIds, EnumSet.of(MedicationAdministration.FollowUpStatus.OPEN,
                MedicationAdministration.FollowUpStatus.IN_PROGRESS), PageRequest.of(0, 1)).getTotalElements();
    }
}
