package com.careflow.hms.clinical;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.clinical.ClinicalDtos.AllergyRequest;
import com.careflow.hms.clinical.ClinicalDtos.AllergyView;
import com.careflow.hms.clinical.ClinicalDtos.AmendNoteRequest;
import com.careflow.hms.clinical.ClinicalDtos.DiagnosisRequest;
import com.careflow.hms.clinical.ClinicalDtos.DiagnosisView;
import com.careflow.hms.clinical.ClinicalDtos.HistoryRequest;
import com.careflow.hms.clinical.ClinicalDtos.HistoryView;
import com.careflow.hms.clinical.ClinicalDtos.NoteRequest;
import com.careflow.hms.clinical.ClinicalDtos.NoteView;
import com.careflow.hms.clinical.ClinicalDtos.TreatmentPlanRequest;
import com.careflow.hms.clinical.ClinicalDtos.TreatmentPlanView;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.patient.Allergy;
import com.careflow.hms.patient.AllergyRepository;
import com.careflow.hms.patient.MedicalHistory;
import com.careflow.hms.patient.MedicalHistoryRepository;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Permissions;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Allergies, medical history, diagnoses, versioned treatment plans and clinical/nursing notes.
 * Records are never deleted or overwritten: corrections use status changes with a documented
 * reason, amendments or superseding versions.
 */
@Service
@RequiredArgsConstructor
public class ClinicalRecordService {

    private final ClinicalSupport support;
    private final AccessPolicy access;
    private final AllergyRepository allergies;
    private final MedicalHistoryRepository history;
    private final DiagnosisRepository diagnoses;
    private final TreatmentPlanRepository plans;
    private final ClinicalNoteRepository notes;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;
    private final com.careflow.hms.admission.AdmissionRepository admissionRepository;

    // ================================================================== allergies

    @Transactional(readOnly = true)
    public List<AllergyView> allergies(UUID patientId) {
        access.requirePatient(patientId, AccessPolicy.Scope.CLINICAL);
        List<Allergy> list = allergies.findByPatientIdOrderByRecordedAtDesc(patientId);
        Map<UUID, String> names = support.userNames(list.stream().flatMap(a -> Stream.of(a.getRecordedBy(), a.getStatusChangedBy())).toList());
        return list.stream().map(a -> allergyView(a, names)).toList();
    }

    @Transactional
    public AllergyView addAllergy(UUID patientId, AllergyRequest r) {
        Patient p = access.requirePatient(patientId, AccessPolicy.Scope.CLINICAL);
        String allergen = r.allergen().trim();
        boolean duplicate = allergies.findByPatientIdAndStatus(p.getId(), Allergy.Status.ACTIVE).stream()
                .anyMatch(a -> a.getAllergen().equalsIgnoreCase(allergen));
        if (duplicate) {
            throw ApiException.conflict("ALLERGY_EXISTS", allergen + " is already recorded as an active allergy.");
        }
        Allergy a = new Allergy();
        a.setPatientId(p.getId());
        a.setAllergen(allergen);
        a.setAllergyType(r.allergyType());
        a.setReaction(ClinicalSupport.blankToNull(r.reaction()));
        a.setSeverity(r.severity());
        a.setRecordedBy(TenantContext.actorId());
        a.setRecordedAt(Instant.now());
        a = allergies.saveAndFlush(a);
        timelineForPatient(p.getId(), "ALLERGY_RECORDED", TimelineEvent.Category.CLINICAL,
                "Allergy recorded: " + allergen, humanSeverity(r.severity()) + (a.getReaction() != null ? " · " + a.getReaction() : ""),
                "ALLERGY", a.getId());
        audit.record("ALLERGY_RECORDED", "ALLERGY", a.getId(), p.getId(), allergen + " (" + r.severity() + ")");
        return allergyView(a, support.userNames(List.of(a.getRecordedBy())));
    }

