package com.careflow.hms.discharge;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.billing.ChargeService;
import com.careflow.hms.billing.Invoice;
import com.careflow.hms.billing.InvoiceLineItem;
import com.careflow.hms.billing.InvoiceRepository;
import com.careflow.hms.billing.LedgerService;
import com.careflow.hms.clinical.LabOrder;
import com.careflow.hms.clinical.LabOrderRepository;
import com.careflow.hms.common.Money;
import com.careflow.hms.discharge.DischargeDtos.ClinicalAction;
import com.careflow.hms.discharge.DischargeDtos.ClinicalDecisionRequest;
import com.careflow.hms.discharge.DischargeDtos.DischargeDetail;
import com.careflow.hms.discharge.DischargeDtos.FollowUpOutcomeRequest;
import com.careflow.hms.discharge.DischargeDtos.FollowUpRequest;
import com.careflow.hms.discharge.DischargeDtos.FollowUpView;
import com.careflow.hms.discharge.DischargeDtos.InitiateRequest;
import com.careflow.hms.discharge.DischargeDtos.StepUpdateRequest;
import com.careflow.hms.discharge.DischargeDtos.SummaryEnvelope;
import com.careflow.hms.discharge.DischargeDtos.SummaryRequest;
import com.careflow.hms.org.SequenceService;
import com.careflow.hms.seed.DemoContext;
import com.careflow.hms.seed.DemoSeedContributor;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Demo data for discharge and continuity of care (clearly fictional). Uses the real services so that steps,
 * blockers, timeline, notifications and the development outbox are populated exactly as in production use,
 * then backdates timestamps to tell a realistic story:
 * <ul>
 *   <li>Deepa Krishnan - discharge in progress: clinically authorized by Dr Mehta, pharmacy reconciliation done,
 *       follow-up booked, summary still a draft, nursing education (or an unreviewed result) and billing pending.</li>
 *   <li>Lakshmi Pillai, Sanjay Patel, Rohan Desai - completed discharges with FINAL released summaries and
 *       follow-ups (upcoming cardiology / surgical reviews; Rohan: one completed, one missed).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DischargeDemoSeed implements DemoSeedContributor {

    private final DischargeWorkflowService workflowService;
    private final DischargeSummaryService summaryService;
    private final FollowUpService followUpService;
    private final DischargeWorkflowRepository workflows;
    private final DischargeStepRepository steps;
    private final DischargeStepTemplateRepository templates;
    private final DischargeSummaryRepository summaries;
    private final FollowUpRepository followUps;
    private final AdmissionRepository admissions;
    private final InvoiceRepository invoices;
    private final LedgerService ledger;
    private final ChargeService charges;
    private final LabOrderRepository labOrders;
    private final SequenceService sequences;
    private final TimelineService timeline;
    private final JdbcTemplate jdbc;
    private final EntityManager em;

    @Override
    public int order() {
        return 450;
    }

    @Override
    public String name() {
        return "Discharge workflows, summaries and follow-ups";
    }

    @Override
    public void seed(DemoContext ctx) {
        seedDeepa(ctx);
        seedLakshmi(ctx);
        seedSanjay(ctx);
        seedRohan(ctx);
        em.flush();
        ctx.log.add("discharge seeded");
    }

    // ------------------------------------------------------------------ Deepa: discharge in progress

    private void seedDeepa(DemoContext ctx) {
        UUID adm = ctx.admission("deepa");
        Admission a = admissions.findById(adm).orElseThrow();
        if (a.getStatus() != Admission.Status.ADMITTED) {
            log.info("Deepa's admission is {}, skipping discharge demo workflow", a.getStatus());
            return;
        }
        ZoneId zone = ctx.organization().zone();
        Instant planned = LocalDate.now(zone).plusDays(1).atTime(11, 0).atZone(zone).toInstant();
        Instant initiated = DemoContext.hoursAgo(20);
        Instant authorized = DemoContext.hoursAgo(5.5);
        Instant medRec = DemoContext.hoursAgo(3.2);
        Instant fuAt = DemoContext.hoursAgo(4.6);
        Instant summaryAt = DemoContext.hoursAgo(2.1);
        Instant eduAt = DemoContext.hoursAgo(0.8);

        DischargeDetail d = ctx.actAs("dr_mehta", () -> workflowService.initiate(adm,
                new InitiateRequest(planned, "Afebrile and cellulitis settling - plan discharge tomorrow morning")));
        UUID wf = d.workflow().id();

        ctx.actAs("dr_mehta", () -> workflowService.clinicalDecision(wf, new ClinicalDecisionRequest(ClinicalAction.AUTHORIZE,
                "Afebrile for more than 48 hours; erythema receding well within the marked margins; tolerating oral cephalexin "
                        + "and walking comfortably. Clinically fit for discharge home once nursing education is complete.")));

        DischargeStep rec = step(wf, "MED_RECONCILIATION");
        if (rec != null) {
            ctx.actAs("pharmacist", () -> workflowService.updateStep(wf, rec.getId(), new StepUpdateRequest(
                    DischargeStep.Status.COMPLETED, null, null, null, null, null,
                    "IV ceftriaxone stopped after 5 days. Take-home: cephalexin 500 mg four times a day for 7 days; "
                            + "paracetamol when required. NSAID allergy (bronchospasm) re-confirmed - no ibuprofen.",
                    null, false, rec.getVersion())));
        }

        FollowUpView fu = ctx.actAs("dr_mehta", () -> followUpService.create(adm, new FollowUpRequest(
                FollowUp.FollowUpType.APPOINTMENT, "Cellulitis review - Internal Medicine OPD",
                "Bring this summary and your medicines. We will check the leg and confirm when the antibiotics can stop. "
                        + "Keep the leg raised when sitting until then.",
                LocalDate.now(zone).plusDays(7).atTime(10, 30).atZone(zone).toInstant(), ctx.departments.get("GEN_MED"),
                ctx.user("dr_mehta").getId(), ctx.user("dr_mehta").getId(), "OPD Block A, Room 12", null, true, null)));

        SummaryEnvelope env = ctx.actAs("dr_mehta", () -> summaryService.createDraft(adm));
        SummaryEnvelope draft = env;
        ctx.actAs("dr_mehta", () -> summaryService.update(adm, new SummaryRequest(
                "Fever with painful redness and swelling of the left lower leg for 3 days; no injury recalled. "
                        + "Known mild intermittent asthma. Severe NSAID allergy (bronchospasm).",
                "- Cellulitis of the left lower limb (L03.116) - primary\n- Bronchial asthma, mild intermittent - stable",
                "IV ceftriaxone 1 g once daily for 5 days with leg elevation. Erythema margins were marked on admission and "
                        + "receded from day 3; fever settled within 48 hours. Paracetamol for pain (NSAIDs avoided because of allergy). "
                        + "Switched to oral cephalexin on day 5.",
                null, draft.summary().relevantResults(), null, draft.summary().dischargeMedications(),
                "- Keep the leg raised on a pillow when sitting or lying down.\n- Complete the full 7-day course of cephalexin, even if the leg looks better.\n"
                        + "- Do not take ibuprofen or any other NSAID painkiller; paracetamol is safe for you.",
                draft.summary().followUpPlan(), null, null, draft.summary().version())));

        boolean unreviewedResult = !labOrders.findByAdmissionIdAndStatusIn(adm, EnumSet.of(LabOrder.Status.ORDERED,
                LabOrder.Status.COLLECTED, LabOrder.Status.IN_PROGRESS, LabOrder.Status.RESULTED)).isEmpty();
        DischargeStep edu = step(wf, "NURSING_EDUCATION");
        if (edu != null) {
            ctx.actAs("nurse_priya", () -> workflowService.updateStep(wf, edu.getId(), new StepUpdateRequest(
                    unreviewedResult ? DischargeStep.Status.COMPLETED : DischargeStep.Status.IN_PROGRESS,
                    DischargeStep.Priority.HIGH, ctx.user("nurse_priya").getId(), null, null,
                    unreviewedResult ? null : "Teach-back with Deepa and her husband after the 14:00 dose: antibiotic timing, leg elevation, warning signs",
                    unreviewedResult ? "Education completed with patient and husband using teach-back."
                            : "Antibiotic schedule and leg elevation explained this morning. Husband arriving at 14:00 for the teach-back session.",
                    null, false, edu.getVersion())));
        }

        // Unpaid balance: only when no charges have been posted for this stay yet (e.g. no billing demo data).
        Invoice inv = invoices.findActiveByAdmission(adm).orElse(null);
        if (inv != null && inv.getStatus() == Invoice.Status.OPEN && ledger.balance(inv.getId()).signum() <= 0) {
            ctx.actAs("billing", () -> {
                charge(adm, ctx.tariff("CONS_DAILY"), 5, "Daily doctor visits (5 days)");
                charge(adm, ctx.tariff("NURSING_DAILY"), 5, "Nursing care (5 days)");
                charge(adm, ctx.tariff("PROC_DRESSING"), 3, "Leg dressing and limb care");
            });
        }

        // Backdate the story.
        em.flush();
        DischargeWorkflow w = workflows.findById(wf).orElseThrow();
        w.setInitiatedAt(initiated);
        w.setClinicalAuthorizedAt(authorized);
        for (DischargeStep s : steps.findByWorkflowIdOrderByStepOrder(wf)) {
            Instant at = switch (s.getTemplateCode()) {
                case "CLINICAL_DECISION" -> authorized;
                case "MED_RECONCILIATION" -> medRec;
                case "FOLLOW_UP" -> fuAt;
                case "NURSING_EDUCATION" -> eduAt;
                case "CLINICAL_REVIEWS", "RESULTS_REVIEWED", "DISCHARGE_PRESCRIPTIONS" -> initiated.plusSeconds(120);
                default -> initiated;
            };
            s.setUpdatedAt(at);
            if (s.getCompletedAt() != null) {
                s.setCompletedAt(at);
            }
        }
        followUps.findById(fu.id()).ifPresent(f -> {
            f.setCreatedAt(fuAt);
            f.setUpdatedAt(fuAt);
            if (f.getPatientNotifiedAt() != null) {
                f.setPatientNotifiedAt(fuAt.plusSeconds(5));
            }
        });
        summaries.findByAdmissionId(adm).ifPresent(s -> {
            s.setPreparedAt(summaryAt);
            s.setUpdatedAt(summaryAt.plusSeconds(900));
        });
        em.flush();
        retime(wf, "DISCHARGE_INITIATED", initiated);
        retime(wf, "DISCHARGE_CLINICALLY_AUTHORIZED", authorized);
        retimeEntity(fu.id(), fuAt);
        summaries.findByAdmissionId(adm).ifPresent(s -> retimeEntity(s.getId(), summaryAt));
        if (rec != null) {
            retimeEntity(rec.getId(), medRec);
        }
        if (edu != null) {
            retimeEntity(edu.getId(), eduAt);
        }
        jdbc.update("UPDATE notifications SET created_at = ? WHERE dedupe_key LIKE ?", ts(initiated.plusSeconds(30)), "DISCHARGE_STARTED:" + wf + "%");
        jdbc.update("UPDATE notifications SET created_at = ? WHERE dedupe_key LIKE ?", ts(authorized.plusSeconds(30)), "DISCHARGE_AUTHORIZED:" + wf + "%");
        jdbc.update("UPDATE dev_outbox SET created_at = ? WHERE body LIKE ? AND created_at > now() - interval '10 minutes'",
                ts(fuAt.plusSeconds(5)), "%Cellulitis review%");
    }

    private void charge(UUID admissionId, UUID tariffId, int qty, String description) {
        charges.postCharge(ChargeService.ChargeRequest.builder().admissionId(admissionId).tariffId(tariffId)
                .quantity(BigDecimal.valueOf(qty)).description(description).sourceType(InvoiceLineItem.SourceType.MANUAL)
                .reason("Inpatient services").build());
    }

    // ------------------------------------------------------------------ completed discharges

    private void seedLakshmi(DemoContext ctx) {
        UUID adm = ctx.admission("lakshmi");
        Completed c = completed(ctx, "lakshmi", "dr_iyer", "nurse_fatima", false,
                "Diuresed to dry weight; breathless only on moderate exertion; rate-controlled AF. Fit for discharge home with heart failure follow-up.",
                "Insurance claim under review with the TPA; family to settle any shortfall after the decision. Authorized by accounts supervisor.");
        if (c == null) {
            return;
        }
        summary(ctx, "dr_iyer", adm, c,
                "Progressive breathlessness on minimal exertion for one week, orthopnoea and bilateral ankle swelling. "
                        + "Known heart failure with reduced ejection fraction (EF 35%) and atrial fibrillation on anticoagulation.",
                "- Acute decompensated heart failure with reduced ejection fraction (I50.21) - primary\n"
                        + "- Atrial fibrillation, rate controlled (I48.91)\n- Hypokalaemia during diuresis (E87.6) - corrected",
                "Treated with IV furosemide 40 mg twice daily for 4 days with a net negative fluid balance of about 4.5 litres; "
                        + "weight fell from 68.2 kg to 63.9 kg. Oxygen weaned to room air on day 3. Beta-blocker continued and up-titrated "
                        + "before discharge. Potassium replaced orally. Anticoagulation continued throughout. Heart failure education given "
                        + "on fluid and salt restriction and daily weights.",
                "- 2D echocardiography (day 2): LVEF 35%, moderate functional mitral regurgitation, no pericardial effusion",
                "- NT-proBNP 4,850 pg/mL on admission; 1,620 pg/mL at discharge\n- Serum potassium 3.2 mmol/L on day 3; 4.3 mmol/L at discharge\n"
                        + "- Creatinine stable at 1.1 mg/dL",
                "Comfortable at rest on room air (SpO2 96%). Chest clear; mild residual ankle oedema. Pulse 72/min (AF, controlled), "
                        + "BP 112/70 mmHg. Discharge weight 63.9 kg. Walking independently.",
                "- Metoprolol 25 mg tablet - one tablet twice a day. Check your pulse; call us if it is below 55.\n"
                        + "- Atorvastatin 40 mg tablet - one tablet at bedtime.\n"
                        + "- Continue your usual anticoagulant exactly as before; do not stop it without your cardiologist's advice.",
                "- Weigh yourself every morning after passing urine and before breakfast; write it down.\n"
                        + "- Keep fluids to about 1.5 litres a day and avoid added salt, pickles and papad.\n"
                        + "- Walk short distances at home and increase slowly; rest if you feel breathless.\n"
                        + "- Bring your weight diary and all your medicines to the clinic.",
                "- Cardiology OPD review with Dr. Kavya Iyer, about two weeks after discharge (kidney function and electrolytes "
                        + "30 minutes before the visit).",
                "- Weight gain of more than 2 kg in 3 days\n- Breathlessness at rest, or needing more pillows to sleep\n"
                        + "- Chest pain, fainting, or a very fast or very slow pulse\n- Increasing swelling of the legs or tummy\n"
                        + "Call the hospital or come to the Emergency Department straight away.");
        ZoneId zone = ctx.organization().zone();
        ctx.actAs("dr_iyer", () -> {
            followUpService.create(adm, new FollowUpRequest(FollowUp.FollowUpType.APPOINTMENT, "Heart failure review - Cardiology OPD",
                    "Bring your weight diary, BP readings and all medicines. Please give blood for kidney function and electrolytes "
                            + "at the lab counter 30 minutes before your appointment. Fasting is not needed.",
                    LocalDate.now(zone).plusDays(3).atTime(11, 0).atZone(zone).toInstant(), ctx.departments.get("CARD"),
                    ctx.user("dr_iyer").getId(), ctx.user("dr_iyer").getId(), "Cardiology OPD, 3rd floor, Room 305", null, true, null));
            followUpService.create(adm, new FollowUpRequest(FollowUp.FollowUpType.INSTRUCTION, "Daily weight and fluid diary",
                    "Weigh yourself every morning and keep fluids to about 1.5 litres a day. Call the cardiology nurse line if your "
                            + "weight goes up by more than 2 kg in 3 days.",
                    null, ctx.departments.get("CARD"), ctx.user("dr_iyer").getId(), ctx.user("nurse_fatima").getId(), null, null, false, null));
        });
    }

    private void seedSanjay(DemoContext ctx) {
        UUID adm = ctx.admission("sanjay");
        Completed c = completed(ctx, "sanjay", "dr_khan", "nurse_rahul", true,
                "Uneventful recovery after laparoscopic cholecystectomy; tolerating diet, pain controlled orally. Fit for discharge.",
                "Corporate payer letter of guarantee received; the employer settles the balance directly. Authorized by accounts supervisor.");
        if (c == null) {
            return;
        }
        // Gallbladder histopathology still pending at discharge, explicitly handed over to the surgical clinic.
        LabOrder histo = new LabOrder();
        Admission a = admissions.findById(adm).orElseThrow();
        histo.setAdmissionId(adm);
        histo.setPatientId(a.getPatientId());
        histo.setOrderNumber(sequences.labOrderNumber());
        histo.setTestCode("HISTO-GB");
        histo.setTestName("Histopathology - gallbladder");
        histo.setCategory(LabOrder.Category.OTHER);
        histo.setSpecimen("Gallbladder (cholecystectomy specimen)");
        histo.setPriority(LabOrder.Priority.ROUTINE);
        histo.setStatus(LabOrder.Status.IN_PROGRESS);
        histo.setClinicalInfo("Chronic calculous cholecystitis suspected; routine histology.");
        histo.setOrderedBy(ctx.user("dr_khan").getId());
        histo.setOrderedAt(a.getAdmittedAt().plus(Duration.ofHours(26)));
        histo.setCollectedBy(ctx.user("dr_khan").getId());
        histo.setCollectedAt(a.getAdmittedAt().plus(Duration.ofHours(27)));
        UUID histoId = labOrders.saveAndFlush(histo).getId();

        summary(ctx, "dr_khan", adm, c,
                "Recurrent right upper abdominal pain after fatty meals for six months; ultrasound showed multiple gallbladder stones "
                        + "without bile duct dilatation. Admitted for elective laparoscopic cholecystectomy.",
                "- Symptomatic cholelithiasis (K80.20) - primary, treated surgically",
                "Uncomplicated laparoscopic cholecystectomy under general anaesthesia on day 1 with antibiotic prophylaxis and "
                        + "multimodal analgesia. Oral diet resumed the same evening; walking on day 1. No drain was needed.",
                "- Laparoscopic cholecystectomy (day 1): chronically inflamed gallbladder with multiple mixed stones; cystic duct and "
                        + "artery clipped; specimen sent for histopathology. Minimal blood loss.",
                "- Liver function tests before surgery: within normal limits\n- Gallbladder histopathology: pending at discharge, "
                        + "to be reviewed at the surgical clinic",
                "Afebrile; pain controlled with paracetamol; port sites clean and dry; eating a normal diet; passing urine normally.",
                "- Paracetamol 500 mg tablet - one tablet four times a day for 5 days if needed for pain.",
                "- Keep the dressings dry for 48 hours; after that you may shower and pat the wounds dry.\n"
                        + "- Do not lift more than 5 kg for 4 weeks.\n- Eat light, low-fat meals for the first week and increase gradually.\n"
                        + "- Desk work can usually restart after one week.",
                "- Surgical clinic with Dr. Sameer Khan in about one week: wound check and histopathology report.",
                "- Fever above 38 °C or shivering\n- Increasing pain, redness or discharge from a wound\n"
                        + "- Yellow eyes or skin, dark urine or pale stools\n- Repeated vomiting or a swollen, tight tummy");
        ZoneId zone = ctx.organization().zone();
        ctx.actAs("dr_khan", () -> {
            followUpService.create(adm, new FollowUpRequest(FollowUp.FollowUpType.APPOINTMENT, "Post-operative review - Surgery OPD",
                    "Wound check after laparoscopic gallbladder removal. We will also go through your histopathology report.",
                    LocalDate.now(zone).plusDays(6).atTime(10, 0).atZone(zone).toInstant(), ctx.departments.get("SURG"),
                    ctx.user("dr_khan").getId(), ctx.user("dr_khan").getId(), "Surgery OPD, Ground floor, Room 8", null, true, null));
            followUpService.create(adm, new FollowUpRequest(FollowUp.FollowUpType.RESULT_REVIEW, "Review gallbladder histopathology",
                    "Report expected within 5 working days. Inform the patient at the post-operative review; escalate if unexpected findings.",
                    null, ctx.departments.get("SURG"), ctx.user("dr_khan").getId(), ctx.user("dr_khan").getId(), null, histoId, false, null));
        });
    }

    private void seedRohan(DemoContext ctx) {
        UUID adm = ctx.admission("rohan");
        Completed c = completed(ctx, "rohan", "dr_khan", "nurse_rahul", true,
                "Post-operative X-ray satisfactory; hand neurovascularly intact; pain controlled. Fit for discharge with fracture clinic follow-up.",
                null);
        if (c == null) {
            return;
        }
        summary(ctx, "dr_khan", adm, c,
                "Pain, swelling and deformity of the right forearm after a fall from a two-wheeler. X-ray showed displaced fractures "
                        + "of the shafts of the right radius and ulna. Hand neurovascularly intact.",
                "- Closed displaced fracture of the shafts of the right radius and ulna (S52.201A) - primary",
                "Above-elbow slab and analgesia on arrival. Open reduction and internal fixation with plates and screws on day 1 under "
                        + "regional block. Antibiotics for 24 hours after surgery. Limb elevation and finger exercises from day 1.",
                "- ORIF right radius and ulna (day 1): 3.5 mm compression plates to both bones; satisfactory reduction on image intensifier.",
                "- Post-operative X-ray: anatomical alignment, implants in position",
                "Pain well controlled; circulation and sensation in the hand normal; full finger movement; wound clean. Arm in a sling.",
                "- Paracetamol 500 mg - one or two tablets up to four times a day for pain (no more than 8 tablets in 24 hours).",
                "- Keep the arm raised on pillows and move your fingers often.\n- Keep the wound dry until the stitches are removed.\n"
                        + "- No lifting, pushing or riding a two-wheeler with the right arm until the clinic says it is safe.\n"
                        + "- Use the sling for comfort only.",
                "- Fracture clinic review with stitch removal at 2 weeks.\n- Physiotherapy assessment at about 4 weeks.",
                "- Increasing pain, tightness or numbness in the hand or fingers\n- Fingers becoming pale, blue or cold\n"
                        + "- Fever, redness or discharge from the wound");
        ZoneId zone = ctx.organization().zone();
        Instant departed = c.departure();
        FollowUpView review = ctx.actAs("dr_khan", () -> followUpService.create(adm, new FollowUpRequest(
                FollowUp.FollowUpType.APPOINTMENT, "Fracture clinic review and stitch removal",
                "Wound check, stitch removal and check X-ray of the right forearm.",
                Instant.now().plus(Duration.ofDays(1)), ctx.departments.get("ORTHO"), ctx.user("dr_khan").getId(),
                ctx.user("dr_khan").getId(), "Orthopaedic OPD, Room 4", null, false, null)));
        FollowUpView physio = ctx.actAs("dr_khan", () -> followUpService.create(adm, new FollowUpRequest(
                FollowUp.FollowUpType.APPOINTMENT, "Physiotherapy assessment - wrist and forearm mobilisation",
                "Bring loose clothing. The physiotherapist will start a home exercise programme for the wrist and elbow.",
                Instant.now().plus(Duration.ofDays(1)), ctx.departments.get("ORTHO"), null, ctx.user("nurse_rahul").getId(),
                "Physiotherapy unit, 1st floor", null, false, null)));
        Instant reviewAt = LocalDate.ofInstant(departed, zone).plusDays(14).atTime(10, 0).atZone(zone).toInstant();
        Instant physioAt = LocalDate.now(zone).minusDays(3).atTime(15, 0).atZone(zone).toInstant();
        followUps.findById(review.id()).ifPresent(f -> f.setScheduledAt(reviewAt));
        followUps.findById(physio.id()).ifPresent(f -> f.setScheduledAt(physioAt));
        em.flush();
        ctx.actAs("dr_khan", () -> followUpService.recordOutcome(review.id(), new FollowUpOutcomeRequest(FollowUp.Status.COMPLETED,
                "Wound healed; stitches removed. Check X-ray: fixation in place, alignment maintained. Referred for physiotherapy at 4 weeks.")));
        ctx.actAs("nurse_rahul", () -> followUpService.recordOutcome(physio.id(), new FollowUpOutcomeRequest(FollowUp.Status.MISSED,
                "Did not attend. Phoned the patient: travelling for work this week; asked to rebook through the OPD desk on return.")));
        followUps.findById(review.id()).ifPresent(f -> {
            f.setCreatedAt(departed.minusSeconds(3600));
            f.setPatientNotifiedAt(departed.minusSeconds(3500));
            f.setReminderSentAt(reviewAt.minus(Duration.ofHours(24)));
            f.setCompletedAt(reviewAt.plusSeconds(2400));
            f.setUpdatedAt(reviewAt.plusSeconds(2400));
        });
        followUps.findById(physio.id()).ifPresent(f -> {
            f.setCreatedAt(reviewAt.plusSeconds(2600));
            f.setPatientNotifiedAt(reviewAt.plusSeconds(2700));
            f.setReminderSentAt(physioAt.minus(Duration.ofHours(24)));
            f.setCompletedAt(physioAt.plus(Duration.ofHours(20)));
            f.setUpdatedAt(physioAt.plus(Duration.ofHours(20)));
        });
        em.flush();
        retimeEntity(review.id(), reviewAt.plusSeconds(2400));
        retimeEntity(physio.id(), physioAt.plus(Duration.ofHours(20)));
    }

    // ------------------------------------------------------------------ helpers

    private record Completed(UUID workflowId, Instant initiated, Instant authorized, Instant departure) {
    }

    /** Builds the completed workflow record for a seeded historical discharge. */
    private Completed completed(DemoContext ctx, String key, String doctorKey, String nurseKey, boolean ackDone,
                                String clinicalNotes, String exceptionReason) {
        UUID adm = ctx.admission(key);
        Admission a = admissions.findById(adm).orElseThrow();
        if (a.getStatus() != Admission.Status.DISCHARGED || a.getDischargedAt() == null
                || !workflows.findByAdmissionIdOrderByInitiatedAtDesc(adm).isEmpty()) {
            return null;
        }
        Instant departure = a.getDischargedAt();
        Instant initiated = departure.minus(Duration.ofHours(28));
        Instant authorized = departure.minus(Duration.ofHours(7));
        Invoice inv = invoices.findActiveByAdmission(adm).orElse(null);
        BigDecimal balance = inv == null ? Money.ZERO : ledger.balance(inv.getId());

        DischargeWorkflow w = new DischargeWorkflow();
        w.setAdmissionId(adm);
        w.setStatus(DischargeWorkflow.Status.COMPLETED);
        w.setPlannedDischargeAt(departure.minus(Duration.ofHours(1)));
        w.setInitiatedBy(ctx.user(doctorKey).getId());
        w.setInitiatedAt(initiated);
        w.setClinicalDecision(DischargeWorkflow.ClinicalDecision.AUTHORIZED);
        w.setClinicalAuthorizedBy(ctx.user(doctorKey).getId());
        w.setClinicalAuthorizedAt(authorized);
        w.setClinicalNotes(clinicalNotes);
        boolean exception = balance.signum() > 0 && exceptionReason != null;
        w.setFinancialClearance(exception ? DischargeWorkflow.FinancialClearance.EXCEPTION : DischargeWorkflow.FinancialClearance.CLEARED);
        w.setFinancialClearedBy(ctx.user(exception ? "billing_lead" : "billing").getId());
        w.setFinancialClearedAt(departure.minus(Duration.ofHours(2)));
        w.setFinancialExceptionReason(exception ? exceptionReason : null);
        w.setBalanceAtClearance(balance);
        w.setDischargeDisposition(Admission.Disposition.HOME.name());
        w.setActualDepartureAt(departure);
        w.setCompletedBy(ctx.user(nurseKey).getId());
        w.setCompletedAt(departure.plusSeconds(300));
        w = workflows.saveAndFlush(w);

        List<DischargeStepTemplate> list = templates.findByOrganizationIdAndActiveTrueOrderByStepOrder(ctx.orgId());
        long span = Duration.between(initiated, departure).getSeconds();
        int n = list.size();
        for (int i = 0; i < n; i++) {
            DischargeStepTemplate t = list.get(i);
            DischargeStep s = new DischargeStep();
            s.setWorkflowId(w.getId());
            s.setTemplateCode(t.getCode());
            s.setStepOrder(t.getStepOrder());
            s.setName(t.getName());
            s.setDescription(t.getDescription());
            s.setCategory(t.getCategory());
            s.setResponsibleRole(t.getResponsibleRole());
            s.setRequired(t.isRequired());
            s.setAutoCheck(t.getAutoCheck());
            Instant at = switch (t.getCode()) {
                case "CLINICAL_DECISION" -> authorized;
                case "FINANCIAL_CLEARANCE" -> w.getFinancialClearedAt();
                case "DEPARTURE" -> departure;
                case "BED_TURNOVER" -> departure.plusSeconds(300);
                default -> initiated.plusSeconds(span * (i + 1) / (n + 1));
            };
            // A completed discharge implies billing finalized the stay's invoice (the step is a required
            // automatic check); keep the historical record consistent when no billing seed has done so.
            // Finalizing does not settle anything: the ledger balance is unchanged.
            if ("INVOICE_FINALIZED".equals(t.getAutoCheck()) && inv != null && inv.getStatus() == Invoice.Status.OPEN) {
                inv.setStatus(Invoice.Status.FINALIZED);
                inv.setFinalizedBy(ctx.user("billing").getId());
                inv.setFinalizedAt(at);
                invoices.save(inv);
            }
            boolean ackPending = "PATIENT_ACK".equals(t.getCode()) && !ackDone;
            s.setStatus(ackPending ? DischargeStep.Status.NOT_STARTED : DischargeStep.Status.COMPLETED);
            // Departure and bed turnover are recorded by the nurse who completed the discharge.
            UUID by = ctx.user(switch (t.getResponsibleRole()) {
                case DOCTOR -> doctorKey;
                case PHARMACIST -> "pharmacist";
                case BILLING -> exception && "FINANCIAL_CLEARANCE".equals(t.getCode()) ? "billing_lead" : "billing";
                default -> nurseKey;
            }).getId();
            if (!ackPending) {
                s.setCompletedBy(by);
                s.setCompletedAt(at);
                s.setUpdatedBy(t.getAutoCheck() == null || DischargeChecks.TERMINAL.contains(t.getAutoCheck()) ? by : null);
            }
            s.setUpdatedAt(ackPending ? initiated : at);
            s.setNotes(switch (t.getCode()) {
                case "NURSING_EDUCATION" -> "Medication, wound/limb care and warning signs explained to patient and family; teach-back satisfactory.";
                case "MED_RECONCILIATION" -> "Inpatient orders reconciled; take-home medicines checked against the summary.";
                case "PATIENT_ACK" -> ackPending ? null : "Patient and family acknowledged the instructions at the bedside (recorded by nursing).";
                case "BED_TURNOVER" -> "Bed released to housekeeping for cleaning.";
                default -> null;
            });
            steps.save(s);
        }
        em.flush();
        UUID wfId = w.getId();
        ctx.actAs(doctorKey, () -> {
            timeline.record(TimelineService.Entry.builder().patientId(a.getPatientId()).admissionId(adm)
                    .eventType("DISCHARGE_INITIATED").category(TimelineEvent.Category.DISCHARGE).title("Discharge planning started")
                    .detail("Your care team has started preparing your discharge.").status("IN_PROGRESS")
                    .visibility(TimelineEvent.Visibility.PATIENT).entityType("DISCHARGE_WORKFLOW").entityId(wfId).occurredAt(initiated).build());
            timeline.record(TimelineService.Entry.builder().patientId(a.getPatientId()).admissionId(adm)
                    .eventType("DISCHARGE_CLINICALLY_AUTHORIZED").category(TimelineEvent.Category.DISCHARGE)
                    .title("Your doctor has approved your discharge").status("AUTHORIZED")
                    .visibility(TimelineEvent.Visibility.PATIENT).entityType("DISCHARGE_WORKFLOW").entityId(wfId).occurredAt(authorized).build());
        });
        ctx.actAs(nurseKey, () -> timeline.record(TimelineService.Entry.builder().patientId(a.getPatientId()).admissionId(adm)
                .eventType("PATIENT_DISCHARGED").category(TimelineEvent.Category.DISCHARGE).title("Discharged home")
                .detail("Departure recorded").status("DISCHARGED").visibility(TimelineEvent.Visibility.PATIENT)
                .entityType("DISCHARGE_WORKFLOW").entityId(wfId).occurredAt(departure).build()));
        return new Completed(wfId, initiated, authorized, departure);
    }

    private void summary(DemoContext ctx, String doctorKey, UUID adm, Completed c, String presenting, String diagnoses,
                         String treatment, String procedures, String results, String condition, String meds,
                         String instructions, String followUp, String warning) {
        SummaryEnvelope env = ctx.actAs(doctorKey, () -> summaryService.createDraft(adm));
        ctx.actAs(doctorKey, () -> {
            SummaryEnvelope e2 = summaryService.update(adm, new SummaryRequest(presenting, diagnoses, treatment, procedures, results,
                    condition, meds, instructions, followUp, warning, null, env.summary().version()));
            summaryService.finalizeSummary(adm, e2.summary().version());
            summaryService.release(adm);
        });
        Instant prepared = c.authorized().minus(Duration.ofHours(2));
        Instant finalized = c.departure().minus(Duration.ofHours(3));
        Instant released = c.departure().minus(Duration.ofHours(1));
        summaries.findByAdmissionId(adm).ifPresent(s -> {
            s.setPreparedAt(prepared);
            s.setFinalizedAt(finalized);
            s.setReleasedAt(released);
            s.setUpdatedAt(finalized);
        });
        em.flush();
        summaries.findByAdmissionId(adm).ifPresent(s -> {
            jdbc.update("UPDATE timeline_events SET occurred_at = ? WHERE entity_id = ? AND event_type = 'DISCHARGE_SUMMARY_DRAFTED'", ts(prepared), s.getId());
            jdbc.update("UPDATE timeline_events SET occurred_at = ? WHERE entity_id = ? AND event_type = 'DISCHARGE_SUMMARY_FINALIZED'", ts(finalized), s.getId());
            jdbc.update("UPDATE timeline_events SET occurred_at = ? WHERE entity_id = ? AND event_type = 'DISCHARGE_SUMMARY_RELEASED'", ts(released), s.getId());
            jdbc.update("UPDATE notifications SET created_at = ?, read_at = ? WHERE entity_id = ?", ts(released), ts(released.plusSeconds(7200)), s.getId());
        });
    }

    private DischargeStep step(UUID workflowId, String code) {
        return steps.findByWorkflowIdAndTemplateCode(workflowId, code).orElse(null);
    }

    private void retime(UUID entityId, String eventType, Instant at) {
        jdbc.update("UPDATE timeline_events SET occurred_at = ? WHERE entity_id = ? AND event_type = ?", ts(at), entityId, eventType);
    }

    private void retimeEntity(UUID entityId, Instant at) {
        jdbc.update("UPDATE timeline_events SET occurred_at = ? WHERE entity_id = ?", ts(at), entityId);
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
