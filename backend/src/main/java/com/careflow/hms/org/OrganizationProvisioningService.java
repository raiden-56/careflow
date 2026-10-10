package com.careflow.hms.org;

import com.careflow.hms.clinical.ThresholdRule;
import com.careflow.hms.clinical.ThresholdRuleRepository;
import com.careflow.hms.discharge.DischargeStepTemplate;
import com.careflow.hms.discharge.DischargeStepTemplateRepository;
import com.careflow.hms.integration.IntegrationConfig;
import com.careflow.hms.integration.IntegrationConfigRepository;
import com.careflow.hms.notification.EscalationRule;
import com.careflow.hms.notification.EscalationRuleRepository;
import com.careflow.hms.notification.NotificationTemplate;
import com.careflow.hms.notification.NotificationTemplateRepository;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.RolePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Applies default configuration to a newly registered organization. All defaults are editable by
 * the hospital; clinical thresholds in particular are EXAMPLE values that the hospital's clinical
 * governance team must review before go-live.
 */
@Service
@RequiredArgsConstructor
public class OrganizationProvisioningService {

    private final RolePermissionService rolePermissions;
    private final DischargeStepTemplateRepository dischargeTemplates;
    private final ThresholdRuleRepository thresholds;
    private final EscalationRuleRepository escalations;
    private final NotificationTemplateRepository notificationTemplates;
    private final IntegrationConfigRepository integrations;

    @Transactional
    public void provision(Organization org) {
        UUID orgId = org.getId();
        rolePermissions.applyDefaultTemplates(orgId);
        if (dischargeTemplates.findByOrganizationIdOrderByStepOrder(orgId).isEmpty()) {
            createDischargeTemplates(orgId);
        }
        if (thresholds.findByOrganizationIdOrderByParameter(orgId).isEmpty()) {
            createThresholds(orgId);
        }
        if (escalations.findByOrganizationIdOrderByEventTypeAscEscalationLevelAsc(orgId).isEmpty()) {
            createEscalations(orgId);
        }
        if (notificationTemplates.findByOrganizationIdOrderByCode(orgId).isEmpty()) {
            createNotificationTemplates(orgId);
        }
        if (integrations.findByOrganizationIdOrderByIntegrationType(orgId).isEmpty()) {
            createIntegrationBoundaries(orgId);
        }
    }

    private void createDischargeTemplates(UUID orgId) {
        Object[][] steps = {
                {"CLINICAL_DECISION", "Clinical discharge decision & authorization", "Treating clinician records the discharge decision.", DischargeStepTemplate.Category.CLINICAL, Role.DOCTOR, true, "CLINICAL_AUTHORIZED"},
                {"CLINICAL_REVIEWS", "Required clinical reviews completed", "All open clinical alerts and escalations are resolved or handed over.", DischargeStepTemplate.Category.CLINICAL, Role.DOCTOR, true, "OPEN_ALERTS"},
                {"RESULTS_REVIEWED", "Outstanding investigation results reviewed or handled", "Every ordered investigation is reviewed, cancelled or explicitly handed to follow-up.", DischargeStepTemplate.Category.CLINICAL, Role.DOCTOR, true, "PENDING_RESULTS"},
                {"MED_RECONCILIATION", "Medication reconciliation completed", "Inpatient orders reconciled against discharge medications.", DischargeStepTemplate.Category.PHARMACY, Role.PHARMACIST, true, null},
                {"DISCHARGE_PRESCRIPTIONS", "Discharge prescriptions and instructions prepared", "Discharge medications prescribed with patient instructions.", DischargeStepTemplate.Category.CLINICAL, Role.DOCTOR, true, "DISCHARGE_MEDS"},
                {"NURSING_EDUCATION", "Nursing education and care instructions completed", "Patient/caregiver education on medications, wound care and warning signs.", DischargeStepTemplate.Category.NURSING, Role.NURSE, true, null},
                {"FOLLOW_UP", "Follow-up appointments and recommendations documented", "Follow-up appointments, result reviews and responsibilities recorded.", DischargeStepTemplate.Category.CLINICAL, Role.DOCTOR, true, "FOLLOW_UP_PLANNED"},
                {"DOCUMENTATION", "Required documentation completed", "Discharge summary finalized by the treating clinician.", DischargeStepTemplate.Category.CLINICAL, Role.DOCTOR, true, "SUMMARY_FINAL"},
                {"FINAL_BILL", "Final bill generated and reviewed", "All charges posted and the final invoice reviewed by billing.", DischargeStepTemplate.Category.FINANCIAL, Role.BILLING, true, "INVOICE_FINALIZED"},
                {"FINANCIAL_CLEARANCE", "Financial clearance or authorized exception", "Balance settled, or an authorized exception recorded per policy.", DischargeStepTemplate.Category.FINANCIAL, Role.BILLING, true, "FINANCIAL_CLEARED"},
                {"PATIENT_ACK", "Patient / caregiver acknowledgement recorded", "Patient or caregiver acknowledges instructions and summary.", DischargeStepTemplate.Category.PATIENT, Role.NURSE, false, null},
                {"DEPARTURE", "Actual departure time and discharge status recorded", "Departure time and disposition recorded.", DischargeStepTemplate.Category.ADMINISTRATIVE, Role.NURSE, true, "DEPARTED"},
                {"BED_TURNOVER", "Bed moved into cleaning / turnover", "Vacated bed released to housekeeping.", DischargeStepTemplate.Category.ADMINISTRATIVE, Role.HOSPITAL_ADMIN, true, "BED_RELEASED"},
        };
        int order = 1;
        for (Object[] s : steps) {
            DischargeStepTemplate t = new DischargeStepTemplate();
            t.setOrganizationId(orgId);
            t.setCode((String) s[0]);
            t.setStepOrder(order++);
            t.setName((String) s[1]);
            t.setDescription((String) s[2]);
            t.setCategory((DischargeStepTemplate.Category) s[3]);
            t.setResponsibleRole((Role) s[4]);
            t.setRequired((Boolean) s[5]);
            t.setAutoCheck((String) s[6]);
            dischargeTemplates.save(t);
        }
    }