    @Transactional
    public AllergyView changeAllergyStatus(UUID allergyId, Allergy.Status status, String reason) {
        Allergy a = support.requireOrg(allergies.findById(allergyId), "Allergy");
        access.requirePatient(a.getPatientId(), AccessPolicy.Scope.CLINICAL);
        if (a.getStatus() == Allergy.Status.ENTERED_IN_ERROR) {
            throw ApiException.rule("ALLERGY_IN_ERROR", "An allergy entered in error cannot be changed; record a new one.");
        }
        if (a.getStatus() == status) {
            throw ApiException.rule("NO_CHANGE", "The allergy is already " + status.name().toLowerCase().replace('_', ' ') + ".");
        }
        if (status != Allergy.Status.ACTIVE && (reason == null || reason.trim().length() < 3)) {
            throw ApiException.badRequest("REASON_REQUIRED", "Document a reason for this change.");
        }
        Allergy.Status from = a.getStatus();
        a.setStatus(status);
        a.setStatusReason(ClinicalSupport.blankToNull(reason));
        a.setStatusChangedBy(TenantContext.actorId());
        a.setStatusChangedAt(Instant.now());
        timelineForPatient(a.getPatientId(), "ALLERGY_STATUS_CHANGED", TimelineEvent.Category.CLINICAL,
                "Allergy " + a.getAllergen() + " marked " + status.name().toLowerCase().replace('_', ' '), reason, "ALLERGY", a.getId());
        audit.record("ALLERGY_STATUS_CHANGED", "ALLERGY", a.getId(), a.getPatientId(), from + " -> " + status + ": " + reason);
        allergies.flush();
        return allergyView(a, support.userNames(List.of(a.getRecordedBy(), a.getStatusChangedBy())));
    }

    private AllergyView allergyView(Allergy a, Map<UUID, String> names) {
        return new AllergyView(a.getId(), a.getPatientId(), a.getAllergen(), a.getAllergyType(), a.getReaction(), a.getSeverity(),
                a.getStatus(), names.get(a.getRecordedBy()), a.getRecordedAt(), a.getStatusReason(),
                names.get(a.getStatusChangedBy()), a.getStatusChangedAt(), a.getVersion());
    }

    // ================================================================== medical history

    @Transactional(readOnly = true)
    public List<HistoryView> history(UUID patientId) {
        access.requirePatient(patientId, AccessPolicy.Scope.CLINICAL);
        List<MedicalHistory> list = history.findByPatientIdOrderByRecordedAtDesc(patientId);
        Map<UUID, String> names = support.userNames(list.stream().map(MedicalHistory::getRecordedBy).toList());
        return list.stream().map(h -> historyView(h, names)).toList();
    }

    @Transactional
    public HistoryView addHistory(UUID patientId, HistoryRequest r) {
        Patient p = access.requirePatient(patientId, AccessPolicy.Scope.CLINICAL);
        if (r.onsetDate() != null && r.onsetDate().isAfter(java.time.LocalDate.now().plusDays(1))) {
            throw ApiException.badRequest("INVALID_ONSET", "Onset date cannot be in the future.");
        }
        MedicalHistory h = new MedicalHistory();
        h.setPatientId(p.getId());
        h.setCategory(r.category());
        h.setConditionName(r.conditionName().trim());
        h.setDetails(ClinicalSupport.blankToNull(r.details()));
        h.setOnsetDate(r.onsetDate());
        h.setRecordedBy(TenantContext.actorId());
        h.setRecordedAt(Instant.now());
        h = history.saveAndFlush(h);
        timelineForPatient(p.getId(), "HISTORY_RECORDED", TimelineEvent.Category.CLINICAL,
                "Medical history updated: " + h.getConditionName(), r.category().name().toLowerCase(), "MEDICAL_HISTORY", h.getId());
        audit.record("HISTORY_RECORDED", "MEDICAL_HISTORY", h.getId(), p.getId(), h.getConditionName());
        return historyView(h, support.userNames(List.of(h.getRecordedBy())));
    }

