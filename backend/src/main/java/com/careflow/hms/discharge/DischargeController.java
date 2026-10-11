package com.careflow.hms.discharge;

import com.careflow.hms.discharge.DischargeBoardService.BoardScope;
import com.careflow.hms.discharge.DischargeDtos.CancelRequest;
import com.careflow.hms.discharge.DischargeDtos.ClinicalDecisionRequest;
import com.careflow.hms.discharge.DischargeDtos.CompleteRequest;
import com.careflow.hms.discharge.DischargeDtos.DelayReport;
import com.careflow.hms.discharge.DischargeDtos.DischargeDetail;
import com.careflow.hms.discharge.DischargeDtos.FinancialClearanceRequest;
import com.careflow.hms.discharge.DischargeDtos.FinancialQueueItem;
import com.careflow.hms.discharge.DischargeDtos.InitiateRequest;
import com.careflow.hms.discharge.DischargeDtos.PlanRequest;
import com.careflow.hms.discharge.DischargeDtos.StepUpdateRequest;
import com.careflow.hms.discharge.DischargeDtos.SummaryEnvelope;
import com.careflow.hms.discharge.DischargeDtos.SummaryRequest;
import com.careflow.hms.discharge.DischargeDtos.TemplateRequest;
import com.careflow.hms.discharge.DischargeDtos.TemplateView;
import com.careflow.hms.discharge.DischargeDtos.WorkflowListItem;
import com.careflow.hms.discharge.DischargeDtos.Worklist;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Discharge workflow, blockers and summaries")
@RestController
@RequestMapping("/api/discharge")
@RequiredArgsConstructor
public class DischargeController {

    private final DischargeWorkflowService workflows;
    private final DischargeBoardService board;
    private final DischargeSummaryService summaries;
    private final DischargeTemplateService templates;

    // ------------------------------------------------------------------ workflows

