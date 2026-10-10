package com.careflow.hms.admission;

import com.careflow.hms.admission.AdmissionDtos.AdmissionDetail;
import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.admission.AdmissionDtos.AdmitRequest;
import com.careflow.hms.admission.AdmissionDtos.AssignBedRequest;
import com.careflow.hms.admission.AdmissionDtos.CancelRequest;
import com.careflow.hms.admission.AdmissionDtos.CareTeamMember;
import com.careflow.hms.admission.AdmissionDtos.CareTeamRequest;
import com.careflow.hms.admission.AdmissionDtos.ExpectedDischargeRequest;
import com.careflow.hms.admission.AdmissionDtos.TransferDecisionRequest;
import com.careflow.hms.admission.AdmissionDtos.TransferRequest;
import com.careflow.hms.admission.AdmissionDtos.TransferView;
import com.careflow.hms.admission.AdmissionDtos.UpdateAdmissionRequest;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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

@Tag(name = "Admissions, bed assignment and transfers")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AdmissionController {

    private final AdmissionService admissions;
    private final TransferService transfers;
    private final TimelineService timeline;
    private final AccessPolicy access;

    @PostMapping("/admissions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ADMISSION_MANAGE')")
    public AdmissionDetail admit(@Valid @RequestBody AdmitRequest body) {
        return admissions.admit(body);
    }

    @Operation(summary = "Search admissions (operational list for admissions, billing and pharmacy staff)")
    @GetMapping("/admissions")
    @PreAuthorize("hasAnyAuthority('ADMISSION_MANAGE','REPORT_OPERATIONAL','BILLING_READ','DISPENSE')")
    public PageResponse<AdmissionSummary> search(@RequestParam(required = false) Set<Admission.Status> status,
                                                 @RequestParam(required = false) UUID departmentId,
                                                 @RequestParam(required = false) UUID doctorId,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @RequestParam(required = false) String sort) {
        return admissions.search(status, departmentId, doctorId, q, page, size, sort);
    }

    @Operation(summary = "Clinician worklist (doctor: attending/care team/department; nurse: assigned wards)")
    @GetMapping("/admissions/my")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<AdmissionSummary> my() {
        return admissions.myAdmissions();
    }

    @GetMapping("/admissions/{id}")
    @PreAuthorize("hasAnyAuthority('PATIENT_DEMOGRAPHICS_READ','PORTAL_ACCESS')")
    public AdmissionDetail get(@PathVariable UUID id) {
        return admissions.get(id);
    }

    @GetMapping("/patients/{patientId}/admissions")
    @PreAuthorize("hasAnyAuthority('PATIENT_DEMOGRAPHICS_READ','PORTAL_ACCESS')")
    public List<AdmissionSummary> forPatient(@PathVariable UUID patientId) {
        return admissions.forPatient(patientId);
    }

    @PutMapping("/admissions/{id}")
    @PreAuthorize("hasAuthority('ADMISSION_MANAGE')")
    public AdmissionDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateAdmissionRequest body) {
        return admissions.update(id, body);
    }

    @PutMapping("/admissions/{id}/expected-discharge")
    @PreAuthorize("hasAnyAuthority('ADMISSION_MANAGE','DISCHARGE_CLINICAL_AUTHORIZE')")
    public AdmissionDetail expectedDischarge(@PathVariable UUID id, @RequestBody ExpectedDischargeRequest body) {
        return admissions.updateExpectedDischarge(id, body.expectedDischargeDate());
    }

    @PostMapping("/admissions/{id}/assign-bed")
    @PreAuthorize("hasAuthority('BED_ASSIGN')")
    public AdmissionDetail assignBed(@PathVariable UUID id, @Valid @RequestBody AssignBedRequest body) {
        return admissions.assignBed(id, body.bedId(), body.notes());
    }

    @PostMapping("/admissions/{id}/cancel")
    @PreAuthorize("hasAuthority('ADMISSION_MANAGE')")
    public AdmissionDetail cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest body) {
        return admissions.cancel(id, body.reason());
    }

    @GetMapping("/admissions/{id}/care-team")
    @PreAuthorize("hasAnyAuthority('PATIENT_DEMOGRAPHICS_READ')")
    public List<CareTeamMember> careTeam(@PathVariable UUID id) {
        access.requireAdmission(id, AccessPolicy.Scope.DEMOGRAPHICS);
        return admissions.careTeamOf(id);
    }

    @PostMapping("/admissions/{id}/care-team")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ADMISSION_MANAGE','CLINICAL_NOTE_WRITE')")
    public List<CareTeamMember> addCareTeam(@PathVariable UUID id, @Valid @RequestBody CareTeamRequest body) {
        return admissions.addCareTeamMember(id, body.userId(), body.careRole());
    }

    @DeleteMapping("/admissions/{id}/care-team/{memberId}")
    @PreAuthorize("hasAnyAuthority('ADMISSION_MANAGE','CLINICAL_NOTE_WRITE')")
    public List<CareTeamMember> removeCareTeam(@PathVariable UUID id, @PathVariable UUID memberId) {
        return admissions.removeCareTeamMember(id, memberId);
    }

    @Operation(summary = "Role-filtered admission timeline")
    @GetMapping("/admissions/{id}/timeline")
    public PageResponse<TimelineService.TimelineItem> timeline(@PathVariable UUID id,
                                                               @RequestParam(required = false) Set<TimelineEvent.Category> category,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "50") int size) {
        access.requireAdmission(id, timelineScope(TenantContext.requireUser()));
        return timeline.forAdmission(id, category, page, size);
    }

    @GetMapping("/patients/{patientId}/timeline")
    public PageResponse<TimelineService.TimelineItem> patientTimeline(@PathVariable UUID patientId,
                                                                      @RequestParam(required = false) Set<TimelineEvent.Category> category,
                                                                      @RequestParam(defaultValue = "0") int page,
                                                                      @RequestParam(defaultValue = "50") int size) {
        access.requirePatient(patientId, timelineScope(TenantContext.requireUser()));
        return timeline.forPatient(patientId, category, page, size);
    }

    private static AccessPolicy.Scope timelineScope(CurrentUser u) {
        return switch (u.role()) {
            case DOCTOR, NURSE, PATIENT -> AccessPolicy.Scope.CLINICAL;
            case PHARMACIST -> AccessPolicy.Scope.MEDICATION;
            case BILLING -> AccessPolicy.Scope.FINANCIAL;
            default -> u.role() == Role.SUPER_ADMIN ? AccessPolicy.Scope.CLINICAL : AccessPolicy.Scope.DEMOGRAPHICS;
        };
    }

    // ------------------------------------------------------------------ transfers

    @PostMapping("/admissions/{id}/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('TRANSFER_REQUEST','TRANSFER_COMPLETE')")
    public TransferView requestTransfer(@PathVariable UUID id, @Valid @RequestBody TransferRequest body) {
        return transfers.request(id, body);
    }

    @GetMapping("/transfers")
    @PreAuthorize("hasAnyAuthority('TRANSFER_REQUEST','TRANSFER_COMPLETE')")
    public PageResponse<TransferView> listTransfers(@RequestParam(required = false) Set<Transfer.Status> status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return transfers.list(status, page, size);
    }

    @PostMapping("/transfers/{id}/approve")
    @PreAuthorize("hasAuthority('TRANSFER_COMPLETE')")
    public TransferView approve(@PathVariable UUID id, @Valid @RequestBody TransferDecisionRequest body) {
        return transfers.approve(id, body.toBedId(), body.notes());
    }

    @PostMapping("/transfers/{id}/complete")
    @PreAuthorize("hasAuthority('TRANSFER_COMPLETE')")
    public TransferView complete(@PathVariable UUID id) {
        return transfers.complete(id);
    }

    @PostMapping("/transfers/{id}/reject")
    @PreAuthorize("hasAuthority('TRANSFER_COMPLETE')")
    public TransferView reject(@PathVariable UUID id, @Valid @RequestBody TransferDecisionRequest body) {
        return transfers.reject(id, body.notes(), false);
    }

    @PostMapping("/transfers/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('TRANSFER_REQUEST','TRANSFER_COMPLETE')")
    public TransferView cancelTransfer(@PathVariable UUID id, @Valid @RequestBody TransferDecisionRequest body) {
        return transfers.reject(id, body.notes(), true);
    }
}