    @Transactional
    public HistoryView changeHistoryStatus(UUID id, MedicalHistory.Status status, String reason) {
        MedicalHistory h = support.requireOrg(history.findById(id), "History entry");
        access.requirePatient(h.getPatientId(), AccessPolicy.Scope.CLINICAL);
        if (h.getStatus() == MedicalHistory.Status.ENTERED_IN_ERROR) {
            throw ApiException.rule("HISTORY_IN_ERROR", "An entry marked in error cannot be changed.");
        }
        if (h.getStatus() == status) {
            throw ApiException.rule("NO_CHANGE", "No change.");
        }
        if (status == MedicalHistory.Status.ENTERED_IN_ERROR && (reason == null || reason.trim().length() < 3)) {
            throw ApiException.badRequest("REASON_REQUIRED", "Document a reason for marking this entry in error.");
        }
        h.setStatus(status);
        h.setStatusReason(ClinicalSupport.blankToNull(reason));
        h.setStatusChangedBy(TenantContext.actorId());
        h.setStatusChangedAt(Instant.now());
        audit.record("HISTORY_STATUS_CHANGED", "MEDICAL_HISTORY", h.getId(), h.getPatientId(), status + ": " + reason);
        history.flush();
        return historyView(h, support.userNames(List.of(h.getRecordedBy())));
    }

    private HistoryView historyView(MedicalHistory h, Map<UUID, String> names) {
        return new HistoryView(h.getId(), h.getPatientId(), h.getCategory(), h.getConditionName(), h.getDetails(), h.getOnsetDate(),
                h.getStatus(), names.get(h.getRecordedBy()), h.getRecordedAt(), h.getStatusReason(), h.getVersion());
    }

    // ================================================================== diagnoses

    @Transactional(readOnly = true)
    public List<DiagnosisView> diagnoses(UUID admissionId) {
        support.requireClinical(admissionId);
        List<Diagnosis> list = diagnoses.findByAdmissionIdOrderByRecordedAtDesc(admissionId);
        Map<UUID, String> names = support.userNames(list.stream().map(Diagnosis::getRecordedBy).toList());
        return list.stream().map(d -> diagnosisView(d, names)).toList();
    }

