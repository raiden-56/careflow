package com.careflow.hms.clinical;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.clinical.ClinicalDtos.AlertView;
import com.careflow.hms.clinical.ClinicalDtos.CompareRow;
import com.careflow.hms.clinical.ClinicalDtos.CompareView;
import com.careflow.hms.clinical.ClinicalDtos.Crossing;
import com.careflow.hms.clinical.ClinicalDtos.Gap;
import com.careflow.hms.clinical.ClinicalDtos.SeriesStats;
import com.careflow.hms.clinical.ClinicalDtos.ThresholdBand;
import com.careflow.hms.clinical.ClinicalDtos.TrendPoint;
import com.careflow.hms.clinical.ClinicalDtos.TrendSeries;
import com.careflow.hms.clinical.ClinicalDtos.TrendsView;
import com.careflow.hms.clinical.ClinicalDtos.VitalView;
import com.careflow.hms.clinical.ClinicalDtos.VitalsRecorded;
import com.careflow.hms.clinical.ClinicalDtos.VitalsRequest;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.notification.EscalationRule;
import com.careflow.hms.notification.EscalationRuleRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.org.SettingsService;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Vital-sign recording with range validation, threshold evaluation against hospital-configured
 * rules (decision support only - never a diagnosis), corrections, and the trend/compare views used by
 * the Recovery Trend Explorer.
 */
@Service
@RequiredArgsConstructor
public class VitalsService {

    public static final String DISCLAIMER = "Decision support only. Trends and threshold markers summarise recorded observations "
            + "against hospital-configured ranges; they are not a diagnosis. Interpret in clinical context.";

    private final VitalSignRepository vitals;
    private final ThresholdRuleRepository rules;
    private final ClinicalAlertRepository alertRepository;
    private final EscalationRuleRepository escalationRules;
    private final AlertService alerts;
    private final ClinicalSupport support;
    private final SettingsService settings;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    /** Display metadata and value extraction per parameter. */
    public enum Param {
        TEMPERATURE_C("Temperature", "°C", v -> v.getTemperatureC()),
        PULSE_BPM("Pulse", "bpm", v -> dec(v.getPulseBpm())),
        RESPIRATORY_RATE("Respiratory rate", "/min", v -> dec(v.getRespiratoryRate())),
        SYSTOLIC_BP("Systolic BP", "mmHg", v -> dec(v.getSystolicBp())),
        DIASTOLIC_BP("Diastolic BP", "mmHg", v -> dec(v.getDiastolicBp())),
        SPO2("SpO2", "%", v -> dec(v.getSpo2())),
        PAIN_SCORE("Pain score", "/10", v -> dec(v.getPainScore())),
        BLOOD_GLUCOSE("Blood glucose", "mg/dL", v -> v.getBloodGlucose());

        final String label;
        final String unit;
        final Function<VitalSign, BigDecimal> extract;

        Param(String label, String unit, Function<VitalSign, BigDecimal> extract) {
            this.label = label;
            this.unit = unit;
            this.extract = extract;
        }

        private static BigDecimal dec(Integer i) {
            return i == null ? null : BigDecimal.valueOf(i);
        }
    }

    // ------------------------------------------------------------------ record

    @Transactional
    public VitalsRecorded record(UUID admissionId, VitalsRequest r) {
        Admission a = support.requireOpenClinical(admissionId);
        return doRecord(a, r);
    }

