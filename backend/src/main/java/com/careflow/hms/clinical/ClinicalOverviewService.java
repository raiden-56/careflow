package com.careflow.hms.clinical;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.clinical.ClinicalDtos.AlertHistoryReport;
import com.careflow.hms.clinical.ClinicalDtos.AlertView;
import com.careflow.hms.clinical.ClinicalDtos.ClinicalSummary;
import com.careflow.hms.clinical.ClinicalDtos.CountRow;
import com.careflow.hms.clinical.ClinicalDtos.DoctorDashboard;
import com.careflow.hms.clinical.ClinicalDtos.DocumentationReport;
import com.careflow.hms.clinical.ClinicalDtos.DocumentationRow;
import com.careflow.hms.clinical.ClinicalDtos.LabOrderView;
import com.careflow.hms.clinical.ClinicalDtos.NurseDashboard;
import com.careflow.hms.clinical.ClinicalDtos.OutstandingReport;
import com.careflow.hms.clinical.ClinicalDtos.OutstandingRow;
import com.careflow.hms.clinical.ClinicalDtos.PatientRow;
import com.careflow.hms.clinical.ClinicalDtos.PortalNote;
import com.careflow.hms.clinical.ClinicalDtos.PortalPlan;
import com.careflow.hms.clinical.ClinicalDtos.PortalRecords;
import com.careflow.hms.clinical.ClinicalDtos.PortalResult;
import com.careflow.hms.clinical.ClinicalDtos.TaskView;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.medication.MarService;
import com.careflow.hms.medication.MedicationDtos.OrderView;
import com.careflow.hms.medication.MedicationOrder;
import com.careflow.hms.medication.MedicationOrderRepository;
import com.careflow.hms.medication.MedicationViews;
import com.careflow.hms.org.SettingsService;
import com.careflow.hms.patient.AllergyRepository;
import com.careflow.hms.patient.MedicalHistoryRepository;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read models: per-admission clinical summary, doctor/nurse dashboards, clinical reports and portal views. */
@Service
@RequiredArgsConstructor
public class ClinicalOverviewService {

    private final ClinicalSupport support;
    private final AccessPolicy access;
    private final AdmissionRepository admissions;
    private final AllergyRepository allergies;
    private final MedicalHistoryRepository history;
    private final DiagnosisRepository diagnoses;
    private final TreatmentPlanRepository plans;
    private final ClinicalNoteRepository notes;
    private final VitalSignRepository vitalsRepo;
    private final ClinicalAlertRepository alertRepo;
    private final CareTaskRepository taskRepo;
    private final LabOrderRepository labOrders;
    private final LabResultRepository labResults;
    private final MedicationOrderRepository medOrders;
    private final ClinicalRecordService records;
    private final VitalsService vitals;
    private final AlertService alerts;
    private final CareTaskService tasks;
    private final InvestigationService investigations;
    private final HandoverService handovers;
    private final MarService mar;
    private final MedicationViews medViews;
    private final SettingsService settings;

    private static final EnumSet<LabOrder.Status> OUTSTANDING = EnumSet.of(LabOrder.Status.ORDERED, LabOrder.Status.COLLECTED,
            LabOrder.Status.IN_PROGRESS, LabOrder.Status.RESULTED);

    // ------------------------------------------------------------------ summary

    @Transactional(readOnly = true)
    public ClinicalSummary summary(UUID admissionId) {
        Admission a = support.requireClinical(admissionId);
        AdmissionSummary s = support.summaries(List.of(a.getId()), true).get(a.getId());
        int interval = settings.getInt(a.getOrganizationId(), SettingsService.VITALS_EXPECTED_INTERVAL_HOURS);
        Double since = vitals.hoursSinceLast(a.getId());
        boolean overdue = a.getStatus().isOpen() && (since == null || since > interval);
        List<OrderView> meds = medViews.orders(medOrders.findByAdmissionIdAndStatusIn(a.getId(),
                EnumSet.of(MedicationOrder.Status.ACTIVE, MedicationOrder.Status.ON_HOLD)));
        return new ClinicalSummary(s, a.getAdmissionReason(),
                records.allergies(a.getPatientId()), records.history(a.getPatientId()),
                records.diagnoses(a.getId()).stream().filter(d -> d.status() == Diagnosis.Status.ACTIVE).toList(),
                plans.findByAdmissionIdAndStatus(a.getId(), TreatmentPlan.Status.ACTIVE)
                        .map(p -> records.planView(p, support.userNames(List.of(p.getCreatedBy())))).orElse(null),
                meds, vitals.latest(a.getId()).map(vitals::view).orElse(null), overdue, since, interval,
                investigations.views(labOrders.findByAdmissionIdAndStatusIn(a.getId(), OUTSTANDING).stream()
                        .sorted(Comparator.comparing(LabOrder::getOrderedAt).reversed()).toList()),
                alerts.views(alertRepo.findByAdmissionIdInAndStatusInOrderByRaisedAtDesc(List.of(a.getId()), AlertService.UNRESOLVED)),
                tasks.views(taskRepo.findByAdmissionIdAndStatusIn(a.getId(), CareTaskService.ACTIVE).stream()
                        .sorted(Comparator.comparing(t -> t.getDueAt() == null ? Instant.MAX : t.getDueAt())).toList()),
                records.notes(a.getId(), null, 0, 5).content());
    }