    @Operation(summary = "Discharge coordination board: workflows with blocker counts, age and stage")
    @GetMapping("/workflows")
    @PreAuthorize("hasAuthority('DISCHARGE_VIEW')")
    public List<WorkflowListItem> board(@RequestParam(defaultValue = "ACTIVE") BoardScope scope,
                                        @RequestParam(required = false) UUID wardId,
                                        @RequestParam(required = false) UUID departmentId,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "false") boolean blockedOnly,
                                        @RequestParam(defaultValue = "30") int days) {
        return board.board(scope, wardId, departmentId, q, blockedOnly, days);
    }

    @Operation(summary = "Workflow detail: steps, computed blockers, clinical and financial panels")
    @GetMapping("/workflows/{id}")
    @PreAuthorize("hasAuthority('DISCHARGE_VIEW')")
    public DischargeDetail workflow(@PathVariable UUID id) {
        return workflows.detailForWorkflow(id);
    }

    @Operation(summary = "Discharge view of an admission (current or latest workflow, permitted actions)")
    @GetMapping("/admissions/{admissionId}")
    @PreAuthorize("hasAuthority('DISCHARGE_VIEW')")
    public DischargeDetail forAdmission(@PathVariable UUID admissionId) {
        return workflows.detailForAdmission(admissionId);
    }

    @Operation(summary = "Start the discharge workflow (steps instantiated from the hospital's active templates)")
    @PostMapping("/admissions/{admissionId}/workflow")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('DISCHARGE_STEP_UPDATE','DISCHARGE_CLINICAL_AUTHORIZE')")
    public DischargeDetail initiate(@PathVariable UUID admissionId, @Valid @RequestBody(required = false) InitiateRequest body) {
        return workflows.initiate(admissionId, body);
    }

    @PostMapping("/workflows/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('DISCHARGE_STEP_UPDATE','DISCHARGE_CLINICAL_AUTHORIZE')")
    public DischargeDetail cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest body) {
        return workflows.cancel(id, body.reason());
    }

    @PutMapping("/workflows/{id}/plan")
    @PreAuthorize("hasAnyAuthority('DISCHARGE_STEP_UPDATE','DISCHARGE_CLINICAL_AUTHORIZE')")
    public DischargeDetail plan(@PathVariable UUID id, @Valid @RequestBody PlanRequest body) {
        return workflows.updatePlan(id, body.plannedDischargeAt());
    }

    @Operation(summary = "Update a checklist step (responsible role or hospital admin)")
    @PatchMapping("/workflows/{id}/steps/{stepId}")
    @PreAuthorize("hasAuthority('DISCHARGE_STEP_UPDATE')")
    public DischargeDetail updateStep(@PathVariable UUID id, @PathVariable UUID stepId, @Valid @RequestBody StepUpdateRequest body) {
        return workflows.updateStep(id, stepId, body);
    }

    @Operation(summary = "Record or withdraw the clinical discharge decision (treating doctor only)")
    @PostMapping("/workflows/{id}/clinical-decision")
    @PreAuthorize("hasAuthority('DISCHARGE_CLINICAL_AUTHORIZE')")
    public DischargeDetail clinicalDecision(@PathVariable UUID id, @Valid @RequestBody ClinicalDecisionRequest body) {
        return workflows.clinicalDecision(id, body);
    }

    @Operation(summary = "Record financial clearance (balance settled) or an authorized exception (billing)")
    @PostMapping("/workflows/{id}/financial-clearance")
    @PreAuthorize("hasAuthority('DISCHARGE_FINANCIAL_CLEAR')")
    public DischargeDetail financialClearance(@PathVariable UUID id, @Valid @RequestBody FinancialClearanceRequest body) {
        return workflows.financialClearance(id, body);
    }

    @Operation(summary = "Record departure and complete the discharge (nursing / coordination)")
    @PostMapping("/workflows/{id}/complete")
    @PreAuthorize("hasAuthority('DISCHARGE_COMPLETE')")
    public DischargeDetail complete(@PathVariable UUID id, @Valid @RequestBody CompleteRequest body) {
        return workflows.complete(id, body);
    }

    @Operation(summary = "Clinician discharge worklist (my patients / my ward)")
    @GetMapping("/worklist")
    @PreAuthorize("hasAuthority('DISCHARGE_VIEW') and hasAuthority('CLINICAL_READ')")
    public Worklist worklist() {
        return board.worklist();
    }

    @Operation(summary = "Financial clearance queue with ledger balances (PENDING, CLEARED or ALL)")
    @GetMapping("/financial-queue")
    @PreAuthorize("hasAnyAuthority('DISCHARGE_FINANCIAL_CLEAR','BILLING_READ')")
    public List<FinancialQueueItem> financialQueue(@RequestParam(defaultValue = "ALL") String filter) {
        return board.financialQueue(filter);
    }

    @Operation(summary = "Discharge delays by workflow stage (how long steps stay open; current blocked counts)")
    @GetMapping("/reports/delays")
    @PreAuthorize("hasAnyAuthority('DISCHARGE_VIEW','REPORT_OPERATIONAL')")
    public DelayReport delays(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return board.delayReport(from, to);
    }

    // ------------------------------------------------------------------ step templates (hospital configuration)

    @GetMapping("/step-templates")
    @PreAuthorize("hasAnyAuthority('DISCHARGE_VIEW','SETTINGS_MANAGE')")
    public List<TemplateView> templates() {
        return templates.list();
    }

    @PostMapping("/step-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public TemplateView createTemplate(@Valid @RequestBody TemplateRequest body) {
        return templates.create(body);
    }

    @PutMapping("/step-templates/{id}")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public TemplateView updateTemplate(@PathVariable UUID id, @Valid @RequestBody TemplateRequest body) {
        return templates.update(id, body);
    }

    // ------------------------------------------------------------------ summaries

    @GetMapping("/summaries/{admissionId}")
    @PreAuthorize("hasAnyAuthority('DISCHARGE_VIEW','DISCHARGE_SUMMARY_WRITE')")
    public SummaryEnvelope summary(@PathVariable UUID admissionId) {
        return summaries.get(admissionId);
    }

    @Operation(summary = "Create a draft summary pre-filled from recorded data")
    @PostMapping("/summaries/{admissionId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DISCHARGE_SUMMARY_WRITE')")
    public SummaryEnvelope createSummary(@PathVariable UUID admissionId) {
        return summaries.createDraft(admissionId);
    }

    @Operation(summary = "Edit a draft, or amend a finalized summary (reason required)")
    @PutMapping("/summaries/{admissionId}")
    @PreAuthorize("hasAuthority('DISCHARGE_SUMMARY_WRITE')")
    public SummaryEnvelope updateSummary(@PathVariable UUID admissionId, @Valid @RequestBody SummaryRequest body) {
        return summaries.update(admissionId, body);
    }

    @PostMapping("/summaries/{admissionId}/finalize")
    @PreAuthorize("hasAuthority('DISCHARGE_SUMMARY_WRITE')")
    public SummaryEnvelope finalizeSummary(@PathVariable UUID admissionId, @RequestBody(required = false) Map<String, Long> body) {
        return summaries.finalizeSummary(admissionId, body == null ? null : body.get("version"));
    }

    @PostMapping("/summaries/{admissionId}/release")
    @PreAuthorize("hasAuthority('DISCHARGE_SUMMARY_WRITE') and hasAuthority('RELEASE_TO_PATIENT')")
    public SummaryEnvelope releaseSummary(@PathVariable UUID admissionId) {
        return summaries.release(admissionId);
    }

    @Operation(summary = "Discharge summary PDF (staff with clinical access; the patient once released)")
    @GetMapping("/summaries/{admissionId}/summary.pdf")
    @PreAuthorize("hasAnyAuthority('DISCHARGE_VIEW','DISCHARGE_SUMMARY_WRITE','PORTAL_ACCESS')")
    public ResponseEntity<byte[]> summaryPdf(@PathVariable UUID admissionId) {
        DischargeSummaryService.PdfFile f = summaries.pdf(admissionId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(f.content());
    }
}
