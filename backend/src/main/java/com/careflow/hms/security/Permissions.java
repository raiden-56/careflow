package com.careflow.hms.security;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Permission codes (mirrors the {@code permissions} table) and the default least-privilege
 * permission matrix applied to every new organization. Organizations can tailor their
 * templates via the role management screen; changes are audited.
 *
 * <p>Use in controllers as {@code @PreAuthorize("hasAuthority('PRESCRIBE')")}. Permissions are
 * necessary but NOT sufficient: object-level checks in {@link AccessPolicy} are always applied.
 */
public final class Permissions {

    // Platform
    public static final String ORG_MANAGE = "ORG_MANAGE";
    public static final String PLATFORM_AUDIT_READ = "PLATFORM_AUDIT_READ";
    public static final String INTEGRATION_MANAGE = "INTEGRATION_MANAGE";
    // Administration
    public static final String USER_MANAGE = "USER_MANAGE";
    public static final String ROLE_MANAGE = "ROLE_MANAGE";
    public static final String DEPARTMENT_MANAGE = "DEPARTMENT_MANAGE";
    public static final String FACILITY_MANAGE = "FACILITY_MANAGE";
    public static final String TARIFF_MANAGE = "TARIFF_MANAGE";
    public static final String SETTINGS_MANAGE = "SETTINGS_MANAGE";
    public static final String AUDIT_READ = "AUDIT_READ";
    public static final String REPORT_OPERATIONAL = "REPORT_OPERATIONAL";
    public static final String REPORT_CLINICAL = "REPORT_CLINICAL";
    public static final String REPORT_FINANCIAL = "REPORT_FINANCIAL";
    // Patients & ADT
    public static final String PATIENT_REGISTER = "PATIENT_REGISTER";
    public static final String PATIENT_DEMOGRAPHICS_READ = "PATIENT_DEMOGRAPHICS_READ";
    public static final String ADMISSION_MANAGE = "ADMISSION_MANAGE";
    public static final String BED_ASSIGN = "BED_ASSIGN";
    public static final String BED_STATUS_MANAGE = "BED_STATUS_MANAGE";
    public static final String TRANSFER_REQUEST = "TRANSFER_REQUEST";
    public static final String TRANSFER_COMPLETE = "TRANSFER_COMPLETE";
    // Clinical
    public static final String CLINICAL_READ = "CLINICAL_READ";
    public static final String CLINICAL_NOTE_WRITE = "CLINICAL_NOTE_WRITE";
    public static final String NURSING_NOTE_WRITE = "NURSING_NOTE_WRITE";
    public static final String DIAGNOSIS_WRITE = "DIAGNOSIS_WRITE";
    public static final String TREATMENT_PLAN_WRITE = "TREATMENT_PLAN_WRITE";
    public static final String ALLERGY_WRITE = "ALLERGY_WRITE";
    public static final String VITALS_WRITE = "VITALS_WRITE";
    public static final String PRESCRIBE = "PRESCRIBE";
    public static final String MED_ADMINISTER = "MED_ADMINISTER";
    public static final String LAB_ORDER = "LAB_ORDER";
    public static final String LAB_COLLECT = "LAB_COLLECT";
    public static final String LAB_RESULT_ENTER = "LAB_RESULT_ENTER";
    public static final String LAB_RESULT_REVIEW = "LAB_RESULT_REVIEW";
    public static final String PROCEDURE_WRITE = "PROCEDURE_WRITE";
    public static final String CARE_TASK_MANAGE = "CARE_TASK_MANAGE";
    public static final String ALERT_MANAGE = "ALERT_MANAGE";
    public static final String HANDOVER_MANAGE = "HANDOVER_MANAGE";
    public static final String RELEASE_TO_PATIENT = "RELEASE_TO_PATIENT";
    public static final String AI_DRAFT_USE = "AI_DRAFT_USE";
    // Pharmacy
    public static final String MEDICATION_CATALOGUE_MANAGE = "MEDICATION_CATALOGUE_MANAGE";
    public static final String INVENTORY_MANAGE = "INVENTORY_MANAGE";
    public static final String DISPENSE = "DISPENSE";
    // Billing
    public static final String BILLING_READ = "BILLING_READ";
    public static final String BILLING_WRITE = "BILLING_WRITE";
    public static final String PAYMENT_RECORD = "PAYMENT_RECORD";
    public static final String ADJUSTMENT_REQUEST = "ADJUSTMENT_REQUEST";
    public static final String ADJUSTMENT_APPROVE = "ADJUSTMENT_APPROVE";
    public static final String INSURANCE_MANAGE = "INSURANCE_MANAGE";
    // Discharge
    public static final String DISCHARGE_VIEW = "DISCHARGE_VIEW";
    public static final String DISCHARGE_CLINICAL_AUTHORIZE = "DISCHARGE_CLINICAL_AUTHORIZE";
    public static final String DISCHARGE_STEP_UPDATE = "DISCHARGE_STEP_UPDATE";
    public static final String DISCHARGE_FINANCIAL_CLEAR = "DISCHARGE_FINANCIAL_CLEAR";
    public static final String DISCHARGE_COMPLETE = "DISCHARGE_COMPLETE";
    public static final String DISCHARGE_SUMMARY_WRITE = "DISCHARGE_SUMMARY_WRITE";
    public static final String FOLLOWUP_MANAGE = "FOLLOWUP_MANAGE";
    // Safety, privacy, documents
    public static final String INCIDENT_REPORT = "INCIDENT_REPORT";
    public static final String INCIDENT_REVIEW = "INCIDENT_REVIEW";
    public static final String CONSENT_MANAGE = "CONSENT_MANAGE";
    public static final String PATIENT_REQUEST_MANAGE = "PATIENT_REQUEST_MANAGE";
    public static final String DOCUMENT_UPLOAD = "DOCUMENT_UPLOAD";
    // Portal
    public static final String PORTAL_ACCESS = "PORTAL_ACCESS";

