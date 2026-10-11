package com.careflow.hms.discharge;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.admission.AdmissionService;
import com.careflow.hms.billing.Invoice;
import com.careflow.hms.billing.InvoiceRepository;
import com.careflow.hms.billing.LedgerService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.discharge.DischargeDtos.DelayReport;
import com.careflow.hms.discharge.DischargeDtos.FinancialQueueItem;
import com.careflow.hms.discharge.DischargeDtos.StageDelay;
import com.careflow.hms.discharge.DischargeDtos.StepDelay;
import com.careflow.hms.discharge.DischargeDtos.WorkflowListItem;
import com.careflow.hms.discharge.DischargeDtos.Worklist;
import com.careflow.hms.discharge.DischargeDtos.WorklistItem;
import com.careflow.hms.discharge.DischargeWorkflowService.Names;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Coordination views: discharge board, clinician worklists, billing clearance queue and delay analytics. */
@Service
@RequiredArgsConstructor
public class DischargeBoardService {

    private static final EnumSet<DischargeWorkflow.Status> OPEN =
            EnumSet.of(DischargeWorkflow.Status.IN_PROGRESS, DischargeWorkflow.Status.READY);

    private final DischargeWorkflowRepository workflows;
    private final DischargeStepRepository steps;
    private final AdmissionRepository admissions;
    private final AdmissionService admissionService;
    private final InvoiceRepository invoices;
    private final LedgerService ledger;
    private final UserRepository users;
    private final AccessPolicy access;
    private final DischargeWorkflowService workflowService;

    public enum BoardScope { ACTIVE, COMPLETED, CANCELLED }

    /** All workflows the caller may see, with blockers, age and stage (coordination board). */
    @Transactional
    public List<WorkflowListItem> board(BoardScope scope, UUID wardId, UUID departmentId, String q, boolean blockedOnly, int days) {
        CurrentUser u = TenantContext.requireUser();
        UUID org = u.organizationId();
        List<DischargeWorkflow> list = switch (scope) {
            case ACTIVE -> workflows.findByOrganizationIdAndStatusIn(org, OPEN);
            case COMPLETED -> workflows.findByOrganizationIdAndStatusAndCompletedAtAfterOrderByCompletedAtDesc(
                    org, DischargeWorkflow.Status.COMPLETED, Instant.now().minus(Duration.ofDays(clampDays(days))));
            case CANCELLED -> workflows.findByOrganizationIdAndStatusAndInitiatedAtAfterOrderByInitiatedAtDesc(
                    org, DischargeWorkflow.Status.CANCELLED, Instant.now().minus(Duration.ofDays(clampDays(days))));
        };
        if (scope == BoardScope.ACTIVE) {
            list.stream().map(DischargeWorkflow::getId).sorted().forEach(workflowService::syncWorkflow);
        }
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        List<WorkflowListItem> rows = rows(u, list).stream()
                .filter(r -> wardId == null || matchesWard(r, wardId))
                .filter(r -> departmentId == null || departmentId.equals(r.departmentId()))
                .filter(r -> needle == null || r.item().patient().fullName().toLowerCase(Locale.ROOT).contains(needle)
                        || r.item().patient().mrn().toLowerCase(Locale.ROOT).contains(needle)
                        || r.item().admissionNumber().toLowerCase(Locale.ROOT).contains(needle))
                .filter(r -> !blockedOnly || r.item().blockedSteps() > 0)
                .map(Row::item)
                .collect(Collectors.toCollection(ArrayList::new));
        if (scope == BoardScope.ACTIVE) {
            rows.sort(Comparator.comparing((WorkflowListItem r) -> r.status() != DischargeWorkflow.Status.READY)
                    .thenComparing(r -> -r.blockedSteps())
                    .thenComparing(r -> r.plannedDischargeAt() == null ? Instant.MAX : r.plannedDischargeAt()));
        }
        return rows;
    }

