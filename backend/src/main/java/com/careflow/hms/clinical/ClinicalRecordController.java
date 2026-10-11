package com.careflow.hms.clinical;

import com.careflow.hms.clinical.ClinicalDtos.AllergyRequest;
import com.careflow.hms.clinical.ClinicalDtos.AllergyView;
import com.careflow.hms.clinical.ClinicalDtos.AmendNoteRequest;
import com.careflow.hms.clinical.ClinicalDtos.ClinicalSummary;
import com.careflow.hms.clinical.ClinicalDtos.CompleteProcedureRequest;
import com.careflow.hms.clinical.ClinicalDtos.DiagnosisRequest;
import com.careflow.hms.clinical.ClinicalDtos.DiagnosisView;
import com.careflow.hms.clinical.ClinicalDtos.HistoryRequest;
import com.careflow.hms.clinical.ClinicalDtos.HistoryView;
import com.careflow.hms.clinical.ClinicalDtos.NoteRequest;
import com.careflow.hms.clinical.ClinicalDtos.NoteView;
import com.careflow.hms.clinical.ClinicalDtos.OptionalNotesRequest;
import com.careflow.hms.clinical.ClinicalDtos.ProcedureRequest;
import com.careflow.hms.clinical.ClinicalDtos.ProcedureView;
import com.careflow.hms.clinical.ClinicalDtos.ReasonRequest;
import com.careflow.hms.clinical.ClinicalDtos.ReleaseRequest;
import com.careflow.hms.clinical.ClinicalDtos.StatusChangeRequest;
import com.careflow.hms.clinical.ClinicalDtos.TreatmentPlanRequest;
import com.careflow.hms.clinical.ClinicalDtos.TreatmentPlanView;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.patient.Allergy;
import com.careflow.hms.patient.MedicalHistory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Tag(name = "Clinical record: allergies, history, diagnoses, treatment plans, notes, procedures")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ClinicalRecordController {

    private final ClinicalRecordService records;
    private final ClinicalOverviewService overview;
    private final ProcedureService procedures;

    @Operation(summary = "Clinical summary of an admission (allergies, history, diagnoses, plan, medications, vitals, alerts, tasks, notes)")
    @GetMapping("/admissions/{id}/clinical-summary")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public ClinicalSummary summary(@PathVariable UUID id) {
        return overview.summary(id);
    }

    // ------------------------------------------------------------------ allergies & history

    @GetMapping("/patients/{patientId}/allergies")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<AllergyView> allergies(@PathVariable UUID patientId) {
        return records.allergies(patientId);
    }

    @PostMapping("/patients/{patientId}/allergies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ALLERGY_WRITE')")
    public AllergyView addAllergy(@PathVariable UUID patientId, @Valid @RequestBody AllergyRequest body) {
        return records.addAllergy(patientId, body);
    }

    @Operation(summary = "Change allergy status (ACTIVE / INACTIVE / ENTERED_IN_ERROR with reason). Allergies are never deleted.")
    @PostMapping("/allergies/{id}/status")
    @PreAuthorize("hasAuthority('ALLERGY_WRITE')")
    public AllergyView allergyStatus(@PathVariable UUID id, @Valid @RequestBody StatusChangeRequest body) {
        return records.changeAllergyStatus(id, parse(Allergy.Status.class, body.status()), body.reason());
    }

    @GetMapping("/patients/{patientId}/medical-history")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<HistoryView> history(@PathVariable UUID patientId) {
        return records.history(patientId);
    }

    @PostMapping("/patients/{patientId}/medical-history")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ALLERGY_WRITE')")
    public HistoryView addHistory(@PathVariable UUID patientId, @Valid @RequestBody HistoryRequest body) {
        return records.addHistory(patientId, body);
    }

    @PostMapping("/medical-history/{id}/status")
    @PreAuthorize("hasAuthority('ALLERGY_WRITE')")
    public HistoryView historyStatus(@PathVariable UUID id, @Valid @RequestBody StatusChangeRequest body) {
        return records.changeHistoryStatus(id, parse(MedicalHistory.Status.class, body.status()), body.reason());
    }

    // ------------------------------------------------------------------ diagnoses

    @GetMapping("/admissions/{id}/diagnoses")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<DiagnosisView> diagnoses(@PathVariable UUID id) {
        return records.diagnoses(id);
    }

    @PostMapping("/admissions/{id}/diagnoses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DIAGNOSIS_WRITE')")
    public DiagnosisView addDiagnosis(@PathVariable UUID id, @Valid @RequestBody DiagnosisRequest body) {
        return records.addDiagnosis(id, body);
    }

    @PostMapping("/diagnoses/{id}/resolve")
    @PreAuthorize("hasAuthority('DIAGNOSIS_WRITE')")
    public DiagnosisView resolveDiagnosis(@PathVariable UUID id, @Valid @RequestBody(required = false) OptionalNotesRequest body) {
        return records.changeDiagnosisStatus(id, Diagnosis.Status.RESOLVED, body == null ? null : body.notes());
    }

    @PostMapping("/diagnoses/{id}/entered-in-error")
    @PreAuthorize("hasAuthority('DIAGNOSIS_WRITE')")
    public DiagnosisView diagnosisInError(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return records.changeDiagnosisStatus(id, Diagnosis.Status.ENTERED_IN_ERROR, body.reason());
    }

    // ------------------------------------------------------------------ treatment plans

    @Operation(summary = "Treatment plan history (newest version first)")
    @GetMapping("/admissions/{id}/treatment-plans")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<TreatmentPlanView> plans(@PathVariable UUID id) {
        return records.plans(id);
    }

    @Operation(summary = "Create the first plan or a revision that supersedes the ACTIVE plan (pass basedOnPlanId)")
    @PostMapping("/admissions/{id}/treatment-plans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TREATMENT_PLAN_WRITE')")
    public TreatmentPlanView revisePlan(@PathVariable UUID id, @Valid @RequestBody TreatmentPlanRequest body) {
        return records.revisePlan(id, body);
    }

    @PostMapping("/treatment-plans/{id}/release")
    @PreAuthorize("hasAuthority('RELEASE_TO_PATIENT')")
    public TreatmentPlanView releasePlan(@PathVariable UUID id, @Valid @RequestBody ReleaseRequest body) {
        return records.releasePlan(id, body.patientSummary());
    }

    // ------------------------------------------------------------------ notes

    @GetMapping("/admissions/{id}/notes")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public PageResponse<NoteView> notes(@PathVariable UUID id, @RequestParam(required = false) Set<ClinicalNote.NoteType> type,
                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return records.notes(id, type, page, size);
    }

    @Operation(summary = "Notes across the caller's patients (e.g. nursing assessments worklist)")
    @GetMapping("/clinical/notes")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public PageResponse<NoteView> myNotes(@RequestParam(required = false) Set<ClinicalNote.NoteType> type,
                                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return records.myNotes(type, page, size);
    }

    @Operation(summary = "Write a note. Doctor types need CLINICAL_NOTE_WRITE; nursing types need NURSING_NOTE_WRITE.")
    @PostMapping("/admissions/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('CLINICAL_NOTE_WRITE','NURSING_NOTE_WRITE')")
    public NoteView addNote(@PathVariable UUID id, @Valid @RequestBody NoteRequest body) {
        return records.addNote(id, body);
    }

    @Operation(summary = "Amend a note: creates a new note referencing the original (original becomes AMENDED)")
    @PostMapping("/notes/{id}/amend")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('CLINICAL_NOTE_WRITE','NURSING_NOTE_WRITE')")
    public NoteView amend(@PathVariable UUID id, @Valid @RequestBody AmendNoteRequest body) {
        return records.amendNote(id, body);
    }

    @PostMapping("/notes/{id}/entered-in-error")
    @PreAuthorize("hasAnyAuthority('CLINICAL_NOTE_WRITE','NURSING_NOTE_WRITE')")
    public NoteView noteInError(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return records.markNoteInError(id, body.reason());
    }

    @PostMapping("/notes/{id}/release")
    @PreAuthorize("hasAuthority('RELEASE_TO_PATIENT')")
    public NoteView releaseNote(@PathVariable UUID id, @Valid @RequestBody ReleaseRequest body) {
        return records.releaseNote(id, body.patientSummary());
    }

    // ------------------------------------------------------------------ procedures

    @GetMapping("/admissions/{id}/procedures")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<ProcedureView> procedures(@PathVariable UUID id) {
        return procedures.forAdmission(id);
    }

    @PostMapping("/admissions/{id}/procedures")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PROCEDURE_WRITE')")
    public ProcedureView planProcedure(@PathVariable UUID id, @Valid @RequestBody ProcedureRequest body) {
        return procedures.plan(id, body);
    }

    @Operation(summary = "Complete a planned procedure (posts the PROCEDURE charge when a tariff is linked)")
    @PostMapping("/procedures/{id}/complete")
    @PreAuthorize("hasAuthority('PROCEDURE_WRITE')")
    public ProcedureView completeProcedure(@PathVariable UUID id, @Valid @RequestBody CompleteProcedureRequest body) {
        return procedures.complete(id, body);
    }

    @PostMapping("/procedures/{id}/cancel")
    @PreAuthorize("hasAuthority('PROCEDURE_WRITE')")
    public ProcedureView cancelProcedure(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return procedures.cancel(id, body.reason());
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("INVALID_STATUS", "Unknown status '" + value + "'.");
        }
    }
}