    private Permissions() {
    }

    /** Default permission matrix. SUPER_ADMIN deliberately has no clinical or financial permissions. */
    public static final Map<Role, Set<String>> DEFAULT_TEMPLATES = new EnumMap<>(Map.of(
            Role.SUPER_ADMIN, Set.of(
                    ORG_MANAGE, PLATFORM_AUDIT_READ, INTEGRATION_MANAGE, USER_MANAGE, ROLE_MANAGE, SETTINGS_MANAGE),
            Role.HOSPITAL_ADMIN, Set.of(
                    USER_MANAGE, ROLE_MANAGE, DEPARTMENT_MANAGE, FACILITY_MANAGE, TARIFF_MANAGE, SETTINGS_MANAGE,
                    AUDIT_READ, REPORT_OPERATIONAL, REPORT_FINANCIAL, PATIENT_REGISTER, PATIENT_DEMOGRAPHICS_READ,
                    ADMISSION_MANAGE, BED_ASSIGN, BED_STATUS_MANAGE, TRANSFER_REQUEST, TRANSFER_COMPLETE,
                    DISCHARGE_VIEW, DISCHARGE_STEP_UPDATE, DISCHARGE_COMPLETE, INCIDENT_REPORT, INCIDENT_REVIEW,
                    CONSENT_MANAGE, PATIENT_REQUEST_MANAGE, DOCUMENT_UPLOAD, INTEGRATION_MANAGE),
            Role.DOCTOR, Set.of(
                    PATIENT_DEMOGRAPHICS_READ, CLINICAL_READ, CLINICAL_NOTE_WRITE, DIAGNOSIS_WRITE, TREATMENT_PLAN_WRITE,
                    ALLERGY_WRITE, VITALS_WRITE, PRESCRIBE, LAB_ORDER, LAB_RESULT_ENTER, LAB_RESULT_REVIEW,
                    PROCEDURE_WRITE, CARE_TASK_MANAGE, ALERT_MANAGE, RELEASE_TO_PATIENT, AI_DRAFT_USE,
                    TRANSFER_REQUEST, DISCHARGE_VIEW, DISCHARGE_CLINICAL_AUTHORIZE, DISCHARGE_STEP_UPDATE,
                    DISCHARGE_SUMMARY_WRITE, FOLLOWUP_MANAGE, INCIDENT_REPORT, REPORT_CLINICAL, CONSENT_MANAGE,
                    DOCUMENT_UPLOAD),
            Role.NURSE, Set.of(
                    PATIENT_DEMOGRAPHICS_READ, CLINICAL_READ, NURSING_NOTE_WRITE, ALLERGY_WRITE, VITALS_WRITE,
                    MED_ADMINISTER, LAB_COLLECT, LAB_RESULT_ENTER, CARE_TASK_MANAGE, ALERT_MANAGE, HANDOVER_MANAGE,
                    AI_DRAFT_USE, TRANSFER_REQUEST, BED_STATUS_MANAGE, DISCHARGE_VIEW, DISCHARGE_STEP_UPDATE,
                    FOLLOWUP_MANAGE, INCIDENT_REPORT, CONSENT_MANAGE, DOCUMENT_UPLOAD),
            Role.BILLING, Set.of(
                    PATIENT_DEMOGRAPHICS_READ, BILLING_READ, BILLING_WRITE, PAYMENT_RECORD, ADJUSTMENT_REQUEST,
                    ADJUSTMENT_APPROVE, INSURANCE_MANAGE, REPORT_FINANCIAL, DISCHARGE_VIEW, DISCHARGE_STEP_UPDATE,
                    DISCHARGE_FINANCIAL_CLEAR, INCIDENT_REPORT),
            Role.PHARMACIST, Set.of(
                    PATIENT_DEMOGRAPHICS_READ, MEDICATION_CATALOGUE_MANAGE, INVENTORY_MANAGE, DISPENSE,
                    DISCHARGE_VIEW, DISCHARGE_STEP_UPDATE, INCIDENT_REPORT),
            Role.PATIENT, Set.of(PORTAL_ACCESS)));
}