    @Transactional
    public DiagnosisView addDiagnosis(UUID admissionId, DiagnosisRequest r) {
        Admission a = support.requireOpenClinical(admissionId);
        if (r.primary() && r.diagnosisType() == Diagnosis.DiagnosisType.DIFFERENTIAL) {
            throw ApiException.rule("PRIMARY_DIFFERENTIAL", "A differential diagnosis cannot be the primary diagnosis.");
        }
        if (r.primary()) {
            diagnoses.findByAdmissionIdOrderByRecordedAtDesc(a.getId()).stream()
                    .filter(d -> d.isPrimary() && d.getStatus() == Diagnosis.Status.ACTIVE)
                    .forEach(d -> d.setPrimary(false));
        }
        Diagnosis d = new Diagnosis();
        d.setAdmissionId(a.getId());
        d.setPatientId(a.getPatientId());
        d.setIcdCode(r.icdCode() == null || r.icdCode().isBlank() ? null : r.icdCode().trim().toUpperCase());
        d.setDescription(r.description().trim());
        d.setDiagnosisType(r.diagnosisType());
        d.setPrimary(r.primary());
        d.setNotes(ClinicalSupport.blankToNull(r.notes()));
        d.setRecordedBy(TenantContext.actorId());
        d.setRecordedAt(Instant.now());
        d = diagnoses.saveAndFlush(d);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("DIAGNOSIS_RECORDED").category(TimelineEvent.Category.CLINICAL)
                .title((r.primary() ? "Primary diagnosis: " : "Diagnosis recorded: ") + d.getDescription())
                .detail(r.diagnosisType().name().toLowerCase() + (d.getIcdCode() != null ? " · ICD-10 " + d.getIcdCode() : ""))
                .status(r.diagnosisType().name()).visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("DIAGNOSIS").entityId(d.getId()).build());
        audit.record("DIAGNOSIS_RECORDED", "DIAGNOSIS", d.getId(), a.getPatientId(), d.getDescription());
        return diagnosisView(d, support.userNames(List.of(d.getRecordedBy())));
    }

    @Transactional
    public DiagnosisView changeDiagnosisStatus(UUID id, Diagnosis.Status status, String reason) {
        Diagnosis d = support.requireOrg(diagnoses.findById(id), "Diagnosis");
        Admission a = support.requireClinical(d.getAdmissionId());
        if (d.getStatus() != Diagnosis.Status.ACTIVE) {
            throw ApiException.rule("DIAGNOSIS_NOT_ACTIVE", "Only active diagnoses can be resolved or marked in error.");
        }
        if (status == Diagnosis.Status.ENTERED_IN_ERROR && (reason == null || reason.trim().length() < 3)) {
            throw ApiException.badRequest("REASON_REQUIRED", "Document why this diagnosis was entered in error.");
        }
        if (status == Diagnosis.Status.ACTIVE) {
            throw ApiException.badRequest("INVALID_STATUS", "Use RESOLVED or ENTERED_IN_ERROR.");
        }
        d.setStatus(status);
        d.setStatusReason(ClinicalSupport.blankToNull(reason));
        d.setStatusChangedBy(TenantContext.actorId());
        d.setStatusChangedAt(Instant.now());
        if (status == Diagnosis.Status.ENTERED_IN_ERROR) {
            d.setPrimary(false);
        }
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType(status == Diagnosis.Status.RESOLVED ? "DIAGNOSIS_RESOLVED" : "DIAGNOSIS_ENTERED_IN_ERROR")
                .category(TimelineEvent.Category.CLINICAL)
                .title((status == Diagnosis.Status.RESOLVED ? "Diagnosis resolved: " : "Diagnosis marked entered in error: ") + d.getDescription())
                .detail(reason).status(status.name()).visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("DIAGNOSIS").entityId(d.getId()).build());
        audit.record("DIAGNOSIS_" + status.name(), "DIAGNOSIS", d.getId(), a.getPatientId(), reason);
        diagnoses.flush();
        return diagnosisView(d, support.userNames(List.of(d.getRecordedBy())));
    }

    DiagnosisView diagnosisView(Diagnosis d, Map<UUID, String> names) {
        return new DiagnosisView(d.getId(), d.getAdmissionId(), d.getIcdCode(), d.getDescription(), d.getDiagnosisType(), d.isPrimary(),
                d.getStatus(), d.getNotes(), names.get(d.getRecordedBy()), d.getRecordedAt(), d.getStatusReason(),
                d.getStatusChangedAt(), d.getVersion());
    }

    // ================================================================== treatment plans

    @Transactional(readOnly = true)
    public List<TreatmentPlanView> plans(UUID admissionId) {
        support.requireClinical(admissionId);
        List<TreatmentPlan> list = plans.findByAdmissionIdOrderByPlanVersionDesc(admissionId);
        Map<UUID, String> names = support.userNames(list.stream().map(TreatmentPlan::getCreatedBy).toList());
        return list.stream().map(p -> planView(p, names)).toList();
    }

    /** Creates the first plan or a new version that supersedes the ACTIVE plan (never overwritten). */
    @Transactional
    public TreatmentPlanView revisePlan(UUID admissionId, TreatmentPlanRequest r) {
        Admission a = support.requireOpenClinical(admissionId);
        TreatmentPlan current = plans.findByAdmissionIdAndStatus(a.getId(), TreatmentPlan.Status.ACTIVE).orElse(null);
        if (current != null && (r.basedOnPlanId() == null || !r.basedOnPlanId().equals(current.getId()))) {
            throw ApiException.conflict("PLAN_CHANGED",
                    "The treatment plan was revised by someone else (now version " + current.getPlanVersion() + "). Reload and review before revising.");
        }
        if (current == null && r.basedOnPlanId() != null) {
            throw ApiException.conflict("PLAN_CHANGED", "The plan you based this revision on is no longer active. Reload and try again.");
        }
        int nextVersion = plans.findByAdmissionIdOrderByPlanVersionDesc(a.getId()).stream()
                .mapToInt(TreatmentPlan::getPlanVersion).max().orElse(0) + 1;
        if (current != null) {
            if (r.revisionReason() == null || r.revisionReason().trim().length() < 3) {
                throw ApiException.badRequest("REASON_REQUIRED", "Document the reason for revising the treatment plan.");
            }
            current.setStatus(TreatmentPlan.Status.SUPERSEDED);
            plans.saveAndFlush(current);
        }
        TreatmentPlan p = new TreatmentPlan();
        p.setAdmissionId(a.getId());
        p.setPlanVersion(nextVersion);
        p.setGoals(r.goals().trim());
        p.setPlanDetails(r.planDetails().trim());
        p.setMonitoring(ClinicalSupport.blankToNull(r.monitoring()));
        p.setDiet(ClinicalSupport.blankToNull(r.diet()));
        p.setActivity(ClinicalSupport.blankToNull(r.activity()));
        p.setPatientSummary(ClinicalSupport.blankToNull(r.patientSummary()));
        p.setRevisionReason(ClinicalSupport.blankToNull(r.revisionReason()));
        p.setStatus(TreatmentPlan.Status.ACTIVE);
        p.setCreatedBy(TenantContext.actorId());
        p.setCreatedAt(Instant.now());
        p = plans.saveAndFlush(p);
        if (current != null) {
            current.setSupersededById(p.getId());
        }
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType(current == null ? "TREATMENT_PLAN_CREATED" : "TREATMENT_PLAN_REVISED")
                .category(TimelineEvent.Category.CLINICAL)
                .title(current == null ? "Treatment plan created (v1)" : "Treatment plan revised (v" + nextVersion + ")")
                .detail(ClinicalSupport.truncate(current == null ? p.getGoals() : r.revisionReason(), 500))
                .status("ACTIVE").visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("TREATMENT_PLAN").entityId(p.getId()).build());
        notifications.notifyWardNurses(a.getId(), NotificationRequest.builder()
                .category("REVIEW_REQUEST").title("Treatment plan " + (current == null ? "created" : "updated"))
                .message("Version " + nextVersion + " for admission " + a.getAdmissionNumber() + ". Review monitoring and nursing actions.")
                .link("/nurse/patients/" + a.getId()).entityType("TREATMENT_PLAN").entityId(p.getId())
                .dedupeKey("PLAN:" + p.getId()).build());
        audit.record(current == null ? "TREATMENT_PLAN_CREATED" : "TREATMENT_PLAN_REVISED", "TREATMENT_PLAN", p.getId(),
                a.getPatientId(), "Version " + nextVersion);
        return planView(p, support.userNames(List.of(p.getCreatedBy())));
    }

    @Transactional
    public TreatmentPlanView releasePlan(UUID planId, String patientSummary) {
        TreatmentPlan p = support.requireOrg(plans.findById(planId), "Treatment plan");
        Admission a = support.requireClinical(p.getAdmissionId());
        if (p.getStatus() != TreatmentPlan.Status.ACTIVE) {
            throw ApiException.rule("PLAN_NOT_ACTIVE", "Only the active treatment plan can be released to the patient.");
        }
        String summary = ClinicalSupport.blankToNull(patientSummary) != null ? patientSummary.trim() : p.getPatientSummary();
        if (summary == null || summary.isBlank()) {
            throw ApiException.badRequest("PATIENT_SUMMARY_REQUIRED", "Write a plain-language summary before releasing to the patient.");
        }
        p.setPatientSummary(summary);
        p.setReleasedToPatient(true);
        p.setReleasedBy(TenantContext.actorId());
        p.setReleasedAt(Instant.now());
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("TREATMENT_PLAN_RELEASED").category(TimelineEvent.Category.CLINICAL)
                .title("Your care plan has been shared").visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("TREATMENT_PLAN").entityId(p.getId()).build());
        notifications.notifyPatient(a.getPatientId(), NotificationRequest.builder()
                .category("CLINICAL_UPDATE").title("Your care plan is available")
                .message("Your care team has shared a summary of your treatment plan.").link("/patient/records")
                .entityType("TREATMENT_PLAN").entityId(p.getId()).dedupeKey("PLAN_RELEASED:" + p.getId()).build());
        audit.record("RELEASED_TO_PATIENT", "TREATMENT_PLAN", p.getId(), a.getPatientId(), "Treatment plan v" + p.getPlanVersion());
        plans.flush();
        return planView(p, support.userNames(List.of(p.getCreatedBy())));
    }

    TreatmentPlanView planView(TreatmentPlan p, Map<UUID, String> names) {
        return new TreatmentPlanView(p.getId(), p.getAdmissionId(), p.getPlanVersion(), p.getGoals(), p.getPlanDetails(),
                p.getMonitoring(), p.getDiet(), p.getActivity(), p.getStatus(), p.getPatientSummary(), p.isReleasedToPatient(),
                p.getReleasedAt(), names.get(p.getCreatedBy()), p.getCreatedAt(), p.getSupersededById(), p.getRevisionReason(),
                p.getVersion());
    }

    // ================================================================== notes

    @Transactional(readOnly = true)
    public PageResponse<NoteView> notes(UUID admissionId, Collection<ClinicalNote.NoteType> types, int page, int size) {
        support.requireClinical(admissionId);
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "authoredAt"));
        Page<ClinicalNote> result = types == null || types.isEmpty()
                ? notes.findByAdmissionId(admissionId, pr)
                : notes.findByAdmissionIdAndNoteTypeIn(admissionId, types, pr);
        Map<UUID, String> names = support.userNames(result.getContent().stream().map(ClinicalNote::getAuthorId).toList());
        return PageResponse.of(result, n -> noteView(n, names));
    }

    /** Nursing notes across the caller's patients (assessment worklist). */
    @Transactional(readOnly = true)
    public PageResponse<ClinicalDtos.NoteView> myNotes(Collection<ClinicalNote.NoteType> types, int page, int size) {
        List<UUID> ids = support.myAdmissionIds();
        if (ids.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0, 0);
        }
        Collection<ClinicalNote.NoteType> t = types == null || types.isEmpty() ? EnumSet.allOf(ClinicalNote.NoteType.class) : types;
        Page<ClinicalNote> result = notes.findByAdmissionIdInAndNoteTypeIn(ids, t,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "authoredAt")));
        Map<UUID, String> names = support.userNames(result.getContent().stream().map(ClinicalNote::getAuthorId).toList());
        return PageResponse.of(result, n -> noteView(n, names));
    }

    @Transactional
    public NoteView addNote(UUID admissionId, NoteRequest r) {
        requireNotePermission(r.noteType());
        Admission a = support.requireOpenClinical(admissionId);
        ClinicalNote n = new ClinicalNote();
        n.setAdmissionId(a.getId());
        n.setPatientId(a.getPatientId());
        n.setNoteType(r.noteType());
        n.setTitle(r.title().trim());
        n.setSubjective(ClinicalSupport.blankToNull(r.subjective()));
        n.setObjective(ClinicalSupport.blankToNull(r.objective()));
        n.setAssessment(ClinicalSupport.blankToNull(r.assessment()));
        n.setPlan(ClinicalSupport.blankToNull(r.plan()));
        n.setContent(r.content().trim());
        n.setPatientSummary(ClinicalSupport.blankToNull(r.patientSummary()));
        n.setAuthorId(TenantContext.actorId());
        n.setAuthoredAt(Instant.now());
        n = notes.saveAndFlush(n);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("NOTE_" + r.noteType().name()).category(r.noteType().isNursing() ? TimelineEvent.Category.NURSING : TimelineEvent.Category.CLINICAL)
                .title(humanType(r.noteType()) + ": " + n.getTitle())
                .detail(ClinicalSupport.truncate(n.getAssessment() != null ? n.getAssessment() : n.getContent(), 300))
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("CLINICAL_NOTE").entityId(n.getId()).build());
        audit.record("NOTE_CREATED", "CLINICAL_NOTE", n.getId(), a.getPatientId(), r.noteType() + ": " + n.getTitle());
        return noteView(n, support.userNames(List.of(n.getAuthorId())));
    }

    /** Amendment: a new note referencing the original; the original becomes AMENDED (never edited in place). */
    @Transactional
    public NoteView amendNote(UUID noteId, AmendNoteRequest r) {
        ClinicalNote original = support.requireOrg(notes.findById(noteId), "Note");
        requireNotePermission(original.getNoteType());
        Admission a = support.requireClinical(original.getAdmissionId());
        requireAuthor(original);
        if (original.getStatus() != ClinicalNote.Status.FINAL) {
            throw ApiException.rule("NOTE_NOT_FINAL", "Only the current version of a note can be amended.");
        }
        original.setStatus(ClinicalNote.Status.AMENDED);
        original.setCorrectionReason(r.reason().trim());
        notes.saveAndFlush(original);
        ClinicalNote n = new ClinicalNote();
        n.setAdmissionId(original.getAdmissionId());
        n.setPatientId(original.getPatientId());
        n.setNoteType(original.getNoteType());
        n.setTitle(r.title().trim());
        n.setSubjective(ClinicalSupport.blankToNull(r.subjective()));
        n.setObjective(ClinicalSupport.blankToNull(r.objective()));
        n.setAssessment(ClinicalSupport.blankToNull(r.assessment()));
        n.setPlan(ClinicalSupport.blankToNull(r.plan()));
        n.setContent(r.content().trim());
        n.setPatientSummary(original.getPatientSummary());
        n.setAmendsNoteId(original.getId());
        n.setCorrectionReason(r.reason().trim());
        n.setAuthorId(TenantContext.actorId());
        n.setAuthoredAt(Instant.now());
        n = notes.saveAndFlush(n);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("NOTE_AMENDED").category(original.getNoteType().isNursing() ? TimelineEvent.Category.NURSING : TimelineEvent.Category.CLINICAL)
                .title("Note amended: " + n.getTitle()).detail("Reason: " + r.reason().trim()).status("AMENDED")
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("CLINICAL_NOTE").entityId(n.getId()).build());
        audit.record("NOTE_AMENDED", "CLINICAL_NOTE", n.getId(), a.getPatientId(), "Amends " + original.getId() + ": " + r.reason());
        return noteView(n, support.userNames(List.of(n.getAuthorId())));
    }

    @Transactional
    public NoteView markNoteInError(UUID noteId, String reason) {
        ClinicalNote n = support.requireOrg(notes.findById(noteId), "Note");
        requireNotePermission(n.getNoteType());
        Admission a = support.requireClinical(n.getAdmissionId());
        requireAuthor(n);
        if (n.getStatus() != ClinicalNote.Status.FINAL) {
            throw ApiException.rule("NOTE_NOT_FINAL", "Only the current version of a note can be marked in error.");
        }
        n.setStatus(ClinicalNote.Status.ENTERED_IN_ERROR);
        n.setCorrectionReason(reason.trim());
        boolean wasReleased = n.isReleasedToPatient();
        n.setReleasedToPatient(false);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("NOTE_ENTERED_IN_ERROR").category(n.getNoteType().isNursing() ? TimelineEvent.Category.NURSING : TimelineEvent.Category.CLINICAL)
                .title("Note marked entered in error: " + n.getTitle()).detail(reason).status("ENTERED_IN_ERROR")
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("CLINICAL_NOTE").entityId(n.getId()).build());
        audit.record("NOTE_ENTERED_IN_ERROR", "CLINICAL_NOTE", n.getId(), a.getPatientId(),
                reason + (wasReleased ? " (withdrawn from patient portal)" : ""));
        notes.flush();
        return noteView(n, support.userNames(List.of(n.getAuthorId())));
    }

    @Transactional
    public NoteView releaseNote(UUID noteId, String patientSummary) {
        ClinicalNote n = support.requireOrg(notes.findById(noteId), "Note");
        Admission a = support.requireClinical(n.getAdmissionId());
        if (n.getStatus() != ClinicalNote.Status.FINAL) {
            throw ApiException.rule("NOTE_NOT_FINAL", "Only the current version of a note can be released.");
        }
        String summary = ClinicalSupport.blankToNull(patientSummary) != null ? patientSummary.trim() : n.getPatientSummary();
        if (summary == null) {
            throw ApiException.badRequest("PATIENT_SUMMARY_REQUIRED", "Write a plain-language summary for the patient before releasing.");
        }
        n.setPatientSummary(summary);
        n.setReleasedToPatient(true);
        n.setReleasedBy(TenantContext.actorId());
        n.setReleasedAt(Instant.now());
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("NOTE_RELEASED").category(TimelineEvent.Category.CLINICAL)
                .title("Update from your care team: " + n.getTitle()).detail(ClinicalSupport.truncate(summary, 500))
                .visibility(TimelineEvent.Visibility.PATIENT).entityType("CLINICAL_NOTE").entityId(n.getId()).build());
        notifications.notifyPatient(a.getPatientId(), NotificationRequest.builder()
                .category("CLINICAL_UPDATE").title("New update from your care team").message(n.getTitle())
                .link("/patient/records").entityType("CLINICAL_NOTE").entityId(n.getId())
                .dedupeKey("NOTE_RELEASED:" + n.getId()).build());
        audit.record("RELEASED_TO_PATIENT", "CLINICAL_NOTE", n.getId(), a.getPatientId(), n.getTitle());
        notes.flush();
        return noteView(n, support.userNames(List.of(n.getAuthorId())));
    }

    NoteView noteView(ClinicalNote n, Map<UUID, String> names) {
        return new NoteView(n.getId(), n.getAdmissionId(), n.getNoteType(), n.getTitle(), n.getSubjective(), n.getObjective(),
                n.getAssessment(), n.getPlan(), n.getContent(), n.getPatientSummary(), n.isReleasedToPatient(), n.getReleasedAt(),
                n.getStatus(), n.getAmendsNoteId(), n.getCorrectionReason(), n.getAuthorId(), names.get(n.getAuthorId()),
                n.getAuthoredAt(), n.getVersion());
    }

    /** Doctor note types need CLINICAL_NOTE_WRITE; nursing types need NURSING_NOTE_WRITE. */
    static void requireNotePermission(ClinicalNote.NoteType type) {
        CurrentUser u = TenantContext.requireUser();
        String needed = type.isNursing() ? Permissions.NURSING_NOTE_WRITE : Permissions.CLINICAL_NOTE_WRITE;
        if (!u.has(needed)) {
            throw ApiException.forbidden("Your role cannot write " + humanType(type).toLowerCase() + " notes.");
        }
    }

    private static void requireAuthor(ClinicalNote n) {
        if (!n.getAuthorId().equals(TenantContext.actorId())) {
            throw ApiException.forbidden("Only the author can amend or withdraw this note. Add a new note instead.");
        }
    }

    static String humanType(ClinicalNote.NoteType t) {
        return switch (t) {
            case ADMISSION_ASSESSMENT -> "Admission assessment";
            case PROGRESS -> "Progress note";
            case DOCTOR_REVIEW -> "Doctor review";
            case NURSING_ASSESSMENT -> "Nursing assessment";
            case NURSING_OBSERVATION -> "Nursing observation";
            case PROCEDURE -> "Procedure note";
            case CONSULTATION -> "Consultation";
            case EDUCATION -> "Patient education";
        };
    }

    private static String humanSeverity(Allergy.Severity s) {
        return s.name().toLowerCase().replace('_', '-');
    }

    private void timelineForPatient(UUID patientId, String type, TimelineEvent.Category cat, String title, String detail,
                                    String entityType, UUID entityId) {
        timeline.record(TimelineService.Entry.builder()
                .patientId(patientId).admissionId(currentAdmission(patientId))
                .eventType(type).category(cat).title(title).detail(detail)
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType(entityType).entityId(entityId).build());
    }

    private UUID currentAdmission(UUID patientId) {
        return admissionRepository.findOpenByPatient(patientId).map(Admission::getId).orElse(null);
    }

    static Set<ClinicalNote.NoteType> doctorTypes() {
        return EnumSet.of(ClinicalNote.NoteType.ADMISSION_ASSESSMENT, ClinicalNote.NoteType.PROGRESS, ClinicalNote.NoteType.DOCTOR_REVIEW,
                ClinicalNote.NoteType.PROCEDURE, ClinicalNote.NoteType.CONSULTATION);
    }
}
