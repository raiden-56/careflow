package com.careflow.hms.discharge;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.clinical.Diagnosis;
import com.careflow.hms.clinical.DiagnosisRepository;
import com.careflow.hms.clinical.LabOrder;
import com.careflow.hms.clinical.LabOrderRepository;
import com.careflow.hms.clinical.LabResult;
import com.careflow.hms.clinical.LabResultRepository;
import com.careflow.hms.clinical.ProcedureRecord;
import com.careflow.hms.clinical.ProcedureRecordRepository;
import com.careflow.hms.clinical.TreatmentPlan;
import com.careflow.hms.clinical.TreatmentPlanRepository;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.discharge.DischargeDtos.AmendmentView;
import com.careflow.hms.discharge.DischargeDtos.SummaryEnvelope;
import com.careflow.hms.discharge.DischargeDtos.SummaryRequest;
import com.careflow.hms.discharge.DischargeDtos.SummaryView;
import com.careflow.hms.discharge.DischargeWorkflowService.Names;
import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.medication.Medication;
import com.careflow.hms.medication.MedicationOrder;
import com.careflow.hms.medication.MedicationOrderRepository;
import com.careflow.hms.medication.MedicationRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.AccessPolicy.Scope;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Permissions;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Discharge summaries: a draft pre-filled only from recorded data (every section stays editable), finalized
 * by a doctor, amendable with an audited reason, released to the patient portal and exportable as PDF.
 */
@Service
@RequiredArgsConstructor
public class DischargeSummaryService {

    static final int MAX_SECTION = 10_000;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private final DischargeSummaryRepository summaries;
    private final DischargeSummaryAmendmentRepository amendments;
    private final FollowUpRepository followUps;
    private final DiagnosisRepository diagnoses;
    private final ProcedureRecordRepository procedures;
    private final LabOrderRepository labOrders;
    private final LabResultRepository labResults;
    private final TreatmentPlanRepository plans;
    private final MedicationOrderRepository medicationOrders;
    private final MedicationRepository medications;
    private final PatientRepository patients;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final OrganizationRepository organizations;
    private final AccessPolicy access;
    private final DischargeWorkflowService workflowService;
    private final DischargeSummaryPdf pdf;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ObjectMapper json;

