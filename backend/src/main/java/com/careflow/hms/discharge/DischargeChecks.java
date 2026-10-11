package com.careflow.hms.discharge;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.BedAssignmentRepository;
import com.careflow.hms.billing.Invoice;
import com.careflow.hms.billing.InvoiceRepository;
import com.careflow.hms.clinical.ClinicalAlert;
import com.careflow.hms.clinical.ClinicalAlertRepository;
import com.careflow.hms.clinical.LabOrder;
import com.careflow.hms.clinical.LabOrderRepository;
import com.careflow.hms.discharge.DischargeDtos.BlockerView;
import com.careflow.hms.discharge.DischargeDtos.Progress;
import com.careflow.hms.discharge.DischargeDtos.Readiness;
import com.careflow.hms.medication.MedicationOrder;
import com.careflow.hms.medication.MedicationOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Discharge Blocker Intelligence. Evaluates the hospital-configured automatic checks of a workflow's steps
 * against recorded data only (alerts, investigations, prescriptions, follow-ups, summary, invoice, clearance,
 * departure, bed). It never invents clinical requirements: a blocker exists only because a configured step is
 * not yet satisfied.
 */
@Component
@RequiredArgsConstructor
public class DischargeChecks {

    public static final String CLINICAL_AUTHORIZED = "CLINICAL_AUTHORIZED";
    public static final String OPEN_ALERTS = "OPEN_ALERTS";
    public static final String PENDING_RESULTS = "PENDING_RESULTS";
    public static final String DISCHARGE_MEDS = "DISCHARGE_MEDS";
    public static final String FOLLOW_UP_PLANNED = "FOLLOW_UP_PLANNED";
    public static final String SUMMARY_FINAL = "SUMMARY_FINAL";
    public static final String INVOICE_FINALIZED = "INVOICE_FINALIZED";
    public static final String FINANCIAL_CLEARED = "FINANCIAL_CLEARED";
    public static final String DEPARTED = "DEPARTED";
    public static final String BED_RELEASED = "BED_RELEASED";

    public static final Set<String> KNOWN = Set.of(CLINICAL_AUTHORIZED, OPEN_ALERTS, PENDING_RESULTS, DISCHARGE_MEDS,
            FOLLOW_UP_PLANNED, SUMMARY_FINAL, INVOICE_FINALIZED, FINANCIAL_CLEARED, DEPARTED, BED_RELEASED);

    /** Satisfied by completing the discharge itself; never shown as blockers. */
    public static final Set<String> TERMINAL = Set.of(DEPARTED, BED_RELEASED);

    /** Automatic checks a clinician may explicitly mark not applicable (with a documented reason). */
    public static final Set<String> CLINICIAN_MAY_MARK_NA = Set.of(DISCHARGE_MEDS, FOLLOW_UP_PLANNED);

    private static final Set<LabOrder.Status> OUTSTANDING_LAB = EnumSet.of(LabOrder.Status.ORDERED,
            LabOrder.Status.COLLECTED, LabOrder.Status.IN_PROGRESS, LabOrder.Status.RESULTED);

    private final ClinicalAlertRepository alerts;
    private final LabOrderRepository labOrders;
    private final MedicationOrderRepository medicationOrders;
    private final FollowUpRepository followUps;
    private final DischargeSummaryRepository summaries;
    private final InvoiceRepository invoices;
    private final BedAssignmentRepository bedAssignments;

    public record Result(boolean met, String explanation, String nextAction) {
    }

    /** Recorded data relevant to one admission's discharge checks. */
    public record Snapshot(DischargeWorkflow workflow, Admission admission, List<ClinicalAlert> openAlerts,
                           List<LabOrder> outstandingResults, int handedOverResults, List<MedicationOrder> dischargeMeds,
                           int followUps, Optional<DischargeSummary> summary, Optional<Invoice> invoice, boolean bedOpen) {
    }

    public record Evaluation(Map<UUID, Result> results, List<BlockerView> blockers, Progress progress, String stage,
                             String stageLabel, Readiness readiness) {
    }

