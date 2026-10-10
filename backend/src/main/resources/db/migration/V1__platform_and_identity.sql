-- =====================================================================
-- CareFlow HMS - V1: platform, organizations, identity and access
-- Conventions:
--   * UUID primary keys (non-enumerable identifiers).
--   * Human-readable identifiers (MRN, admission no, invoice no) are separate, unique per organization.
--   * All timestamps are TIMESTAMPTZ and stored in UTC.
--   * Foreign keys never cascade deletes: clinical/financial history is preserved.
--   * Mutable rows carry a "version" column for optimistic locking.
-- =====================================================================

CREATE TABLE organizations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(32)  NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    legal_name      VARCHAR(200),
    timezone        VARCHAR(64)  NOT NULL DEFAULT 'Asia/Kolkata',
    currency        VARCHAR(3)   NOT NULL DEFAULT 'INR',
    address         VARCHAR(500),
    city            VARCHAR(100),
    state           VARCHAR(100),
    phone           VARCHAR(32),
    email           VARCHAR(200),
    registration_number VARCHAR(100),
    is_platform     BOOLEAN      NOT NULL DEFAULT FALSE,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);

-- Key/value hospital settings (workflow switches, grace periods, operating hours...).
CREATE TABLE organization_settings (
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    setting_key     VARCHAR(100) NOT NULL,
    setting_value   VARCHAR(2000) NOT NULL,
    description     VARCHAR(500),
    updated_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, setting_key)
);

-- Per-organization numeric sequences used for MRN / admission / invoice / receipt numbers.
CREATE TABLE organization_sequences (
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    sequence_name   VARCHAR(50) NOT NULL,
    next_value      BIGINT      NOT NULL DEFAULT 1,
    PRIMARY KEY (organization_id, sequence_name)
);

CREATE TABLE roles (
    code        VARCHAR(32) PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500)
);

CREATE TABLE permissions (
    code        VARCHAR(64) PRIMARY KEY,
    category    VARCHAR(50) NOT NULL,
    description VARCHAR(500) NOT NULL
);

-- Permission templates per organization and role (least privilege, editable by admins).
CREATE TABLE role_permissions (
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    role_code       VARCHAR(32) NOT NULL REFERENCES roles(code),
    permission_code VARCHAR(64) NOT NULL REFERENCES permissions(code),
    granted_by      UUID,
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, role_code, permission_code)
);

CREATE TABLE departments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    code            VARCHAR(32)  NOT NULL,
    name            VARCHAR(200) NOT NULL,
    specialty       VARCHAR(200),
    description     VARCHAR(1000),
    head_user_id    UUID,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, code)
);

CREATE TABLE users (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID         NOT NULL REFERENCES organizations(id),
    email                 VARCHAR(200) NOT NULL,
    password_hash         VARCHAR(200) NOT NULL,
    full_name             VARCHAR(200) NOT NULL,
    phone                 VARCHAR(32),
    role_code             VARCHAR(32)  NOT NULL REFERENCES roles(code),
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED','DISABLED')),
    failed_login_count    INT          NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    password_changed_at   TIMESTAMPTZ,
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));
CREATE INDEX ix_users_org_role ON users (organization_id, role_code);

ALTER TABLE departments ADD CONSTRAINT fk_departments_head FOREIGN KEY (head_user_id) REFERENCES users(id);

-- Professional profile for staff users (doctors, nurses, billing, pharmacy, admin).
CREATE TABLE staff_profiles (
    user_id             UUID PRIMARY KEY REFERENCES users(id),
    organization_id     UUID         NOT NULL REFERENCES organizations(id),
    employee_code       VARCHAR(50),
    designation         VARCHAR(100),
    department_id       UUID REFERENCES departments(id),
    specialty           VARCHAR(200),
    registration_number VARCHAR(100),
    qualification       VARCHAR(200),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, employee_code)
);
CREATE INDEX ix_staff_department ON staff_profiles (department_id);

-- Rotating refresh-token sessions. Only the SHA-256 hash of the token is stored.
CREATE TABLE user_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id),
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    revoke_reason   VARCHAR(50),
    replaced_by_id  UUID REFERENCES user_sessions(id),
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(500)
);
CREATE INDEX ix_sessions_user ON user_sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(128) NOT NULL UNIQUE,
    purpose     VARCHAR(20)  NOT NULL DEFAULT 'RESET' CHECK (purpose IN ('RESET','RECOVERY','INVITE')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    ip_address  VARCHAR(64)
);