    // ------------------------------------------------------------------ dashboards

    private List<PatientRow> patientRows(List<AdmissionSummary> mine) {
        if (mine.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = mine.stream().map(AdmissionSummary::id).toList();
        Map<UUID, List<ClinicalAlert>> open = alertRepo.findByAdmissionIdInAndStatusInOrderByRaisedAtDesc(ids, AlertService.UNRESOLVED)
                .stream().collect(Collectors.groupingBy(ClinicalAlert::getAdmissionId));
        Map<UUID, int[]> doses = mar.dueOverdueCounts(ids);
        Map<UUID, Long> openTasks = taskRepo.findByAdmissionIdInAndStatusInOrderByDueAtAsc(ids, CareTaskService.ACTIVE).stream()
                .collect(Collectors.groupingBy(CareTask::getAdmissionId, Collectors.counting()));
        Map<UUID, Long> pending = labOrders.findByAdmissionIdInAndStatusIn(ids, EnumSet.of(LabOrder.Status.RESULTED)).stream()
                .collect(Collectors.groupingBy(LabOrder::getAdmissionId, Collectors.counting()));
        Map<UUID, VitalSign> latest = new HashMap<>();
        vitalsRepo.findByAdmissionIdInAndEnteredInErrorFalseAndRecordedAtAfterOrderByRecordedAtDesc(ids, Instant.now().minus(Duration.ofDays(7)))
                .forEach(v -> latest.putIfAbsent(v.getAdmissionId(), v));
        UUID org = TenantContext.requireOrganizationId();
        int interval = settings.getInt(org, SettingsService.VITALS_EXPECTED_INTERVAL_HOURS);
        LocalDate today = TimeUtil.today();
        Instant now = Instant.now();
        List<PatientRow> rows = new ArrayList<>();
        for (AdmissionSummary s : mine) {
            List<ClinicalAlert> al = open.getOrDefault(s.id(), List.of());
            VitalSign v = latest.get(s.id());
            boolean obsOverdue = s.status() != Admission.Status.PENDING_BED
                    && (v == null || Duration.between(v.getRecordedAt(), now).toHours() >= interval);
            int[] d = doses.getOrDefault(s.id(), new int[2]);
            String primary = diagnoses.findByAdmissionIdOrderByRecordedAtDesc(s.id()).stream()
                    .filter(x -> x.getStatus() == Diagnosis.Status.ACTIVE)
                    .sorted(Comparator.comparing(x -> !x.isPrimary())).map(Diagnosis::getDescription).findFirst().orElse(null);
            rows.add(new PatientRow(ClinicalSupport.ref(s), al.size(),
                    (int) al.stream().filter(x -> x.getSeverity() == ClinicalAlert.Severity.CRITICAL).count(), obsOverdue,
                    v == null ? null : v.getRecordedAt(), d[0], d[1], openTasks.getOrDefault(s.id(), 0L).intValue(),
                    pending.getOrDefault(s.id(), 0L).intValue(),
                    s.expectedDischargeDate() != null && !s.expectedDischargeDate().isAfter(today), s.expectedDischargeDate(),
                    v == null ? null : VitalsService.describe(v), s.admittedAt(), s.lengthOfStayDays(), primary));
        }
        rows.sort(Comparator.comparing((PatientRow r) -> -r.criticalAlerts()).thenComparing(r -> -r.openAlerts())
                .thenComparing(r -> -r.overdueDoses()).thenComparing(r -> r.patient().patientName() == null ? "" : r.patient().patientName()));
        return rows;
    }

    @Transactional(readOnly = true)
    public List<PatientRow> myPatients() {
        return patientRows(support.myAdmissions());
    }

    @Transactional(readOnly = true)
    public DoctorDashboard doctor() {
        List<AdmissionSummary> mine = support.myAdmissions();
        List<UUID> ids = mine.stream().map(AdmissionSummary::id).toList();
        List<PatientRow> rows = patientRows(mine);
        List<ClinicalAlert> open = ids.isEmpty() ? List.of() : alertRepo.findByAdmissionIdInAndStatusInOrderByRaisedAtDesc(ids, AlertService.UNRESOLVED);
        List<ClinicalAlert> critical = open.stream().filter(a -> a.getSeverity() == ClinicalAlert.Severity.CRITICAL).toList();
        List<LabOrder> review = ids.isEmpty() ? List.of() : labOrders.findByAdmissionIdInAndStatusIn(ids, EnumSet.of(LabOrder.Status.RESULTED))
                .stream().sorted(Comparator.comparing(LabOrder::getOrderedAt)).toList();
        int pendingInv = ids.isEmpty() ? 0 : labOrders.findByAdmissionIdInAndStatusIn(ids, EnumSet.of(LabOrder.Status.ORDERED,
                LabOrder.Status.COLLECTED, LabOrder.Status.IN_PROGRESS)).size();
        int activeOrders = ids.isEmpty() ? 0 : medOrders.findByAdmissionIdInAndStatusIn(ids, EnumSet.of(MedicationOrder.Status.ACTIVE)).size();
        Instant dayAgo = Instant.now().minus(Duration.ofHours(24));
        int gaps = 0;
        for (UUID id : ids) {
            boolean recentNote = notes.findByAdmissionIdOrderByAuthoredAtDesc(id).stream()
                    .anyMatch(n -> n.getStatus() == ClinicalNote.Status.FINAL && !n.getNoteType().isNursing() && n.getAuthoredAt().isAfter(dayAgo));
            if (!recentNote) {
                gaps++;
            }
        }
        return new DoctorDashboard(mine.size(), critical.size(), open.size(), review.size(), pendingInv,
                (int) rows.stream().filter(PatientRow::expectedDischargeToday).count(),
                (int) mine.stream().filter(s -> s.admittedAt().isAfter(dayAgo)).count(), activeOrders, gaps,
                alerts.views(critical.stream().limit(8).toList()), investigations.views(review.stream().limit(8).toList()), rows);
    }

    @Transactional(readOnly = true)
    public NurseDashboard nurse() {
        List<AdmissionSummary> mine = support.myAdmissions();
        List<UUID> ids = mine.stream().map(AdmissionSummary::id).toList();
        List<PatientRow> rows = patientRows(mine);
        Instant now = Instant.now();
        List<CareTask> active = ids.isEmpty() ? List.of() : taskRepo.findByAdmissionIdInAndStatusInOrderByDueAtAsc(ids, CareTaskService.ACTIVE);
        int tasksDue = (int) active.stream().filter(t -> t.getDueAt() != null && !t.getDueAt().isBefore(now)
                && t.getDueAt().isBefore(now.plus(Duration.ofHours(4)))).count();
        int tasksOverdue = (int) active.stream().filter(t -> t.getDueAt() != null && t.getDueAt().isBefore(now)).count();
        List<TaskView> upcoming = tasks.views(active.stream().filter(t -> t.getDueAt() == null || t.getDueAt().isBefore(now.plus(Duration.ofHours(8))))
                .sorted(Comparator.comparing((CareTask t) -> -t.getPriority().ordinal()).thenComparing(t -> t.getDueAt() == null ? Instant.MAX : t.getDueAt()))
                .limit(8).toList());
        List<ClinicalAlert> open = ids.isEmpty() ? List.of() : alertRepo.findByAdmissionIdInAndStatusInOrderByRaisedAtDesc(ids, AlertService.UNRESOLVED);
        int collections = ids.isEmpty() ? 0 : (int) labOrders.findByAdmissionIdInAndStatusIn(ids, EnumSet.of(LabOrder.Status.ORDERED)).stream()
                .filter(o -> o.getCategory() == LabOrder.Category.LAB).count();
        LocalDate today = TimeUtil.today();
        return new NurseDashboard(mine.size(), rows.stream().mapToInt(PatientRow::dueDoses).sum(),
                rows.stream().mapToInt(PatientRow::overdueDoses).sum(), (int) mar.openExceptionCount(ids), tasksDue, tasksOverdue,
                open.size(), (int) rows.stream().filter(PatientRow::observationOverdue).count(), collections,
                (int) handovers.awaitingMyAck(),
                (int) mine.stream().filter(s -> s.expectedDischargeDate() != null && !s.expectedDischargeDate().isAfter(today)).count(),
                rows, upcoming, alerts.views(open.stream().sorted(Comparator.comparing((ClinicalAlert a) -> -a.getSeverity().ordinal())).limit(8).toList()));
    }

    // ------------------------------------------------------------------ reports (REPORT_CLINICAL)

    /** Open admissions the caller may access clinically (worklist plus any explicitly accessible). */
    private List<AdmissionSummary> reportScope() {
        return support.myAdmissions();
    }

    @Transactional(readOnly = true)
    public DocumentationReport documentation() {
        List<AdmissionSummary> scope = reportScope().stream().filter(s -> s.status() != Admission.Status.PENDING_BED).toList();
        UUID org = TenantContext.requireOrganizationId();
        int interval = settings.getInt(org, SettingsService.VITALS_EXPECTED_INTERVAL_HOURS);
        Instant now = Instant.now();
        List<DocumentationRow> rows = new ArrayList<>();
        for (AdmissionSummary s : scope) {
            List<ClinicalNote> ns = notes.findByAdmissionIdOrderByAuthoredAtDesc(s.id()).stream()
                    .filter(n -> n.getStatus() == ClinicalNote.Status.FINAL).toList();
            boolean assessment = ns.stream().anyMatch(n -> n.getNoteType() == ClinicalNote.NoteType.ADMISSION_ASSESSMENT);
            Optional<ClinicalNote> progress = ns.stream().filter(n -> n.getNoteType() == ClinicalNote.NoteType.PROGRESS
                    || n.getNoteType() == ClinicalNote.NoteType.DOCTOR_REVIEW).findFirst();
            Double hours = progress.map(n -> Duration.between(n.getAuthoredAt(), now).toMinutes() / 60.0).map(h -> Math.round(h * 10) / 10.0).orElse(null);
            boolean plan = plans.findByAdmissionIdAndStatus(s.id(), TreatmentPlan.Status.ACTIVE).isPresent();
            boolean primary = diagnoses.findByAdmissionIdOrderByRecordedAtDesc(s.id()).stream()
                    .anyMatch(d -> d.isPrimary() && d.getStatus() == Diagnosis.Status.ACTIVE);
            Optional<VitalSign> v = vitals.latest(s.id());
            boolean vOverdue = v.isEmpty() || Duration.between(v.get().getRecordedAt(), now).toHours() >= interval;
            List<String> gaps = new ArrayList<>();
            if (!assessment) gaps.add("No admission assessment");
            if (hours == null || hours > 24) gaps.add(hours == null ? "No progress note" : "No progress note in 24 h");
            if (!plan) gaps.add("No active treatment plan");
            if (!primary) gaps.add("No primary diagnosis");
            if (vOverdue) gaps.add("Observations overdue");
            int pct = (int) Math.round(100.0 * (5 - gaps.size()) / 5);
            rows.add(new DocumentationRow(ClinicalSupport.ref(s), assessment, progress.map(ClinicalNote::getAuthoredAt).orElse(null), hours,
                    plan, primary, v.map(VitalSign::getRecordedAt).orElse(null), vOverdue, pct, gaps));
        }
        rows.sort(Comparator.comparing(DocumentationRow::completenessPercent));
        int avg = rows.isEmpty() ? 100 : (int) Math.round(rows.stream().mapToInt(DocumentationRow::completenessPercent).average().orElse(100));
        return new DocumentationReport(rows.size(), (int) rows.stream().filter(r -> r.gaps().isEmpty()).count(), avg, rows);
    }

    @Transactional(readOnly = true)
    public OutstandingReport outstanding() {
        List<UUID> ids = reportScope().stream().map(AdmissionSummary::id).toList();
        if (ids.isEmpty()) {
            return new OutstandingReport(0, 0, 0, 0, 0, List.of());
        }
        Instant now = Instant.now();
        List<LabOrder> list = labOrders.findByAdmissionIdInAndStatusIn(ids, OUTSTANDING).stream()
                .sorted(Comparator.comparing(LabOrder::getOrderedAt)).toList();
        Map<UUID, LabOrderView> v = investigations.views(list).stream().collect(Collectors.toMap(LabOrderView::id, Function.identity()));
        List<OutstandingRow> rows = new ArrayList<>();
        for (LabOrder o : list) {
            double age = Math.round(Duration.between(o.getOrderedAt(), now).toMinutes() / 6.0) / 10.0;
            String reason;
            boolean overdue;
            if (o.getStatus() == LabOrder.Status.RESULTED) {
                Instant resulted = v.get(o.getId()).results().stream().map(ClinicalDtos.LabResultView::resultedAt).max(Instant::compareTo).orElse(o.getOrderedAt());
                overdue = Duration.between(resulted, now).toHours() >= 4;
                reason = overdue ? "Result unreviewed for " + Duration.between(resulted, now).toHours() + " h" : "Awaiting review";
            } else if (o.getStatus() == LabOrder.Status.ORDERED) {
                overdue = o.getDueAt() != null && o.getDueAt().isBefore(now);
                reason = overdue ? "Collection overdue" : "Awaiting collection";
            } else {
                overdue = age > 24;
                reason = overdue ? "No result after " + Math.round(age) + " h" : "Awaiting result";
            }
            rows.add(new OutstandingRow(v.get(o.getId()), age, overdue, reason));
        }
        return new OutstandingReport(rows.size(),
                (int) list.stream().filter(o -> o.getStatus() == LabOrder.Status.ORDERED).count(),
                (int) list.stream().filter(o -> o.getStatus() == LabOrder.Status.COLLECTED || o.getStatus() == LabOrder.Status.IN_PROGRESS).count(),
                (int) list.stream().filter(o -> o.getStatus() == LabOrder.Status.RESULTED).count(),
                (int) rows.stream().filter(OutstandingRow::overdue).count(), rows);
    }

    @Transactional(readOnly = true)
    public AlertHistoryReport alertHistory(int days) {
        int d = Math.min(Math.max(days, 1), 90);
        List<UUID> ids = reportScope().stream().map(AdmissionSummary::id).toList();
        if (ids.isEmpty()) {
            return new AlertHistoryReport(d, 0, 0, 0, null, List.of(), List.of(), List.of(), List.of());
        }
        List<ClinicalAlert> list = alertRepo.findByAdmissionIdInAndRaisedAtAfterOrderByRaisedAtDesc(ids, Instant.now().minus(Duration.ofDays(d)));
        List<Long> ackMinutes = list.stream().filter(a -> a.getAcknowledgedAt() != null)
                .map(a -> Duration.between(a.getRaisedAt(), a.getAcknowledgedAt()).toMinutes()).sorted().toList();
        Double median = ackMinutes.isEmpty() ? null : ackMinutes.size() % 2 == 1 ? (double) ackMinutes.get(ackMinutes.size() / 2)
                : (ackMinutes.get(ackMinutes.size() / 2 - 1) + ackMinutes.get(ackMinutes.size() / 2)) / 2.0;
        Map<String, Long> byType = new LinkedHashMap<>();
        list.forEach(a -> byType.merge(a.getAlertType().name(), 1L, Long::sum));
        Map<String, Long> bySev = new LinkedHashMap<>();
        list.forEach(a -> bySev.merge(a.getSeverity().name(), 1L, Long::sum));
        Map<String, Long> byDay = new java.util.TreeMap<>();
        LocalDate today = TimeUtil.today();
        for (int i = d - 1; i >= 0; i--) {
            byDay.put(today.minusDays(i).toString(), 0L);
        }
        list.forEach(a -> byDay.merge(TimeUtil.localDate(a.getRaisedAt()).toString(), 1L, Long::sum));
        return new AlertHistoryReport(d, list.size(), list.stream().filter(a -> a.getSeverity() == ClinicalAlert.Severity.CRITICAL).count(),
                list.stream().filter(a -> a.getStatus() == ClinicalAlert.Status.RESOLVED).count(), median,
                rowsOf(byType), rowsOf(bySev), rowsOf(byDay), alerts.views(list.stream().limit(15).toList()));
    }

    private static List<CountRow> rowsOf(Map<String, Long> m) {
        return m.entrySet().stream().map(e -> new CountRow(e.getKey(), e.getValue())).toList();
    }

    // ------------------------------------------------------------------ patient portal (released content only)

    @Transactional(readOnly = true)
    public PortalRecords portalRecords(UUID patientId) {
        access.requirePatient(patientId, AccessPolicy.Scope.CLINICAL);
        Map<UUID, String> admissionNumbers = admissions.findByPatientIdOrderByAdmittedAtDesc(patientId).stream()
                .collect(Collectors.toMap(Admission::getId, Admission::getAdmissionNumber));
        List<ClinicalNote> ns = notes.findByPatientIdAndReleasedToPatientTrueAndStatusOrderByAuthoredAtDesc(patientId, ClinicalNote.Status.FINAL);
        Map<UUID, String> names = support.userNames(ns.stream().map(ClinicalNote::getAuthorId).toList());
        List<PortalNote> portalNotes = ns.stream().filter(n -> n.getPatientSummary() != null)
                .map(n -> new PortalNote(n.getId(), n.getNoteType().name(), n.getTitle(), n.getPatientSummary(), names.get(n.getAuthorId()),
                        n.getAuthoredAt(), n.getReleasedAt(), admissionNumbers.get(n.getAdmissionId()))).toList();

        List<LabResult> released = labResults.findByPatientIdAndReleasedToPatientTrueOrderByResultedAtDesc(patientId).stream()
                .filter(r -> r.getStatus() != LabResult.Status.ENTERED_IN_ERROR).toList();
        Map<UUID, List<LabResult>> byOrder = released.stream().collect(Collectors.groupingBy(LabResult::getLabOrderId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, LabOrder> orderMap = labOrders.findAllById(byOrder.keySet()).stream().collect(Collectors.toMap(LabOrder::getId, Function.identity()));
        Map<UUID, String> resultNames = support.userNames(released.stream().flatMap(r -> java.util.stream.Stream.of(r.getResultedBy(), r.getReviewedBy())).toList());
        List<PortalResult> results = byOrder.entrySet().stream().filter(e -> orderMap.containsKey(e.getKey())).map(e -> {
            LabOrder o = orderMap.get(e.getKey());
            List<LabResult> rs = e.getValue();
            return new PortalResult(o.getId(), o.getTestName(), o.getCategory().name(), o.getOrderedAt(),
                    rs.stream().map(LabResult::getResultedAt).max(Instant::compareTo).orElse(null),
                    rs.stream().map(LabResult::getReviewComment).filter(java.util.Objects::nonNull).findFirst().orElse(null),
                    admissionNumbers.get(o.getAdmissionId()),
                    rs.stream().map(r -> InvestigationService.resultView(r, resultNames)).toList());
        }).toList();

        List<PortalPlan> portalPlans = new ArrayList<>();
        for (UUID admissionId : admissionNumbers.keySet()) {
            for (TreatmentPlan p : plans.findByAdmissionIdOrderByPlanVersionDesc(admissionId)) {
                if (p.isReleasedToPatient() && p.getPatientSummary() != null) {
                    portalPlans.add(new PortalPlan(p.getId(), p.getPlanVersion(), p.getPatientSummary(), p.getStatus().name(),
                            p.getCreatedAt(), p.getReleasedAt(), support.userName(p.getCreatedBy()), admissionNumbers.get(admissionId)));
                }
            }
        }
        portalPlans.sort(Comparator.comparing(PortalPlan::createdAt).reversed());
        return new PortalRecords(patientId, portalNotes, results, portalPlans);
    }

    @Transactional(readOnly = true)
    public List<OrderView> portalMedications(UUID patientId) {
        access.requirePatient(patientId, AccessPolicy.Scope.CLINICAL);
        List<MedicationOrder> list = medOrders.findByPatientIdAndReleasedToPatientTrueOrderByPrescribedAtDesc(patientId);
        // Strip staff-only fields: the portal sees the medicine, dose, schedule and plain-language instructions.
        return medViews.orders(list).stream().map(o -> new OrderView(o.id(), o.admissionId(), null, o.medicationId(), o.medicationName(),
                o.genericName(), o.strength(), o.form(), false, false, o.dose(), o.doseUnit(), o.route(), o.frequency(), o.scheduleTimes(),
                o.startAt(), o.endAt(), o.durationDays(), null, null, o.patientInstructions(), o.dischargeMedication(), o.status(),
                null, null, o.prescribedBy(), o.prescribedAt(), null, null, null, o.discontinuedAt(), null, null, null, null, true,
                null, null, null)).toList();
    }

    CurrentUser me() {
        return TenantContext.requireUser();
    }
}