    public Snapshot load(DischargeWorkflow w, Admission a) {
        List<ClinicalAlert> open = alerts.findByAdmissionIdOrderByRaisedAtDesc(a.getId()).stream()
                .filter(x -> x.getStatus() != ClinicalAlert.Status.RESOLVED).toList();
        List<LabOrder> outstanding = labOrders.findByAdmissionIdAndStatusIn(a.getId(), OUTSTANDING_LAB);
        Set<UUID> handedOver = outstanding.isEmpty() ? Set.of()
                : followUps.findByLabOrderIdIn(outstanding.stream().map(LabOrder::getId).toList()).stream()
                .filter(f -> f.getFollowUpType() == FollowUp.FollowUpType.RESULT_REVIEW
                        && f.getStatus() != FollowUp.Status.CANCELLED)
                .map(FollowUp::getLabOrderId).collect(Collectors.toSet());
        List<LabOrder> notHandled = outstanding.stream().filter(o -> !handedOver.contains(o.getId()))
                .sorted(Comparator.comparing(LabOrder::getOrderedAt)).toList();
        List<MedicationOrder> meds = medicationOrders.findByAdmissionIdAndStatusIn(a.getId(),
                        EnumSet.of(MedicationOrder.Status.ACTIVE)).stream()
                .filter(MedicationOrder::isDischargeMedication).toList();
        int fu = (int) followUps.findByAdmissionIdOrderByScheduledAtAsc(a.getId()).stream()
                .filter(f -> f.getStatus() != FollowUp.Status.CANCELLED).count();
        return new Snapshot(w, a, open, notHandled, handedOver.size(), meds, fu, summaries.findByAdmissionId(a.getId()),
                invoices.findActiveByAdmission(a.getId()),
                bedAssignments.findByAdmissionIdAndReleasedAtIsNull(a.getId()).isPresent());
    }