    private void createThresholds(UUID orgId) {
        threshold(orgId, ThresholdRule.Parameter.SPO2, "92", null, ThresholdRule.Severity.WARNING, "SpO2 below 92%");
        threshold(orgId, ThresholdRule.Parameter.SPO2, "88", null, ThresholdRule.Severity.CRITICAL, "SpO2 below 88%");
        threshold(orgId, ThresholdRule.Parameter.PULSE_BPM, "50", "110", ThresholdRule.Severity.WARNING, "Pulse outside 50-110 bpm");
        threshold(orgId, ThresholdRule.Parameter.PULSE_BPM, "40", "130", ThresholdRule.Severity.CRITICAL, "Pulse outside 40-130 bpm");
        threshold(orgId, ThresholdRule.Parameter.SYSTOLIC_BP, "90", "160", ThresholdRule.Severity.WARNING, "Systolic BP outside 90-160 mmHg");
        threshold(orgId, ThresholdRule.Parameter.SYSTOLIC_BP, "80", "180", ThresholdRule.Severity.CRITICAL, "Systolic BP outside 80-180 mmHg");
        threshold(orgId, ThresholdRule.Parameter.TEMPERATURE_C, "35.5", "38.0", ThresholdRule.Severity.WARNING, "Temperature outside 35.5-38.0 C");
        threshold(orgId, ThresholdRule.Parameter.TEMPERATURE_C, null, "39.5", ThresholdRule.Severity.CRITICAL, "Temperature above 39.5 C");
        threshold(orgId, ThresholdRule.Parameter.RESPIRATORY_RATE, "10", "24", ThresholdRule.Severity.WARNING, "Respiratory rate outside 10-24 /min");
        threshold(orgId, ThresholdRule.Parameter.RESPIRATORY_RATE, "8", "30", ThresholdRule.Severity.CRITICAL, "Respiratory rate outside 8-30 /min");
        threshold(orgId, ThresholdRule.Parameter.BLOOD_GLUCOSE, "70", "250", ThresholdRule.Severity.WARNING, "Blood glucose outside 70-250 mg/dL");
        threshold(orgId, ThresholdRule.Parameter.BLOOD_GLUCOSE, "54", "400", ThresholdRule.Severity.CRITICAL, "Blood glucose outside 54-400 mg/dL");
        threshold(orgId, ThresholdRule.Parameter.PAIN_SCORE, null, "7", ThresholdRule.Severity.WARNING, "Pain score above 7/10");
    }

    private void threshold(UUID orgId, ThresholdRule.Parameter p, String min, String max, ThresholdRule.Severity sev, String desc) {
        ThresholdRule r = new ThresholdRule();
        r.setOrganizationId(orgId);
        r.setParameter(p);
        r.setMinValue(min == null ? null : new BigDecimal(min));
        r.setMaxValue(max == null ? null : new BigDecimal(max));
        r.setSeverity(sev);
        r.setDescription(desc + " (example default - review with clinical governance)");
        r.setNotifyRole(sev == ThresholdRule.Severity.CRITICAL ? Role.DOCTOR : Role.NURSE);
        thresholds.save(r);
    }