    /** Doctor / nurse discharge worklist: my open admissions with their workflow, plus recent discharges. */
    @Transactional
    public Worklist worklist() {
        CurrentUser u = TenantContext.requireUser();
        if (u.role() != Role.DOCTOR && u.role() != Role.NURSE) {
            throw ApiException.forbidden("The discharge worklist is for clinicians.");
        }
        List<AdmissionSummary> mine = admissionService.myAdmissions().stream()
                .filter(a -> a.status() == Admission.Status.ADMITTED || a.status() == Admission.Status.DISCHARGE_IN_PROGRESS)
                .toList();
        Map<UUID, DischargeWorkflow> open = new HashMap<>();
        if (!mine.isEmpty()) {
            workflows.findByAdmissionIdInOrderByInitiatedAtDesc(mine.stream().map(AdmissionSummary::id).toList()).stream()
                    .filter(w -> OPEN.contains(w.getStatus()))
                    .forEach(w -> open.putIfAbsent(w.getAdmissionId(), w));
        }
        open.values().stream().map(DischargeWorkflow::getId).sorted().forEach(workflowService::syncWorkflow);
        Names names = new Names(users);
        Map<UUID, Admission> admMap = admissions.findByIdIn(mine.stream().map(AdmissionSummary::id).toList()).stream()
                .collect(Collectors.toMap(Admission::getId, Function.identity()));
        List<WorklistItem> active = new ArrayList<>();
        for (AdmissionSummary s : mine) {
            DischargeWorkflow w = open.get(s.id());
            Admission a = admMap.get(s.id());
            WorkflowListItem item = w == null || a == null ? null : workflowService.listItem(u, workflows.findById(w.getId()).orElseThrow(), a, s, names);
            active.add(new WorklistItem(s, item));
        }
        active.sort(Comparator.comparing((WorklistItem i) -> i.workflow() == null)
                .thenComparing(i -> i.admission().expectedDischargeDate() == null ? java.time.LocalDate.MAX : i.admission().expectedDischargeDate()));
        List<DischargeWorkflow> recent = workflows.findByOrganizationIdAndStatusAndCompletedAtAfterOrderByCompletedAtDesc(
                u.organizationId(), DischargeWorkflow.Status.COMPLETED, Instant.now().minus(Duration.ofDays(7)));
        List<WorkflowListItem> recentRows = rows(u, recent).stream()
                .filter(r -> access.canAccessAdmission(u, r.admission(), AccessPolicy.Scope.CLINICAL))
                .map(Row::item).toList();
        return new Worklist(active, recentRows);
    }

    /** Billing clearance queue with authoritative ledger balances. */
    @Transactional
    public List<FinancialQueueItem> financialQueue(String filter) {
        CurrentUser u = TenantContext.requireUser();
        UUID org = u.organizationId();
        List<DischargeWorkflow> list = new ArrayList<>(workflows.findByOrganizationIdAndStatusIn(org, OPEN));
        list.stream().map(DischargeWorkflow::getId).sorted().forEach(workflowService::syncWorkflow);
        boolean includeRecent = !"PENDING".equalsIgnoreCase(filter);
        if (includeRecent) {
            list.addAll(workflows.findByOrganizationIdAndStatusAndCompletedAtAfterOrderByCompletedAtDesc(
                    org, DischargeWorkflow.Status.COMPLETED, Instant.now().minus(Duration.ofDays(14))));
        }
        List<FinancialQueueItem> out = new ArrayList<>();
        for (Row r : rows(u, list)) {
            if (!access.canAccessAdmission(u, r.admission(), AccessPolicy.Scope.FINANCIAL)) {
                continue;
            }
            DischargeWorkflow w = r.workflow();
            boolean pending = w.getFinancialClearance() == null && OPEN.contains(w.getStatus());
            if ("PENDING".equalsIgnoreCase(filter) && !pending || "CLEARED".equalsIgnoreCase(filter) && w.getFinancialClearance() == null) {
                continue;
            }
            Invoice inv = invoices.findActiveByAdmission(w.getAdmissionId()).orElse(null);
            LedgerService.BalanceSummary bs = inv == null ? null : ledger.summarize(inv.getId());
            Names names = new Names(users);
            out.add(new FinancialQueueItem(r.item(), inv == null ? null : inv.getId(), inv == null ? null : inv.getInvoiceNumber(),
                    inv == null ? null : inv.getStatus(), bs == null ? null : bs.balance(),
                    bs == null ? null : bs.paymentStatus().name(), w.getFinancialClearance(), names.of(w.getFinancialClearedBy()),
                    w.getFinancialClearedAt(), w.getFinancialExceptionReason(), w.getBalanceAtClearance()));
        }
        out.sort(Comparator.comparing((FinancialQueueItem i) -> i.clearance() != null)
                .thenComparing(i -> i.workflow().clinicalDecision() != DischargeWorkflow.ClinicalDecision.AUTHORIZED)
                .thenComparing(i -> i.workflow().plannedDischargeAt() == null ? Instant.MAX : i.workflow().plannedDischargeAt()));
        return out;
    }

