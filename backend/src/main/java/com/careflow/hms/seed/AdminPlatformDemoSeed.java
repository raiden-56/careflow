package com.careflow.hms.seed;

import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditEventRepository;
import com.careflow.hms.consent.ConsentRecord;
import com.careflow.hms.consent.PatientRequest;
import com.careflow.hms.consent.PrivacyDtos;
import com.careflow.hms.consent.PrivacyService;
import com.careflow.hms.document.DocumentService;
import com.careflow.hms.document.PatientDocument;
import com.careflow.hms.facility.Bed;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.facility.Department;
import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.facility.Room;
import com.careflow.hms.facility.RoomRepository;
import com.careflow.hms.facility.Ward;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.incident.IncidentDtos;
import com.careflow.hms.incident.IncidentReport;
import com.careflow.hms.incident.IncidentReportRepository;
import com.careflow.hms.incident.IncidentService;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationProvisioningService;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.org.SequenceService;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.StaffProfile;
import com.careflow.hms.user.StaffProfileRepository;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administration / safety / privacy / platform demo data (all fictional):
 * a second organization for tenant-isolation demos, safety incidents in several review states, consent
 * records, patient requests, a released lab-report PDF and a short history of audit events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminPlatformDemoSeed implements DemoSeedContributor {

    public static final String SUNRISE_CODE = "CFSUN";
    public static final String SUNRISE_ADMIN_EMAIL = "admin@sunrise.careflow.local";

    private final OrganizationRepository organizations;
    private final OrganizationProvisioningService provisioning;
    private final DepartmentRepository departments;
    private final WardRepository wards;
    private final RoomRepository rooms;
    private final BedRepository beds;
    private final UserRepository users;
    private final StaffProfileRepository profiles;
    private final PatientRepository patients;
    private final SequenceService sequences;
    private final PasswordEncoder passwordEncoder;
    private final IncidentService incidentService;
    private final IncidentReportRepository incidents;
    private final PrivacyService privacy;
    private final DocumentService documentService;
    private final AuditEventRepository auditEvents;
    private final JdbcTemplate jdbc;

    @Override
    public int order() {
        return 500;
    }

    @Override
    public String name() {
        return "Administration, safety, privacy and platform";
    }

    @Override
    public void seed(DemoContext ctx) {
        seedSunrise();
        seedIncidents(ctx);
        seedConsents(ctx);
        seedRequests(ctx);
        seedDocuments(ctx);
        seedAuditHistory(ctx);
        ctx.log.add("admin/platform seeded");
    }

    // ------------------------------------------------------------------ second organization

    private void seedSunrise() {
        if (organizations.findByCode(SUNRISE_CODE).isPresent()) {
            return;
        }
        Organization org = new Organization();
        org.setCode(SUNRISE_CODE);
        org.setName("Sunrise Community Hospital (Demo)");
        org.setLegalName("Sunrise Community Health Trust (fictional)");
        org.setAddress("7 Fictional Lake Road");
        org.setCity("Mysuru");
        org.setState("Karnataka");
        org.setPhone("+91 821 000 0000");
        org.setEmail("contact@sunrise.careflow.local");
        org.setRegistrationNumber("DEMO-REG-0002");
        Organization saved = organizations.saveAndFlush(org);
        provisioning.provision(saved);
        String hash = passwordEncoder.encode(PlatformBootstrap.DEMO_PASSWORD);
        TenantContext.runAs(saved.getId(), null, () -> {
            Department d = new Department();
            d.setCode("SUN_GM");
            d.setName("General Medicine");
            d.setSpecialty("Community general medicine");
            d = departments.save(d);
            Ward w = new Ward();
            w.setCode("SUN-GW");
            w.setName("Sunrise General Ward");
            w.setWardType(Ward.WardType.GENERAL);
            w.setDepartmentId(d.getId());
            w.setFloor("1");
            w = wards.save(w);
            for (String rn : new String[]{"S101", "S102"}) {
                Room r = new Room();
                r.setWardId(w.getId());
                r.setRoomNumber(rn);
                r.setRoomType(Room.RoomType.GENERAL);
                r = rooms.save(r);
                for (String bn : new String[]{"A", "B", "C"}) {
                    Bed b = new Bed();
                    b.setWardId(w.getId());
                    b.setRoomId(r.getId());
                    b.setBedNumber(bn);
                    b.setBedType(Bed.BedType.STANDARD);
                    beds.save(b);
                }
            }
            User admin = new User();
            admin.setEmail(SUNRISE_ADMIN_EMAIL);
            admin.setFullName("Meera Kulkarni");
            admin.setRole(Role.HOSPITAL_ADMIN);
            admin.setPasswordHash(hash);
            admin.setPhone("+91 90000 55501");
            admin = users.save(admin);
            StaffProfile p = new StaffProfile();
            p.setUserId(admin.getId());
            p.setOrganizationId(saved.getId());
            p.setDesignation("Hospital Administrator");
            p.setEmployeeCode("SUN-0001");
            profiles.save(p);
            Patient pt = new Patient();
            pt.setMrn(sequences.mrn());
            pt.setFirstName("Kamala");
            pt.setLastName("Venkatesh");
            pt.setDateOfBirth(LocalDate.now().minusYears(61).minusDays(12));
            pt.setGender(Patient.Gender.FEMALE);
            pt.setPhone("+91 98450 20001");
            pt.setCity("Mysuru");
            pt.setState("Karnataka");
            pt.setCreatedBy(admin.getId());
            patients.save(pt);
        });
    }

    // ------------------------------------------------------------------ incidents

    private void seedIncidents(DemoContext ctx) {
        UUID raviAdmission = ctx.admission("ravi");
        List<UUID> delayed = jdbc.queryForList("""
                SELECT id FROM medication_administrations WHERE admission_id = ? AND status IN ('DELAYED','MISSED','HELD')
                ORDER BY scheduled_at DESC LIMIT 1
                """, UUID.class, raviAdmission);
        UUID medEvent = delayed.isEmpty() ? null : delayed.get(0);

        IncidentDtos.IncidentDetail nearMiss = ctx.actAs("nurse_priya", () -> incidentService.report(new IncidentDtos.ReportRequest(
                IncidentReport.IncidentType.NEAR_MISS, IncidentReport.Severity.NO_HARM, DemoContext.hoursAgo(20),
                "Medical Ward A, medication trolley", ctx.wards.get("MWA"), ctx.patient("ravi"), raviAdmission, medEvent,
                medEvent != null
                        ? "Scheduled IV antibiotic dose was delayed while the infusion was prepared for the neighbouring bed. "
                        + "The mix-up was noticed at the bedside identity check before administration."
                        : "IV antibiotic prepared for bed 201-A was almost hung for the patient in bed 201-B. The mix-up was "
                        + "noticed at the bedside two-identifier check before administration.",
                "Dose withheld from the wrong patient, correct patient dosed after verification, charge nurse informed.")));
        backdate(nearMiss.id(), DemoContext.hoursAgo(20), DemoContext.hoursAgo(19));

        IncidentDtos.IncidentDetail fall = ctx.actAs("nurse_priya", () -> incidentService.report(new IncidentDtos.ReportRequest(
                IncidentReport.IncidentType.FALL, IncidentReport.Severity.MINOR, DemoContext.daysAgo(2.3),
                "Medical Ward A, room 202 bathroom", ctx.wards.get("MWA"), ctx.patient("deepa"), ctx.admission("deepa"), null,
                "Patient slipped on a wet floor while walking to the bathroom unaccompanied at night. Small abrasion on the left forearm.",
                "Patient assisted back to bed, vitals checked and stable, abrasion dressed, doctor on duty informed, floor dried.")));
        backdate(fall.id(), DemoContext.daysAgo(2.3), DemoContext.daysAgo(2.25));
        ctx.actAs("admin", () -> {
            incidentService.transition(fall.id(), new IncidentDtos.TransitionRequest(IncidentReport.Status.UNDER_REVIEW,
                    "Reviewing night-time mobility assessment and call-bell response for room 202.", null, version(fall.id())));
        });

        IncidentDtos.IncidentDetail equipment = ctx.actAs("nurse_fatima", () -> incidentService.report(new IncidentDtos.ReportRequest(
                IncidentReport.IncidentType.EQUIPMENT, IncidentReport.Severity.NO_HARM, DemoContext.daysAgo(5.1),
                "Cardiac Care Unit, bay 302", ctx.wards.get("CCU"), null, null, null,
                "Infusion pump (asset CCU-PMP-07, fictional) did not sound the occlusion alarm during the routine pre-use check.",
                "Pump removed from service and labelled faulty; replacement pump issued from the equipment library.")));
        backdate(equipment.id(), DemoContext.daysAgo(5.1), DemoContext.daysAgo(5.05));
        ctx.actAs("admin", () -> {
            incidentService.transition(equipment.id(), new IncidentDtos.TransitionRequest(IncidentReport.Status.UNDER_REVIEW,
                    "Biomedical engineering asked to inspect the pump fleet from the same batch.", null, version(equipment.id())));
            incidentService.transition(equipment.id(), new IncidentDtos.TransitionRequest(IncidentReport.Status.ACTION_REQUIRED,
                    "Two further pumps from the batch showed delayed alarms.",
                    "Inspect all 12 pumps of the batch; add an alarm self-test to the daily CCU equipment checklist.", version(equipment.id())));
        });

        IncidentDtos.IncidentDetail wristband = ctx.actAs("nurse_rahul", () -> incidentService.report(new IncidentDtos.ReportRequest(
                IncidentReport.IncidentType.PATIENT_IDENTIFICATION, IncidentReport.Severity.MINOR, DemoContext.daysAgo(9),
                "Surgical Ward B, admission desk", ctx.wards.get("SWB"), ctx.patient("irfan"), ctx.admission("irfan"), null,
                "Identification wristband printed with a misspelt surname; detected before the pre-operative check.",
                "Wristband reprinted and verified against the registration record with the patient.")));
        backdate(wristband.id(), DemoContext.daysAgo(9), DemoContext.daysAgo(8.95));
        ctx.actAs("admin", () -> {
            incidentService.transition(wristband.id(), new IncidentDtos.TransitionRequest(IncidentReport.Status.UNDER_REVIEW,
                    "Registration clerk confirmed manual re-entry of the surname.", null, version(wristband.id())));
            incidentService.transition(wristband.id(), new IncidentDtos.TransitionRequest(IncidentReport.Status.CLOSED,
                    "Root cause: manual re-typing at the ward desk instead of printing from the registration record.",
                    "Ward desks now print wristbands only from the registration system; reminder issued to all ward clerks.",
                    version(wristband.id())));
        });
        IncidentReport closed = incidents.findById(wristband.id()).orElseThrow();
        closed.setClosedAt(DemoContext.daysAgo(6));
        incidents.saveAndFlush(closed);

        IncidentDtos.IncidentDetail lookAlike = ctx.actAs("pharmacist", () -> incidentService.report(new IncidentDtos.ReportRequest(
                IncidentReport.IncidentType.NEAR_MISS, IncidentReport.Severity.NO_HARM, DemoContext.hoursAgo(30),
                "Main pharmacy, dispensing bench", null, null, null, null,
                "Two look-alike vials (different strengths) were stored in adjacent bins; the wrong strength was picked and caught at the final check.",
                "Bins separated and shelf labels highlighted; stock re-checked.")));
        backdate(lookAlike.id(), DemoContext.hoursAgo(30), DemoContext.hoursAgo(29));
    }

    private Long version(UUID incidentId) {
        return incidents.findById(incidentId).map(IncidentReport::getVersion).orElse(0L);
    }

    /** Backdates through the managed entity (so later flushes keep the values) and the timeline row via JDBC. */
    private void backdate(UUID incidentId, Instant occurred, Instant reported) {
        IncidentReport i = incidents.findById(incidentId).orElseThrow();
        i.setOccurredAt(occurred);
        i.setReportedAt(reported);
        incidents.saveAndFlush(i);
        jdbc.update("UPDATE timeline_events SET occurred_at = ? WHERE entity_id = ? AND event_type = 'INCIDENT_REPORTED'",
                Timestamp.from(occurred), incidentId);
    }

    // ------------------------------------------------------------------ consents & requests

    private void seedConsents(DemoContext ctx) {
        ctx.actAs("admin", () -> {
            consent(ctx, "ravi", "ravi", ConsentRecord.ConsentType.TREATMENT, "Inpatient treatment for community-acquired pneumonia, including IV antibiotics",
                    null, ConsentRecord.CaptureMethod.WRITTEN, "GRANTED", DemoContext.daysAgo(4.2), null);
            consent(ctx, "ravi", null, ConsentRecord.ConsentType.CAREGIVER_ACCESS, "Allow my spouse to view my records and bills in the patient portal",
                    "Sunita Sharma (spouse)", ConsentRecord.CaptureMethod.WRITTEN, "GRANTED", DemoContext.daysAgo(3), null);
            consent(ctx, "ravi", "ravi", ConsentRecord.ConsentType.INSURANCE_SHARING, "Share admission and billing details for cashless claim processing",
                    "Star Shield Health TPA (fictional)", ConsentRecord.CaptureMethod.WRITTEN, "GRANTED", DemoContext.daysAgo(4.1),
                    DemoContext.daysAgo(-60));
            consent(ctx, "ravi", null, ConsentRecord.ConsentType.COMMUNICATION, "Appointment reminders and health tips by SMS",
                    null, ConsentRecord.CaptureMethod.ELECTRONIC, "GRANTED", DemoContext.daysAgo(4.2), null);
            consent(ctx, "irfan", "irfan", ConsentRecord.ConsentType.PROCEDURE, "Laparoscopic appendectomy under general anaesthesia",
                    null, ConsentRecord.CaptureMethod.WRITTEN, "GRANTED", DemoContext.daysAgo(2.0), null);
            consent(ctx, "george", "george", ConsentRecord.ConsentType.TREATMENT, "Critical care treatment including central line insertion",
                    null, ConsentRecord.CaptureMethod.VERBAL, "GRANTED", DemoContext.daysAgo(6.2), null);
            consent(ctx, "aarav", "aarav", ConsentRecord.ConsentType.TREATMENT, "Paediatric inpatient asthma management (consent by parent)",
                    null, ConsentRecord.CaptureMethod.WRITTEN, "GRANTED", DemoContext.daysAgo(1.6), null);
            consent(ctx, "vikram", "vikram", ConsentRecord.ConsentType.PROCEDURE, "Elective coronary angiography with contrast premedication",
                    null, ConsentRecord.CaptureMethod.WRITTEN, "GRANTED", DemoContext.daysAgo(2.9), null);
            consent(ctx, "vikram", null, ConsentRecord.ConsentType.RESEARCH, "Inclusion of anonymised angiography data in a cardiology registry",
                    "CareFlow Cardiac Registry (fictional)", ConsentRecord.CaptureMethod.WRITTEN, "DECLINED", DemoContext.daysAgo(2.9), null);
        });
        // Ravi withdraws his communication consent himself in the portal.
        UUID communication = jdbc.queryForObject("""
                SELECT id FROM consent_records WHERE patient_id = ? AND consent_type = 'COMMUNICATION' LIMIT 1
                """, UUID.class, ctx.patient("ravi"));
        ctx.actAs("p_ravi", () -> privacy.portalWithdraw(ctx.patient("ravi"), communication, "I prefer reminders by email only."));
        jdbc.update("UPDATE consent_records SET withdrawn_at = ? WHERE id = ?", Timestamp.from(DemoContext.daysAgo(1)), communication);
    }

    private void consent(DemoContext ctx, String patientKey, String admissionKey, ConsentRecord.ConsentType type, String purpose,
                         String sharedWith, ConsentRecord.CaptureMethod method, String decision, Instant at, Instant expires) {
        privacy.recordConsent(new PrivacyDtos.ConsentRequest(ctx.patient(patientKey),
                admissionKey == null ? null : ctx.admission(admissionKey), type, purpose, sharedWith, method, decision, at, expires,
                null));
    }

    private void seedRequests(DemoContext ctx) {
        PrivacyDtos.RequestView copy = ctx.actAs("p_ravi", () -> privacy.portalSubmit(ctx.patient("ravi"), new PrivacyDtos.NewRequest(
                PatientRequest.RequestType.RECORD_COPY, "Copy of my admission records",
                "Please share a copy of my current admission records, including laboratory reports, for my family physician.")));
        PrivacyDtos.RequestView correction = ctx.actAs("p_ravi", () -> privacy.portalSubmit(ctx.patient("ravi"), new PrivacyDtos.NewRequest(
                PatientRequest.RequestType.CORRECTION, "Correct my emergency contact number",
                "The emergency contact number for my wife has one digit wrong. The correct number ends in 10002.")));
        ctx.actAs("admin", () -> privacy.respond(correction.id(), new PrivacyDtos.RespondRequest(PatientRequest.Status.IN_REVIEW,
                "Thank you. We are verifying the number with you at the bedside and will update your record.", correction.version())));
        jdbc.update("UPDATE patient_requests SET raised_at = ? WHERE id = ?", Timestamp.from(DemoContext.daysAgo(1.5)), copy.id());
        jdbc.update("UPDATE patient_requests SET raised_at = ?, responded_at = ? WHERE id = ?",
                Timestamp.from(DemoContext.daysAgo(2)), Timestamp.from(DemoContext.daysAgo(1.8)), correction.id());
    }

    // ------------------------------------------------------------------ documents

    private void seedDocuments(DemoContext ctx) {
        ZoneId zone = ctx.organization().zone();
        Patient ravi = patients.findById(ctx.patient("ravi")).orElseThrow();
        byte[] cbc = labReportPdf(ctx, ravi, zone);
        DocumentService.DocumentView doc = ctx.actAs("dr_mehta", () -> documentService.upload(ravi.getId(), ctx.admission("ravi"),
                PatientDocument.DocumentType.LAB_REPORT, "Complete blood count and CRP report", "CBC_CRP_report.pdf",
                "application/pdf", cbc));
        ctx.actAs("dr_mehta", () -> documentService.release(doc.id()));
        byte[] xray = simplePdf("Chest X-ray report (draft, fictional)", List.of(
                "Patient: " + ravi.fullName() + " (" + ravi.getMrn() + ")",
                "Findings: right lower zone consolidation. Awaiting consultant review before release.",
                "FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL MEDICAL RECORD"));
        ctx.actAs("dr_mehta", () -> documentService.upload(ravi.getId(), ctx.admission("ravi"),
                PatientDocument.DocumentType.IMAGING, "Chest X-ray report (awaiting review)", "CXR_report.pdf", "application/pdf", xray));
        jdbc.update("UPDATE documents SET uploaded_at = ? WHERE id = ?", Timestamp.from(DemoContext.daysAgo(3.5)), doc.id());
    }

    private byte[] labReportPdf(DemoContext ctx, Patient p, ZoneId zone) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document d = new Document(PageSize.A4, 48, 48, 48, 48);
        PdfWriter.getInstance(d, out);
        d.open();
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, new Color(16, 38, 58));
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(74, 91, 108));
        Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE);
        Font cell = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font warn = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(180, 35, 24));
        d.add(new Paragraph(ctx.organization().getName(), title));
        d.add(new Paragraph("Department of Laboratory Medicine - Haematology & Biochemistry", small));
        d.add(new Paragraph("FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL MEDICAL RECORD", warn));
        d.add(new Paragraph(" "));
        String collected = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").format(DemoContext.daysAgo(3.6).atZone(zone));
        d.add(new Paragraph("Patient: " + p.fullName() + "    MRN: " + p.getMrn() + "    Sex: " + p.getGender(), cell));
        d.add(new Paragraph("Referring doctor: Dr. Arjun Mehta    Collected: " + collected, cell));
        d.add(new Paragraph(" "));
        PdfPTable t = new PdfPTable(new float[]{3, 2, 1.5f, 2.5f, 1.5f});
        t.setWidthPercentage(100);
        for (String h : new String[]{"Test", "Result", "Unit", "Reference range", "Flag"}) {
            PdfPCell c = new PdfPCell(new Phrase(h, head));
            c.setBackgroundColor(new Color(15, 118, 110));
            c.setPadding(5);
            t.addCell(c);
        }
        String[][] rows = {
                {"Haemoglobin", "13.1", "g/dL", "13.0 - 17.0", ""},
                {"Total leucocyte count", "14.8", "x10^3/uL", "4.0 - 11.0", "HIGH"},
                {"Neutrophils", "82", "%", "40 - 75", "HIGH"},
                {"Platelets", "245", "x10^3/uL", "150 - 410", ""},
                {"C-reactive protein", "86", "mg/L", "< 5", "HIGH"},
                {"Random blood glucose", "212", "mg/dL", "70 - 140", "HIGH"}};
        for (String[] r : rows) {
            for (String v : r) {
                PdfPCell c = new PdfPCell(new Phrase(v, cell));
                c.setPadding(4);
                t.addCell(c);
            }
        }
        d.add(t);
        d.add(new Paragraph(" "));
        d.add(new Paragraph("Comment: Neutrophil leucocytosis with raised CRP, consistent with an acute bacterial infection. "
                + "Interpret with the clinical picture. Reviewed and released to the patient by the treating doctor.", cell));
        d.add(new Paragraph(" "));
        d.add(new Paragraph("Electronically verified - CareFlow demo laboratory (fictional).", small));
        d.close();
        return out.toByteArray();
    }

    private byte[] simplePdf(String heading, List<String> lines) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document d = new Document(PageSize.A4, 48, 48, 48, 48);
        PdfWriter.getInstance(d, out);
        d.open();
        d.add(new Paragraph(heading, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15)));
        for (String l : lines) {
            d.add(new Paragraph(l, FontFactory.getFont(FontFactory.HELVETICA, 10)));
        }
        d.close();
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ audit history

    private void seedAuditHistory(DemoContext ctx) {
        UUID org = ctx.orgId();
        UUID raviPatient = ctx.patient("ravi");
        UUID raviAdmission = ctx.admission("ravi");
        login(org, ctx.user("admin"), DemoContext.daysAgo(3.1), true);
        login(org, ctx.user("dr_mehta"), DemoContext.daysAgo(2.9), true);
        login(org, ctx.user("nurse_priya"), DemoContext.daysAgo(2.8), true);
        login(org, ctx.user("billing"), DemoContext.daysAgo(2.4), false);
        login(org, ctx.user("billing"), DemoContext.daysAgo(2.39), true);
        login(org, ctx.user("pharmacist"), DemoContext.daysAgo(1.2), true);
        login(org, ctx.user("admin"), DemoContext.hoursAgo(5), true);

        event(org, ctx.user("dr_mehta"), "READ_CLINICAL", "ADMISSION", raviAdmission, raviPatient, AuditEvent.Outcome.SUCCESS,
                "Viewed clinical records", null, DemoContext.daysAgo(2.85));
        event(org, ctx.user("nurse_priya"), "READ_MEDICATION", "ADMISSION", raviAdmission, raviPatient, AuditEvent.Outcome.SUCCESS,
                "Viewed medication records", null, DemoContext.daysAgo(2.7));
        event(org, ctx.user("billing"), "READ_FINANCIAL", "PATIENT", raviPatient, raviPatient, AuditEvent.Outcome.SUCCESS,
                "Viewed financial records", null, DemoContext.daysAgo(2.3));
        event(org, ctx.user("nurse_rahul"), "ACCESS_DENIED", "ADMISSION", raviAdmission, raviPatient, AuditEvent.Outcome.DENIED,
                "NURSE denied CLINICAL access", Map.of("scope", "CLINICAL"), DemoContext.daysAgo(1.9));
        event(org, ctx.user("dr_mehta"), "READ_CLINICAL", "ADMISSION", raviAdmission, raviPatient, AuditEvent.Outcome.SUCCESS,
                "Viewed clinical records", null, DemoContext.hoursAgo(26));

        UUID tariff = ctx.tariff("LAB_CRP");
        Map<String, Object> price = new LinkedHashMap<>();
        price.put("code", "LAB_CRP");
        price.put("oldUnitPrice", "500.00");
        price.put("newUnitPrice", "550.00");
        price.put("oldTaxRate", "0.00");
        price.put("newTaxRate", "0.00");
        event(org, ctx.user("admin"), "TARIFF_PRICE_CHANGED", "TARIFF", tariff, null, AuditEvent.Outcome.SUCCESS,
                "LAB_CRP price 500.00 -> 550.00, tax 0.00% -> 0.00%", price, DemoContext.daysAgo(12));
        event(org, ctx.user("admin"), "SETTINGS_UPDATED", "SETTINGS", null, null, AuditEvent.Outcome.SUCCESS,
                "Updated medication.overdue.graceMinutes", Map.of("medication.overdue.graceMinutes", Map.of("old", "45", "new", "30")),
                DemoContext.daysAgo(10));
        event(org, ctx.user("admin"), "USER_UPDATED", "USER", ctx.user("nurse_anita").getId(), null, AuditEvent.Outcome.SUCCESS,
                ctx.user("nurse_anita").getFullName(), null, DemoContext.daysAgo(8));
        event(org, ctx.user("admin"), "ROLE_PERMISSIONS_CHANGED", "ROLE", null, null, AuditEvent.Outcome.SUCCESS,
                "PHARMACIST +[INCIDENT_REPORT] -[]", null, DemoContext.daysAgo(15));

        // Platform-level security signals (visible to super administrators).
        AuditEvent unknown = new AuditEvent();
        unknown.setActorName("unknown.user@example.test");
        unknown.setAction("LOGIN_FAILED");
        unknown.setEntityType("USER");
        unknown.setOutcome(AuditEvent.Outcome.FAILURE);
        unknown.setSummary("Unknown account");
        unknown.setIpAddress("203.0.113.24");
        unknown.setOccurredAt(DemoContext.hoursAgo(9));
        auditEvents.save(unknown);
        event(org, ctx.user("billing_lead"), "LOGIN_BLOCKED", "USER", ctx.user("billing_lead").getId(), null, AuditEvent.Outcome.DENIED,
                "Account temporarily locked", null, DemoContext.hoursAgo(31));
        event(null, ctx.user("pharmacist"), "SESSION_REUSE_DETECTED", "USER", ctx.user("pharmacist").getId(), null,
                AuditEvent.Outcome.DENIED, "Refresh token reuse; all sessions revoked", null, DemoContext.daysAgo(4));
        organizations.findByCode(SUNRISE_CODE).ifPresent(sun -> users.findByEmailIgnoreCase(SUNRISE_ADMIN_EMAIL)
                .ifPresent(u -> login(sun.getId(), u, DemoContext.daysAgo(1.1), true)));
    }

    private void login(UUID org, User u, Instant at, boolean ok) {
        event(org, u, ok ? "LOGIN_SUCCESS" : "LOGIN_FAILED", "USER", u.getId(), null,
                ok ? AuditEvent.Outcome.SUCCESS : AuditEvent.Outcome.FAILURE, ok ? "Signed in" : "Invalid password (1)", null, at);
    }

    private void event(UUID org, User actor, String action, String entityType, UUID entityId, UUID patientId,
                       AuditEvent.Outcome outcome, String summary, Map<String, Object> details, Instant at) {
        AuditEvent e = new AuditEvent();
        e.setOrganizationId(org);
        e.setActorUserId(actor.getId());
        e.setActorName(actor.getFullName());
        e.setActorRole(actor.getRole().name());
        e.setAction(action);
        e.setEntityType(entityType);
        e.setEntityId(entityId);
        e.setPatientId(patientId);
        e.setOutcome(outcome);
        e.setSummary(summary);
        e.setDetails(details);
        e.setIpAddress("10.20.0." + (Math.abs(actor.getEmail().hashCode()) % 200 + 10));
        e.setOccurredAt(at);
        auditEvents.save(e);
    }
}