    VitalsRecorded doRecord(Admission a, VitalsRequest r) {
        if (r.temperatureC() == null && r.pulseBpm() == null && r.respiratoryRate() == null && r.systolicBp() == null
                && r.diastolicBp() == null && r.spo2() == null && r.painScore() == null && r.bloodGlucose() == null
                && r.weightKg() == null && r.consciousness() == null) {
            throw ApiException.badRequest("NO_MEASUREMENTS", "Enter at least one measurement.");
        }
        if ((r.systolicBp() == null) != (r.diastolicBp() == null)) {
            throw ApiException.badRequest("BP_INCOMPLETE", "Enter both systolic and diastolic blood pressure.");
        }
        if (r.systolicBp() != null && r.systolicBp() <= r.diastolicBp()) {
            throw ApiException.badRequest("BP_INVALID", "Systolic pressure must be higher than diastolic pressure.");
        }
        Instant now = Instant.now();
        Instant at = r.recordedAt() == null ? now : r.recordedAt();
        if (at.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("FUTURE_TIME", "Observation time cannot be in the future.");
        }
        if (at.isBefore(a.getAdmittedAt().minus(Duration.ofHours(2)))) {
            throw ApiException.badRequest("BEFORE_ADMISSION", "Observation time is before the admission.");
        }
        VitalSign v = new VitalSign();
        v.setAdmissionId(a.getId());
        v.setPatientId(a.getPatientId());
        v.setRecordedAt(at);
        v.setRecordedBy(TenantContext.actorId());
        v.setTemperatureC(r.temperatureC() == null ? null : r.temperatureC().setScale(1, RoundingMode.HALF_UP));
        v.setPulseBpm(r.pulseBpm());
        v.setRespiratoryRate(r.respiratoryRate());
        v.setSystolicBp(r.systolicBp());
        v.setDiastolicBp(r.diastolicBp());
        v.setSpo2(r.spo2());
        v.setOnOxygen(r.onOxygen());
        v.setPainScore(r.painScore());
        v.setBloodGlucose(r.bloodGlucose() == null ? null : r.bloodGlucose().setScale(1, RoundingMode.HALF_UP));
        v.setWeightKg(r.weightKg() == null ? null : r.weightKg().setScale(1, RoundingMode.HALF_UP));
        v.setConsciousness(r.consciousness());
        v.setNotes(ClinicalSupport.blankToNull(r.notes()));
        v = vitals.saveAndFlush(v);

        List<ThresholdRule> active = rules.findByOrganizationIdAndActiveTrue(a.getOrganizationId());
        Map<Param, ThresholdRule> breaches = evaluate(v, active);
        String worst = breaches.values().stream().anyMatch(t -> t.getSeverity() == ThresholdRule.Severity.CRITICAL) ? "CRITICAL"
                : breaches.isEmpty() ? null : "WARNING";
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("VITALS_RECORDED").category(TimelineEvent.Category.OBSERVATION)
                .title("Vital signs recorded" + (worst != null ? " - outside configured range" : ""))
                .detail(describe(v)).status(worst).visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("VITAL_SIGN").entityId(v.getId()).occurredAt(at).build());