    /** "Discharge delays by workflow stage": how long steps stay open, and what is blocked right now. */
    @Transactional(readOnly = true)
    public DelayReport delayReport(Instant from, Instant to) {
        UUID org = TenantContext.requireOrganizationId();
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofDays(30)) : from;
        if (!start.isBefore(end)) {
            throw ApiException.badRequest("INVALID_RANGE", "The start of the range must be before its end.");
        }
        List<DischargeWorkflow> list = workflows.findByOrganizationIdAndInitiatedAtBetween(org, start, end).stream()
                .filter(w -> w.getStatus() != DischargeWorkflow.Status.CANCELLED).toList();
        Map<UUID, DischargeWorkflow> byId = list.stream().collect(Collectors.toMap(DischargeWorkflow::getId, Function.identity()));
        List<DischargeStep> all = list.isEmpty() ? List.of() : steps.findByWorkflowIdIn(byId.keySet());
        Instant now = Instant.now();
        Map<String, List<Double>> stageHours = new LinkedHashMap<>();
        Map<String, int[]> stageCounts = new LinkedHashMap<>();
        Map<String, List<Double>> stepHours = new LinkedHashMap<>();
        Map<String, int[]> stepCounts = new LinkedHashMap<>();
        Map<String, String[]> stepMeta = new LinkedHashMap<>();
        for (DischargeStepTemplate.Category c : DischargeStepTemplate.Category.values()) {
            stageHours.put(c.name(), new ArrayList<>());
            stageCounts.put(c.name(), new int[2]);
        }
        all.stream().sorted(Comparator.comparingInt(DischargeStep::getStepOrder)).forEach(s -> {
            if (s.getStatus() == DischargeStep.Status.NOT_APPLICABLE || DischargeChecks.isTerminal(s)) {
                return;
            }
            DischargeWorkflow w = byId.get(s.getWorkflowId());
            boolean open = OPEN.contains(w.getStatus());
            Instant closed = s.getCompletedAt() != null ? s.getCompletedAt() : open ? now : w.getCompletedAt();
            if (closed == null) {
                return;
            }
            double hours = Math.max(0, Duration.between(w.getInitiatedAt(), closed).toMinutes() / 60.0);
            String stage = s.getCategory().name();
            stageHours.get(stage).add(hours);
            stepHours.computeIfAbsent(s.getTemplateCode(), k -> new ArrayList<>()).add(hours);
            stepMeta.putIfAbsent(s.getTemplateCode(), new String[]{s.getName(), stage});
            int[] sc = stageCounts.get(stage);
            int[] tc = stepCounts.computeIfAbsent(s.getTemplateCode(), k -> new int[2]);
            if (open && !s.getStatus().isDone()) {
                sc[0]++;
                tc[0]++;
                if (s.getStatus() == DischargeStep.Status.BLOCKED) {
                    sc[1]++;
                    tc[1]++;
                }
            }
        });
        List<StageDelay> byStage = stageHours.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(e -> new StageDelay(e.getKey(), DischargeChecks.stageLabel(e.getKey()), e.getValue().size(),
                        round(avg(e.getValue())), round(median(e.getValue())), stageCounts.get(e.getKey())[0],
                        stageCounts.get(e.getKey())[1]))
                .toList();
        List<StepDelay> bySteps = stepHours.entrySet().stream()
                .map(e -> new StepDelay(e.getKey(), stepMeta.get(e.getKey())[0], stepMeta.get(e.getKey())[1],
                        e.getValue().size(), round(avg(e.getValue())), round(median(e.getValue())),
                        stepCounts.get(e.getKey())[0], stepCounts.get(e.getKey())[1]))
                .sorted(Comparator.comparingDouble(StepDelay::avgOpenHours).reversed())
                .toList();
        List<Double> toDischarge = list.stream().filter(w -> w.getStatus() == DischargeWorkflow.Status.COMPLETED && w.getCompletedAt() != null)
                .map(w -> Duration.between(w.getInitiatedAt(), w.getCompletedAt()).toMinutes() / 60.0).toList();
        return new DelayReport(start, end, list.size(), toDischarge.size(), round(avg(toDischarge)), round(median(toDischarge)),
                byStage, bySteps);
    }

    // ------------------------------------------------------------------ helpers

    private record Row(DischargeWorkflow workflow, Admission admission, AdmissionSummary summary, WorkflowListItem item) {
        UUID departmentId() {
            return admission.getDepartmentId();
        }
    }

    private boolean matchesWard(Row r, UUID wardId) {
        return r.summary().location() != null && wardId.equals(r.summary().location().wardId());
    }

    private List<Row> rows(CurrentUser u, List<DischargeWorkflow> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, Admission> admMap = admissions.findByIdIn(list.stream().map(DischargeWorkflow::getAdmissionId).distinct().toList())
                .stream().collect(Collectors.toMap(Admission::getId, Function.identity()));
        List<DischargeWorkflow> visible = list.stream()
                .filter(w -> admMap.containsKey(w.getAdmissionId()) && workflowService.canView(u, admMap.get(w.getAdmissionId())))
                .toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        Map<UUID, AdmissionSummary> sums = admissionService.summaries(
                        visible.stream().map(w -> admMap.get(w.getAdmissionId())).distinct().toList(), false)
                .stream().collect(Collectors.toMap(AdmissionSummary::id, Function.identity()));
        Names names = new Names(users);
        List<Row> rows = new ArrayList<>();
        for (DischargeWorkflow w : visible) {
            Admission a = admMap.get(w.getAdmissionId());
            DischargeWorkflow fresh = workflows.findById(w.getId()).orElse(w);
            rows.add(new Row(fresh, a, sums.get(a.getId()), workflowService.listItem(u, fresh, a, sums.get(a.getId()), names)));
        }
        return rows;
    }

    private static int clampDays(int days) {
        return Math.min(Math.max(days, 1), 365);
    }

    private static double avg(List<Double> v) {
        return v.isEmpty() ? 0 : v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) {
            return 0;
        }
        List<Double> s = v.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