    private void createEscalations(UUID orgId) {
        escalation(orgId, EscalationRule.EventType.MEDICATION_OVERDUE, 30, Role.NURSE, 1, "HIGH", "Dose not recorded 30 min after scheduled time: notify ward nurses");
        escalation(orgId, EscalationRule.EventType.MEDICATION_OVERDUE, 90, Role.DOCTOR, 2, "HIGH", "Dose still not recorded after 90 min: notify the attending doctor");
        escalation(orgId, EscalationRule.EventType.CRITICAL_OBSERVATION, 0, Role.DOCTOR, 1, "CRITICAL", "Critical observation threshold crossed: notify the attending doctor immediately");
        escalation(orgId, EscalationRule.EventType.ALERT_UNACKNOWLEDGED, 15, Role.DOCTOR, 1, "CRITICAL", "Critical alert unacknowledged for 15 min: re-notify the care team");
        escalation(orgId, EscalationRule.EventType.HANDOVER_UNACKNOWLEDGED, 60, Role.NURSE, 1, "HIGH", "Handover unacknowledged for 60 min: notify ward nurses");
        escalation(orgId, EscalationRule.EventType.LAB_RESULT_UNREVIEWED, 240, Role.DOCTOR, 1, "NORMAL", "Result unreviewed for 4 h: remind the ordering clinician");
        escalation(orgId, EscalationRule.EventType.DISCHARGE_BLOCKED, 240, Role.HOSPITAL_ADMIN, 1, "NORMAL", "Discharge blocked for 4 h: notify discharge coordination");
    }

    private void escalation(UUID orgId, EscalationRule.EventType type, int minutes, Role role, int level, String priority, String desc) {
        EscalationRule r = new EscalationRule();
        r.setOrganizationId(orgId);
        r.setEventType(type);
        r.setThresholdMinutes(minutes);
        r.setEscalateToRole(role);
        r.setEscalationLevel(level);
        r.setPriority(priority);
        r.setDescription(desc);
        escalations.save(r);
    }

    private void createNotificationTemplates(UUID orgId) {
        template(orgId, "FOLLOW_UP_SCHEDULED", "SMS", "Follow-up scheduled",
                "{{hospital}}: {{patient}}, your follow-up '{{title}}' is on {{when}}. Reply not monitored.");
        template(orgId, "FOLLOW_UP_SCHEDULED", "EMAIL", "Your follow-up appointment at {{hospital}}",
                "Dear {{patient}},\n\nYour follow-up '{{title}}' is scheduled for {{when}} at {{location}}.\n\n{{hospital}}");
        template(orgId, "FOLLOW_UP_REMINDER", "SMS", "Follow-up reminder",
                "{{hospital}} reminder: '{{title}}' on {{when}}.");
        template(orgId, "PAYMENT_RECEIVED", "IN_APP", "Payment received",
                "We received {{amount}} towards invoice {{invoice}}. Receipt {{receipt}}.");
        template(orgId, "DISCHARGE_COMPLETED", "IN_APP", "You have been discharged",
                "Your discharge summary and instructions are available in the portal.");
    }

    private void template(UUID orgId, String code, String channel, String subject, String body) {
        NotificationTemplate t = new NotificationTemplate();
        t.setOrganizationId(orgId);
        t.setCode(code);
        t.setChannel(channel);
        t.setSubject(subject);
        t.setBody(body);
        notificationTemplates.save(t);
    }

    private void createIntegrationBoundaries(UUID orgId) {
        integration(orgId, IntegrationConfig.IntegrationType.ABDM, IntegrationConfig.Mode.DISABLED, "Requires ABDM sandbox credentials, HFR/HPR registration and consent-manager flows before enabling.");
        integration(orgId, IntegrationConfig.IntegrationType.FHIR, IntegrationConfig.Mode.DISABLED, "FHIR R4 export boundary. Not connected.");
        integration(orgId, IntegrationConfig.IntegrationType.LIS, IntegrationConfig.Mode.DEV_ADAPTER, "Development adapter: results entered manually or via the mock LIS endpoint.");
        integration(orgId, IntegrationConfig.IntegrationType.PAYMENT_GATEWAY, IntegrationConfig.Mode.DISABLED, "Online payments not connected; counter payments are recorded by billing staff.");
        integration(orgId, IntegrationConfig.IntegrationType.SMS, IntegrationConfig.Mode.DEV_ADAPTER, "Development adapter: messages are written to the dev outbox, not sent.");
        integration(orgId, IntegrationConfig.IntegrationType.EMAIL, IntegrationConfig.Mode.DEV_ADAPTER, "Development adapter: messages are written to the dev outbox, not sent.");
        integration(orgId, IntegrationConfig.IntegrationType.INSURANCE_TPA, IntegrationConfig.Mode.DISABLED, "Claims are tracked manually; no TPA connectivity.");
    }

    private void integration(UUID orgId, IntegrationConfig.IntegrationType type, IntegrationConfig.Mode mode, String note) {
        IntegrationConfig c = new IntegrationConfig();
        c.setOrganizationId(orgId);
        c.setIntegrationType(type);
        c.setMode(mode);
        c.getSettings().put("note", note);
        integrations.save(c);
    }
}