        List<ClinicalAlert> raised = new ArrayList<>();
        for (Map.Entry<Param, ThresholdRule> e : breaches.entrySet()) {
            ClinicalAlert al = raiseOrUpdate(a, v, e.getKey(), e.getValue());
            if (al != null) {
                raised.add(al);
            }
        }
        audit.record("VITALS_RECORDED", "VITAL_SIGN", v.getId(), a.getPatientId(),
                describe(v) + (raised.isEmpty() ? "" : " (" + raised.size() + " alert(s) raised)"));
        return new VitalsRecorded(view(v, active, support.userNames(List.of(v.getRecordedBy()))), alerts.views(raised));
    }

    /** Most severe breached rule per parameter. */
    static Map<Param, ThresholdRule> evaluate(VitalSign v, List<ThresholdRule> active) {
        Map<Param, ThresholdRule> out = new EnumMap<>(Param.class);
        for (ThresholdRule rule : active) {
            Param p = Param.valueOf(rule.getParameter().name());
            BigDecimal value = p.extract.apply(v);
            if (value == null || !breaches(rule, value)) {
                continue;
            }
            ThresholdRule current = out.get(p);
            if (current == null || rule.getSeverity().ordinal() > current.getSeverity().ordinal()) {
                out.put(p, rule);
            }
        }
        return out;
    }

    static boolean breaches(ThresholdRule rule, BigDecimal value) {
        return rule.getMinValue() != null && value.compareTo(rule.getMinValue()) < 0
                || rule.getMaxValue() != null && value.compareTo(rule.getMaxValue()) > 0;
    }

    /**
     * Raises a VITAL_THRESHOLD alert unless an unresolved alert of the same or higher severity already exists
     * for this parameter (then the existing alert is updated with the latest value - no repeat notification).
     */
    private ClinicalAlert raiseOrUpdate(Admission a, VitalSign v, Param p, ThresholdRule rule) {
        BigDecimal value = p.extract.apply(v);
        String observed = value.stripTrailingZeros().toPlainString() + " " + p.unit;
        ClinicalAlert.Severity sev = rule.getSeverity() == ThresholdRule.Severity.CRITICAL ? ClinicalAlert.Severity.CRITICAL
                : ClinicalAlert.Severity.WARNING;
        Optional<ClinicalAlert> existing = alertRepository.findByAdmissionIdAndAlertTypeAndParameterAndStatusIn(a.getId(),
                        ClinicalAlert.AlertType.VITAL_THRESHOLD, p.name(), AlertService.UNRESOLVED).stream()
                .filter(x -> x.getSeverity().ordinal() >= sev.ordinal()).findFirst();
        if (existing.isPresent()) {
            ClinicalAlert x = existing.get();
            x.setObservedValue(observed);
            x.setMessage(ClinicalSupport.truncate(p.label + " " + observed + " at latest observation (" + rule.getDescription()
                    + "). Previously alerted; still outside range.", 2000));
            x.setSourceId(v.getId());
            return null;
        }
        String threshold = rule.describeBounds().replace(rule.getParameter().name(), p.label);
        ClinicalAlert al = alerts.raise(a, ClinicalAlert.AlertType.VITAL_THRESHOLD, sev,
                p.label + " " + observed + " - " + (sev == ClinicalAlert.Severity.CRITICAL ? "critical threshold" : "outside range"),
                p.label + " recorded at " + observed + ". Hospital threshold: " + rule.getDescription()
                        + ". Review the patient and recent observations.",
                "VITAL_SIGN", v.getId(), p.name(), observed, rule.getId(), threshold, a.getAttendingDoctorId(), v.getRecordedAt());
        notifyThreshold(a, al, rule);
        return al;
    }

    private void notifyThreshold(Admission a, ClinicalAlert al, ThresholdRule rule) {
        boolean critical = al.getSeverity() == ClinicalAlert.Severity.CRITICAL;
        Role target = rule.getNotifyRole() != null ? rule.getNotifyRole() : critical ? Role.DOCTOR : Role.NURSE;
        Notification.Priority priority = critical ? Notification.Priority.CRITICAL : Notification.Priority.HIGH;
        if (critical) {
            // Escalation rule CRITICAL_OBSERVATION decides the role and priority for critical breaches.
            Optional<EscalationRule> esc = escalationRules.findByOrganizationIdAndEventTypeAndActiveTrueOrderByEscalationLevel(
                    a.getOrganizationId(), EscalationRule.EventType.CRITICAL_OBSERVATION).stream().findFirst();
            if (esc.isPresent()) {
                target = esc.get().getEscalateToRole();
                priority = parsePriority(esc.get().getPriority(), Notification.Priority.CRITICAL);
            }
        }
        NotificationRequest.NotificationRequestBuilder req = NotificationRequest.builder()
                .category("OBSERVATION_ALERT").priority(priority).requiresAck(critical)
                .title((critical ? "Critical observation: " : "Observation alert: ") + patientName(a))
                .message(al.getTitle() + " · " + a.getAdmissionNumber())
                .entityType("CLINICAL_ALERT").entityId(al.getId()).dedupeKey("VITAL_ALERT:" + al.getId());
        if (target == Role.DOCTOR) {
            notifications.notifyUsers(List.of(a.getAttendingDoctorId()), req.link("/doctor/patients/" + a.getId()).build());
            notifications.notifyCareTeam(a.getId(), Role.DOCTOR, req.link("/doctor/patients/" + a.getId()).build());
            // Ward nurses are kept informed of critical breaches on their patients.
            notifications.notifyWardNurses(a.getId(), req.link("/nurse/patients/" + a.getId()).requiresAck(false).build());
        } else if (target == Role.NURSE) {
            notifications.notifyWardNurses(a.getId(), req.link("/nurse/patients/" + a.getId()).build());
        } else {
            notifications.notifyRole(target, req.link(null).build());
        }
    }

    private String patientName(Admission a) {
        ClinicalSupport.PatientRef ref = support.ref(a.getId());
        return ref == null ? a.getAdmissionNumber() : ref.patientName();
    }

    private static Notification.Priority parsePriority(String s, Notification.Priority dflt) {
        try {
            return Notification.Priority.valueOf(s);
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    // ------------------------------------------------------------------ corrections & lists

    @Transactional
    public VitalView markInError(UUID vitalId, String reason) {
        VitalSign v = support.requireOrg(vitals.findById(vitalId), "Observation");
        Admission a = support.requireClinical(v.getAdmissionId());
        if (v.isEnteredInError()) {
            throw ApiException.rule("ALREADY_IN_ERROR", "This observation is already marked entered in error.");
        }
        v.setEnteredInError(true);
        v.setErrorReason(reason.trim());
        alertRepository.findBySourceTypeAndSourceIdAndStatusIn("VITAL_SIGN", v.getId(), AlertService.UNRESOLVED)
                .forEach(al -> alerts.doResolve(al, "Source observation marked entered in error: " + reason.trim()));
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("VITALS_ENTERED_IN_ERROR").category(TimelineEvent.Category.OBSERVATION)
                .title("Observation marked entered in error").detail(reason).status("ENTERED_IN_ERROR")
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("VITAL_SIGN").entityId(v.getId()).build());
        audit.record("VITALS_ENTERED_IN_ERROR", "VITAL_SIGN", v.getId(), a.getPatientId(), reason);
        vitals.flush();
        return view(v, rules.findByOrganizationIdAndActiveTrue(a.getOrganizationId()), support.userNames(List.of(v.getRecordedBy())));
    }

    @Transactional(readOnly = true)
    public List<VitalView> list(UUID admissionId, Instant from, Instant to) {
        Admission a = support.requireClinical(admissionId);
        Instant f = from != null ? from : a.getAdmittedAt().minus(Duration.ofHours(2));
        Instant t = to != null ? to : Instant.now().plus(Duration.ofMinutes(5));
        List<VitalSign> list = new ArrayList<>(vitals.findByAdmissionIdAndRecordedAtBetweenOrderByRecordedAtAsc(admissionId, f, t));
        list.sort(Comparator.comparing(VitalSign::getRecordedAt).reversed());
        List<ThresholdRule> active = rules.findByOrganizationIdAndActiveTrue(a.getOrganizationId());
        Map<UUID, String> names = support.userNames(list.stream().map(VitalSign::getRecordedBy).toList());
        return list.stream().map(v -> view(v, active, names)).toList();
    }

    public Optional<VitalSign> latest(UUID admissionId) {
        return vitals.findFirstByAdmissionIdAndEnteredInErrorFalseOrderByRecordedAtDesc(admissionId);
    }

    VitalView view(VitalSign v, List<ThresholdRule> active, Map<UUID, String> names) {
        List<String> b = v.isEnteredInError() ? List.of() : evaluate(v, active).entrySet().stream()
                .map(e -> e.getKey().name() + ":" + e.getValue().getSeverity().name()).toList();
        return new VitalView(v.getId(), v.getAdmissionId(), v.getRecordedAt(), names.get(v.getRecordedBy()), v.getTemperatureC(),
                v.getPulseBpm(), v.getRespiratoryRate(), v.getSystolicBp(), v.getDiastolicBp(), v.getSpo2(), v.isOnOxygen(),
                v.getPainScore(), v.getBloodGlucose(), v.getWeightKg(), v.getConsciousness(), v.getNotes(), v.isEnteredInError(),
                v.getErrorReason(), b);
    }

    public VitalView view(VitalSign v) {
        return view(v, rules.findByOrganizationIdAndActiveTrue(v.getOrganizationId()), support.userNames(List.of(v.getRecordedBy())));
    }

    static String describe(VitalSign v) {
        List<String> parts = new ArrayList<>();
        if (v.getTemperatureC() != null) parts.add("T " + v.getTemperatureC().toPlainString() + " °C");
        if (v.getPulseBpm() != null) parts.add("P " + v.getPulseBpm());
        if (v.getRespiratoryRate() != null) parts.add("RR " + v.getRespiratoryRate());
        if (v.getSystolicBp() != null) parts.add("BP " + v.getSystolicBp() + "/" + v.getDiastolicBp());
        if (v.getSpo2() != null) parts.add("SpO2 " + v.getSpo2() + "%" + (v.isOnOxygen() ? " on O2" : ""));
        if (v.getPainScore() != null) parts.add("Pain " + v.getPainScore() + "/10");
        if (v.getBloodGlucose() != null) parts.add("Glucose " + v.getBloodGlucose().stripTrailingZeros().toPlainString());
        if (v.getConsciousness() != null) parts.add(v.getConsciousness().name().toLowerCase());
        return String.join(" · ", parts);
    }

    // ------------------------------------------------------------------ trends

    @Transactional(readOnly = true)
    public TrendsView trends(UUID admissionId, Instant from, Instant to, Collection<String> parameters) {
        Admission a = support.requireClinical(admissionId);
        Instant end = to != null ? to : (a.getDischargedAt() != null ? a.getDischargedAt() : Instant.now());
        Instant start = from != null ? from : a.getAdmittedAt();
        if (!start.isBefore(end)) {
            throw ApiException.badRequest("INVALID_RANGE", "The start of the range must be before its end.");
        }
        int interval = settings.getInt(a.getOrganizationId(), SettingsService.VITALS_EXPECTED_INTERVAL_HOURS);
        List<VitalSign> list = vitals.findByAdmissionIdAndEnteredInErrorFalseAndRecordedAtBetweenOrderByRecordedAtAsc(a.getId(), start, end);
        List<ThresholdRule> active = rules.findByOrganizationIdAndActiveTrue(a.getOrganizationId());
        List<TrendSeries> series = new ArrayList<>();
        for (Param p : Param.values()) {
            if (parameters != null && !parameters.isEmpty() && !parameters.contains(p.name())) {
                continue;
            }
            List<ThresholdRule> pr = active.stream().filter(r -> r.getParameter().name().equals(p.name())).toList();
            List<TrendPoint> points = new ArrayList<>();
            List<Crossing> crossings = new ArrayList<>();
            String prevLevel = null;
            for (VitalSign v : list) {
                BigDecimal value = p.extract.apply(v);
                if (value == null) {
                    continue;
                }
                ThresholdRule worst = null;
                for (ThresholdRule r : pr) {
                    if (breaches(r, value) && (worst == null || r.getSeverity().ordinal() > worst.getSeverity().ordinal())) {
                        worst = r;
                    }
                }
                String level = worst == null ? "NORMAL" : worst.getSeverity().name();
                points.add(new TrendPoint(v.getRecordedAt(), value, v.getId(), worst == null ? null : level));
                if (prevLevel != null && !prevLevel.equals(level)) {
                    String direction = worst == null ? "RETURNED_TO_RANGE"
                            : (worst.getMaxValue() != null && value.compareTo(worst.getMaxValue()) > 0 ? "ABOVE" : "BELOW");
                    String thresholdText = worst != null ? worst.getDescription()
                            : pr.stream().filter(r -> r.getSeverity() == ThresholdRule.Severity.WARNING).map(ThresholdRule::getDescription)
                            .findFirst().orElse("configured range");
                    crossings.add(new Crossing(v.getRecordedAt(), value, prevLevel, level, direction, thresholdText));
                }
                prevLevel = level;
            }
            if (points.isEmpty() && (parameters == null || parameters.isEmpty())) {
                continue;
            }
            List<ThresholdBand> bands = pr.stream()
                    .sorted(Comparator.comparing(ThresholdRule::getSeverity))
                    .map(r -> new ThresholdBand(r.getSeverity().name(), r.getMinValue(), r.getMaxValue(), r.getDescription()))
                    .toList();
            series.add(new TrendSeries(p.name(), p.label, p.unit, points, bands, crossings, stats(points.stream().map(TrendPoint::value).toList())));
        }
        List<Gap> gaps = gaps(list, start, end, interval, a);
        return new TrendsView(a.getId(), start, end, interval, series, gaps, list.size(), DISCLAIMER);
    }

    private static List<Gap> gaps(List<VitalSign> list, Instant start, Instant end, int intervalHours, Admission a) {
        List<Gap> gaps = new ArrayList<>();
        Duration limit = Duration.ofHours(intervalHours);
        // The range may start inside a gap: measure the leading period from the later of range start and admission.
        Instant periodStart = a.getAdmittedAt() != null && a.getAdmittedAt().isAfter(start) ? a.getAdmittedAt() : start;
        if (!list.isEmpty() && Duration.between(periodStart, list.get(0).getRecordedAt()).compareTo(limit) > 0) {
            gaps.add(new Gap(periodStart, list.get(0).getRecordedAt(), hours(periodStart, list.get(0).getRecordedAt()), false));
        }
        Instant prev = null;
        for (VitalSign v : list) {
            if (prev != null && Duration.between(prev, v.getRecordedAt()).compareTo(limit) > 0) {
                gaps.add(new Gap(prev, v.getRecordedAt(), hours(prev, v.getRecordedAt()), false));
            }
            prev = v.getRecordedAt();
        }
        boolean open = a.getStatus().isOpen();
        Instant now = Instant.now();
        Instant tail = end.isAfter(now) ? now : end;
        if (prev != null && open && Duration.between(prev, tail).compareTo(limit) > 0) {
            gaps.add(new Gap(prev, tail, hours(prev, tail), !end.isBefore(now.minus(Duration.ofMinutes(5)))));
        }
        if (prev == null && open && tail.isAfter(periodStart) && Duration.between(periodStart, tail).compareTo(limit) > 0) {
            gaps.add(new Gap(periodStart, tail, hours(periodStart, tail), !end.isBefore(now.minus(Duration.ofMinutes(5)))));
        }
        return gaps;
    }

    private static double hours(Instant a, Instant b) {
        return Math.round(Duration.between(a, b).toMinutes() / 6.0) / 10.0;
    }

    static SeriesStats stats(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return new SeriesStats(null, null, null, null, null, 0);
        }
        BigDecimal min = values.stream().min(BigDecimal::compareTo).orElseThrow();
        BigDecimal max = values.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mean = sum.divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
        return new SeriesStats(min, max, mean, values.get(0), values.get(values.size() - 1), values.size());
    }

    @Transactional(readOnly = true)
    public CompareView compare(UUID admissionId, Instant aFrom, Instant aTo, Instant bFrom, Instant bTo) {
        Admission a = support.requireClinical(admissionId);
        if (aFrom == null || aTo == null || bFrom == null || bTo == null || !aFrom.isBefore(aTo) || !bFrom.isBefore(bTo)) {
            throw ApiException.badRequest("INVALID_RANGE", "Choose two valid time ranges to compare.");
        }
        List<VitalSign> la = vitals.findByAdmissionIdAndEnteredInErrorFalseAndRecordedAtBetweenOrderByRecordedAtAsc(a.getId(), aFrom, aTo);
        List<VitalSign> lb = vitals.findByAdmissionIdAndEnteredInErrorFalseAndRecordedAtBetweenOrderByRecordedAtAsc(a.getId(), bFrom, bTo);
        Map<String, CompareRow> rows = new LinkedHashMap<>();
        for (Param p : Param.values()) {
            SeriesStats sa = stats(la.stream().map(p.extract).filter(Objects::nonNull).toList());
            SeriesStats sb = stats(lb.stream().map(p.extract).filter(Objects::nonNull).toList());
            if (sa.count() == 0 && sb.count() == 0) {
                continue;
            }
            BigDecimal change = sa.mean() != null && sb.mean() != null ? sb.mean().subtract(sa.mean()) : null;
            String dir = change == null ? "INSUFFICIENT_DATA" : change.signum() == 0 ? "UNCHANGED" : change.signum() > 0 ? "HIGHER" : "LOWER";
            rows.put(p.name(), new CompareRow(p.name(), p.label, p.unit, sa, sb, change, dir));
        }
        return new CompareView(a.getId(), aFrom, aTo, bFrom, bTo, new ArrayList<>(rows.values()), DISCLAIMER);
    }

    /** Hours since the latest valid observation for an admission (null when none). */
    public Double hoursSinceLast(UUID admissionId) {
        return latest(admissionId).map(v -> hours(v.getRecordedAt(), Instant.now())).orElse(null);
    }
}