-- Append-only audit trail for sensitive reads and important changes.
CREATE TABLE audit_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id),
    actor_user_id   UUID REFERENCES users(id),
    actor_name      VARCHAR(200),
    actor_role      VARCHAR(32),
    action          VARCHAR(64)  NOT NULL,
    entity_type     VARCHAR(64),
    entity_id       UUID,
    patient_id      UUID,
    outcome         VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' CHECK (outcome IN ('SUCCESS','DENIED','FAILURE')),
    summary         VARCHAR(1000),
    details         JSONB,
    ip_address      VARCHAR(64),
    trace_id        VARCHAR(64),
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_org_time ON audit_events (organization_id, occurred_at DESC);
CREATE INDEX ix_audit_patient ON audit_events (patient_id, occurred_at DESC);
CREATE INDEX ix_audit_actor ON audit_events (actor_user_id, occurred_at DESC);
CREATE INDEX ix_audit_entity ON audit_events (entity_type, entity_id);

-- Audit rows are immutable.
CREATE OR REPLACE FUNCTION forbid_update_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Rows in % are append-only', TG_TABLE_NAME USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_immutable BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

-- ---------------------------------------------------------------------
-- Reference data: roles and permissions
-- ---------------------------------------------------------------------
INSERT INTO roles (code, name, description) VALUES
 ('SUPER_ADMIN',    'Super Administrator',  'Platform-level configuration, organizations and provisioning. No clinical access.'),
 ('HOSPITAL_ADMIN', 'Hospital Administrator','Hospital operations, beds, staff and configuration. Clinical access must be separately granted.'),
 ('DOCTOR',         'Doctor',               'Clinical review, documentation, prescribing and discharge authorization.'),
 ('NURSE',          'Nurse',                'Bedside care, medication administration, observations and handover.'),
 ('BILLING',        'Billing & Accounts',   'Invoices, payments, adjustments, insurance claims and financial reports.'),
 ('PHARMACIST',     'Pharmacist',           'Medication catalogue, inventory, prescription review and dispensing.'),
 ('PATIENT',        'Patient / Caregiver',  'Access to own records and to explicitly authorized dependants.');

