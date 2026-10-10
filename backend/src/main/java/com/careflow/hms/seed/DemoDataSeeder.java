package com.careflow.hms.seed;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionDtos;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.admission.AdmissionService;
import com.careflow.hms.admission.BedAssignment;
import com.careflow.hms.admission.BedAssignmentRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.ServiceTariff;
import com.careflow.hms.billing.ServiceTariffRepository;
import com.careflow.hms.config.CareFlowProperties;
import com.careflow.hms.facility.Bed;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.facility.Department;
import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.facility.Room;
import com.careflow.hms.facility.RoomRepository;
import com.careflow.hms.facility.StaffWardAssignment;
import com.careflow.hms.facility.StaffWardAssignmentRepository;
import com.careflow.hms.facility.Ward;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationProvisioningService;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.org.SequenceService;
import com.careflow.hms.patient.Allergy;
import com.careflow.hms.patient.AllergyRepository;
import com.careflow.hms.patient.MedicalHistory;
import com.careflow.hms.patient.MedicalHistoryRepository;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientAccessGrant;
import com.careflow.hms.patient.PatientAccessGrantRepository;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.RolePermissionService;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.StaffProfile;
import com.careflow.hms.user.StaffProfileRepository;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Seeds a clearly fictional demonstration hospital when careflow.demo.enabled=true and the demo
 * organization does not exist yet. All people, identifiers and contact details are invented.
 * Demo credentials are development-only (see README).
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_ORG_CODE = "CFDEMO";

    private final CareFlowProperties props;
    private final OrganizationRepository organizations;
    private final OrganizationProvisioningService provisioning;
    private final RolePermissionService rolePermissions;
    private final DepartmentRepository departments;
    private final WardRepository wards;
    private final RoomRepository rooms;
    private final BedRepository beds;
    private final ServiceTariffRepository tariffs;
    private final UserRepository users;
    private final StaffProfileRepository staffProfiles;
    private final StaffWardAssignmentRepository wardAssignments;
    private final PatientRepository patients;
    private final AllergyRepository allergies;
    private final MedicalHistoryRepository history;
    private final PatientAccessGrantRepository grants;
    private final AdmissionRepository admissions;
    private final BedAssignmentRepository bedAssignments;
    private final AdmissionService admissionService;
    private final SequenceService sequences;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final List<DemoSeedContributor> contributors;
    private final jakarta.persistence.EntityManager em;

    private String demoHash;

    @Override
    public void run(ApplicationArguments args) {
        if (!props.demo().enabled() || organizations.findByCode(DEMO_ORG_CODE).isPresent()) {
            return;
        }
        log.info("Seeding CareFlow demonstration data (fictional) ...");
        demoHash = passwordEncoder.encode(PlatformBootstrap.DEMO_PASSWORD);
        AuditService.runSuppressed(() -> tx.executeWithoutResult(status -> {
            Organization org = new Organization();
            org.setCode(DEMO_ORG_CODE);
            org.setName("CareFlow General Hospital (Demo)");
            org.setLegalName("CareFlow Demo Healthcare Pvt. Ltd. (fictional)");
            org.setAddress("12 Demo Health Avenue, Indiranagar");
            org.setCity("Bengaluru");
            org.setState("Karnataka");
            org.setPhone("+91 80 0000 0000");
            org.setEmail("contact@demo.careflow.local");
            org.setRegistrationNumber("DEMO-REG-0001");
            org = organizations.saveAndFlush(org);
            provisioning.provision(org);
            DemoContext ctx = new DemoContext(org, rolePermissions);
            Organization finalOrg = org;
            TenantContext.runAs(org.getId(), null, () -> {
                seedCore(ctx);
                List<DemoSeedContributor> ordered = contributors.stream()
                        .sorted(Comparator.comparingInt(DemoSeedContributor::order)).toList();
                for (DemoSeedContributor c : ordered) {
                    log.info("Demo seed contributor: {}", c.name());
                    c.seed(ctx);
                }
            });
            log.info("Demo data seeded for {} ({} contributors)", finalOrg.getName(), contributors.size());
        }));
    }

    private void seedCore(DemoContext ctx) {
        // ---------------------------------------------------------------- departments
        dept(ctx, "GEN_MED", "Internal Medicine", "General medicine");
        dept(ctx, "CARD", "Cardiology", "Cardiology & cardiac care");
        dept(ctx, "SURG", "General Surgery", "General & laparoscopic surgery");
        dept(ctx, "ORTHO", "Orthopaedics", "Trauma & joint surgery");
        dept(ctx, "PAED", "Paediatrics", "Child health");
        dept(ctx, "CRIT", "Critical Care", "Intensive care");

        // ---------------------------------------------------------------- wards, rooms, beds
        ward(ctx, "MWA", "Medical Ward A", Ward.WardType.GENERAL, "GEN_MED", "2", Ward.GenderPolicy.MIXED,
                new String[]{"201", "202", "203", "204"}, Room.RoomType.GENERAL, 4, Bed.BedType.STANDARD, false);
        ward(ctx, "CCU", "Cardiac Care Unit", Ward.WardType.HDU, "CARD", "3", Ward.GenderPolicy.MIXED,
                new String[]{"301", "302", "303"}, Room.RoomType.SEMI_PRIVATE, 2, Bed.BedType.STANDARD, false);
        ward(ctx, "SWB", "Surgical Ward B", Ward.WardType.GENERAL, "SURG", "4", Ward.GenderPolicy.MIXED,
                new String[]{"401", "402", "403"}, Room.RoomType.GENERAL, 4, Bed.BedType.STANDARD, false);
        ward(ctx, "ICU", "Intensive Care Unit", Ward.WardType.ICU, "CRIT", "1", Ward.GenderPolicy.MIXED,
                new String[]{"ICU-1", "ICU-2"}, Room.RoomType.ICU, 3, Bed.BedType.ICU, true);
        ward(ctx, "PVT", "Private Wing", Ward.WardType.PRIVATE, null, "5", Ward.GenderPolicy.MIXED,
                new String[]{"501", "502", "503", "504"}, Room.RoomType.PRIVATE, 1, Bed.BedType.STANDARD, false);
        ward(ctx, "PAE", "Paediatric Ward", Ward.WardType.PAEDIATRIC, "PAED", "2", Ward.GenderPolicy.MIXED,
                new String[]{"P1", "P2"}, Room.RoomType.GENERAL, 4, Bed.BedType.PAEDIATRIC, false);
        // Two deluxe suites in the private wing.
        UUID pvt = ctx.wards.get("PVT");
        for (String rn : new String[]{"505", "506"}) {
            Room r = new Room();
            r.setWardId(pvt);
            r.setRoomNumber(rn);
            r.setRoomType(Room.RoomType.DELUXE);
            r = rooms.save(r);
            bed(r, "A", Bed.BedType.STANDARD, false);
        }

        // ---------------------------------------------------------------- tariffs
        tariff(ctx, "ROOM_GENERAL", "General ward bed (per day)", ServiceTariff.Category.ROOM, "1500", "0", ServiceTariff.BillingUnit.PER_DAY, "GENERAL");
        tariff(ctx, "ROOM_SEMI_PRIVATE", "Semi-private room (per day)", ServiceTariff.Category.ROOM, "3000", "0", ServiceTariff.BillingUnit.PER_DAY, "SEMI_PRIVATE");
        tariff(ctx, "ROOM_PRIVATE", "Private room (per day)", ServiceTariff.Category.ROOM, "5500", "5", ServiceTariff.BillingUnit.PER_DAY, "PRIVATE");
        tariff(ctx, "ROOM_DELUXE", "Deluxe suite (per day)", ServiceTariff.Category.ROOM, "8000", "5", ServiceTariff.BillingUnit.PER_DAY, "DELUXE");
        tariff(ctx, "ROOM_ICU", "ICU bed (per day)", ServiceTariff.Category.ROOM, "12000", "0", ServiceTariff.BillingUnit.PER_DAY, "ICU");
        tariff(ctx, "ROOM_ISOLATION", "Isolation room (per day)", ServiceTariff.Category.ROOM, "6000", "0", ServiceTariff.BillingUnit.PER_DAY, "ISOLATION");
        tariff(ctx, "CONS_DAILY", "Daily doctor visit", ServiceTariff.Category.CONSULTATION, "800", "0", ServiceTariff.BillingUnit.PER_VISIT, null);
        tariff(ctx, "CONS_SPECIALIST", "Specialist consultation", ServiceTariff.Category.CONSULTATION, "1200", "0", ServiceTariff.BillingUnit.PER_VISIT, null);
        tariff(ctx, "NURSING_DAILY", "Nursing care (per day)", ServiceTariff.Category.NURSING, "600", "0", ServiceTariff.BillingUnit.PER_DAY, null);
        tariff(ctx, "ADMISSION_FEE", "Admission & registration", ServiceTariff.Category.OTHER, "500", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "MED_RECORDS", "Medical records copy", ServiceTariff.Category.OTHER, "200", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "LAB_CBC", "Complete blood count", ServiceTariff.Category.LAB, "450", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "LAB_LFT", "Liver function test", ServiceTariff.Category.LAB, "900", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "LAB_RFT", "Renal function test", ServiceTariff.Category.LAB, "850", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "LAB_ELEC", "Serum electrolytes", ServiceTariff.Category.LAB, "600", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "LAB_HBA1C", "HbA1c", ServiceTariff.Category.LAB, "650", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "LAB_CRP", "C-reactive protein", ServiceTariff.Category.LAB, "550", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "LAB_BCX", "Blood culture & sensitivity", ServiceTariff.Category.LAB, "1200", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "LAB_TROP", "Troponin I", ServiceTariff.Category.LAB, "1500", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "LAB_URINE", "Urine routine & microscopy", ServiceTariff.Category.LAB, "250", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "LAB_UCX", "Urine culture", ServiceTariff.Category.LAB, "800", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "LAB_LIPID", "Lipid profile", ServiceTariff.Category.LAB, "750", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "IMG_CXR", "Chest X-ray", ServiceTariff.Category.IMAGING, "600", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "IMG_ECG", "12-lead ECG", ServiceTariff.Category.IMAGING, "300", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "IMG_ECHO", "2D echocardiography", ServiceTariff.Category.IMAGING, "2500", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "IMG_USG_ABD", "Ultrasound abdomen", ServiceTariff.Category.IMAGING, "1800", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "PROC_APPENDECTOMY", "Laparoscopic appendectomy", ServiceTariff.Category.PROCEDURE, "45000", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "PROC_LAP_CHOLE", "Laparoscopic cholecystectomy", ServiceTariff.Category.PROCEDURE, "65000", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "PROC_ANGIO", "Coronary angiography", ServiceTariff.Category.PROCEDURE, "18000", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "PROC_DRESSING", "Wound dressing", ServiceTariff.Category.PROCEDURE, "400", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "PROC_CVC", "Central venous catheter insertion", ServiceTariff.Category.PROCEDURE, "3500", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "PROC_ORIF", "Open reduction internal fixation (forearm)", ServiceTariff.Category.PROCEDURE, "55000", "0", ServiceTariff.BillingUnit.PER_UNIT, null);
        tariff(ctx, "CONSUMABLE_IV_SET", "IV infusion set", ServiceTariff.Category.CONSUMABLE, "180", "12", ServiceTariff.BillingUnit.PER_UNIT, null);

        // ---------------------------------------------------------------- users
        user(ctx, "superadmin", null, null, null, null, null);
        ctx.users.put("superadmin", users.findByEmailIgnoreCase("superadmin@careflow.local").orElse(null));
        if (ctx.users.get("superadmin") == null) {
            ctx.users.remove("superadmin");
        }
        user(ctx, "admin", "admin@careflow.local", "Ananya Rao", Role.HOSPITAL_ADMIN, null, "Hospital Operations Manager");
        user(ctx, "dr_mehta", "dr.mehta@careflow.local", "Dr. Arjun Mehta", Role.DOCTOR, "GEN_MED", "Consultant Physician");
        user(ctx, "dr_iyer", "dr.iyer@careflow.local", "Dr. Kavya Iyer", Role.DOCTOR, "CARD", "Consultant Cardiologist");
        user(ctx, "dr_khan", "dr.khan@careflow.local", "Dr. Sameer Khan", Role.DOCTOR, "SURG", "Consultant Surgeon");
        user(ctx, "dr_rao_paeds", "dr.rao@careflow.local", "Dr. Nandini Rao", Role.DOCTOR, "PAED", "Consultant Paediatrician");
        user(ctx, "dr_bose_icu", "dr.bose@careflow.local", "Dr. Amit Bose", Role.DOCTOR, "CRIT", "Intensivist");
        user(ctx, "nurse_priya", "nurse.priya@careflow.local", "Priya Nair", Role.NURSE, "GEN_MED", "Staff Nurse");
        user(ctx, "nurse_rahul", "nurse.rahul@careflow.local", "Rahul Verma", Role.NURSE, "SURG", "Senior Staff Nurse");
        user(ctx, "nurse_fatima", "nurse.fatima@careflow.local", "Fatima Sheikh", Role.NURSE, "CARD", "Staff Nurse");
        user(ctx, "nurse_anita", "nurse.anita@careflow.local", "Anita D'Souza", Role.NURSE, "PAED", "Nurse In-charge");
        user(ctx, "billing", "billing@careflow.local", "Suresh Kumar", Role.BILLING, null, "Billing Executive");
        user(ctx, "billing_lead", "accounts@careflow.local", "Divya Menon", Role.BILLING, null, "Accounts Supervisor");
        user(ctx, "pharmacist", "pharmacy@careflow.local", "Meera Joshi", Role.PHARMACIST, null, "Chief Pharmacist");
        specialty("dr_mehta", "Internal Medicine", "KMC-DEMO-10231");
        specialty("dr_iyer", "Cardiology", "KMC-DEMO-10877");
        specialty("dr_khan", "General & Laparoscopic Surgery", "KMC-DEMO-11402");
        specialty("dr_rao_paeds", "Paediatrics", "KMC-DEMO-12054");
        specialty("dr_bose_icu", "Critical Care Medicine", "KMC-DEMO-12890");

        assignWard(ctx, "nurse_priya", "MWA");
        assignWard(ctx, "nurse_priya", "PVT");
        assignWard(ctx, "nurse_rahul", "SWB");
        assignWard(ctx, "nurse_rahul", "ICU");
        assignWard(ctx, "nurse_fatima", "CCU");
        assignWard(ctx, "nurse_fatima", "PVT");
        assignWard(ctx, "nurse_anita", "PAE");

        // ---------------------------------------------------------------- patients
        patient(ctx, "ravi", "Ravi", "Sharma", LocalDate.now().minusYears(58).minusDays(40), Patient.Gender.MALE, "B+", "+91 98450 10001", "Bengaluru", "Sunita Sharma", "+91 98450 10002", "Spouse");
        patient(ctx, "lakshmi", "Lakshmi", "Pillai", LocalDate.now().minusYears(67).minusDays(120), Patient.Gender.FEMALE, "O+", "+91 98450 10003", "Bengaluru", "Hari Pillai", "+91 98450 10004", "Son");
        patient(ctx, "irfan", "Mohammed", "Irfan", LocalDate.now().minusYears(45).minusDays(10), Patient.Gender.MALE, "A+", "+91 98450 10005", "Mysuru", "Ayesha Irfan", "+91 98450 10006", "Spouse");
        patient(ctx, "ananya", "Ananya", "Gupta", LocalDate.now().minusYears(32).minusDays(200), Patient.Gender.FEMALE, "AB+", "+91 98450 10007", "Bengaluru", "Rohit Gupta", "+91 98450 10008", "Spouse");
        patient(ctx, "george", "George", "Mathew", LocalDate.now().minusYears(72).minusDays(15), Patient.Gender.MALE, "O-", "+91 98450 10009", "Kochi", "Ann Mathew", "+91 98450 10010", "Daughter");
        patient(ctx, "deepa", "Deepa", "Krishnan", LocalDate.now().minusYears(51).minusDays(77), Patient.Gender.FEMALE, "B-", "+91 98450 10011", "Chennai", "Krishnan R", "+91 98450 10012", "Spouse");
        patient(ctx, "aarav", "Aarav", "Reddy", LocalDate.now().minusYears(8).minusDays(33), Patient.Gender.MALE, "A-", "+91 98450 10013", "Hyderabad", "Kiran Reddy", "+91 98450 10014", "Mother");
        patient(ctx, "fatima", "Fatima", "Begum", LocalDate.now().minusYears(63).minusDays(5), Patient.Gender.FEMALE, "O+", "+91 98450 10015", "Bengaluru", "Salim Khan", "+91 98450 10016", "Son");
        patient(ctx, "rohan", "Rohan", "Desai", LocalDate.now().minusYears(29).minusDays(90), Patient.Gender.MALE, "B+", "+91 98450 10017", "Pune", "Meena Desai", "+91 98450 10018", "Mother");
        patient(ctx, "neha", "Neha", "Kapoor", LocalDate.now().minusYears(40).minusDays(60), Patient.Gender.FEMALE, "A+", "+91 98450 10019", "Delhi", "Amit Kapoor", "+91 98450 10020", "Spouse");
        patient(ctx, "vikram", "Vikram", "Singh", LocalDate.now().minusYears(55).minusDays(150), Patient.Gender.MALE, "B+", "+91 98450 10021", "Bengaluru", "Preeti Singh", "+91 98450 10022", "Spouse");
        patient(ctx, "sanjay", "Sanjay", "Patel", LocalDate.now().minusYears(60).minusDays(25), Patient.Gender.MALE, "O+", "+91 98450 10023", "Ahmedabad", "Kavita Patel", "+91 98450 10024", "Spouse");

        allergy(ctx, "ravi", "Penicillin", Allergy.AllergyType.DRUG, "Urticarial rash", Allergy.Severity.MODERATE);
        allergy(ctx, "george", "Sulfonamides", Allergy.AllergyType.DRUG, "Stevens-Johnson syndrome (2015)", Allergy.Severity.LIFE_THREATENING);
        allergy(ctx, "ananya", "Peanuts", Allergy.AllergyType.FOOD, "Angioedema", Allergy.Severity.SEVERE);
        allergy(ctx, "deepa", "Ibuprofen (NSAIDs)", Allergy.AllergyType.DRUG, "Bronchospasm", Allergy.Severity.SEVERE);
        allergy(ctx, "aarav", "Dust mite", Allergy.AllergyType.ENVIRONMENTAL, "Wheeze", Allergy.Severity.MILD);
        allergy(ctx, "vikram", "Iodinated contrast", Allergy.AllergyType.DRUG, "Mild hives - premedicate", Allergy.Severity.MILD);

        history(ctx, "ravi", MedicalHistory.Category.MEDICAL, "Type 2 diabetes mellitus", "On oral hypoglycaemics for 9 years", 9);
        history(ctx, "ravi", MedicalHistory.Category.MEDICAL, "Hypertension", "Well controlled", 6);
        history(ctx, "ravi", MedicalHistory.Category.SOCIAL, "Ex-smoker", "Quit 2018, 20 pack-years", null);
        history(ctx, "lakshmi", MedicalHistory.Category.MEDICAL, "Heart failure with reduced ejection fraction", "EF 35% (2024)", 3);
        history(ctx, "lakshmi", MedicalHistory.Category.MEDICAL, "Atrial fibrillation", "On anticoagulation", 3);
        history(ctx, "george", MedicalHistory.Category.MEDICAL, "Chronic kidney disease stage 3", null, 5);
        history(ctx, "george", MedicalHistory.Category.SURGICAL, "TURP", "Benign prostatic hyperplasia, 2019", 7);
        history(ctx, "deepa", MedicalHistory.Category.MEDICAL, "Bronchial asthma", "Mild intermittent", 20);
        history(ctx, "aarav", MedicalHistory.Category.MEDICAL, "Childhood asthma", "Two prior admissions", 4);
        history(ctx, "vikram", MedicalHistory.Category.MEDICAL, "Dyslipidaemia", null, 4);
        history(ctx, "vikram", MedicalHistory.Category.FAMILY, "Premature coronary artery disease", "Father - MI at 52", null);
        history(ctx, "sanjay", MedicalHistory.Category.MEDICAL, "Gallstone disease", "Recurrent biliary colic", 1);

        // Portal logins (patients and caregivers).
        portal(ctx, "p_ravi", "ravi.sharma@demo.careflow.local", "Ravi Sharma", "ravi");
        portal(ctx, "p_lakshmi", "lakshmi.pillai@demo.careflow.local", "Lakshmi Pillai", "lakshmi");
        portal(ctx, "p_vikram", "vikram.singh@demo.careflow.local", "Vikram Singh", "vikram");
        portal(ctx, "cg_sunita", "sunita.sharma@demo.careflow.local", "Sunita Sharma", null);
        portal(ctx, "cg_kiran", "kiran.reddy@demo.careflow.local", "Kiran Reddy", null);
        grant(ctx, "ravi", "cg_sunita", "Spouse", PatientAccessGrant.Scope.FULL);
        grant(ctx, "aarav", "cg_kiran", "Mother", PatientAccessGrant.Scope.FULL);

        // ---------------------------------------------------------------- admissions (through the real service)
        admit(ctx, "ravi", "GEN_MED", "dr_mehta", "MWA", "201", "A", 4.2, 3,
                "Fever, productive cough and breathlessness for 5 days; suspected community-acquired pneumonia. Known T2DM.",
                Admission.AdmissionType.EMERGENCY, false);
        admit(ctx, "irfan", "SURG", "dr_khan", "SWB", "401", "B", 2.1, 2,
                "Acute right iliac fossa pain with vomiting; clinical diagnosis acute appendicitis for laparoscopic appendectomy.",
                Admission.AdmissionType.EMERGENCY, false);
        admit(ctx, "ananya", "CARD", "dr_iyer", "CCU", "301", "A", 1.1, 2,
                "Recurrent palpitations with documented narrow-complex tachycardia; evaluation and rhythm monitoring.",
                Admission.AdmissionType.ELECTIVE, false);
        admit(ctx, "george", "CRIT", "dr_bose_icu", "ICU", "ICU-1", "B", 6.3, 4,
                "Fever, confusion and hypotension; urosepsis with acute kidney injury on CKD.",
                Admission.AdmissionType.EMERGENCY, true);
        admit(ctx, "deepa", "GEN_MED", "dr_mehta", "MWA", "202", "C", 5.0, 0,
                "Left lower limb cellulitis with fever; IV antibiotics.",
                Admission.AdmissionType.EMERGENCY, false);
        admit(ctx, "aarav", "PAED", "dr_rao_paeds", "PAE", "P1", "B", 1.6, 2,
                "Acute exacerbation of asthma not responding to home nebulisation.",
                Admission.AdmissionType.EMERGENCY, false);
        admit(ctx, "vikram", "CARD", "dr_iyer", "PVT", "503", "A", 3.0, 1,
                "Exertional chest discomfort; positive treadmill test; elective coronary angiography.",
                Admission.AdmissionType.ELECTIVE, false);
        admit(ctx, "fatima", "GEN_MED", "dr_mehta", null, null, null, 0.15, 4,
                "Uncontrolled blood sugars with dehydration; awaiting medical bed.",
                Admission.AdmissionType.EMERGENCY, false);
        // Historical admissions, discharged.
        admit(ctx, "lakshmi", "CARD", "dr_iyer", "CCU", "302", "A", 14.0, null,
                "Acute decompensated heart failure.", Admission.AdmissionType.EMERGENCY, false);
        discharge(ctx, "lakshmi", 9.0);
        admit(ctx, "rohan", "ORTHO", "dr_khan", "SWB", "403", "A", 34.0, null,
                "Closed fracture of the right radius and ulna after a two-wheeler fall; ORIF.", Admission.AdmissionType.EMERGENCY, false);
        discharge(ctx, "rohan", 30.0);
        admit(ctx, "sanjay", "SURG", "dr_khan", "PVT", "504", "A", 5.0, null,
                "Symptomatic cholelithiasis; elective laparoscopic cholecystectomy.", Admission.AdmissionType.ELECTIVE, false);
        discharge(ctx, "sanjay", 2.0);

        // Care-team members and bed housekeeping states for a realistic bed board.
        ctx.actAs("admin", () -> {
            admissionService.addCareTeamMember(ctx.admission("ravi"), ctx.user("nurse_priya").getId(),
                    com.careflow.hms.admission.AdmissionCareTeam.CareRole.PRIMARY_NURSE);
            admissionService.addCareTeamMember(ctx.admission("george"), ctx.user("dr_mehta").getId(),
                    com.careflow.hms.admission.AdmissionCareTeam.CareRole.CONSULTANT);
        });
        housekeeping("SWB", "402", "A", Bed.Status.CLEANING, "Post-discharge cleaning");
        housekeeping("MWA", "203", "D", Bed.Status.MAINTENANCE, "Bed rail repair");
        housekeeping("ICU", "ICU-2", "A", Bed.Status.RESERVED, "Reserved for post-op case");
        ctx.log.add("core seeded");
    }

    // ------------------------------------------------------------------ helpers

    private void dept(DemoContext ctx, String code, String name, String specialty) {
        Department d = new Department();
        d.setCode(code);
        d.setName(name);
        d.setSpecialty(specialty);
        ctx.departments.put(code, departments.save(d).getId());
    }

    private void ward(DemoContext ctx, String code, String name, Ward.WardType type, String dept, String floor,
                      Ward.GenderPolicy policy, String[] roomNumbers, Room.RoomType roomType, int bedsPerRoom,
                      Bed.BedType bedType, boolean isolation) {
        Ward w = new Ward();
        w.setCode(code);
        w.setName(name);
        w.setWardType(type);
        w.setDepartmentId(dept == null ? null : ctx.departments.get(dept));
        w.setFloor(floor);
        w.setGenderPolicy(policy);
        w = wards.save(w);
        ctx.wards.put(code, w.getId());
        for (String rn : roomNumbers) {
            Room r = new Room();
            r.setWardId(w.getId());
            r.setRoomNumber(rn);
            r.setRoomType(roomType);
            r = rooms.save(r);
            for (int i = 0; i < bedsPerRoom; i++) {
                bed(r, String.valueOf((char) ('A' + i)), bedType, isolation || type == Ward.WardType.ICU && i == 0);
            }
        }
    }

    private void bed(Room r, String number, Bed.BedType type, boolean isolation) {
        Bed b = new Bed();
        b.setWardId(r.getWardId());
        b.setRoomId(r.getId());
        b.setBedNumber(number);
        b.setBedType(type);
        b.setIsolationCapable(isolation);
        b.setOxygenSupported(type == Bed.BedType.ICU || r.getRoomType() != Room.RoomType.GENERAL);
        beds.save(b);
    }

    private void tariff(DemoContext ctx, String code, String name, ServiceTariff.Category cat, String price, String tax,
                        ServiceTariff.BillingUnit unit, String roomType) {
        ServiceTariff t = new ServiceTariff();
        t.setCode(code);
        t.setName(name);
        t.setCategory(cat);
        t.setUnitPrice(new BigDecimal(price));
        t.setTaxRate(new BigDecimal(tax));
        t.setBillingUnit(unit);
        t.setRoomType(roomType);
        t.setEffectiveFrom(LocalDate.now().minusYears(1));
        ctx.tariffs.put(code, tariffs.save(t).getId());
    }

    private void user(DemoContext ctx, String key, String email, String name, Role role, String dept, String designation) {
        if (email == null) {
            return;
        }
        User u = new User();
        u.setEmail(email);
        u.setFullName(name);
        u.setRole(role);
        u.setPasswordHash(demoHash);
        u.setPhone("+91 90000 " + String.format("%05d", Math.abs(email.hashCode()) % 100000));
        u = users.save(u);
        ctx.users.put(key, u);
        StaffProfile p = new StaffProfile();
        p.setUserId(u.getId());
        p.setOrganizationId(u.getOrganizationId());
        p.setDepartmentId(dept == null ? null : ctx.departments.get(dept));
        p.setDesignation(designation);
        p.setEmployeeCode("EMP-" + String.format("%04d", ctx.users.size()));
        staffProfiles.save(p);
    }

    private void specialty(String key, String specialty, String reg) {
        em.flush();
        // Applied after flush via JDBC to keep the helper short.
        jdbc.update("UPDATE staff_profiles SET specialty = ?, registration_number = ?, qualification = 'MBBS, MD (demo)' "
                + "WHERE user_id = (SELECT id FROM users WHERE email = ?)", specialty, reg,
                switch (key) {
                    case "dr_mehta" -> "dr.mehta@careflow.local";
                    case "dr_iyer" -> "dr.iyer@careflow.local";
                    case "dr_khan" -> "dr.khan@careflow.local";
                    case "dr_rao_paeds" -> "dr.rao@careflow.local";
                    default -> "dr.bose@careflow.local";
                });
    }

    private void assignWard(DemoContext ctx, String userKey, String wardCode) {
        StaffWardAssignment a = new StaffWardAssignment();
        a.setUserId(ctx.user(userKey).getId());
        a.setWardId(ctx.wards.get(wardCode));
        wardAssignments.save(a);
    }

    private void patient(DemoContext ctx, String key, String first, String last, LocalDate dob, Patient.Gender g,
                         String blood, String phone, String city, String ecName, String ecPhone, String ecRel) {
        Patient p = new Patient();
        p.setMrn(sequences.mrn());
        p.setFirstName(first);
        p.setLastName(last);
        p.setDateOfBirth(dob);
        p.setGender(g);
        p.setBloodGroup(blood);
        p.setPhone(phone);
        p.setEmail(key + "@demo.careflow.local");
        p.setAddressLine("Demo address " + (ctx.patients.size() + 1));
        p.setCity(city);
        p.setState("Karnataka");
        p.setPincode("5600" + String.format("%02d", ctx.patients.size() + 1));
        p.setEmergencyContactName(ecName);
        p.setEmergencyContactPhone(ecPhone);
        p.setEmergencyContactRelation(ecRel);
        p.setPreferredLanguage("English");
        p.setCreatedBy(ctx.user("admin").getId());
        ctx.patients.put(key, patients.save(p).getId());
    }

    private void allergy(DemoContext ctx, String key, String allergen, Allergy.AllergyType type, String reaction, Allergy.Severity sev) {
        Allergy a = new Allergy();
        a.setPatientId(ctx.patient(key));
        a.setAllergen(allergen);
        a.setAllergyType(type);
        a.setReaction(reaction);
        a.setSeverity(sev);
        a.setRecordedBy(ctx.user("dr_mehta").getId());
        a.setRecordedAt(DemoContext.daysAgo(40));
        allergies.save(a);
    }

    private void history(DemoContext ctx, String key, MedicalHistory.Category cat, String condition, String details, Integer yearsAgo) {
        MedicalHistory h = new MedicalHistory();
        h.setPatientId(ctx.patient(key));
        h.setCategory(cat);
        h.setConditionName(condition);
        h.setDetails(details);
        h.setOnsetDate(yearsAgo == null ? null : LocalDate.now().minusYears(yearsAgo));
        h.setRecordedBy(ctx.user("dr_mehta").getId());
        history.save(h);
    }

    private void portal(DemoContext ctx, String key, String email, String name, String patientKey) {
        User u = new User();
        u.setEmail(email);
        u.setFullName(name);
        u.setRole(Role.PATIENT);
        u.setPasswordHash(demoHash);
        u = users.save(u);
        ctx.users.put(key, u);
        if (patientKey != null) {
            Patient p = patients.findById(ctx.patient(patientKey)).orElseThrow();
            p.setUserId(u.getId());
        }
    }

    private void grant(DemoContext ctx, String patientKey, String userKey, String rel, PatientAccessGrant.Scope scope) {
        PatientAccessGrant g = new PatientAccessGrant();
        g.setPatientId(ctx.patient(patientKey));
        g.setGranteeUserId(ctx.user(userKey).getId());
        g.setRelationship(rel);
        g.setScope(scope);
        g.setGrantedBy(ctx.user("admin").getId());
        g.setGrantedAt(DemoContext.daysAgo(3));
        grants.save(g);
    }

    private UUID findBed(String wardCode, String roomNumber, String bedNumber) {
        em.flush();
        return jdbc.queryForObject("""
                SELECT b.id FROM beds b JOIN rooms r ON r.id = b.room_id JOIN wards w ON w.id = b.ward_id
                WHERE w.code = ? AND r.room_number = ? AND b.bed_number = ?
                  AND w.organization_id = ?
                """, UUID.class, wardCode, roomNumber, bedNumber, TenantContext.requireOrganizationId());
    }

    private void admit(DemoContext ctx, String key, String dept, String doctor, String ward, String room, String bed,
                       double daysAgo, Integer expectedStayDays, String reason, Admission.AdmissionType type, boolean isolation) {
        Instant admittedAt = DemoContext.daysAgo(daysAgo);
        UUID bedId = ward == null ? null : findBed(ward, room, bed);
        LocalDate expected = expectedStayDays == null ? null : LocalDate.now().plusDays(expectedStayDays);
        AdmissionDtos.AdmissionDetail d = ctx.actAs("admin", () -> admissionService.admit(new AdmissionDtos.AdmitRequest(
                ctx.patient(key), type, admittedAt, reason, ctx.departments.get(dept), ctx.user(doctor).getId(),
                null, expected, isolation, isolation ? "Isolation-capable bed" : null, bedId)));
        UUID admissionId = d.summary().id();
        ctx.admissions.put(key, admissionId);
        // Backdate the bed occupancy to the admission time (seeded history).
        bedAssignments.findByAdmissionIdAndReleasedAtIsNull(admissionId).ifPresent(ba -> {
            ba.setAssignedAt(admittedAt.plusSeconds(1800));
            bedAssignments.save(ba);
        });
        em.flush();
        jdbc.update("UPDATE timeline_events SET occurred_at = ? WHERE admission_id = ? AND event_type = 'BED_ASSIGNED'",
                java.sql.Timestamp.from(admittedAt.plusSeconds(1800)), admissionId);
        jdbc.update("UPDATE invoices SET created_at = ? WHERE admission_id = ?", java.sql.Timestamp.from(admittedAt), admissionId);
        jdbc.update("UPDATE timeline_events SET occurred_at = ? WHERE admission_id = ? AND event_type = 'INVOICE_CREATED'",
                java.sql.Timestamp.from(admittedAt.plusSeconds(60)), admissionId);
        jdbc.update("UPDATE notifications SET created_at = ?, read_at = ? WHERE entity_id = ? AND created_at > ?",
                java.sql.Timestamp.from(admittedAt.plusSeconds(600)), java.sql.Timestamp.from(admittedAt.plusSeconds(3600)),
                admissionId, java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
    }

    /** Marks a seeded historical admission as discharged (the discharge contributor adds its workflow records). */
    private void discharge(DemoContext ctx, String key, double daysAgo) {
        Instant at = DemoContext.daysAgo(daysAgo);
        Admission a = admissions.findById(ctx.admission(key)).orElseThrow();
        bedAssignments.findByAdmissionIdAndReleasedAtIsNull(a.getId()).ifPresent(ba -> {
            ba.setReleasedAt(at);
            ba.setReleasedBy(ctx.user("nurse_priya").getId());
            ba.setReleaseReason(BedAssignment.ReleaseReason.DISCHARGE);
            bedAssignments.save(ba);
            beds.findById(ba.getBedId()).ifPresent(b -> {
                b.setStatus(Bed.Status.AVAILABLE);
                b.setStatusChangedAt(at.plusSeconds(5400));
            });
        });
        a.setStatus(Admission.Status.DISCHARGED);
        a.setDischargedAt(at);
        a.setDischargeDisposition(Admission.Disposition.HOME);
        a.setExpectedDischargeDate(com.careflow.hms.common.TimeUtil.localDate(at, ctx.organization().zone()));
        admissions.saveAndFlush(a);
    }

    private void housekeeping(String ward, String room, String bed, Bed.Status status, String reason) {
        UUID id = findBed(ward, room, bed);
        beds.findById(id).ifPresent(b -> {
            b.setStatus(status);
            b.setNotes(reason);
            b.setStatusChangedAt(Instant.now().minusSeconds(2400));
        });
    }
}
