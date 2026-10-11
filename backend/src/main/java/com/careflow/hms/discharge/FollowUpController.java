package com.careflow.hms.discharge;

import com.careflow.hms.discharge.DischargeDtos.FollowUpOptions;
import com.careflow.hms.discharge.DischargeDtos.FollowUpOutcomeRequest;
import com.careflow.hms.discharge.DischargeDtos.FollowUpRequest;
import com.careflow.hms.discharge.DischargeDtos.FollowUpView;
import com.careflow.hms.discharge.DischargeDtos.PendingResultView;
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
import java.util.UUID;

@Tag(name = "Follow-up and continuity of care")
@RestController
@RequestMapping("/api/discharge")
@RequiredArgsConstructor
public class FollowUpController {

    private final FollowUpService followUps;

    @GetMapping("/admissions/{admissionId}/follow-ups")
    @PreAuthorize("hasAnyAuthority('FOLLOWUP_MANAGE','DISCHARGE_VIEW') and hasAuthority('CLINICAL_READ')")
    public List<FollowUpView> forAdmission(@PathVariable UUID admissionId) {
        return followUps.forAdmission(admissionId);
    }

    @GetMapping("/admissions/{admissionId}/follow-up-options")
    @PreAuthorize("hasAuthority('FOLLOWUP_MANAGE')")
    public FollowUpOptions options(@PathVariable UUID admissionId) {
        return followUps.options(admissionId);
    }

    @Operation(summary = "Plan a follow-up; the patient is notified through the configured channels")
    @PostMapping("/admissions/{admissionId}/follow-ups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('FOLLOWUP_MANAGE')")
    public FollowUpView create(@PathVariable UUID admissionId, @Valid @RequestBody FollowUpRequest body) {
        return followUps.create(admissionId, body);
    }

    @PutMapping("/follow-ups/{id}")
    @PreAuthorize("hasAuthority('FOLLOWUP_MANAGE')")
    public FollowUpView update(@PathVariable UUID id, @Valid @RequestBody FollowUpRequest body) {
        return followUps.update(id, body);
    }

    @Operation(summary = "Record the outcome: COMPLETED, MISSED or CANCELLED")
    @PostMapping("/follow-ups/{id}/outcome")
    @PreAuthorize("hasAuthority('FOLLOWUP_MANAGE')")
    public FollowUpView outcome(@PathVariable UUID id, @Valid @RequestBody FollowUpOutcomeRequest body) {
        return followUps.recordOutcome(id, body);
    }

    @PostMapping("/follow-ups/{id}/resend")
    @PreAuthorize("hasAuthority('FOLLOWUP_MANAGE')")
    public FollowUpView resend(@PathVariable UUID id) {
        return followUps.resend(id);
    }

    @Operation(summary = "My follow-up responsibilities (responsible user or named clinician)")
    @GetMapping("/follow-ups/my")
    @PreAuthorize("hasAuthority('FOLLOWUP_MANAGE')")
    public List<FollowUpView> mine(@RequestParam(defaultValue = "false") boolean includeClosed) {
        return followUps.mine(includeClosed);
    }

    @Operation(summary = "Investigations awaiting review for my discharged / discharging patients")
    @GetMapping("/follow-ups/pending-results")
    @PreAuthorize("hasAuthority('LAB_RESULT_REVIEW')")
    public List<PendingResultView> pendingResults() {
        return followUps.pendingResults();
    }
}