    /**
     * @param clinicalDetail include clinical specifics (alert titles, test names) - only for viewers with
     *                       clinical access; others get counts.
     */
    public Result evaluate(String key, Snapshot s, boolean clinicalDetail) {
        DischargeWorkflow w = s.workflow();
        return switch (key) {
            case CLINICAL_AUTHORIZED -> w.getClinicalDecision() == DischargeWorkflow.ClinicalDecision.AUTHORIZED
                    ? new Result(true, "Discharge clinically authorized by the treating doctor.", null)
                    : w.getClinicalDecision() == DischargeWorkflow.ClinicalDecision.REVOKED
                    ? new Result(false, "The clinical discharge authorization was withdrawn; a new clinical decision is required.",
                    "Treating doctor to review the patient and record the clinical decision")
                    : new Result(false, "The treating doctor has not yet recorded the clinical discharge decision.",
                    "Treating doctor to review the patient and record the clinical decision");
            case OPEN_ALERTS -> {
                int n = s.openAlerts().size();
                if (n == 0) {
                    yield new Result(true, "No open clinical alerts.", null);
                }
                String detail = clinicalDetail
                        ? ": " + s.openAlerts().stream().limit(4)
                        .map(x -> x.getTitle() + " (" + x.getSeverity().name().toLowerCase() + ", " + x.getStatus().name().toLowerCase() + ")")
                        .collect(Collectors.joining("; "))
                        : "";
                yield new Result(false, n + " clinical alert" + (n == 1 ? " is" : "s are") + " still open" + detail + ".",
                        "Care team to resolve or formally hand over each open alert");
            }
            case PENDING_RESULTS -> {
                int n = s.outstandingResults().size();
                if (n == 0) {
                    yield new Result(true, s.handedOverResults() > 0
                            ? "All results reviewed; " + s.handedOverResults() + " handed to a result-review follow-up."
                            : "No investigation results awaiting review.", null);
                }
                String detail = clinicalDetail
                        ? ": " + s.outstandingResults().stream().limit(5)
                        .map(o -> o.getTestName() + " (" + humanize(o.getStatus().name()) + ")")
                        .collect(Collectors.joining("; "))
                        : "";
                yield new Result(false, n + " investigation" + (n == 1 ? " is" : "s are") + " not yet reviewed or handled" + detail + ".",
                        "Review the results, cancel orders no longer needed, or hand them to a result-review follow-up");
            }
            case DISCHARGE_MEDS -> s.dischargeMeds().isEmpty()
                    ? new Result(false, "No active discharge medication has been prescribed.",
                    "Prescribe discharge medications with patient instructions, or record why none are needed")
                    : new Result(true, s.dischargeMeds().size() + " discharge medication" + (s.dischargeMeds().size() == 1 ? "" : "s") + " prescribed.", null);
            case FOLLOW_UP_PLANNED -> s.followUps() == 0
                    ? new Result(false, "No follow-up appointment, result review or instruction has been documented.",
                    "Document follow-up appointments and responsibilities")
                    : new Result(true, s.followUps() + " follow-up item" + (s.followUps() == 1 ? "" : "s") + " documented.", null);
            case SUMMARY_FINAL -> {
                Optional<DischargeSummary> sum = s.summary();
                if (sum.isEmpty()) {
                    yield new Result(false, "The discharge summary has not been started.",
                            "Treating doctor to prepare and finalize the discharge summary");
                }
                yield sum.get().getStatus() == DischargeSummary.Status.DRAFT
                        ? new Result(false, "The discharge summary is still a draft.",
                        "Treating doctor to complete and finalize the discharge summary")
                        : new Result(true, "Discharge summary finalized.", null);
            }
            case INVOICE_FINALIZED -> {
                Optional<Invoice> inv = s.invoice();
                if (inv.isEmpty()) {
                    yield new Result(false, "No active invoice exists for this admission.",
                            "Billing to prepare the final invoice");
                }
                yield inv.get().getStatus() == Invoice.Status.FINALIZED
                        ? new Result(true, "Invoice " + inv.get().getInvoiceNumber() + " finalized.", null)
                        : new Result(false, "Invoice " + inv.get().getInvoiceNumber() + " is still open.",
                        "Billing to post remaining charges, review and finalize the invoice");
            }
            case FINANCIAL_CLEARED -> w.getFinancialClearance() == null
                    ? new Result(false, "Financial clearance has not been recorded.",
                    "Billing to record clearance, or an authorized exception if the balance is not settled")
                    : new Result(true, w.getFinancialClearance() == DischargeWorkflow.FinancialClearance.CLEARED
                    ? "Financially cleared." : "Authorized financial exception recorded.", null);
            case DEPARTED -> w.getActualDepartureAt() != null
                    ? new Result(true, "Departure recorded.", null)
                    : new Result(false, "Recorded when the discharge is completed.", "Record the actual departure time and disposition");
            case BED_RELEASED -> w.getStatus() == DischargeWorkflow.Status.COMPLETED && !s.bedOpen()
                    ? new Result(true, "Bed released for turnover.", null)
                    : new Result(false, "The bed is released automatically when the discharge is completed.", null);
            default -> new Result(false, "Unknown automatic check '" + key + "' - review the hospital's step configuration.", null);
        };
    }

