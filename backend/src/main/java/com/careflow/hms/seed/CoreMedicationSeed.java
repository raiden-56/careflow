package com.careflow.hms.seed;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.medication.Medication;
import com.careflow.hms.medication.MedicationOrder;
import com.careflow.hms.medication.MedicationOrderRepository;
import com.careflow.hms.medication.MedicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Seeds the (fictional demo) medication catalogue and the active prescriptions of the seeded
 * admissions. Medication administration schedules (MAR), dispensing and stock are seeded by the
 * clinical and pharmacy contributors. Codes are available as {@code ctx.medications} (e.g. MED-CEFTRX1G).
 */
@Component
@RequiredArgsConstructor
class CoreMedicationSeed {

    private final MedicationRepository medications;
    private final MedicationOrderRepository orders;
    private final AdmissionRepository admissions;

    void seed(DemoContext ctx) {
        med(ctx, "MED-PCM500", "Paracetamol", "Pyrexin (demo)", Medication.Form.TABLET, "500 mg", Medication.Route.ORAL, "Analgesic / antipyretic", null, false, false, "2.00", 200);
        med(ctx, "MED-AMXCLV625", "Amoxicillin + Clavulanic acid", "Clavamox (demo)", Medication.Form.TABLET, "625 mg", Medication.Route.ORAL, "Penicillin antibiotic", "PENICILLIN", false, false, "28.00", 60);
        med(ctx, "MED-PIPTAZ45", "Piperacillin + Tazobactam", null, Medication.Form.INJECTION, "4.5 g", Medication.Route.IV, "Penicillin antibiotic", "PENICILLIN", false, false, "420.00", 20);
        med(ctx, "MED-CEFTRX1G", "Ceftriaxone", null, Medication.Form.INJECTION, "1 g", Medication.Route.IV, "Cephalosporin antibiotic", "CEPHALOSPORIN", false, false, "65.00", 40);
        med(ctx, "MED-CEPHAL500", "Cephalexin", null, Medication.Form.CAPSULE, "500 mg", Medication.Route.ORAL, "Cephalosporin antibiotic", "CEPHALOSPORIN", false, false, "9.00", 60);
        med(ctx, "MED-AZI500", "Azithromycin", null, Medication.Form.TABLET, "500 mg", Medication.Route.ORAL, "Macrolide antibiotic", "MACROLIDE", false, false, "22.00", 30);
        med(ctx, "MED-MEROP1G", "Meropenem", null, Medication.Form.INJECTION, "1 g", Medication.Route.IV, "Carbapenem antibiotic", "CARBAPENEM", false, false, "980.00", 15);
        med(ctx, "MED-METF500", "Metformin", null, Medication.Form.TABLET, "500 mg", Medication.Route.ORAL, "Biguanide", null, false, false, "1.50", 100);
        med(ctx, "MED-INSREG", "Insulin regular (human)", null, Medication.Form.INJECTION, "100 IU/mL", Medication.Route.SC, "Insulin", null, true, false, "180.00", 10);
        med(ctx, "MED-PANTO40", "Pantoprazole", null, Medication.Form.TABLET, "40 mg", Medication.Route.ORAL, "Proton pump inhibitor", null, false, false, "6.00", 100);
        med(ctx, "MED-PANTO40IV", "Pantoprazole", null, Medication.Form.INJECTION, "40 mg", Medication.Route.IV, "Proton pump inhibitor", null, false, false, "48.00", 30);
        med(ctx, "MED-ONDAN4", "Ondansetron", null, Medication.Form.INJECTION, "4 mg", Medication.Route.IV, "Antiemetic", null, false, false, "18.00", 40);
        med(ctx, "MED-ENOX40", "Enoxaparin", null, Medication.Form.INJECTION, "40 mg", Medication.Route.SC, "Low-molecular-weight heparin", null, true, false, "320.00", 20);
        med(ctx, "MED-SALBNEB", "Salbutamol nebuliser solution", null, Medication.Form.OTHER, "2.5 mg/2.5 mL", Medication.Route.INHALED, "Bronchodilator", null, false, false, "14.00", 60);
        med(ctx, "MED-BUDNEB", "Budesonide respules", null, Medication.Form.OTHER, "0.5 mg/2 mL", Medication.Route.INHALED, "Inhaled corticosteroid", null, false, false, "38.00", 30);
        med(ctx, "MED-PRED10", "Prednisolone", null, Medication.Form.TABLET, "10 mg", Medication.Route.ORAL, "Corticosteroid", null, false, false, "3.00", 60);
        med(ctx, "MED-METO25", "Metoprolol succinate", null, Medication.Form.TABLET, "25 mg", Medication.Route.ORAL, "Beta blocker", null, false, false, "5.00", 80);
        med(ctx, "MED-ATOR40", "Atorvastatin", null, Medication.Form.TABLET, "40 mg", Medication.Route.ORAL, "Statin", null, false, false, "9.00", 80);
        med(ctx, "MED-ASP75", "Aspirin", null, Medication.Form.TABLET, "75 mg", Medication.Route.ORAL, "Antiplatelet", "NSAID", false, false, "1.00", 100);
        med(ctx, "MED-CLOP75", "Clopidogrel", null, Medication.Form.TABLET, "75 mg", Medication.Route.ORAL, "Antiplatelet", null, false, false, "7.00", 60);
        med(ctx, "MED-FUR40", "Furosemide", null, Medication.Form.INJECTION, "40 mg", Medication.Route.IV, "Loop diuretic", null, false, false, "12.00", 30);
        med(ctx, "MED-NS500", "Sodium chloride 0.9%", null, Medication.Form.INFUSION, "500 mL", Medication.Route.IV, "IV fluid", null, false, false, "45.00", 80);
        med(ctx, "MED-KCL20", "Potassium chloride", null, Medication.Form.INFUSION, "20 mmol/100 mL", Medication.Route.IV, "Electrolyte", null, true, false, "60.00", 20);
        med(ctx, "MED-NORAD4", "Noradrenaline", null, Medication.Form.INFUSION, "4 mg/4 mL", Medication.Route.IV, "Vasopressor", null, true, false, "210.00", 10);
        med(ctx, "MED-ADEN6", "Adenosine", null, Medication.Form.INJECTION, "6 mg/2 mL", Medication.Route.IV, "Antiarrhythmic", null, true, false, "350.00", 5);
        med(ctx, "MED-TRAM50", "Tramadol", null, Medication.Form.CAPSULE, "50 mg", Medication.Route.ORAL, "Opioid analgesic", "OPIOID", false, true, "6.00", 40);
        med(ctx, "MED-MORPH10", "Morphine", null, Medication.Form.INJECTION, "10 mg/mL", Medication.Route.IV, "Opioid analgesic", "OPIOID", true, true, "40.00", 10);
        med(ctx, "MED-IBU400", "Ibuprofen", null, Medication.Form.TABLET, "400 mg", Medication.Route.ORAL, "NSAID", "NSAID", false, false, "2.50", 100);
        med(ctx, "MED-AMLO5", "Amlodipine", null, Medication.Form.TABLET, "5 mg", Medication.Route.ORAL, "Calcium channel blocker", null, false, false, "2.00", 80);
        med(ctx, "MED-LACT", "Lactulose", null, Medication.Form.SYRUP, "10 g/15 mL", Medication.Route.ORAL, "Osmotic laxative", null, false, false, "8.00", 20);

        // ---- Active prescriptions (authorized by the attending doctor at admission)
        order(ctx, "ravi", "MED-CEFTRX1G", "1", "g", Medication.Route.IV, MedicationOrder.Frequency.OD, "09:00", 4.1, 7, "Community-acquired pneumonia", "Infuse over 30 min", "An antibiotic given through your drip once a day.", false);
        order(ctx, "ravi", "MED-AZI500", "500", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.OD, "09:00", 4.1, 5, "Atypical cover", null, "One antibiotic tablet every morning.", false);
        order(ctx, "ravi", "MED-INSREG", "per sliding scale", "IU", Medication.Route.SC, MedicationOrder.Frequency.TDS, "07:30,12:30,19:30", 4.0, null, "Hyperglycaemia", "Check capillary glucose before each dose; follow scale on chart", "Insulin injections before meals to control blood sugar.", false);
        order(ctx, "ravi", "MED-PANTO40", "40", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.OD, "07:00", 4.0, null, "GI protection", "Before breakfast", "Take before breakfast to protect your stomach.", false);
        order(ctx, "ravi", "MED-PCM500", "1000", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.PRN, null, 4.0, null, "Fever > 38.0 C or pain", "Max 4 g/24 h; minimum 6 h between doses", "Paracetamol for fever or pain when needed.", false);

        order(ctx, "irfan", "MED-CEFTRX1G", "1", "g", Medication.Route.IV, MedicationOrder.Frequency.BD, "09:00,21:00", 2.0, 3, "Peri-operative antibiotic", null, null, false);
        order(ctx, "irfan", "MED-TRAM50", "50", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.TDS, "08:00,14:00,20:00", 1.5, 3, "Post-operative pain", "Hold if drowsy or RR < 12", null, false);
        order(ctx, "irfan", "MED-ONDAN4", "4", "mg", Medication.Route.IV, MedicationOrder.Frequency.PRN, null, 1.5, null, "Nausea / vomiting", "Max 3 doses in 24 h", null, false);
        order(ctx, "irfan", "MED-NS500", "500", "mL", Medication.Route.IV, MedicationOrder.Frequency.Q8H, "06:00,14:00,22:00", 2.0, 2, "Maintenance fluids", "Run over 8 h", null, false);
        order(ctx, "irfan", "MED-PANTO40IV", "40", "mg", Medication.Route.IV, MedicationOrder.Frequency.OD, "08:00", 2.0, 3, "Stress ulcer prophylaxis", null, null, false);

        order(ctx, "ananya", "MED-METO25", "25", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.BD, "09:00,21:00", 1.0, null, "Rate control", "Hold if HR < 55 or SBP < 95", "A heart-rhythm tablet twice a day.", false);

        order(ctx, "george", "MED-MEROP1G", "1", "g", Medication.Route.IV, MedicationOrder.Frequency.Q8H, "06:00,14:00,22:00", 6.2, 7, "Urosepsis (sulfonamide allergy noted)", "Renal dose reviewed daily", null, false);
        order(ctx, "george", "MED-NS500", "500", "mL", Medication.Route.IV, MedicationOrder.Frequency.Q8H, "06:00,14:00,22:00", 6.2, null, "Volume resuscitation / maintenance", null, null, false);
        order(ctx, "george", "MED-ENOX40", "40", "mg", Medication.Route.SC, MedicationOrder.Frequency.OD, "18:00", 6.0, null, "VTE prophylaxis", "Hold if platelets < 50", null, false);
        order(ctx, "george", "MED-PANTO40IV", "40", "mg", Medication.Route.IV, MedicationOrder.Frequency.OD, "08:00", 6.0, null, "Stress ulcer prophylaxis", null, null, false);
        order(ctx, "george", "MED-PCM500", "1000", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.PRN, null, 6.0, null, "Fever", "Max 3 g/24 h (CKD)", null, false);

        order(ctx, "deepa", "MED-CEFTRX1G", "1", "g", Medication.Route.IV, MedicationOrder.Frequency.OD, "10:00", 5.0, 5, "Cellulitis left leg", null, null, false);
        order(ctx, "deepa", "MED-CEPHAL500", "500", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.QID, "06:00,12:00,18:00,22:00", 0.2, 7, "Step-down oral antibiotic on discharge", null, "Take one capsule four times a day for 7 days, even if the leg looks better.", true);
        order(ctx, "deepa", "MED-PCM500", "500", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.PRN, null, 5.0, null, "Pain or fever (NSAID allergy — avoid ibuprofen)", null, "Paracetamol for pain. Do not take ibuprofen or similar painkillers.", true);

        order(ctx, "aarav", "MED-SALBNEB", "2.5", "mg", Medication.Route.INHALED, MedicationOrder.Frequency.Q4H, "02:00,06:00,10:00,14:00,18:00,22:00", 1.6, 3, "Acute asthma", "Nebulise with oxygen; reassess after each dose", "Breathing medicine through a mask every 4 hours.", false);
        order(ctx, "aarav", "MED-BUDNEB", "0.5", "mg", Medication.Route.INHALED, MedicationOrder.Frequency.BD, "08:00,20:00", 1.6, 5, "Airway inflammation", null, null, false);
        order(ctx, "aarav", "MED-PRED10", "10", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.OD, "08:00", 1.5, 5, "Asthma exacerbation", "Give with food", "Steroid tablet once a day with breakfast.", false);

        order(ctx, "vikram", "MED-ASP75", "75", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.OD, "09:00", 3.0, null, "Coronary artery disease", null, "Blood thinner — do not stop without your cardiologist's advice.", false);
        order(ctx, "vikram", "MED-CLOP75", "75", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.OD, "09:00", 2.9, null, "Dual antiplatelet therapy", null, null, false);
        order(ctx, "vikram", "MED-ATOR40", "40", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.HS, "22:00", 3.0, null, "Dyslipidaemia", null, "Cholesterol tablet at bedtime.", false);
        order(ctx, "vikram", "MED-METO25", "25", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.BD, "09:00,21:00", 3.0, null, "Secondary prevention", "Hold if HR < 55", null, false);

        order(ctx, "fatima", "MED-INSREG", "per sliding scale", "IU", Medication.Route.SC, MedicationOrder.Frequency.TDS, "07:30,12:30,19:30", 0.1, null, "Uncontrolled diabetes", "Hourly glucose until < 250 mg/dL", null, false);
        order(ctx, "fatima", "MED-NS500", "500", "mL", Medication.Route.IV, MedicationOrder.Frequency.Q8H, "06:00,14:00,22:00", 0.1, 1, "Dehydration", null, null, false);

        // Discharged patients: discharge medications released to the portal; inpatient orders completed.
        MedicationOrder l1 = order(ctx, "lakshmi", "MED-METO25", "25", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.BD, "09:00,21:00", 13.0, null, "Heart failure", null, "Take one tablet twice a day. Check your pulse; call us if it is below 55.", true);
        MedicationOrder l2 = order(ctx, "lakshmi", "MED-ATOR40", "40", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.HS, "22:00", 13.0, null, "Cardiovascular risk", null, "One tablet at bedtime.", true);
        MedicationOrder l3 = order(ctx, "lakshmi", "MED-FUR40", "40", "mg", Medication.Route.IV, MedicationOrder.Frequency.BD, "08:00,16:00", 14.0, 4, "Fluid overload", null, null, false);
        for (MedicationOrder o : new MedicationOrder[]{l1, l2}) {
            o.setReleasedToPatient(true);
        }
        l3.setStatus(MedicationOrder.Status.COMPLETED);
        l3.setEndAt(DemoContext.daysAgo(10));
        MedicationOrder s1 = order(ctx, "sanjay", "MED-PCM500", "500", "mg", Medication.Route.ORAL, MedicationOrder.Frequency.QID, "06:00,12:00,18:00,22:00", 2.5, 5, "Post-operative pain", null, "One tablet four times a day for 5 days if needed for pain.", true);
        s1.setReleasedToPatient(true);
    }

