package com.careflow.hms.clinical;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.clinical.ClinicalDtos.CollectRequest;
import com.careflow.hms.clinical.ClinicalDtos.CompleteProcedureRequest;
import com.careflow.hms.clinical.ClinicalDtos.DiagnosisRequest;
import com.careflow.hms.clinical.ClinicalDtos.EscalationRequest;
import com.careflow.hms.clinical.ClinicalDtos.HandoverCreateRequest;
import com.careflow.hms.clinical.ClinicalDtos.LabOrderRequest;
import com.careflow.hms.clinical.ClinicalDtos.LisResultPayload;
import com.careflow.hms.clinical.ClinicalDtos.NoteRequest;
import com.careflow.hms.clinical.ClinicalDtos.NoteView;
import com.careflow.hms.clinical.ClinicalDtos.ProcedureRequest;
import com.careflow.hms.clinical.ClinicalDtos.ResultComponent;
import com.careflow.hms.clinical.ClinicalDtos.ResultEntryRequest;
import com.careflow.hms.clinical.ClinicalDtos.TaskRequest;
import com.careflow.hms.clinical.ClinicalDtos.TreatmentPlanRequest;
import com.careflow.hms.clinical.ClinicalDtos.TreatmentPlanView;
import com.careflow.hms.clinical.ClinicalDtos.VitalsRequest;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.facility.StaffWardAssignment;
import com.careflow.hms.facility.StaffWardAssignmentRepository;
import com.careflow.hms.medication.MarService;
import com.careflow.hms.medication.MedicationAdministration;
import com.careflow.hms.medication.MedicationAdministrationRepository;
import com.careflow.hms.medication.MedicationDtos.AdministerRequest;
import com.careflow.hms.medication.MedicationDtos.ExceptionRequest;
import com.careflow.hms.medication.MedicationDtos.FollowUpRequest;
import com.careflow.hms.medication.MedicationDtos.PrnDoseRequest;
import com.careflow.hms.medication.MedicationOrder;
import com.careflow.hms.medication.MedicationOrderRepository;
import com.careflow.hms.seed.DemoContext;
import com.careflow.hms.seed.DemoSeedContributor;
import com.careflow.hms.seed.PlatformBootstrap;
import com.careflow.hms.security.Role;
import com.careflow.hms.user.StaffProfile;
import com.careflow.hms.user.StaffProfileRepository;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Clinical demo data (fictional): assessments and progress notes, diagnoses, versioned treatment plans,
 * vital-sign histories with realistic trends (and one intentional observation gap), resulting threshold
 * alerts, investigations in every state with charges, procedures, care tasks, MAR history for the
 * core-seeded prescriptions (with exceptions and upcoming doses), handovers and an accepted rule-based draft.
 * Everything is created through the real services acting as demo users, then backdated where history is needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClinicalDemoSeed implements DemoSeedContributor {

    private final ClinicalRecordService records;
    private final VitalsService vitals;
    private final AlertService alerts;
    private final InvestigationService investigations;
    private final ProcedureService procedures;
    private final CareTaskService tasks;
    private final HandoverService handovers;
    private final DraftSummaryService drafts;
    private final MarService mar;
    private final com.careflow.hms.medication.MedicationOrderService medicationOrders;
    private final AdmissionRepository admissions;
    private final ClinicalNoteRepository noteRepo;
    private final DiagnosisRepository diagnosisRepo;
    private final TreatmentPlanRepository planRepo;
    private final ClinicalAlertRepository alertRepo;
    private final LabOrderRepository labRepo;
    private final LabResultRepository resultRepo;
    private final ProcedureRecordRepository procedureRepo;
    private final CareTaskRepository taskRepo;
    private final HandoverRepository handoverRepo;
    private final AiDraftRepository draftRepo;
    private final MedicationOrderRepository orderRepo;
    private final MedicationAdministrationRepository marRepo;
    private final UserRepository users;
    private final StaffProfileRepository staffProfiles;
    private final StaffWardAssignmentRepository wardAssignments;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager em;
    private final JdbcTemplate jdbc;

    private final Random rnd = new Random(20261010L);
    private Instant seedStart;
    private final Map<String, String> wardNurse = Map.of(
            "ravi", "nurse_priya", "deepa", "nurse_priya", "vikram", "nurse_priya", "george", "nurse_rahul",
            "irfan", "nurse_rahul", "ananya", "nurse_fatima", "aarav", "nurse_anita");
    private final Map<String, String> doctorOf = Map.of(
            "ravi", "dr_mehta", "deepa", "dr_mehta", "fatima", "dr_mehta", "george", "dr_bose_icu", "irfan", "dr_khan",
            "ananya", "dr_iyer", "vikram", "dr_iyer", "aarav", "dr_rao_paeds");

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String name() {
        return "clinical records, nursing & MAR";
    }

    @Override
    public void seed(DemoContext ctx) {
        seedStart = Instant.now().minusSeconds(5);
        nightNurse(ctx);
        for (String p : List.of("ravi", "george", "irfan", "ananya", "deepa", "aarav", "vikram", "fatima")) {
            diagnosesAndNotes(ctx, p);
        }
        plans(ctx);
        for (String p : List.of("ravi", "george", "irfan", "ananya", "deepa", "aarav", "vikram")) {
            vitalsHistory(ctx, p);
        }
        resolveOldVitalAlerts(ctx);
        investigationsSeed(ctx);
        proceduresSeed(ctx);
        marHistory(ctx);
        releaseMedicationInfo(ctx);
        tasksSeed(ctx);
        escalationsSeed(ctx);
        handoverSeed(ctx);
        draftSeed(ctx);
        backdateNotifications(ctx);
        ctx.log.add("clinical seeded");
    }

    // ================================================================== helpers

    private Admission adm(DemoContext ctx, String key) {
        return admissions.findById(ctx.admission(key)).orElseThrow();
    }

    private Instant at(Admission a, double hoursAfterAdmission) {
        return a.getAdmittedAt().plus(Duration.ofMinutes((long) (hoursAfterAdmission * 60)));
    }

    private static Instant hoursAgo(double h) {
        return Instant.now().minus(Duration.ofMinutes((long) (h * 60)));
    }

    /** Moves the timeline events (and only those created by this seed) of an entity to a point in time. */
    private void stamp(UUID entityId, String eventType, Instant when) {
        em.flush();
        if (eventType == null) {
            jdbc.update("UPDATE timeline_events SET occurred_at = ? WHERE entity_id = ? AND occurred_at >= ?",
                    Timestamp.from(when), entityId, Timestamp.from(seedStart));
        } else {
            jdbc.update("UPDATE timeline_events SET occurred_at = ? WHERE entity_id = ? AND event_type = ? AND occurred_at >= ?",
                    Timestamp.from(when), entityId, eventType, Timestamp.from(seedStart));
        }
    }

    private double noise(double amplitude) {
        return (rnd.nextDouble() * 2 - 1) * amplitude;
    }

    private static double lerp(double from, double to, double p) {
        return from + (to - from) * p;
    }

    /** Exponential approach from {@code start} to {@code end} (fast early improvement). */
    private static double decay(double start, double end, double p, double rate) {
        return end + (start - end) * Math.exp(-rate * p);
    }

    // ================================================================== night nurse (handover partner on Medical Ward A)

    private void nightNurse(DemoContext ctx) {
        User u = new User();
        u.setEmail("nurse.leela@careflow.local");
        u.setFullName("Leela Thomas");
        u.setRole(Role.NURSE);
        u.setPasswordHash(passwordEncoder.encode(PlatformBootstrap.DEMO_PASSWORD));
        u.setPhone("+91 90000 47821");
        u = users.save(u);
        ctx.users.put("nurse_leela", u);
        StaffProfile p = new StaffProfile();
        p.setUserId(u.getId());
        p.setOrganizationId(u.getOrganizationId());
        p.setDepartmentId(ctx.departments.get("GEN_MED"));
        p.setDesignation("Night Staff Nurse");
        p.setEmployeeCode("EMP-0100");
        staffProfiles.save(p);
        for (String ward : List.of("MWA", "PVT")) {
            StaffWardAssignment w = new StaffWardAssignment();
            w.setUserId(u.getId());
            w.setWardId(ctx.wards.get(ward));
            wardAssignments.save(w);
        }
        em.flush();
    }

    // ================================================================== diagnoses & notes

    private void dx(DemoContext ctx, String key, String desc, String icd, Diagnosis.DiagnosisType type, boolean primary,
                    double hoursAfter, String notes) {
        Admission a = adm(ctx, key);
        ClinicalDtos.DiagnosisView v = ctx.actAs(doctorOf.get(key), () -> records.addDiagnosis(a.getId(),
                new DiagnosisRequest(desc, icd, type, primary, notes)));
        Diagnosis d = diagnosisRepo.findById(v.id()).orElseThrow();
        d.setRecordedAt(at(a, hoursAfter));
        stamp(d.getId(), null, d.getRecordedAt());
    }

    private UUID note(DemoContext ctx, String key, String author, ClinicalNote.NoteType type, double hoursAfter, String title,
                      String s, String o, String assessment, String plan, String content, String patientSummary) {
        Admission a = adm(ctx, key);
        NoteView v = ctx.actAs(author, () -> records.addNote(a.getId(), new NoteRequest(type, title, s, o, assessment, plan, content, null)));
        ClinicalNote n = noteRepo.findById(v.id()).orElseThrow();
        Instant when = at(a, hoursAfter);
        if (when.isAfter(Instant.now())) {
            when = hoursAgo(0.5);
        }
        n.setAuthoredAt(when);
        stamp(n.getId(), null, when);
        if (patientSummary != null) {
            Instant rel = when.plus(Duration.ofMinutes(40));
            ctx.actAs(author, () -> records.releaseNote(n.getId(), patientSummary));
            n.setReleasedAt(rel);
            stamp(n.getId(), "NOTE_RELEASED", rel);
        }
        return n.getId();
    }

    private void diagnosesAndNotes(DemoContext ctx, String key) {
        String doc = doctorOf.get(key);
        String nurse = wardNurse.get(key);
        switch (key) {
            case "ravi" -> {
                dx(ctx, key, "Community-acquired pneumonia, right lower lobe", "J18.1", Diagnosis.DiagnosisType.CONFIRMED, true, 2, "CURB-65 score 1; CXR right lower zone consolidation");
                dx(ctx, key, "Type 2 diabetes mellitus with hyperglycaemia", "E11.65", Diagnosis.DiagnosisType.CONFIRMED, false, 2, "Admission CBG 286 mg/dL");
                dx(ctx, key, "Essential hypertension", "I10", Diagnosis.DiagnosisType.CONFIRMED, false, 2, null);
                note(ctx, key, doc, ClinicalNote.NoteType.ADMISSION_ASSESSMENT, 1.2, "Admission assessment - febrile respiratory illness",
                        "5 days of fever, productive cough with yellow sputum and breathlessness on exertion. No chest pain. Known T2DM on metformin, HTN.",
                        "T 39.1 °C, P 112, RR 24, BP 138/86, SpO2 91% on room air. Bronchial breathing and crackles right base.",
                        "Community-acquired pneumonia, CURB-65 = 1. Hyperglycaemia secondary to infection.",
                        "IV ceftriaxone + oral azithromycin, O2 to target SpO2 94-98%, sliding-scale insulin, CBC/CRP/blood cultures/CXR.",
                        "Admitted under internal medicine for IV antibiotics and glycaemic control. Penicillin allergy (urticaria) noted - cephalosporin chosen after discussion of the low cross-reactivity risk.",
                        null);
                note(ctx, key, nurse, ClinicalNote.NoteType.NURSING_ASSESSMENT, 2.0, "Nursing admission assessment",
                        null, null, "Moderate dependency; independent with mobility, short of breath on exertion. Falls risk low. Braden 19.",
                        "4-hourly observations, CBG before meals, encourage oral fluids.",
                        "Patient and wife oriented to ward. Allergy band applied (penicillin). Peripheral cannula left forearm.", null);
                note(ctx, key, doc, ClinicalNote.NoteType.PROGRESS, 26, "Day 2 review",
                        "Feels slightly better, still coughing.", "T 38.4 °C, SpO2 94% on 2 L O2.", "Responding slowly to antibiotics.",
                        "Continue current antibiotics, chest physiotherapy, repeat CRP in 48 h.",
                        "Clinically improving. Blood cultures no growth so far.", null);
                note(ctx, key, doc, ClinicalNote.NoteType.PROGRESS, 74, "Day 4 review - fever settling",
                        "Appetite returning, less breathless.", "Afebrile for 24 h. SpO2 96% on room air. Crackles reduced.",
                        "Pneumonia improving; glucose control improving.",
                        "Plan oral switch after 72 h afebrile; diabetes education before discharge.",
                        "Good response. Oxygen weaned off. HbA1c sent to guide outpatient diabetes management.",
                        "Your fever has settled and your oxygen levels are normal without extra oxygen. We plan to switch your antibiotic from the drip to tablets once you have had no fever for three days.");
                note(ctx, key, nurse, ClinicalNote.NoteType.EDUCATION, 90, "Diabetes self-care education",
                        null, null, "Patient demonstrated glucometer use correctly.", "Reinforce foot care and hypoglycaemia recognition.",
                        "Education given to patient and wife on glucose monitoring, diet and sick-day rules. Leaflet provided.", null);
            }
            case "george" -> {
                dx(ctx, key, "Sepsis due to Escherichia coli, urinary source", "A41.51", Diagnosis.DiagnosisType.CONFIRMED, true, 3, "Blood and urine cultures: E. coli, carbapenem-sensitive");
                dx(ctx, key, "Acute kidney injury on chronic kidney disease stage 3", "N17.9", Diagnosis.DiagnosisType.CONFIRMED, false, 3, "Creatinine 3.4 mg/dL on admission (baseline 1.6)");
                dx(ctx, key, "Septic shock", "R65.21", Diagnosis.DiagnosisType.CONFIRMED, false, 2, null);
                Diagnosis shock = diagnosisRepo.findByAdmissionIdOrderByRecordedAtDesc(ctx.admission(key)).stream()
                        .filter(d -> d.getDescription().equals("Septic shock")).findFirst().orElseThrow();
                ctx.actAs(doc, () -> records.changeDiagnosisStatus(shock.getId(), Diagnosis.Status.RESOLVED, "Off vasopressors for 48 h"));
                shock.setStatusChangedAt(hoursAgo(80));
                stamp(shock.getId(), "DIAGNOSIS_RESOLVED", hoursAgo(80));
                note(ctx, key, doc, ClinicalNote.NoteType.ADMISSION_ASSESSMENT, 1.0, "ICU admission - septic shock, urinary source",
                        "Two days of fever and dysuria, new confusion today per daughter.",
                        "T 39.8 °C, P 132, BP 78/46, RR 32, SpO2 87% on room air, confused (GCS 13). Suprapubic tenderness.",
                        "Septic shock from urinary source with AKI on CKD 3. Sulfonamide allergy (SJS 2015).",
                        "Cultures, meropenem (renally dosed), fluid resuscitation, noradrenaline via CVC, hourly urine output.",
                        "Admitted to ICU. Daughter informed of the seriousness of the illness.", null);
                note(ctx, key, "nurse_rahul", ClinicalNote.NoteType.NURSING_ASSESSMENT, 2.0, "ICU nursing assessment",
                        null, null, "High dependency. Delirium screen positive. Braden 13 - high pressure-injury risk.",
                        "2-hourly repositioning, strict fluid balance, delirium prevention bundle.",
                        "CVC right IJ in situ. Urinary catheter draining concentrated urine 20 mL/h.", null);
                note(ctx, key, doc, ClinicalNote.NoteType.PROGRESS, 50, "Day 3 ICU review",
                        null, "Noradrenaline weaned off. MAP > 70. Urine output 0.6 mL/kg/h.", "Shock resolving; AKI improving.",
                        "Continue meropenem to complete 7 days; step down when stable 24 h.", "Improving trajectory.", null);
                note(ctx, key, "dr_mehta", ClinicalNote.NoteType.CONSULTATION, 76, "Internal medicine consultation",
                        null, null, "Recovering urosepsis; renal function improving; diabetes not present.",
                        "Accept on medical ward on step-down. Review renal dosing daily.", "Seen at the request of Dr. Bose.", null);
                note(ctx, key, doc, ClinicalNote.NoteType.PROGRESS, 138, "Day 6 ICU review",
                        "Feels much better, eating.", "Afebrile 36 h. Transient hypotension this morning responded to fluid bolus.",
                        "Sepsis improving. Potassium elevated on today's renal panel - repeat and treat.",
                        "Recheck BP hourly x4, ECG, repeat potassium.", "Ongoing review.", null);
            }
            case "irfan" -> {
                dx(ctx, key, "Acute appendicitis", "K35.80", Diagnosis.DiagnosisType.CONFIRMED, true, 1.5, "Ultrasound: non-compressible appendix 9 mm");
                note(ctx, key, doc, ClinicalNote.NoteType.ADMISSION_ASSESSMENT, 0.8, "Surgical admission - right iliac fossa pain",
                        "18 h of periumbilical pain migrating to RIF, anorexia and two episodes of vomiting.",
                        "T 37.9 °C, P 98, RIF tenderness with rebound. Alvarado 8.", "Acute appendicitis.",
                        "NBM, IV fluids, IV ceftriaxone, ultrasound abdomen, consent for laparoscopic appendectomy.",
                        "Listed for emergency laparoscopic appendectomy.", null);
                note(ctx, key, doc, ClinicalNote.NoteType.PROCEDURE, 13, "Operative note - laparoscopic appendectomy",
                        null, "Inflamed, non-perforated appendix. No collection.", "Uncomplicated appendicitis.",
                        "Oral fluids tonight, mobilise in the morning, 3 days antibiotics.", "Three-port laparoscopic appendectomy under GA. EBL minimal.", null);
                note(ctx, key, "nurse_rahul", ClinicalNote.NoteType.NURSING_OBSERVATION, 20, "Post-operative night",
                        null, null, "Pain 8/10 overnight, settled with tramadol. Port sites clean and dry.", "Encourage mobilisation.",
                        "Passed urine. Tolerating sips.", null);
                note(ctx, key, doc, ClinicalNote.NoteType.PROGRESS, 36, "Post-op day 1",
                        "Mild port-site pain.", "Afebrile, abdomen soft.", "Recovering well.", "Diet as tolerated; plan discharge in 1-2 days.",
                        "Good recovery.", "Your operation went well. You can start eating light food today and we expect you to go home in one or two days.");
            }
            case "ananya" -> {
                dx(ctx, key, "Supraventricular tachycardia", "I47.1", Diagnosis.DiagnosisType.CONFIRMED, true, 2, "Narrow-complex tachycardia 156/min, terminated with vagal manoeuvre");
                dx(ctx, key, "Thyrotoxicosis (to exclude)", "E05.90", Diagnosis.DiagnosisType.DIFFERENTIAL, false, 2, "TSH sent");
                note(ctx, key, doc, ClinicalNote.NoteType.ADMISSION_ASSESSMENT, 1.0, "Cardiology admission - recurrent palpitations",
                        "Recurrent sudden-onset palpitations for 6 months, 3 episodes this week.", "P 156 regular, BP 112/70.",
                        "Paroxysmal SVT.", "Telemetry, metoprolol, echo, consider EP study.", "Admitted to CCU for monitoring.", null);
                note(ctx, key, "nurse_fatima", ClinicalNote.NoteType.NURSING_ASSESSMENT, 2.0, "CCU nursing assessment",
                        null, null, "Low dependency. Anxious about episodes.", "Telemetry; teach vagal manoeuvres.", "Oriented to call bell.", null);
            }
            case "deepa" -> {
                dx(ctx, key, "Cellulitis of left lower limb", "L03.116", Diagnosis.DiagnosisType.CONFIRMED, true, 1.5, null);
                note(ctx, key, doc, ClinicalNote.NoteType.ADMISSION_ASSESSMENT, 1.0, "Admission - left leg cellulitis",
                        "Painful red swollen left shin for 3 days with fever.", "T 38.6 °C. Erythema 12 x 8 cm, marked.",
                        "Non-purulent cellulitis. NSAID allergy (bronchospasm).", "IV ceftriaxone, elevation, paracetamol for pain.",
                        "Margins of erythema marked for monitoring.", null);
                note(ctx, key, doc, ClinicalNote.NoteType.PROGRESS, 96, "Day 5 - ready for oral step-down",
                        null, "Afebrile 72 h, erythema receding inside markings.", "Cellulitis resolving.",
                        "Switch to oral cephalexin; plan discharge.", "Discharge planning started.",
                        "The infection in your leg is clearing. You will go home on antibiotic capsules - please finish the full course.");
                note(ctx, key, "nurse_priya", ClinicalNote.NoteType.EDUCATION, 100, "Discharge education - skin care",
                        null, null, "Patient understands warning signs.", null, "Taught leg elevation, skin care and when to seek help.", null);
            }
            case "aarav" -> {
                dx(ctx, key, "Acute severe exacerbation of asthma", "J45.901", Diagnosis.DiagnosisType.CONFIRMED, true, 1, null);
                note(ctx, key, doc, ClinicalNote.NoteType.ADMISSION_ASSESSMENT, 0.7, "Paediatric admission - acute asthma",
                        "Wheeze and breathlessness for 1 day not relieved by home nebulisation.", "RR 28, SpO2 90% in air, widespread wheeze, speaking in phrases.",
                        "Acute severe asthma.", "Salbutamol nebs 4-hourly, budesonide, oral prednisolone, O2 to keep SpO2 >= 94%.",
                        "Mother present and updated.", null);
                note(ctx, key, "nurse_anita", ClinicalNote.NoteType.NURSING_ASSESSMENT, 1.5, "Paediatric nursing assessment",
                        null, null, "Moderate respiratory distress, playful between nebs.", "4-hourly obs, peak flow when able.",
                        "Weight 26 kg. Mother staying.", null);
                note(ctx, key, doc, ClinicalNote.NoteType.PROGRESS, 26, "Day 2 review",
                        null, "RR 22, SpO2 96% in air, mild end-expiratory wheeze.", "Improving.", "Space nebs to 6-hourly if stable; inhaler technique teaching.",
                        "Good response to treatment.",
                        "Aarav's breathing is much better. We will space out his breathing medicine and teach you both how to use his inhaler with a spacer before going home.");
            }
            case "vikram" -> {
                dx(ctx, key, "Stable angina - suspected coronary artery disease", "I20.9", Diagnosis.DiagnosisType.PROVISIONAL, true, 2, "Positive treadmill test at 7 METs");
                note(ctx, key, doc, ClinicalNote.NoteType.ADMISSION_ASSESSMENT, 1.0, "Elective admission - coronary angiography",
                        "Exertional chest tightness for 2 months relieved by rest.", "BP 136/84, P 72, ECG: no acute changes.",
                        "Probable chronic coronary syndrome. Contrast allergy (mild hives).", "Dual antiplatelets, statin, premedication before angiography.",
                        "Consent discussion for angiography +/- PCI.",
                        "We have admitted you to look at the blood vessels of your heart with an angiogram. Because of your previous mild reaction to contrast dye, you will get medicines beforehand to prevent it.");
            }
            case "fatima" -> {
                dx(ctx, key, "Type 2 diabetes mellitus with hyperglycaemia and dehydration", "E11.65", Diagnosis.DiagnosisType.PROVISIONAL, true, 0.5, null);
                note(ctx, key, doc, ClinicalNote.NoteType.ADMISSION_ASSESSMENT, 0.6, "Emergency admission - hyperglycaemia",
                        "Polyuria and lethargy for 4 days.", "CBG 412 mg/dL, ketones negative, dry mucosa.", "Hyperosmolar hyperglycaemia without ketosis.",
                        "IV fluids, insulin sliding scale, hourly CBG until < 250; awaiting medical bed.", "Admitted, bed requested on Medical Ward A.", null);
            }
            default -> {
            }
        }
    }

    // ================================================================== treatment plans

    private UUID plan(DemoContext ctx, String key, double hoursAfter, String goals, String details, String monitoring, String diet,
                      String activity, String patientSummary, String reason, UUID basedOn, boolean release) {
        Admission a = adm(ctx, key);
        String doc = doctorOf.get(key);
        TreatmentPlanView v = ctx.actAs(doc, () -> records.revisePlan(a.getId(),
                new TreatmentPlanRequest(goals, details, monitoring, diet, activity, patientSummary, reason, basedOn)));
        TreatmentPlan p = planRepo.findById(v.id()).orElseThrow();
        Instant when = at(a, hoursAfter);
        p.setCreatedAt(when);
        stamp(p.getId(), null, when);
        if (release) {
            ctx.actAs(doc, () -> records.releasePlan(p.getId(), null));
            p.setReleasedAt(when.plus(Duration.ofHours(1)));
            stamp(p.getId(), "TREATMENT_PLAN_RELEASED", p.getReleasedAt());
        }
        return p.getId();
    }

    private void plans(DemoContext ctx) {
        UUID r1 = plan(ctx, "ravi", 2, "Treat pneumonia; maintain SpO2 94-98%; glucose 140-180 mg/dL.",
                "IV ceftriaxone 1 g daily + azithromycin 500 mg daily. Oxygen by nasal cannula. Sliding-scale insulin; hold metformin.",
                "4-hourly observations, CBG before meals and at bedtime.", "Diabetic diet, 1800 kcal", "Bed rest with bathroom privileges", null, null, null, false);
        plan(ctx, "ravi", 74, "Complete antibiotic course; transition to oral therapy; restart oral diabetes medication; discharge in 2-3 days.",
                "Continue IV ceftriaxone until 72 h afebrile then switch to oral cefuroxime. Restart metformin. Diabetes education.",
                "6-hourly observations; CBG before meals.", "Diabetic diet", "Mobilise freely; incentive spirometry",
                "Your pneumonia is improving. You will move from drip antibiotics to tablets, restart your usual diabetes tablets, and learn more about checking your sugar levels before going home.",
                "Clinical improvement - afebrile and off oxygen; plan oral step-down", r1, true);
        plan(ctx, "george", 3, "Source control and antimicrobial therapy; restore perfusion (MAP >= 65); recover renal function.",
                "Meropenem renally adjusted; balanced crystalloids; noradrenaline titrated to MAP; avoid nephrotoxins; VTE prophylaxis.",
                "Hourly vitals and urine output; daily renal panel and lactate.", "Nil by mouth then renal diet", "Bed rest; early physiotherapy", null, null, null, false);
        plan(ctx, "irfan", 14, "Uncomplicated post-operative recovery; discharge on day 2.",
                "Complete 3 days IV ceftriaxone, multimodal analgesia, early mobilisation, port-site care.", "4-hourly observations, pain score.",
                "Soft diet as tolerated", "Mobilise from day 1", null, null, null, false);
        plan(ctx, "ananya", 2, "Rhythm control and evaluation of recurrent SVT.", "Metoprolol 25 mg BD; telemetry; echocardiogram; EP referral.",
                "Continuous telemetry", "Normal diet; avoid caffeine", "Ambulate on telemetry", null, null, null, false);
        plan(ctx, "deepa", 2, "Resolution of cellulitis.", "IV ceftriaxone, leg elevation, analgesia without NSAIDs; oral step-down at 72 h afebrile.",
                "6-hourly observations; mark erythema margins daily", "Normal diet", "Leg elevation when resting", null, null, null, false);
        plan(ctx, "aarav", 2, "Relieve bronchospasm; wean to inhaler with spacer; education before discharge.",
                "Salbutamol nebs 4-hourly then spaced; budesonide BD; prednisolone 5 days. O2 for SpO2 < 94%.",
                "4-hourly observations; SpO2 continuous overnight", "Normal diet, extra fluids", "Play in ward as tolerated",
                "Aarav is getting breathing medicines through a mask and a few days of steroid tablets. As he improves we will change to an inhaler with a spacer and show you how to use it at home.",
                null, null, true);
        plan(ctx, "vikram", 2, "Define coronary anatomy; optimise secondary prevention.",
                "Coronary angiography with contrast premedication (prednisolone + antihistamine); continue aspirin, clopidogrel, statin, beta blocker.",
                "Pre- and post-procedure observations; access site checks", "Low-fat diet; NBM 6 h before procedure", "Normal activity before procedure",
                "You will have a coronary angiogram to look at your heart arteries. You will receive medicines beforehand to prevent a reaction to the dye and must not eat for 6 hours before the test.",
                null, null, true);
    }

    // ================================================================== vitals

    private record Obs(Double temp, Integer pulse, Integer rr, Integer sbp, Integer dbp, Integer spo2, boolean o2, Integer pain,
                       Double glucose, VitalSign.Consciousness avpu) {
    }

    private Obs model(String key, double p, int i) {
        return switch (key) {
            case "ravi" -> new Obs(decay(39.2, 36.9, p, 3.2) + noise(0.15), (int) Math.round(decay(114, 80, p, 3) + noise(3)),
                    (int) Math.round(decay(25, 16, p, 3) + noise(1)), (int) Math.round(lerp(140, 126, p) + noise(4)),
                    (int) Math.round(lerp(88, 78, p) + noise(3)), (int) Math.round(decay(90.5, 97, p, 4) + noise(0.6)), p < 0.4,
                    (int) Math.max(0, Math.round(decay(4, 1, p, 3))), i % 2 == 0 ? decay(268, 152, p, 2.5) + noise(8) : null,
                    VitalSign.Consciousness.ALERT);
            case "george" -> new Obs(decay(39.9, 37.0, p, 4) + noise(0.15), (int) Math.round(decay(134, 86, p, 3.5) + noise(3)),
                    (int) Math.round(decay(33, 18, p, 4) + noise(1)), (int) Math.round(decay(76, 124, p, 4) + noise(4)),
                    (int) Math.round(decay(44, 72, p, 4) + noise(3)), (int) Math.min(99, Math.round(decay(86, 96, p, 4) + noise(0.6))), p < 0.6,
                    (int) Math.max(0, Math.round(decay(5, 1, p, 3))), decay(188, 132, p, 2) + noise(6),
                    p < 0.2 ? VitalSign.Consciousness.CONFUSED : VitalSign.Consciousness.ALERT);
            case "irfan" -> new Obs(37.3 + noise(0.3), (int) Math.round(decay(98, 78, p, 2.5) + noise(3)), 16 + (int) Math.round(noise(2)),
                    124 + (int) Math.round(noise(6)), 78 + (int) Math.round(noise(4)), 97 + (int) Math.round(noise(1)), false,
                    p < 0.55 && p > 0.3 ? 8 : (int) Math.max(1, Math.round(decay(6, 2, p, 2))), null, VitalSign.Consciousness.ALERT);
            case "ananya" -> new Obs(36.8 + noise(0.2), i == 0 ? 156 : (int) Math.round(76 + noise(6)), 16 + (int) Math.round(noise(2)),
                    i == 0 ? 104 : 112 + (int) Math.round(noise(5)), i == 0 ? 66 : 72 + (int) Math.round(noise(3)), 99, false, 0, null,
                    VitalSign.Consciousness.ALERT);
            case "deepa" -> new Obs(decay(38.7, 36.8, p, 3.5) + noise(0.15), (int) Math.round(decay(106, 76, p, 3) + noise(3)),
                    16 + (int) Math.round(noise(1.5)), 126 + (int) Math.round(noise(6)), 80 + (int) Math.round(noise(4)), 98, false,
                    (int) Math.max(0, Math.round(decay(6, 1, p, 2.5))), null, VitalSign.Consciousness.ALERT);
            case "aarav" -> new Obs(37.2 + noise(0.25), (int) Math.round(decay(128, 96, p, 3) + noise(3)), (int) Math.round(decay(29, 21, p, 3) + noise(1)),
                    104 + (int) Math.round(noise(5)), 64 + (int) Math.round(noise(4)), (int) Math.min(99, Math.round(decay(90, 97, p, 3.5) + noise(0.5))),
                    p < 0.35, null, null, VitalSign.Consciousness.ALERT);
            default -> new Obs(36.7 + noise(0.2), 70 + (int) Math.round(noise(4)), 15 + (int) Math.round(noise(1.5)), 136 + (int) Math.round(noise(6)),
                    84 + (int) Math.round(noise(4)), 98, false, 0, null, VitalSign.Consciousness.ALERT);
        };
    }

    private void vitalsHistory(DemoContext ctx, String key) {
        Admission a = adm(ctx, key);
        String nurse = wardNurse.get(key);
        double totalHours = Duration.between(a.getAdmittedAt(), Instant.now()).toMinutes() / 60.0;
        double step = key.equals("george") ? 4 : 5;
        int i = 0;
        for (double h = 0.75; h < totalHours - 0.75; h += step + (i % 2 == 0 ? 0.5 : -0.5)) {
            // Intentional observation gap for Ravi on day 2 (missing-data marker in the trend explorer).
            if (key.equals("ravi") && h > 30 && h < 46) {
                i++;
                continue;
            }
            double p = Math.min(1.0, h / Math.max(totalHours, 1));
            Obs o = model(key, p, i);
            // George: transient hypotension about 3 hours ago (open critical alert for the demo).
            if (key.equals("george") && Math.abs((totalHours - h) - 3.0) < step / 2) {
                o = new Obs(37.6, 112, 22, 79, 48, 95, false, 2, 146.0, VitalSign.Consciousness.ALERT);
            }
            Instant when = at(a, h);
            resolveStaleAlerts(ctx, key, a, when);
            Obs fo = o;
            boolean first = i == 0;
            BigDecimal weight = !first ? null : BigDecimal.valueOf(switch (key) {
                case "george" -> 64;
                case "aarav" -> 26;
                default -> 70;
            });
            ctx.actAs(nurse, () -> vitals.record(a.getId(), new VitalsRequest(when,
                    fo.temp() == null ? null : BigDecimal.valueOf(fo.temp()).setScale(1, RoundingMode.HALF_UP),
                    fo.pulse(), fo.rr(), fo.sbp(), fo.dbp(), fo.spo2(), fo.o2(), fo.pain(),
                    fo.glucose() == null ? null : BigDecimal.valueOf(fo.glucose()).setScale(1, RoundingMode.HALF_UP),
                    weight, fo.avpu(), first ? "Admission observations" : null)));
            i++;
        }
    }

    /**
     * As the simulated stay progresses, threshold alerts raised more than 6 h before the next observation set
     * are reviewed and resolved by the team (so later breaches raise fresh alerts rather than updating old ones).
     */
    private void resolveStaleAlerts(DemoContext ctx, String key, Admission a, Instant now) {
        em.flush();
        for (ClinicalAlert al : alertRepo.findByAdmissionIdOrderByRaisedAtDesc(a.getId())) {
            if (al.getAlertType() != ClinicalAlert.AlertType.VITAL_THRESHOLD || al.getStatus() == ClinicalAlert.Status.RESOLVED
                    || !al.getRaisedAt().isBefore(now.minus(Duration.ofHours(6)))) {
                continue;
            }
            boolean critical = al.getSeverity() == ClinicalAlert.Severity.CRITICAL;
            ctx.actAs(critical ? doctorOf.get(key) : wardNurse.get(key), () -> alerts.resolve(al.getId(), critical
                    ? "Reviewed at bedside; treatment escalated per plan. Patient re-assessed and improving."
                    : "Rechecked and escalated per plan; continuing observations."));
            al.setAcknowledgedAt(al.getRaisedAt().plus(Duration.ofMinutes(critical ? 9 : 22)));
            al.setResolvedAt(al.getRaisedAt().plus(Duration.ofMinutes(critical ? 150 : 200)));
            stamp(al.getId(), "ALERT_RESOLVED", al.getResolvedAt());
        }
    }

    /** Alerts whose last breach is older than 10 h were reviewed and resolved by the treating team. */
    private void resolveOldVitalAlerts(DemoContext ctx) {
        em.flush();
        Instant cutoff = hoursAgo(10);
        for (String key : List.of("ravi", "george", "irfan", "ananya", "deepa", "aarav", "vikram")) {
            UUID admissionId = ctx.admission(key);
            for (ClinicalAlert al : alertRepo.findByAdmissionIdOrderByRaisedAtDesc(admissionId)) {
                if (al.getAlertType() != ClinicalAlert.AlertType.VITAL_THRESHOLD || al.getStatus() == ClinicalAlert.Status.RESOLVED) {
                    continue;
                }
                Instant last = al.getSourceId() == null ? al.getRaisedAt()
                        : em.find(VitalSign.class, al.getSourceId()) == null ? al.getRaisedAt() : em.find(VitalSign.class, al.getSourceId()).getRecordedAt();
                String resolver = al.getSeverity() == ClinicalAlert.Severity.CRITICAL ? doctorOf.get(key) : wardNurse.get(key);
                if (last.isBefore(cutoff)) {
                    ctx.actAs(resolver, () -> alerts.resolve(al.getId(), al.getSeverity() == ClinicalAlert.Severity.CRITICAL
                            ? "Reviewed at bedside; treatment escalated per plan. Subsequent observations back within range."
                            : "Rechecked - values returning to range; continuing routine observations."));
                    Instant ack = al.getRaisedAt().plus(Duration.ofMinutes(al.getSeverity() == ClinicalAlert.Severity.CRITICAL ? 9 : 22));
                    al.setAcknowledgedAt(ack);
                    al.setResolvedAt(last.plus(Duration.ofMinutes(95)));
                    stamp(al.getId(), "ALERT_RESOLVED", al.getResolvedAt());
                } else if (al.getSeverity() == ClinicalAlert.Severity.WARNING) {
                    ctx.actAs(wardNurse.get(key), () -> alerts.acknowledge(al.getId()));
                    al.setAcknowledgedAt(al.getRaisedAt().plus(Duration.ofMinutes(15)));
                    stamp(al.getId(), "ALERT_ACKNOWLEDGED", al.getAcknowledgedAt());
                }
            }
        }
    }

    // ================================================================== investigations

    private ResultComponent rc(String name, String value, String unit, String range, LabResult.Flag flag) {
        BigDecimal num = null;
        try {
            num = new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            // textual result
        }
        return new ResultComponent(name, value, num, unit, range, flag, null);
    }

    private enum Stage { ORDERED, COLLECTED, RESULTED, REVIEWED, RELEASED }

    private UUID lab(DemoContext ctx, String key, String code, LabOrder.Priority priority, double orderedHoursAgo, Stage stage,
                     String clinicalInfo, List<ResultComponent> results, String reviewComment) {
        Admission a = adm(ctx, key);
        String doc = doctorOf.get(key);
        String nurse = wardNurse.get(key);
        ClinicalDtos.LabOrderView v = ctx.actAs(doc, () -> investigations.order(a.getId(),
                new LabOrderRequest(ctx.tariff(code), priority, clinicalInfo, null, null)));
        LabOrder o = labRepo.findById(v.id()).orElseThrow();
        Instant ordered = hoursAgo(orderedHoursAgo);
        o.setOrderedAt(ordered);
        o.setDueAt(ordered.plus(Duration.ofHours(priority == LabOrder.Priority.ROUTINE ? 8 : 2)));
        stamp(o.getId(), "INVESTIGATION_ORDERED", ordered);
        for (CareTask t : taskRepo.findBySourceTypeAndSourceIdAndStatusIn("LAB_ORDER", o.getId(), EnumSet.allOf(CareTask.Status.class))) {
            t.setCreatedAt(ordered);
            t.setDueAt(o.getDueAt());
            stamp(t.getId(), "CARE_TASK_CREATED", ordered);
        }
        em.flush();
        jdbc.update("UPDATE invoice_line_items SET posted_at = ?, service_date = ? WHERE source_id = ? AND source_type = 'LAB_ORDER'",
                Timestamp.from(ordered), java.sql.Date.valueOf(TimeUtil.localDate(ordered, ctx.organization().zone())), o.getId());
        if (stage == Stage.ORDERED) {
            return o.getId();
        }
        Instant collected = ordered.plus(Duration.ofMinutes(35));
        if (o.getCategory() == LabOrder.Category.LAB) {
            ctx.actAs(nurse, () -> investigations.collect(o.getId(), new CollectRequest(collected, null, null)));
            stamp(o.getId(), "SPECIMEN_COLLECTED", collected);
            for (CareTask t : taskRepo.findBySourceTypeAndSourceIdAndStatusIn("LAB_ORDER", o.getId(), EnumSet.of(CareTask.Status.DONE))) {
                t.setCompletedAt(collected);
                stamp(t.getId(), "CARE_TASK_DONE", collected);
            }
        }
        if (stage == Stage.COLLECTED) {
            return o.getId();
        }
        Instant resulted = ordered.plus(Duration.ofMinutes(o.getCategory() == LabOrder.Category.IMAGING ? 90 : 150));
        if (resulted.isAfter(Instant.now())) {
            resulted = Instant.now().minusSeconds(120);
        }
        Instant fr = resulted;
        ctx.actAs(doc, () -> investigations.enterResults(o.getId(), new ResultEntryRequest(results, LabResult.Status.FINAL, fr)));
        stamp(o.getId(), "RESULT_AVAILABLE", resulted);
        for (ClinicalAlert al : alertRepo.findBySourceTypeAndSourceIdAndStatusIn("LAB_ORDER", o.getId(), EnumSet.allOf(ClinicalAlert.Status.class))) {
            al.setRaisedAt(resulted);
            stamp(al.getId(), null, resulted);
        }
        if (stage == Stage.RESULTED) {
            return o.getId();
        }
        Instant reviewed = resulted.plus(Duration.ofMinutes(70));
        if (reviewed.isAfter(Instant.now())) {
            reviewed = Instant.now().minusSeconds(60);
        }
        ctx.actAs(doc, () -> investigations.review(o.getId(), reviewComment));
        for (LabResult r : resultRepo.findByLabOrderIdOrderByComponent(o.getId())) {
            r.setReviewedAt(reviewed);
        }
        for (ClinicalAlert al : alertRepo.findBySourceTypeAndSourceIdAndStatusIn("LAB_ORDER", o.getId(), EnumSet.allOf(ClinicalAlert.Status.class))) {
            al.setAcknowledgedAt(resulted.plus(Duration.ofMinutes(12)));
            al.setResolvedAt(reviewed);
        }
        stamp(o.getId(), "RESULT_REVIEWED", reviewed);
        if (stage == Stage.RELEASED) {
            Instant rel = reviewed.plus(Duration.ofMinutes(30));
            ctx.actAs(doc, () -> investigations.release(o.getId()));
            for (LabResult r : resultRepo.findByLabOrderIdOrderByComponent(o.getId())) {
                r.setReleasedAt(rel);
            }
            stamp(o.getId(), "RESULT_RELEASED", rel);
        }
        return o.getId();
    }

    private void investigationsSeed(DemoContext ctx) {
        LabResult.Flag N = LabResult.Flag.NORMAL;
        LabResult.Flag H = LabResult.Flag.HIGH;
        LabResult.Flag L = LabResult.Flag.LOW;
        // Ravi
        lab(ctx, "ravi", "LAB_CBC", LabOrder.Priority.URGENT, 99, Stage.RELEASED, "Fever, suspected pneumonia", List.of(
                rc("Haemoglobin", "13.1", "g/dL", "13.0-17.0", N), rc("Total leucocyte count", "16800", "/µL", "4000-11000", H),
                rc("Neutrophils", "86", "%", "40-75", H), rc("Platelets", "2.4", "lakh/µL", "1.5-4.5", N)),
                "Neutrophilic leucocytosis consistent with bacterial pneumonia.");
        lab(ctx, "ravi", "LAB_CRP", LabOrder.Priority.URGENT, 99, Stage.REVIEWED, null, List.of(rc("C-reactive protein", "148", "mg/L", "< 5", H)),
                "Marked inflammation; repeat in 72 h.");
        lab(ctx, "ravi", "LAB_BCX", LabOrder.Priority.URGENT, 98.5, Stage.REVIEWED, "Before first antibiotic dose", List.of(
                rc("Blood culture", "No growth after 48 hours", null, "No growth", N)), "No bacteraemia.");
        lab(ctx, "ravi", "IMG_CXR", LabOrder.Priority.URGENT, 98, Stage.RELEASED, null, List.of(
                new ResultComponent("Impression", "Right lower zone consolidation; no effusion.", null, null, null, LabResult.Flag.ABNORMAL,
                        "Findings in keeping with right lower lobe pneumonia.")),
                "Consistent with clinical diagnosis.");
        lab(ctx, "ravi", "LAB_HBA1C", LabOrder.Priority.ROUTINE, 30, Stage.RESULTED, "Diabetes control", List.of(
                rc("HbA1c", "8.9", "%", "< 5.7", H)), null);
        lab(ctx, "ravi", "LAB_CBC", LabOrder.Priority.ROUTINE, 3, Stage.COLLECTED, "Day 4 follow-up", List.of(), null);
        lab(ctx, "ravi", "LAB_RFT", LabOrder.Priority.ROUTINE, 1.5, Stage.ORDERED, "Before restarting metformin", List.of(), null);
        // George
        lab(ctx, "george", "LAB_BCX", LabOrder.Priority.STAT, 150, Stage.REVIEWED, "Septic shock", List.of(
                rc("Blood culture", "Escherichia coli isolated", null, "No growth", LabResult.Flag.ABNORMAL),
                rc("Sensitivity", "Sensitive: meropenem, amikacin; resistant: ceftriaxone, co-trimoxazole", null, null, LabResult.Flag.ABNORMAL)),
                "ESBL-producing E. coli - continue meropenem.");
        lab(ctx, "george", "LAB_UCX", LabOrder.Priority.STAT, 150, Stage.REVIEWED, null, List.of(
                rc("Urine culture", "E. coli > 10^5 CFU/mL", null, "< 10^4 CFU/mL", LabResult.Flag.ABNORMAL)), "Urinary source confirmed.");
        lab(ctx, "george", "LAB_RFT", LabOrder.Priority.STAT, 149, Stage.REVIEWED, null, List.of(
                rc("Creatinine", "3.4", "mg/dL", "0.7-1.3", LabResult.Flag.CRITICAL_HIGH), rc("Urea", "112", "mg/dL", "15-45", H),
                rc("Potassium", "5.1", "mmol/L", "3.5-5.1", N), rc("Sodium", "134", "mmol/L", "135-145", L)),
                "AKI on CKD - renal dosing, avoid nephrotoxins.");
        lab(ctx, "george", "LAB_CBC", LabOrder.Priority.ROUTINE, 75, Stage.REVIEWED, null, List.of(
                rc("Haemoglobin", "10.4", "g/dL", "13.0-17.0", L), rc("Total leucocyte count", "13200", "/µL", "4000-11000", H),
                rc("Platelets", "0.48", "lakh/µL", "1.5-4.5", LabResult.Flag.CRITICAL_LOW)),
                "Thrombocytopenia - hold enoxaparin while platelets < 50k.");
        UUID georgeRft = lab(ctx, "george", "LAB_RFT", LabOrder.Priority.URGENT, 5, Stage.COLLECTED, "Daily renal panel", List.of(), null);
        // Result for today's renal panel arrives through the DEVELOPMENT LIS adapter (critical potassium, unreviewed).
        LabOrder rft = labRepo.findById(georgeRft).orElseThrow();
        Instant lisAt = hoursAgo(2.5);
        ctx.actAs("dr_bose_icu", () -> investigations.ingestLisResult(new LisResultPayload(rft.getOrderNumber(), List.of(
                rc("Creatinine", "1.9", "mg/dL", "0.7-1.3", H), rc("Urea", "64", "mg/dL", "15-45", H),
                rc("Potassium", "6.4", "mmol/L", "3.5-5.1", LabResult.Flag.CRITICAL_HIGH), rc("Sodium", "137", "mmol/L", "135-145", N)),
                lisAt, "DEV-LIS-ANALYZER-01")));
        stamp(rft.getId(), "RESULT_AVAILABLE", lisAt);
        for (ClinicalAlert al : alertRepo.findBySourceTypeAndSourceIdAndStatusIn("LAB_ORDER", rft.getId(), AlertService.UNRESOLVED)) {
            al.setRaisedAt(lisAt);
            stamp(al.getId(), null, lisAt);
        }
        lab(ctx, "george", "LAB_CRP", LabOrder.Priority.ROUTINE, 1, Stage.ORDERED, "Trend inflammatory markers", List.of(), null);
        // Irfan
        lab(ctx, "irfan", "LAB_CBC", LabOrder.Priority.STAT, 49, Stage.REVIEWED, "Pre-operative", List.of(
                rc("Haemoglobin", "14.6", "g/dL", "13.0-17.0", N), rc("Total leucocyte count", "14100", "/µL", "4000-11000", H),
                rc("Platelets", "2.8", "lakh/µL", "1.5-4.5", N)), "Fit for surgery.");
        lab(ctx, "irfan", "IMG_USG_ABD", LabOrder.Priority.STAT, 48.5, Stage.RELEASED, null, List.of(
                new ResultComponent("Impression", "Non-compressible appendix 9 mm with periappendiceal fat stranding.", null, null, null,
                        LabResult.Flag.ABNORMAL, "Features of acute appendicitis.")), "Proceed to surgery.");
        lab(ctx, "irfan", "LAB_ELEC", LabOrder.Priority.ROUTINE, 10, Stage.RESULTED, "Post-op day 1", List.of(
                rc("Sodium", "138", "mmol/L", "135-145", N), rc("Potassium", "3.9", "mmol/L", "3.5-5.1", N)), null);
        // Ananya
        lab(ctx, "ananya", "LAB_TROP", LabOrder.Priority.URGENT, 25, Stage.REVIEWED, null, List.of(rc("Troponin I", "0.01", "ng/mL", "< 0.04", N)),
                "No myocardial injury.");
        lab(ctx, "ananya", "IMG_ECG", LabOrder.Priority.URGENT, 25.5, Stage.REVIEWED, null, List.of(
                new ResultComponent("Interpretation", "Sinus rhythm 78/min after conversion; no pre-excitation.", null, null, null, N, null)), null);
        lab(ctx, "ananya", "LAB_ELEC", LabOrder.Priority.ROUTINE, 24, Stage.REVIEWED, null, List.of(
                rc("Sodium", "139", "mmol/L", "135-145", N), rc("Potassium", "3.3", "mmol/L", "3.5-5.1", L)), "Replace potassium orally.");
        lab(ctx, "ananya", "IMG_ECHO", LabOrder.Priority.ROUTINE, 4, Stage.ORDERED, "Structural heart disease?", List.of(), null);
        // Deepa
        lab(ctx, "deepa", "LAB_CBC", LabOrder.Priority.URGENT, 118, Stage.RELEASED, null, List.of(
                rc("Haemoglobin", "12.2", "g/dL", "12.0-15.0", N), rc("Total leucocyte count", "13900", "/µL", "4000-11000", H)), "Mild leucocytosis.");
        lab(ctx, "deepa", "LAB_CRP", LabOrder.Priority.ROUTINE, 30, Stage.REVIEWED, null, List.of(rc("C-reactive protein", "18", "mg/L", "< 5", H)),
                "Falling - responding to treatment.");
        // Aarav
        lab(ctx, "aarav", "IMG_CXR", LabOrder.Priority.URGENT, 37, Stage.RELEASED, null, List.of(
                new ResultComponent("Impression", "Hyperinflated lungs; no consolidation or pneumothorax.", null, null, null, N, null)),
                "No pneumonia.");
        lab(ctx, "aarav", "LAB_CBC", LabOrder.Priority.ROUTINE, 6, Stage.RESULTED, null, List.of(
                rc("Haemoglobin", "12.4", "g/dL", "11.5-15.5", N), rc("Total leucocyte count", "11900", "/µL", "4500-13500", N),
                rc("Eosinophils", "9", "%", "1-6", H)), null);
        // Vikram
        lab(ctx, "vikram", "LAB_LIPID", LabOrder.Priority.ROUTINE, 70, Stage.RELEASED, null, List.of(
                rc("Total cholesterol", "236", "mg/dL", "< 200", H), rc("LDL cholesterol", "158", "mg/dL", "< 100", H),
                rc("HDL cholesterol", "38", "mg/dL", "> 40", L), rc("Triglycerides", "190", "mg/dL", "< 150", H)),
                "High LDL - high-intensity statin continued.");
        lab(ctx, "vikram", "IMG_ECG", LabOrder.Priority.ROUTINE, 70, Stage.REVIEWED, null, List.of(
                new ResultComponent("Interpretation", "Sinus rhythm 70/min, no ST changes.", null, null, null, N, null)), null);
        lab(ctx, "vikram", "LAB_RFT", LabOrder.Priority.ROUTINE, 3, Stage.COLLECTED, "Pre-contrast renal function", List.of(), null);
        // One cancelled order (charge reversed because no specimen was collected).
        Admission ravi = adm(ctx, "ravi");
        UUID dup = lab(ctx, "ravi", "LAB_LFT", LabOrder.Priority.ROUTINE, 20, Stage.ORDERED, null, List.of(), null);
        ctx.actAs("dr_mehta", () -> investigations.cancel(dup, "Duplicate request - LFT already done in emergency department"));
        stamp(dup, "INVESTIGATION_CANCELLED", hoursAgo(19.5));
        LabOrder cancelled = labRepo.findById(dup).orElseThrow();
        cancelled.setCancelledAt(hoursAgo(19.5));
        log.debug("Seeded investigations for {}", ravi.getAdmissionNumber());
    }

    // ================================================================== procedures

    private void proceduresSeed(DemoContext ctx) {
        Admission irfan = adm(ctx, "irfan");
        ClinicalDtos.ProcedureView appx = ctx.actAs("dr_khan", () -> procedures.plan(irfan.getId(),
                new ProcedureRequest("Laparoscopic appendectomy", ctx.tariff("PROC_APPENDECTOMY"), at(irfan, 10), "Consent obtained; NBM from admission")));
        Instant performed = at(irfan, 11);
        ctx.actAs("dr_khan", () -> procedures.complete(appx.id(), new CompleteProcedureRequest(performed, null, "General anaesthesia",
                "Acutely inflamed, non-perforated appendix; no pus or collection.", null, "Specimen sent for histopathology.")));
        ProcedureRecord p1 = procedureRepo.findById(appx.id()).orElseThrow();
        p1.setCreatedAt(at(irfan, 2));
        stamp(p1.getId(), "PROCEDURE_PLANNED", at(irfan, 2));
        stamp(p1.getId(), "PROCEDURE_COMPLETED", performed);
        ctx.actAs("dr_khan", () -> procedures.plan(irfan.getId(), new ProcedureRequest("Wound dressing - port sites", ctx.tariff("PROC_DRESSING"),
                Instant.now().plus(Duration.ofHours(5)), "Check port sites; change dressing if soaked")));

        Admission george = adm(ctx, "george");
        ClinicalDtos.ProcedureView cvc = ctx.actAs("dr_bose_icu", () -> procedures.plan(george.getId(),
                new ProcedureRequest("Central venous catheter insertion (right internal jugular)", ctx.tariff("PROC_CVC"), at(george, 1.5), null)));
        Instant cvcAt = at(george, 2);
        ctx.actAs("dr_bose_icu", () -> procedures.complete(cvc.id(), new CompleteProcedureRequest(cvcAt, null, "Local anaesthesia",
                "Ultrasound-guided, single pass; tip position confirmed on CXR.", null, null)));
        ProcedureRecord p2 = procedureRepo.findById(cvc.id()).orElseThrow();
        p2.setCreatedAt(at(george, 1.5));
        stamp(p2.getId(), "PROCEDURE_PLANNED", at(george, 1.5));
        stamp(p2.getId(), "PROCEDURE_COMPLETED", cvcAt);

        Admission vikram = adm(ctx, "vikram");
        ctx.actAs("dr_iyer", () -> procedures.plan(vikram.getId(), new ProcedureRequest("Coronary angiography (right radial approach)",
                ctx.tariff("PROC_ANGIO"), Instant.now().plus(Duration.ofHours(20)), "Contrast premedication 13 h, 7 h and 1 h before; NBM 6 h")));
        em.flush();
        jdbc.update("UPDATE invoice_line_items SET posted_at = ?, service_date = ? WHERE source_id = ? AND source_type = 'PROCEDURE'",
                Timestamp.from(performed), java.sql.Date.valueOf(TimeUtil.localDate(performed, ctx.organization().zone())), p1.getId());
        jdbc.update("UPDATE invoice_line_items SET posted_at = ?, service_date = ? WHERE source_id = ? AND source_type = 'PROCEDURE'",
                Timestamp.from(cvcAt), java.sql.Date.valueOf(TimeUtil.localDate(cvcAt, ctx.organization().zone())), p2.getId());
    }

    // ================================================================== MAR

    private void marHistory(DemoContext ctx) {
        Instant now = Instant.now();
        Instant horizon = mar.horizonEnd(ctx.orgId());
        for (String key : List.of("ravi", "george", "irfan", "ananya", "deepa", "aarav", "vikram", "fatima")) {
            Admission a = adm(ctx, key);
            List<MedicationOrder> orders = orderRepo.findByAdmissionIdAndStatusIn(a.getId(), EnumSet.of(MedicationOrder.Status.ACTIVE));
            for (MedicationOrder o : orders) {
                mar.generate(o, key.equals("fatima") ? now : o.getStartAt(), horizon);
            }
            em.flush();
            if (key.equals("fatima")) {
                continue;
            }
            String nurse = wardNurse.get(key);
            List<MedicationAdministration> past = new ArrayList<>(marRepo.findByAdmissionIdAndScheduledAtBetweenOrderByScheduledAtAsc(a.getId(),
                    a.getAdmittedAt().minus(Duration.ofDays(1)), now.minus(Duration.ofMinutes(75))));
            past.sort(Comparator.comparing(MedicationAdministration::getScheduledAt));
            // The most recent past dose of George's IV fluids is left unrecorded (overdue in the exception queue).
            UUID leaveOverdue = key.equals("george") ? past.stream()
                    .filter(m -> orderOf(m).getMedicationId().equals(ctx.medications.get("MED-NS500")))
                    .reduce((x, y) -> y).map(MedicationAdministration::getId).orElse(null) : null;
            for (MedicationAdministration m : past) {
                if (m.getStatus() != MedicationAdministration.Status.SCHEDULED || m.getId().equals(leaveOverdue)) {
                    continue;
                }
                MedicationOrder o = orderOf(m);
                String exception = exceptionFor(ctx, key, o, m, past);
                if (exception != null) {
                    recordException(ctx, key, nurse, m, exception);
                    continue;
                }
                Instant given = m.getScheduledAt().plus(Duration.ofMinutes(rnd.nextInt(26)));
                if (given.isAfter(now)) {
                    given = now.minusSeconds(60);
                }
                Instant g = given;
                String site = switch (o.getRoute()) {
                    case IV -> "Left forearm cannula";
                    case SC -> "Abdomen, " + (rnd.nextBoolean() ? "left" : "right") + " lower quadrant";
                    case IM -> "Right deltoid";
                    default -> null;
                };
                ctx.actAs(nurse, () -> mar.administer(m.getId(), new AdministerRequest(g, o.getDose().startsWith("per sliding")
                        ? (4 + rnd.nextInt(5)) + " IU (sliding scale)" : null, null, site, null, null, null)));
                m.setRecordedAt(given.plus(Duration.ofMinutes(3)));
                stamp(m.getId(), "MEDICATION_ADMINISTERED", given);
            }
            // PRN doses given for fever / pain earlier in the stay.
            for (MedicationOrder o : orders) {
                if (o.getFrequency() != MedicationOrder.Frequency.PRN) {
                    continue;
                }
                double[] when = switch (key) {
                    case "ravi" -> new double[]{3, 9, 20};
                    case "george" -> new double[]{4, 15};
                    case "irfan" -> new double[]{14};
                    case "deepa" -> new double[]{6, 30};
                    default -> new double[0];
                };
                for (double h : when) {
                    Instant at = at(a, h);
                    if (at.isBefore(o.getStartAt()) || at.isAfter(now)) {
                        continue;
                    }
                    String reason = o.getIndication() != null && o.getIndication().toLowerCase().contains("nausea")
                            ? "Nausea after mobilising" : "Temperature 38.6 °C, patient uncomfortable";
                    var dose = ctx.actAs(nurse, () -> mar.recordAdHoc(o.getId(), new PrnDoseRequest(at, o.getDose() + " " + o.getDoseUnit(),
                            null, null, reason, null, null)));
                    stamp(dose.id(), "MEDICATION_PRN_ADMINISTERED", at);
                }
            }
        }
    }

    /** Medicine information shared with patients / caregivers in the portal (orders with patient instructions). */
    private void releaseMedicationInfo(DemoContext ctx) {
        for (String key : List.of("ravi", "aarav", "vikram")) {
            Admission a = adm(ctx, key);
            for (MedicationOrder o : orderRepo.findByAdmissionIdAndStatusIn(a.getId(), EnumSet.of(MedicationOrder.Status.ACTIVE))) {
                if (o.getPatientInstructions() == null || o.isReleasedToPatient()) {
                    continue;
                }
                ctx.actAs(doctorOf.get(key), () -> medicationOrders.release(o.getId(), null));
                stamp(o.getId(), "MEDICATION_RELEASED", o.getStartAt().plus(Duration.ofHours(3)));
            }
        }
    }

    private MedicationOrder orderOf(MedicationAdministration m) {
        return orderRepo.findById(m.getOrderId()).orElseThrow();
    }

    /** Deterministic, clinically plausible exceptions at specific points in the history. */
    private String exceptionFor(DemoContext ctx, String key, MedicationOrder o, MedicationAdministration m, List<MedicationAdministration> past) {
        List<MedicationAdministration> same = past.stream().filter(x -> x.getOrderId().equals(o.getId())).toList();
        int idx = same.indexOf(m);
        UUID med = o.getMedicationId();
        if (key.equals("ravi") && med.equals(ctx.medications.get("MED-AZI500")) && idx == 1) {
            return "REFUSED|Nausea after breakfast; patient declined, will retry with food.|RESOLVED|Discussed with Dr. Mehta - next dose given with food, tolerated.";
        }
        if (key.equals("irfan") && med.equals(ctx.medications.get("MED-TRAM50")) && idx == 0) {
            return "MISSED|Patient in operating theatre at scheduled time.|OPEN|";
        }
        if (key.equals("george") && med.equals(ctx.medications.get("MED-ENOX40")) && idx == same.size() - 1 && idx > 0) {
            return "HELD|Platelets 48,000/µL - held per order instructions (hold if platelets < 50); Dr. Bose informed.|IN_PROGRESS|Awaiting repeat platelet count before next dose.";
        }
        if (key.equals("vikram") && med.equals(ctx.medications.get("MED-METO25")) && idx == same.size() - 1 && idx > 0) {
            return "HELD|Heart rate 52/min - below hold parameter (HR < 55).|OPEN|";
        }
        if (key.equals("aarav") && med.equals(ctx.medications.get("MED-BUDNEB")) && idx == 0) {
            return "DELAYED|Child asleep after salbutamol nebulisation; parent requested delay.|RESOLVED|Given 45 minutes later once awake.";
        }
        return null;
    }

    private void recordException(DemoContext ctx, String key, String nurse, MedicationAdministration m, String spec) {
        String[] parts = spec.split("\\|", -1);
        MedicationAdministration.Status status = MedicationAdministration.Status.valueOf(parts[0]);
        Instant at = m.getScheduledAt().plus(Duration.ofMinutes(10 + rnd.nextInt(15)));
        ctx.actAs(nurse, () -> mar.recordException(m.getId(), new ExceptionRequest(status, parts[1], null, null)));
        m.setRecordedAt(at);
        stamp(m.getId(), "MEDICATION_" + status.name(), at);
        if (!parts[2].isEmpty() && !parts[2].equals("OPEN")) {
            MedicationAdministration.FollowUpStatus fu = MedicationAdministration.FollowUpStatus.valueOf(parts[2]);
            ctx.actAs(nurse, () -> mar.updateFollowUp(m.getId(), new FollowUpRequest(fu, parts[3])));
            m.setFollowUpAt(at.plus(Duration.ofMinutes(90)));
            stamp(m.getId(), "MEDICATION_EXCEPTION_RESOLVED", m.getFollowUpAt());
        }
    }

    // ================================================================== care tasks

    private void task(DemoContext ctx, String key, String user, CareTask.TaskType type, String title, String desc, CareTask.Priority pr,
                      double dueInHours, double createdHoursAgo, String completeNotes, double completedHoursAgo) {
        Admission a = adm(ctx, key);
        Instant due = Instant.now().plus(Duration.ofMinutes((long) (Math.max(dueInHours, -0.9) * 60)));
        ClinicalDtos.TaskView v = ctx.actAs(user, () -> tasks.create(a.getId(), new TaskRequest(type, title, desc, pr, due, null)));
        CareTask t = taskRepo.findById(v.id()).orElseThrow();
        t.setCreatedAt(hoursAgo(createdHoursAgo));
        if (dueInHours < -0.9) {
            t.setDueAt(Instant.now().plus(Duration.ofMinutes((long) (dueInHours * 60))));
        }
        stamp(t.getId(), "CARE_TASK_CREATED", t.getCreatedAt());
        if (completeNotes != null) {
            ctx.actAs(user, () -> tasks.complete(t.getId(), completeNotes));
            t.setCompletedAt(hoursAgo(completedHoursAgo));
            stamp(t.getId(), "CARE_TASK_DONE", t.getCompletedAt());
        }
    }

    private void tasksSeed(DemoContext ctx) {
        task(ctx, "irfan", "nurse_rahul", CareTask.TaskType.WOUND_CARE, "Inspect port sites and change dressing", "Report any discharge or redness",
                CareTask.Priority.NORMAL, 2, 10, null, 0);
        task(ctx, "irfan", "nurse_rahul", CareTask.TaskType.MOBILISATION, "Assist first mobilisation after surgery", null,
                CareTask.Priority.HIGH, -20, 24, "Walked to bathroom with assistance; no dizziness.", 18);
        task(ctx, "irfan", "nurse_rahul", CareTask.TaskType.MOBILISATION, "Walk in corridor x3 today", null, CareTask.Priority.NORMAL, 6, 3, null, 0);
        task(ctx, "george", "nurse_rahul", CareTask.TaskType.HYGIENE, "2-hourly repositioning and pressure-area check", "Braden 13 - high risk",
                CareTask.Priority.URGENT, -0.5, 6, null, 0);
        task(ctx, "george", "nurse_rahul", CareTask.TaskType.OBSERVATION, "Hourly BP x4 after hypotensive episode", "Escalate SBP < 90",
                CareTask.Priority.HIGH, 1, 2.5, null, 0);
        task(ctx, "ravi", "nurse_priya", CareTask.TaskType.OBSERVATION, "Capillary glucose before meals and at bedtime", null,
                CareTask.Priority.NORMAL, 1.5, 30, null, 0);
        task(ctx, "ravi", "nurse_priya", CareTask.TaskType.EDUCATION, "Incentive spirometry teaching", null, CareTask.Priority.LOW,
                -30, 50, "Patient using spirometer 10x/hour.", 40);
        task(ctx, "aarav", "nurse_anita", CareTask.TaskType.EDUCATION, "Inhaler with spacer technique - teach parent and child", null,
                CareTask.Priority.HIGH, 4, 5, null, 0);
        task(ctx, "vikram", "nurse_priya", CareTask.TaskType.OTHER, "Pre-angiography checklist (consent, NBM, cannula, premedication)",
                "Angiography planned tomorrow morning", CareTask.Priority.HIGH, 14, 2, null, 0);
        task(ctx, "deepa", "nurse_priya", CareTask.TaskType.DISCHARGE_PREP, "Discharge medicines counselling", "Oral cephalexin course; avoid NSAIDs",
                CareTask.Priority.NORMAL, 3, 4, null, 0);
        task(ctx, "ananya", "nurse_fatima", CareTask.TaskType.EDUCATION, "Teach vagal manoeuvres", null, CareTask.Priority.NORMAL,
                -12, 20, "Patient demonstrated modified Valsalva correctly.", 14);
    }

    // ================================================================== escalations

    private void escalationsSeed(DemoContext ctx) {
        Admission george = adm(ctx, "george");
        ClinicalDtos.AlertView e1 = ctx.actAs("nurse_rahul", () -> alerts.escalate(george.getId(), new EscalationRequest(
                "Increasing oxygen requirement and new confusion", "SpO2 88% on 4 L, RR 30, GCS 13 (was 15)", ClinicalDtos.Urgency.EMERGENCY, null)));
        ClinicalAlert a1 = alertRepo.findById(e1.id()).orElseThrow();
        a1.setRaisedAt(at(george, 5));
        stamp(a1.getId(), "ALERT_ESCALATION", a1.getRaisedAt());
        ctx.actAs("dr_bose_icu", () -> alerts.resolve(a1.getId(), "Reviewed within 10 min: increased FiO2, fluid bolus, noradrenaline started."));
        a1.setAcknowledgedAt(at(george, 5.1));
        a1.setResolvedAt(at(george, 5.6));
        stamp(a1.getId(), "ALERT_RESOLVED", a1.getResolvedAt());

        Admission aarav = adm(ctx, "aarav");
        ClinicalDtos.AlertView e2 = ctx.actAs("nurse_anita", () -> alerts.escalate(aarav.getId(), new EscalationRequest(
                "Wheeze recurring 2 hours after nebulisation", "RR 26, SpO2 93% in air, mild subcostal recession", ClinicalDtos.Urgency.URGENT, null)));
        ClinicalAlert a2 = alertRepo.findById(e2.id()).orElseThrow();
        a2.setRaisedAt(hoursAgo(1.2));
        stamp(a2.getId(), "ALERT_ESCALATION", a2.getRaisedAt());
        ctx.actAs("dr_rao_paeds", () -> alerts.acknowledge(a2.getId()));
        a2.setAcknowledgedAt(hoursAgo(1.0));
        stamp(a2.getId(), "ALERT_ACKNOWLEDGED", a2.getAcknowledgedAt());
    }

    // ================================================================== handover

    private void handoverSeed(DemoContext ctx) {
        UUID mwa = ctx.wards.get("MWA");
        java.time.LocalDate today = TimeUtil.localDate(Instant.now(), ctx.organization().zone());
        // Yesterday evening: Priya -> Leela (acknowledged).
        ClinicalDtos.HandoverView h1 = ctx.actAs("nurse_priya", () -> handovers.create(new HandoverCreateRequest(mwa, today.minusDays(1), Handover.Shift.EVENING)));
        ctx.actAs("nurse_priya", () -> handovers.submit(h1.id(), ctx.user("nurse_leela").getId()));
        ctx.actAs("nurse_leela", () -> handovers.acknowledge(h1.id(), "Received at bedside; all patients reviewed."));
        Handover hh1 = handoverRepo.findById(h1.id()).orElseThrow();
        hh1.setCreatedAt(hoursAgo(15));
        hh1.setSubmittedAt(hoursAgo(14.5));
        hh1.setAcknowledgedAt(hoursAgo(14.2));
        // This morning: Leela (night) -> Priya, submitted but not yet acknowledged.
        ClinicalDtos.HandoverView h2 = ctx.actAs("nurse_leela", () -> handovers.create(new HandoverCreateRequest(mwa, today, Handover.Shift.NIGHT)));
        ClinicalDtos.HandoverView edited = ctx.actAs("nurse_leela", () -> handovers.update(h2.id(), new ClinicalDtos.HandoverUpdateRequest(
                "Quiet night overall. Bed 202-C (Deepa) for discharge today - medicines counselling pending. Fatima Begum (awaiting bed) "
                        + "expected on the ward this morning from emergency.", null, null, h2.version())));
        ctx.actAs("nurse_leela", () -> handovers.submit(edited.id(), ctx.user("nurse_priya").getId()));
        Handover hh2 = handoverRepo.findById(h2.id()).orElseThrow();
        hh2.setCreatedAt(hoursAgo(2.0));
        hh2.setSubmittedAt(hoursAgo(1.6));
        em.flush();
        jdbc.update("UPDATE notifications SET created_at = ? WHERE entity_id = ?", Timestamp.from(hoursAgo(1.6)), h2.id());
        jdbc.update("UPDATE notifications SET created_at = ?, read_at = ? WHERE entity_id = ?",
                Timestamp.from(hoursAgo(14.5)), Timestamp.from(hoursAgo(14.2)), h1.id());
    }

    // ================================================================== draft

    private void draftSeed(DemoContext ctx) {
        Admission ravi = adm(ctx, "ravi");
        ClinicalDtos.DraftView d = ctx.actAs("dr_mehta", () -> drafts.generate(ravi.getId(), AiDraft.DraftType.PROGRESS_SUMMARY));
        ctx.actAs("dr_mehta", () -> drafts.accept(d.id(), "Progress summary - day 4 (reviewed draft)", null));
        AiDraft draft = draftRepo.findById(d.id()).orElseThrow();
        draft.setCreatedAt(hoursAgo(6.5));
        draft.setReviewedAt(hoursAgo(6.2));
        ClinicalNote published = noteRepo.findById(draft.getPublishedNoteId()).orElseThrow();
        published.setAuthoredAt(hoursAgo(6.2));
        stamp(published.getId(), null, hoursAgo(6.2));
    }

    // ================================================================== notifications

    /** Aligns notification timestamps with the (backdated) events they announce; older ones are marked read. */
    private void backdateNotifications(DemoContext ctx) {
        em.flush();
        jdbc.update("""
                UPDATE notifications n SET created_at = t.at,
                       read_at = CASE WHEN t.at < now() - interval '8 hours' THEN t.at + interval '25 minutes' ELSE n.read_at END
                FROM (SELECT entity_id, max(occurred_at) AS at FROM timeline_events
                      WHERE organization_id = ? AND entity_id IS NOT NULL GROUP BY entity_id) t
                WHERE n.entity_id = t.entity_id AND n.organization_id = ? AND n.created_at >= ?
                  AND n.entity_type <> 'HANDOVER'
                """, ctx.orgId(), ctx.orgId(), Timestamp.from(seedStart));
        jdbc.update("""
                UPDATE notifications n SET created_at = a.raised_at,
                       read_at = CASE WHEN a.status <> 'OPEN' THEN coalesce(a.acknowledged_at, a.raised_at + interval '10 minutes') ELSE NULL END,
                       acknowledged_at = CASE WHEN a.status <> 'OPEN' AND n.requires_ack THEN a.acknowledged_at ELSE NULL END
                FROM clinical_alerts a
                WHERE n.entity_id = a.id AND n.organization_id = ?
                """, ctx.orgId());
        Map<String, Long> counts = new HashMap<>();
        counts.put("notes", noteRepo.count());
        counts.put("alerts", alertRepo.count());
        log.info("Clinical demo data: {}", counts);
    }
}