    /** Full evaluation of a workflow: per-step results, blockers, progress, stage and readiness. */
    public Evaluation evaluateAll(DischargeWorkflow w, List<DischargeStep> steps, Snapshot snap, boolean clinicalDetail,
                                  boolean requireClearance) {
        Map<UUID, Result> results = new HashMap<>();
        for (DischargeStep s : steps) {
            if (s.getAutoCheck() != null) {
                results.put(s.getId(), evaluate(s.getAutoCheck(), snap, clinicalDetail));
            }
        }
        boolean open = w.getStatus() == DischargeWorkflow.Status.IN_PROGRESS || w.getStatus() == DischargeWorkflow.Status.READY;
        List<BlockerView> blockers = new ArrayList<>();
        if (open) {
            for (DischargeStep s : steps) {
                if (!s.isRequired() || s.getStatus().isDone() || isTerminal(s)) {
                    continue;
                }
                Result r = results.get(s.getId());
                boolean automatic = r != null && !r.met();
                String explanation;
                if (s.getStatus() == DischargeStep.Status.BLOCKED) {
                    explanation = "Blocked: " + (s.getBlockerReason() == null ? "no reason recorded" : s.getBlockerReason())
                            + (automatic ? " " + r.explanation() : "");
                } else if (automatic) {
                    explanation = r.explanation();
                } else {
                    explanation = (s.getStatus() == DischargeStep.Status.IN_PROGRESS ? "In progress - " : "Not yet started - ")
                            + (s.getDescription() == null ? s.getName() : s.getDescription());
                }
                String next = s.getNextAction() != null && !s.getNextAction().isBlank() ? s.getNextAction()
                        : r != null && r.nextAction() != null ? r.nextAction() : "Complete: " + s.getName();
                Instant since = s.getStatus() == DischargeStep.Status.BLOCKED ? s.getUpdatedAt() : w.getInitiatedAt();
                blockers.add(new BlockerView(s.getId(), s.getTemplateCode(), s.getName(), s.getCategory(),
                        s.getResponsibleRole(), s.getPriority(), explanation, next, automatic, s.getStatus(), since));
            }
            blockers.sort(Comparator
                    .comparing((BlockerView b) -> b.stepStatus() != DischargeStep.Status.BLOCKED)
                    .thenComparing(b -> -b.priority().ordinal())
                    .thenComparing(b -> steps.stream().filter(s -> s.getId().equals(b.stepId())).findFirst()
                            .map(DischargeStep::getStepOrder).orElse(0)));
        }
        int total = (int) steps.stream().filter(s -> s.getStatus() != DischargeStep.Status.NOT_APPLICABLE).count();
        int done = (int) steps.stream().filter(s -> s.getStatus() == DischargeStep.Status.COMPLETED).count();
        Progress progress = new Progress(done, total, total == 0 ? 0 : (int) Math.round(done * 100.0 / total));

        String stage;
        if (w.getStatus() == DischargeWorkflow.Status.COMPLETED) {
            stage = "DISCHARGED";
        } else if (w.getStatus() == DischargeWorkflow.Status.CANCELLED) {
            stage = "CANCELLED";
        } else {
            stage = steps.stream()
                    .filter(s -> s.isRequired() && !s.getStatus().isDone() && !isTerminal(s))
                    .min(Comparator.comparingInt(DischargeStep::getStepOrder))
                    .map(s -> s.getCategory().name()).orElse("READY");
        }

        Set<String> outstanding = new LinkedHashSet<>();
        if (open) {
            if (w.getClinicalDecision() != DischargeWorkflow.ClinicalDecision.AUTHORIZED) {
                outstanding.add("Clinical discharge authorization by the treating doctor");
            }
            for (DischargeStep s : steps) {
                if (s.isRequired() && !s.getStatus().isDone() && !isTerminal(s)
                        && !CLINICAL_AUTHORIZED.equals(s.getAutoCheck()) && !FINANCIAL_CLEARED.equals(s.getAutoCheck())) {
                    outstanding.add(s.getName());
                }
            }
            if (requireClearance && w.getFinancialClearance() == null) {
                outstanding.add("Financial clearance or an authorized exception");
            }
        }
        Readiness readiness = new Readiness(open && outstanding.isEmpty(), List.copyOf(outstanding));
        return new Evaluation(results, blockers, progress, stage, stageLabel(stage), readiness);
    }

    public static boolean isTerminal(DischargeStep s) {
        return s.getAutoCheck() != null && TERMINAL.contains(s.getAutoCheck());
    }

    public static String stageLabel(String stage) {
        return switch (stage) {
            case "CLINICAL" -> "Clinical review";
            case "PHARMACY" -> "Pharmacy";
            case "NURSING" -> "Nursing & education";
            case "ADMINISTRATIVE" -> "Administrative";
            case "FINANCIAL" -> "Billing & clearance";
            case "PATIENT" -> "Patient acknowledgement";
            case "READY" -> "Ready for departure";
            case "DISCHARGED" -> "Discharged";
            case "CANCELLED" -> "Cancelled";
            default -> humanize(stage);
        };
    }

    static String humanize(String v) {
        String s = v.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