INSERT INTO permissions (code, category, description) VALUES
 -- Platform
 ('ORG_MANAGE',              'Platform',   'Register and configure organizations'),
 ('PLATFORM_AUDIT_READ',     'Platform',   'View platform-wide audit and security events'),
 ('INTEGRATION_MANAGE',      'Platform',   'Configure integration boundaries'),
 -- Administration
 ('USER_MANAGE',             'Administration','Create and manage user accounts'),
 ('ROLE_MANAGE',             'Administration','Edit role permission templates'),
 ('DEPARTMENT_MANAGE',       'Administration','Manage departments and specialties'),
 ('FACILITY_MANAGE',         'Administration','Configure wards, rooms and beds'),
 ('TARIFF_MANAGE',           'Administration','Configure service and room tariffs'),
 ('SETTINGS_MANAGE',         'Administration','Manage hospital settings, notification templates and escalation rules'),
 ('AUDIT_READ',              'Administration','View hospital audit logs'),
 ('REPORT_OPERATIONAL',      'Reporting',  'View operational reports and dashboards'),
 ('REPORT_CLINICAL',         'Reporting',  'View clinical quality reports'),
 ('REPORT_FINANCIAL',        'Reporting',  'View financial reports'),
 -- Patients & ADT
 ('PATIENT_REGISTER',        'Patients',   'Register patients and edit demographics'),
 ('PATIENT_DEMOGRAPHICS_READ','Patients',  'View patient demographics and identifiers'),
 ('ADMISSION_MANAGE',        'Admissions', 'Create admissions and manage admission details'),
 ('BED_ASSIGN',              'Admissions', 'Assign and release beds'),
 ('BED_STATUS_MANAGE',       'Admissions', 'Change bed housekeeping/maintenance status'),
 ('TRANSFER_REQUEST',        'Admissions', 'Request patient transfers'),
 ('TRANSFER_COMPLETE',       'Admissions', 'Approve and complete patient transfers'),
 -- Clinical
 ('CLINICAL_READ',           'Clinical',   'Read clinical records for patients under care'),
 ('CLINICAL_NOTE_WRITE',     'Clinical',   'Write clinical/progress notes'),
 ('NURSING_NOTE_WRITE',      'Clinical',   'Write nursing assessments and observation notes'),
 ('DIAGNOSIS_WRITE',         'Clinical',   'Record diagnoses'),
 ('TREATMENT_PLAN_WRITE',    'Clinical',   'Create and revise treatment plans'),
 ('ALLERGY_WRITE',           'Clinical',   'Record allergies and medical history'),
 ('VITALS_WRITE',            'Clinical',   'Record vital signs'),
 ('PRESCRIBE',               'Clinical',   'Create, modify and discontinue medication orders'),
 ('MED_ADMINISTER',          'Clinical',   'Record medication administration events'),
 ('LAB_ORDER',               'Clinical',   'Order investigations'),
 ('LAB_COLLECT',             'Clinical',   'Record specimen collection'),
 ('LAB_RESULT_ENTER',        'Clinical',   'Enter investigation results'),
 ('LAB_RESULT_REVIEW',       'Clinical',   'Review and release investigation results'),
 ('PROCEDURE_WRITE',         'Clinical',   'Document procedures'),
 ('CARE_TASK_MANAGE',        'Clinical',   'Create and complete care tasks'),
 ('ALERT_MANAGE',            'Clinical',   'Raise, acknowledge and resolve clinical alerts and escalations'),
 ('HANDOVER_MANAGE',         'Clinical',   'Create and acknowledge shift handovers'),
 ('RELEASE_TO_PATIENT',      'Clinical',   'Release clinical information to the patient portal'),
 ('AI_DRAFT_USE',            'Clinical',   'Generate draft summaries for clinician review'),
 -- Pharmacy
 ('MEDICATION_CATALOGUE_MANAGE','Pharmacy','Manage the medication catalogue'),
 ('INVENTORY_MANAGE',        'Pharmacy',   'Receive stock, manage batches and adjust inventory'),
 ('DISPENSE',                'Pharmacy',   'Review prescriptions and dispense medication'),
 -- Billing
 ('BILLING_READ',            'Billing',    'View invoices, payments and balances'),
 ('BILLING_WRITE',           'Billing',    'Create invoices and post charges'),
 ('PAYMENT_RECORD',          'Billing',    'Record payments and issue receipts'),
 ('ADJUSTMENT_REQUEST',      'Billing',    'Request discounts, refunds, credit notes and write-offs'),
 ('ADJUSTMENT_APPROVE',      'Billing',    'Approve or reject financial adjustments'),
 ('INSURANCE_MANAGE',        'Billing',    'Manage insurance and third-party claims'),
 -- Discharge
 ('DISCHARGE_VIEW',          'Discharge',  'View discharge workflows'),
 ('DISCHARGE_CLINICAL_AUTHORIZE','Discharge','Record the clinical discharge decision'),
 ('DISCHARGE_STEP_UPDATE',   'Discharge',  'Update discharge checklist steps'),
 ('DISCHARGE_FINANCIAL_CLEAR','Discharge', 'Record financial clearance or an authorized exception'),
 ('DISCHARGE_COMPLETE',      'Discharge',  'Record actual departure and complete discharge'),
 ('DISCHARGE_SUMMARY_WRITE', 'Discharge',  'Prepare and finalize discharge summaries'),
 ('FOLLOWUP_MANAGE',         'Discharge',  'Create and manage follow-up plans'),
 -- Safety & consent
 ('INCIDENT_REPORT',         'Safety',     'Report safety incidents'),
 ('INCIDENT_REVIEW',         'Safety',     'Review and close safety incidents'),
 ('CONSENT_MANAGE',          'Privacy',    'Record and manage patient consent'),
 ('PATIENT_REQUEST_MANAGE',  'Privacy',    'Respond to patient record requests'),
 ('DOCUMENT_UPLOAD',         'Documents',  'Upload patient documents'),
 -- Patient portal
 ('PORTAL_ACCESS',           'Portal',     'Access own (and authorized dependants'') records in the patient portal');
