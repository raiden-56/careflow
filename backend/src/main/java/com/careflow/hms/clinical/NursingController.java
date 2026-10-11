package com.careflow.hms.clinical;

import com.careflow.hms.clinical.ClinicalDtos.AckRequest;
import com.careflow.hms.clinical.ClinicalDtos.AcceptDraftRequest;
import com.careflow.hms.clinical.ClinicalDtos.AssignTaskRequest;
import com.careflow.hms.clinical.ClinicalDtos.DraftRequest;
import com.careflow.hms.clinical.ClinicalDtos.DraftView;
import com.careflow.hms.clinical.ClinicalDtos.HandoverCreateRequest;
import com.careflow.hms.clinical.ClinicalDtos.HandoverSubmitRequest;
import com.careflow.hms.clinical.ClinicalDtos.HandoverUpdateRequest;
import com.careflow.hms.clinical.ClinicalDtos.HandoverView;
import com.careflow.hms.clinical.ClinicalDtos.NurseOption;
import com.careflow.hms.clinical.ClinicalDtos.OptionalNotesRequest;
import com.careflow.hms.clinical.ClinicalDtos.ReasonRequest;
import com.careflow.hms.clinical.ClinicalDtos.TaskRequest;
import com.careflow.hms.clinical.ClinicalDtos.TaskStatusRequest;
import com.careflow.hms.clinical.ClinicalDtos.TaskView;
import com.careflow.hms.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Tag(name = "Nursing: care tasks, shift handover, draft summaries")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class NursingController {

    private final CareTaskService tasks;
    private final HandoverService handovers;
    private final DraftSummaryService drafts;

    // ------------------------------------------------------------------ care tasks

    @GetMapping("/admissions/{id}/tasks")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<TaskView> admissionTasks(@PathVariable UUID id) {
        return tasks.forAdmission(id);
    }

    @PostMapping("/admissions/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CARE_TASK_MANAGE')")
    public TaskView createTask(@PathVariable UUID id, @Valid @RequestBody TaskRequest body) {
        return tasks.create(id, body);
    }

    @Operation(summary = "Care task board across the caller's patients (optional ward filter / assigned to me)")
    @GetMapping("/tasks/board")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<TaskView> board(@RequestParam(required = false) UUID wardId, @RequestParam(defaultValue = "false") boolean mine,
                                @RequestParam(required = false) Set<CareTask.Status> status) {
        return tasks.board(wardId, mine, status);
    }

    @PostMapping("/tasks/{id}/status")
    @PreAuthorize("hasAuthority('CARE_TASK_MANAGE')")
    public TaskView taskStatus(@PathVariable UUID id, @Valid @RequestBody TaskStatusRequest body) {
        return tasks.changeStatus(id, body.status(), body.notes());
    }

    @PostMapping("/tasks/{id}/complete")
    @PreAuthorize("hasAuthority('CARE_TASK_MANAGE')")
    public TaskView completeTask(@PathVariable UUID id, @Valid @RequestBody(required = false) OptionalNotesRequest body) {
        return tasks.complete(id, body == null ? null : body.notes());
    }

    @PostMapping("/tasks/{id}/assign")
    @PreAuthorize("hasAuthority('CARE_TASK_MANAGE')")
    public TaskView assignTask(@PathVariable UUID id, @RequestBody AssignTaskRequest body) {
        return tasks.assign(id, body.userId());
    }

    // ------------------------------------------------------------------ handover

    @GetMapping("/handovers")
    @PreAuthorize("hasAuthority('HANDOVER_MANAGE')")
    public PageResponse<HandoverView> handovers(@RequestParam(required = false) UUID wardId,
                                                @RequestParam(defaultValue = "false") boolean toMe,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return handovers.list(wardId, toMe, page, size);
    }

    @Operation(summary = "Create a handover for a ward/shift; items are pre-filled from live patient records")
    @PostMapping("/handovers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('HANDOVER_MANAGE')")
    public HandoverView createHandover(@Valid @RequestBody HandoverCreateRequest body) {
        return handovers.create(body);
    }

    @GetMapping("/handovers/receiving-nurses")
    @PreAuthorize("hasAuthority('HANDOVER_MANAGE')")
    public List<NurseOption> receivingNurses(@RequestParam UUID wardId) {
        return handovers.receivingNurses(wardId);
    }

    @GetMapping("/handovers/{id}")
    @PreAuthorize("hasAuthority('HANDOVER_MANAGE')")
    public HandoverView handover(@PathVariable UUID id) {
        return handovers.get(id);
    }

    @PutMapping("/handovers/{id}")
    @PreAuthorize("hasAuthority('HANDOVER_MANAGE')")
    public HandoverView updateHandover(@PathVariable UUID id, @Valid @RequestBody HandoverUpdateRequest body) {
        return handovers.update(id, body);
    }

    @PostMapping("/handovers/{id}/items/{itemId}/refresh")
    @PreAuthorize("hasAuthority('HANDOVER_MANAGE')")
    public HandoverView refreshItem(@PathVariable UUID id, @PathVariable UUID itemId) {
        return handovers.refreshItem(id, itemId);
    }

    @PostMapping("/handovers/{id}/submit")
    @PreAuthorize("hasAuthority('HANDOVER_MANAGE')")
    public HandoverView submit(@PathVariable UUID id, @Valid @RequestBody HandoverSubmitRequest body) {
        return handovers.submit(id, body.toUserId());
    }

    @PostMapping("/handovers/{id}/acknowledge")
    @PreAuthorize("hasAuthority('HANDOVER_MANAGE')")
    public HandoverView acknowledge(@PathVariable UUID id, @Valid @RequestBody(required = false) AckRequest body) {
        return handovers.acknowledge(id, body == null ? null : body.comment());
    }

    // ------------------------------------------------------------------ draft summaries (rule-based, no external AI)

    @Operation(summary = "Generate a DRAFT summary with rule-based-summarizer-v1 (deterministic, sources referenced)")
    @PostMapping("/admissions/{id}/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('AI_DRAFT_USE')")
    public DraftView generateDraft(@PathVariable UUID id, @Valid @RequestBody DraftRequest body) {
        return drafts.generate(id, body.draftType());
    }

    @GetMapping("/admissions/{id}/drafts")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<DraftView> listDrafts(@PathVariable UUID id) {
        return drafts.forAdmission(id);
    }

    @Operation(summary = "Accept a draft: creates a note authored by the reviewing clinician")
    @PostMapping("/drafts/{id}/accept")
    @PreAuthorize("hasAnyAuthority('CLINICAL_NOTE_WRITE','NURSING_NOTE_WRITE')")
    public DraftView acceptDraft(@PathVariable UUID id, @Valid @RequestBody(required = false) AcceptDraftRequest body) {
        return drafts.accept(id, body == null ? null : body.title(), body == null ? null : body.editedContent());
    }

    @PostMapping("/drafts/{id}/reject")
    @PreAuthorize("hasAuthority('AI_DRAFT_USE')")
    public DraftView rejectDraft(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return drafts.reject(id, body.reason());
    }
}