    private void med(DemoContext ctx, String code, String generic, String brand, Medication.Form form, String strength,
                     Medication.Route route, String drugClass, String allergenGroup, boolean highAlert, boolean controlled,
                     String price, int reorderLevel) {
        Medication m = new Medication();
        m.setCode(code);
        m.setGenericName(generic);
        m.setBrandName(brand);
        m.setForm(form);
        m.setStrength(strength);
        m.setDefaultRoute(route);
        m.setDrugClass(drugClass);
        m.setAllergenGroup(allergenGroup);
        m.setHighAlert(highAlert);
        m.setControlled(controlled);
        m.setUnitPrice(new BigDecimal(price));
        m.setDispenseUnit(form == Medication.Form.TABLET || form == Medication.Form.CAPSULE ? "TAB/CAP"
                : form == Medication.Form.INFUSION ? "BAG" : form == Medication.Form.SYRUP ? "BOTTLE" : "UNIT");
        m.setReorderLevel(reorderLevel);
        ctx.medications.put(code, medications.save(m).getId());
    }

    private MedicationOrder order(DemoContext ctx, String patientKey, String medCode, String dose, String unit,
                                  Medication.Route route, MedicationOrder.Frequency freq, String times, double startDaysAgo,
                                  Integer durationDays, String indication, String instructions, String patientInstructions,
                                  boolean discharge) {
        UUID admissionId = ctx.admission(patientKey);
        Admission a = admissions.findById(admissionId).orElseThrow();
        Instant start = DemoContext.daysAgo(startDaysAgo);
        MedicationOrder o = new MedicationOrder();
        o.setAdmissionId(admissionId);
        o.setPatientId(a.getPatientId());
        o.setMedicationId(ctx.medications.get(medCode));
        o.setDose(dose);
        o.setDoseUnit(unit);
        o.setRoute(route);
        o.setFrequency(freq);
        o.setScheduleTimes(times);
        o.setStartAt(start);
        o.setDurationDays(durationDays);
        o.setEndAt(durationDays == null ? null : start.plusSeconds(durationDays * 86400L));
        o.setIndication(indication);
        o.setInstructions(instructions);
        o.setPatientInstructions(patientInstructions);
        o.setDischargeMedication(discharge);
        o.setStatus(MedicationOrder.Status.ACTIVE);
        o.setPrescribedBy(a.getAttendingDoctorId());
        o.setPrescribedAt(start);
        return orders.save(o);
    }
}