    public record PdfFile(String fileName, byte[] content) {
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public SummaryEnvelope get(UUID admissionId) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = requireStaffClinical(admissionId);
        DischargeSummary s = summaries.findByAdmissionId(a.getId()).orElse(null);
        return new SummaryEnvelope(s == null ? null : view(s), canWrite(u), canWrite(u) && u.has(Permissions.RELEASE_TO_PATIENT));
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public SummaryEnvelope createDraft(UUID admissionId) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = requireWriter(admissionId);
        if (a.getStatus() == Admission.Status.CANCELLED || a.getStatus() == Admission.Status.PENDING_BED) {
            throw ApiException.rule("ADMISSION_NOT_ELIGIBLE", "A discharge summary is prepared for admitted or discharged patients.");
        }
        if (summaries.findByAdmissionId(a.getId()).isPresent()) {
            throw ApiException.conflict("SUMMARY_EXISTS", "A discharge summary already exists for this admission.");
        }
        DischargeSummary s = new DischargeSummary();
        s.setAdmissionId(a.getId());
        s.setStatus(DischargeSummary.Status.DRAFT);
        prefill(s, a);
        s.setPreparedBy(u.userId());
        s.setPreparedAt(Instant.now());
        s.setUpdatedBy(u.userId());
        s.setUpdatedAt(Instant.now());
        s = summaries.saveAndFlush(s);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("DISCHARGE_SUMMARY_DRAFTED").category(TimelineEvent.Category.DISCHARGE)
                .title("Discharge summary drafted").status("DRAFT").visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("DISCHARGE_SUMMARY").entityId(s.getId()).build());
        audit.record("DISCHARGE_SUMMARY_CREATED", "DISCHARGE_SUMMARY", s.getId(), a.getPatientId(), "Draft created from recorded data");
        workflowService.syncForAdmission(a.getId());
        return new SummaryEnvelope(view(s), true, u.has(Permissions.RELEASE_TO_PATIENT));
    }

    @Transactional
    public SummaryEnvelope update(UUID admissionId, SummaryRequest r) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = requireWriter(admissionId);
        DischargeSummary s = summaries.lockByAdmissionId(a.getId()).orElseThrow(() -> ApiException.notFound("Discharge summary"));
        if (!Objects.equals(r.version(), s.getVersion())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "The summary was changed by someone else. Reload and try again.");
        }
        validateLengths(r);
        boolean amending = s.getStatus() != DischargeSummary.Status.DRAFT;
        String reason = DischargeWorkflowService.trimToNull(r.amendmentReason());
        if (amending) {
            if (reason == null || reason.length() < 5) {
                throw ApiException.badRequest("AMENDMENT_REASON_REQUIRED",
                        "This summary is finalized. Document the reason for the amendment (at least 5 characters).");
            }
            DischargeSummaryAmendment am = new DischargeSummaryAmendment();
            am.setSummaryId(s.getId());
            am.setReason(reason);
            am.setPreviousContent(snapshot(s));
            am.setAmendedBy(u.userId());
            am.setAmendedAt(Instant.now());
            amendments.save(am);
            s.setStatus(DischargeSummary.Status.AMENDED);
        }
        s.setPresentingComplaint(clean(r.presentingComplaint()));
        s.setDiagnosesText(clean(r.diagnosesText()));
        s.setTreatmentSummary(clean(r.treatmentSummary()));
        s.setProceduresText(clean(r.proceduresText()));
        s.setRelevantResults(clean(r.relevantResults()));
        s.setConditionAtDischarge(clean(r.conditionAtDischarge()));
        s.setDischargeMedications(clean(r.dischargeMedications()));
        s.setDischargeInstructions(clean(r.dischargeInstructions()));
        s.setFollowUpPlan(clean(r.followUpPlan()));
        s.setWarningSigns(clean(r.warningSigns()));
        s.setUpdatedBy(u.userId());
        s.setUpdatedAt(Instant.now());
        if (amending) {
            requireComplete(s);
        }
        summaries.saveAndFlush(s);
        if (amending) {
            timeline.record(TimelineService.Entry.builder()
                    .patientId(a.getPatientId()).admissionId(a.getId())
                    .eventType("DISCHARGE_SUMMARY_AMENDED").category(TimelineEvent.Category.DISCHARGE)
                    .title("Discharge summary amended").detail(reason).status("AMENDED")
                    .visibility(TimelineEvent.Visibility.CARE_TEAM)
                    .entityType("DISCHARGE_SUMMARY").entityId(s.getId()).build());
            if (s.isReleasedToPatient()) {
                notifications.notifyPatient(a.getPatientId(), NotificationRequest.builder()
                        .category("DISCHARGE").priority(Notification.Priority.NORMAL)
                        .title("Your discharge summary was updated")
                        .message("Your doctor has updated your discharge summary. Please review the latest version.")
                        .link("/patient/discharge").entityType("DISCHARGE_SUMMARY").entityId(s.getId())
                        .dedupeKey("SUMMARY_AMENDED:" + s.getId() + ":" + s.getUpdatedAt().getEpochSecond()).build());
            }
            audit.record("DISCHARGE_SUMMARY_AMENDED", "DISCHARGE_SUMMARY", s.getId(), a.getPatientId(), reason);
        } else {
            audit.record("DISCHARGE_SUMMARY_UPDATED", "DISCHARGE_SUMMARY", s.getId(), a.getPatientId(), "Draft edited");
        }
        workflowService.syncForAdmission(a.getId());
        return new SummaryEnvelope(view(s), true, u.has(Permissions.RELEASE_TO_PATIENT));
    }

    @Transactional
    public SummaryEnvelope finalizeSummary(UUID admissionId, Long version) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = requireWriter(admissionId);
        DischargeSummary s = summaries.lockByAdmissionId(a.getId()).orElseThrow(() -> ApiException.notFound("Discharge summary"));
        if (version != null && !Objects.equals(version, s.getVersion())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "The summary was changed by someone else. Reload and try again.");
        }
        if (s.getStatus() != DischargeSummary.Status.DRAFT) {
            throw ApiException.conflict("ALREADY_FINAL", "The discharge summary is already finalized.");
        }
        requireComplete(s);
        s.setStatus(DischargeSummary.Status.FINAL);
        s.setFinalizedBy(u.userId());
        s.setFinalizedAt(Instant.now());
        s.setUpdatedBy(u.userId());
        s.setUpdatedAt(Instant.now());
        summaries.saveAndFlush(s);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("DISCHARGE_SUMMARY_FINALIZED").category(TimelineEvent.Category.DISCHARGE)
                .title("Discharge summary finalized").status("FINAL").visibility(TimelineEvent.Visibility.CARE_TEAM)
                .entityType("DISCHARGE_SUMMARY").entityId(s.getId()).build());
        audit.record("DISCHARGE_SUMMARY_FINALIZED", "DISCHARGE_SUMMARY", s.getId(), a.getPatientId(), "Finalized by " + u.fullName());
        workflowService.syncForAdmission(a.getId());
        return new SummaryEnvelope(view(s), true, u.has(Permissions.RELEASE_TO_PATIENT));
    }

    @Transactional
    public SummaryEnvelope release(UUID admissionId) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = requireWriter(admissionId);
        if (!u.has(Permissions.RELEASE_TO_PATIENT)) {
            throw ApiException.forbidden("You do not have permission to release information to the patient portal.");
        }
        DischargeSummary s = summaries.lockByAdmissionId(a.getId()).orElseThrow(() -> ApiException.notFound("Discharge summary"));
        if (s.getStatus() == DischargeSummary.Status.DRAFT) {
            throw ApiException.rule("SUMMARY_NOT_FINAL", "Finalize the discharge summary before releasing it to the patient.");
        }
        if (s.isReleasedToPatient()) {
            return new SummaryEnvelope(view(s), true, true);
        }
        s.setReleasedToPatient(true);
        s.setReleasedBy(u.userId());
        s.setReleasedAt(Instant.now());
        summaries.saveAndFlush(s);
        // Take-home medicines become visible in the portal together with the summary.
        medicationOrders.findByAdmissionIdAndStatusIn(a.getId(), EnumSet.of(MedicationOrder.Status.ACTIVE, MedicationOrder.Status.COMPLETED))
                .stream().filter(MedicationOrder::isDischargeMedication).forEach(o -> o.setReleasedToPatient(true));
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("DISCHARGE_SUMMARY_RELEASED").category(TimelineEvent.Category.DISCHARGE)
                .title("Your discharge summary is available")
                .detail("Diagnosis, medicines, instructions and warning signs are in the patient portal.")
                .status(s.getStatus().name()).visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("DISCHARGE_SUMMARY").entityId(s.getId()).build());
        notifications.notifyPatient(a.getPatientId(), NotificationRequest.builder()
                .category("DISCHARGE").priority(Notification.Priority.NORMAL)
                .title("Your discharge summary is available")
                .message("Read your diagnosis, medicines, care instructions and warning signs in the portal.")
                .link("/patient/discharge").entityType("DISCHARGE_SUMMARY").entityId(s.getId())
                .dedupeKey("SUMMARY_RELEASED:" + s.getId()).build());
        audit.record("DISCHARGE_SUMMARY_RELEASED", "DISCHARGE_SUMMARY", s.getId(), a.getPatientId(), "Released to patient portal");
        return new SummaryEnvelope(view(s), true, true);
    }

    /** PDF for staff with clinical access, or for the patient / authorized caregiver once released. */
    @Transactional(readOnly = true)
    public PdfFile pdf(UUID admissionId) {
        CurrentUser u = TenantContext.requireUser();
        Admission a;
        if (u.role() == Role.PATIENT) {
            a = access.requireAdmission(admissionId, Scope.CLINICAL);
        } else {
            a = requireStaffClinical(admissionId);
        }
        DischargeSummary s = summaries.findByAdmissionId(a.getId()).orElseThrow(() -> ApiException.notFound("Discharge summary"));
        if (u.role() == Role.PATIENT && !s.isReleasedToPatient()) {
            throw ApiException.notFound("Discharge summary");
        }
        Patient p = patients.findById(a.getPatientId()).orElseThrow();
        Organization org = organizations.findById(a.getOrganizationId()).orElseThrow();
        ZoneId zone = org.zone();
        Names names = new Names(users);
        List<DischargeSummaryPdf.Field> fields = List.of(
                new DischargeSummaryPdf.Field("Patient", p.fullName()),
                new DischargeSummaryPdf.Field("MRN", p.getMrn()),
                new DischargeSummaryPdf.Field("Age / sex", p.ageYears(TimeUtil.today()) + " y / " + p.getGender().name().charAt(0)),
                new DischargeSummaryPdf.Field("Admission", a.getAdmissionNumber()),
                new DischargeSummaryPdf.Field("Admitted", DATE_TIME.format(a.getAdmittedAt().atZone(zone))),
                new DischargeSummaryPdf.Field("Discharged", a.getDischargedAt() == null ? "In progress" : DATE_TIME.format(a.getDischargedAt().atZone(zone))),
                new DischargeSummaryPdf.Field("Department", departments.findById(a.getDepartmentId()).map(d -> d.getName()).orElse("-")),
                new DischargeSummaryPdf.Field("Treating doctor", names.of(a.getAttendingDoctorId())),
                new DischargeSummaryPdf.Field("Disposition", a.getDischargeDisposition() == null ? "-" : DischargeWorkflowService.dispositionLabel(a.getDischargeDisposition())));
        List<DischargeSummaryPdf.Section> sections = List.of(
                new DischargeSummaryPdf.Section("Presenting complaint / reason for admission", s.getPresentingComplaint()),
                new DischargeSummaryPdf.Section("Diagnoses", s.getDiagnosesText()),
                new DischargeSummaryPdf.Section("Hospital course and treatment", s.getTreatmentSummary()),
                new DischargeSummaryPdf.Section("Procedures", s.getProceduresText()),
                new DischargeSummaryPdf.Section("Relevant investigation results", s.getRelevantResults()),
                new DischargeSummaryPdf.Section("Condition at discharge", s.getConditionAtDischarge()),
                new DischargeSummaryPdf.Section("Discharge medications", s.getDischargeMedications()),
                new DischargeSummaryPdf.Section("Instructions", s.getDischargeInstructions()),
                new DischargeSummaryPdf.Section("Follow-up plan", s.getFollowUpPlan()),
                new DischargeSummaryPdf.Section("Warning signs - seek care urgently if", s.getWarningSigns()));
        String status = switch (s.getStatus()) {
            case DRAFT -> "DRAFT - NOT FOR CLINICAL USE";
            case FINAL -> "FINAL";
            case AMENDED -> "AMENDED";
        };
        String signature = s.getFinalizedAt() == null
                ? "Draft prepared by " + names.of(s.getPreparedBy()) + " on " + DATE_TIME.format(s.getPreparedAt().atZone(zone)) + ". Not yet finalized."
                : "Electronically finalized by " + names.of(s.getFinalizedBy()) + " on " + DATE_TIME.format(s.getFinalizedAt().atZone(zone)) + ".";
        List<String> amendList = amendments.findBySummaryIdOrderByAmendedAtDesc(s.getId()).stream()
                .map(x -> DATE_TIME.format(x.getAmendedAt().atZone(zone)) + " by " + names.of(x.getAmendedBy()) + ": " + x.getReason())
                .toList();
        String address = String.join(", ", java.util.stream.Stream.of(org.getAddress(), org.getCity(), org.getState())
                .filter(x -> x != null && !x.isBlank()).toList());
        String contact = java.util.stream.Stream.of(org.getPhone(), org.getEmail()).filter(x -> x != null && !x.isBlank())
                .collect(Collectors.joining(" · "));
        byte[] bytes = pdf.render(new DischargeSummaryPdf.Model(org.getName(), address.isBlank() ? null : address,
                contact.isBlank() ? null : contact, status, fields, sections, signature, amendList,
                "Generated " + DATE_TIME.format(Instant.now().atZone(zone)) + " · " + p.getMrn() + " · Confidential patient information"));
        audit.record("DISCHARGE_SUMMARY_EXPORTED", "DISCHARGE_SUMMARY", s.getId(), a.getPatientId(), "PDF downloaded");
        return new PdfFile("discharge-summary-" + a.getAdmissionNumber() + ".pdf", bytes);
    }

    // ------------------------------------------------------------------ prefill (recorded data only)

    void prefill(DischargeSummary s, Admission a) {
        ZoneId zone = organizations.findById(a.getOrganizationId()).map(Organization::zone).orElse(TimeUtil.DEFAULT_ZONE);
        s.setPresentingComplaint(a.getAdmissionReason());
        List<Diagnosis> dx = diagnoses.findByAdmissionIdOrderByRecordedAtDesc(a.getId()).stream()
                .filter(d -> d.getStatus() != Diagnosis.Status.ENTERED_IN_ERROR)
                .sorted(Comparator.comparing((Diagnosis d) -> !d.isPrimary()).thenComparing(Diagnosis::getRecordedAt))
                .toList();
        s.setDiagnosesText(dx.isEmpty() ? null : dx.stream().map(d -> "- " + d.getDescription()
                        + (d.getIcdCode() == null ? "" : " (" + d.getIcdCode() + ")")
                        + " - " + d.getDiagnosisType().name().toLowerCase() + (d.isPrimary() ? ", primary" : "")
                        + (d.getStatus() == Diagnosis.Status.RESOLVED ? ", resolved" : ""))
                .collect(Collectors.joining("\n")));
        List<ProcedureRecord> procs = procedures.findByAdmissionIdOrderByCreatedAtDesc(a.getId()).stream()
                .filter(x -> x.getStatus() == ProcedureRecord.Status.COMPLETED)
                .sorted(Comparator.comparing(x -> x.getPerformedAt() == null ? x.getCreatedAt() : x.getPerformedAt()))
                .toList();
        s.setProceduresText(procs.isEmpty() ? null : procs.stream().map(x -> "- " + x.getName()
                        + (x.getPerformedAt() == null ? "" : " - " + DATE.format(x.getPerformedAt().atZone(zone)))
                        + (x.getFindings() == null || x.getFindings().isBlank() ? "" : "\n  Findings: " + x.getFindings().trim()))
                .collect(Collectors.joining("\n")));
        List<LabOrder> reviewed = labOrders.findByAdmissionIdOrderByOrderedAtDesc(a.getId()).stream()
                .filter(o -> o.getStatus() == LabOrder.Status.REVIEWED).toList();
        if (!reviewed.isEmpty()) {
            Map<UUID, List<LabResult>> byOrder = labResults.findByLabOrderIdIn(reviewed.stream().map(LabOrder::getId).toList()).stream()
                    .filter(r -> r.getStatus() != LabResult.Status.ENTERED_IN_ERROR)
                    .collect(Collectors.groupingBy(LabResult::getLabOrderId, LinkedHashMap::new, Collectors.toList()));
            List<String> lines = new ArrayList<>();
            for (LabOrder o : reviewed) {
                List<LabResult> rs = byOrder.getOrDefault(o.getId(), List.of());
                String when = rs.isEmpty() ? "" : " (" + DATE.format(rs.get(0).getResultedAt().atZone(zone)) + ")";
                lines.add("- " + o.getTestName() + when + (rs.isEmpty() ? "" : ": " + rs.stream()
                        .map(r -> r.getComponent() + " " + r.getValueText() + (r.getUnit() == null ? "" : " " + r.getUnit())
                                + (r.getFlag() == LabResult.Flag.NORMAL ? "" : " [" + r.getFlag().name().toLowerCase().replace('_', ' ') + "]"))
                        .collect(Collectors.joining("; "))));
            }
            s.setRelevantResults(String.join("\n", lines));
        }
        StringBuilder course = new StringBuilder();
        plans.findByAdmissionIdAndStatus(a.getId(), TreatmentPlan.Status.ACTIVE).ifPresent(p -> {
            course.append("Goals: ").append(p.getGoals().trim()).append("\n").append(p.getPlanDetails().trim());
        });
        List<MedicationOrder> orders = medicationOrders.findByAdmissionIdOrderByPrescribedAtDesc(a.getId());
        Map<UUID, Medication> meds = medications.findByIdIn(orders.stream().map(MedicationOrder::getMedicationId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Medication::getId, Function.identity()));
        List<String> inpatient = orders.stream().filter(o -> !o.isDischargeMedication())
                .map(o -> meds.containsKey(o.getMedicationId()) ? meds.get(o.getMedicationId()).getGenericName() + " " + o.getDose() + " " + o.getDoseUnit() + " " + o.getRoute().name() : null)
                .filter(Objects::nonNull).distinct().toList();
        if (!inpatient.isEmpty()) {
            course.append(course.length() > 0 ? "\n" : "").append("Inpatient medicines: ").append(String.join(", ", inpatient)).append(".");
        }
        s.setTreatmentSummary(course.length() == 0 ? null : course.toString());
        List<MedicationOrder> discharge = orders.stream()
                .filter(o -> o.isDischargeMedication() && o.getStatus() == MedicationOrder.Status.ACTIVE).toList();
        s.setDischargeMedications(discharge.isEmpty() ? null : discharge.stream()
                .map(o -> "- " + medicationLine(o, meds.get(o.getMedicationId()))
                        + (o.getPatientInstructions() == null || o.getPatientInstructions().isBlank() ? "" : "\n  " + o.getPatientInstructions().trim()))
                .collect(Collectors.joining("\n")));
        plans.findByAdmissionIdAndStatus(a.getId(), TreatmentPlan.Status.ACTIVE).ifPresent(p -> {
            List<String> instr = new ArrayList<>();
            if (p.getDiet() != null && !p.getDiet().isBlank()) {
                instr.add("Diet: " + p.getDiet().trim());
            }
            if (p.getActivity() != null && !p.getActivity().isBlank()) {
                instr.add("Activity: " + p.getActivity().trim());
            }
            s.setDischargeInstructions(instr.isEmpty() ? null : String.join("\n", instr));
        });
        List<FollowUp> fus = followUps.findByAdmissionIdOrderByScheduledAtAsc(a.getId()).stream()
                .filter(f -> f.getStatus() != FollowUp.Status.CANCELLED).toList();
        s.setFollowUpPlan(fus.isEmpty() ? null : fus.stream().map(f -> followUpLine(f, zone)).collect(Collectors.joining("\n")));
    }

    String followUpLine(FollowUp f, ZoneId zone) {
        String clinician = f.getClinicianId() == null ? null : users.findById(f.getClinicianId()).map(User::getFullName).orElse(null);
        String dept = f.getDepartmentId() == null ? null : departments.findById(f.getDepartmentId()).map(d -> d.getName()).orElse(null);
        return "- " + f.getTitle()
                + (f.getScheduledAt() == null ? "" : " - " + DATE_TIME.format(f.getScheduledAt().atZone(zone)))
                + (dept == null && clinician == null ? "" : " (" + java.util.stream.Stream.of(dept, clinician).filter(Objects::nonNull).collect(Collectors.joining(", ")) + ")")
                + (f.getLocation() == null ? "" : ", " + f.getLocation());
    }

    static String medicationLine(MedicationOrder o, Medication m) {
        String name = m == null ? "Medication" : m.getGenericName() + " " + m.getStrength() + " " + m.getForm().name().toLowerCase();
        return name + " - " + o.getDose() + " " + o.getDoseUnit() + " " + o.getRoute().name().toLowerCase() + ", " + frequencyText(o.getFrequency())
                + (o.getDurationDays() == null ? "" : " for " + o.getDurationDays() + " days");
    }

    static String frequencyText(MedicationOrder.Frequency f) {
        return switch (f) {
            case ONCE, STAT -> "once";
            case OD -> "once a day";
            case BD -> "twice a day";
            case TDS -> "three times a day";
            case QID -> "four times a day";
            case Q4H -> "every 4 hours";
            case Q6H -> "every 6 hours";
            case Q8H -> "every 8 hours";
            case Q12H -> "every 12 hours";
            case HS -> "at bedtime";
            case PRN -> "when required";
            case WEEKLY -> "once a week";
        };
    }

    // ------------------------------------------------------------------ helpers

    private boolean canWrite(CurrentUser u) {
        return u.role() == Role.DOCTOR && u.has(Permissions.DISCHARGE_SUMMARY_WRITE);
    }

    private Admission requireStaffClinical(UUID admissionId) {
        CurrentUser u = TenantContext.requireUser();
        if (u.role() == Role.PATIENT || !(u.has(Permissions.DISCHARGE_VIEW) || u.has(Permissions.DISCHARGE_SUMMARY_WRITE))) {
            throw ApiException.forbidden("You do not have access to discharge summaries.");
        }
        return access.requireAdmission(admissionId, Scope.CLINICAL);
    }

    private Admission requireWriter(UUID admissionId) {
        CurrentUser u = TenantContext.requireUser();
        if (!canWrite(u)) {
            throw ApiException.forbidden("Discharge summaries are prepared and finalized by doctors.");
        }
        return access.requireAdmission(admissionId, Scope.CLINICAL);
    }

    private static void requireComplete(DischargeSummary s) {
        List<String> missing = new ArrayList<>();
        if (blank(s.getDiagnosesText())) {
            missing.add("diagnoses");
        }
        if (blank(s.getTreatmentSummary())) {
            missing.add("hospital course / treatment");
        }
        if (blank(s.getConditionAtDischarge())) {
            missing.add("condition at discharge");
        }
        if (blank(s.getDischargeInstructions())) {
            missing.add("instructions");
        }
        if (!missing.isEmpty()) {
            throw ApiException.rule("SUMMARY_INCOMPLETE", "Complete these sections before finalizing: " + String.join(", ", missing) + ".");
        }
    }

    private static void validateLengths(SummaryRequest r) {
        for (String v : new String[]{r.presentingComplaint(), r.diagnosesText(), r.treatmentSummary(), r.proceduresText(),
                r.relevantResults(), r.conditionAtDischarge(), r.dischargeMedications(), r.dischargeInstructions(),
                r.followUpPlan(), r.warningSigns()}) {
            if (v != null && v.length() > MAX_SECTION) {
                throw ApiException.badRequest("SECTION_TOO_LONG", "Each section can hold at most " + MAX_SECTION + " characters.");
            }
        }
    }

    private String snapshot(DischargeSummary s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("status", s.getStatus().name());
        m.put("presentingComplaint", s.getPresentingComplaint());
        m.put("diagnosesText", s.getDiagnosesText());
        m.put("treatmentSummary", s.getTreatmentSummary());
        m.put("proceduresText", s.getProceduresText());
        m.put("relevantResults", s.getRelevantResults());
        m.put("conditionAtDischarge", s.getConditionAtDischarge());
        m.put("dischargeMedications", s.getDischargeMedications());
        m.put("dischargeInstructions", s.getDischargeInstructions());
        m.put("followUpPlan", s.getFollowUpPlan());
        m.put("warningSigns", s.getWarningSigns());
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    SummaryView view(DischargeSummary s) {
        Names names = new Names(users);
        List<AmendmentView> am = amendments.findBySummaryIdOrderByAmendedAtDesc(s.getId()).stream()
                .map(x -> new AmendmentView(x.getReason(), names.of(x.getAmendedBy()), x.getAmendedAt())).toList();
        return new SummaryView(s.getId(), s.getAdmissionId(), s.getStatus(), s.getPresentingComplaint(), s.getDiagnosesText(),
                s.getTreatmentSummary(), s.getProceduresText(), s.getRelevantResults(), s.getConditionAtDischarge(),
                s.getDischargeMedications(), s.getDischargeInstructions(), s.getFollowUpPlan(), s.getWarningSigns(),
                names.of(s.getPreparedBy()), s.getPreparedAt(), names.of(s.getFinalizedBy()), s.getFinalizedAt(),
                s.isReleasedToPatient(), names.of(s.getReleasedBy()), s.getReleasedAt(), names.of(s.getUpdatedBy()),
                s.getUpdatedAt(), am, s.getVersion());
    }

    private static String clean(String v) {
        if (v == null) {
            return null;
        }
        String t = v.strip();
        return t.isEmpty() ? null : t;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
