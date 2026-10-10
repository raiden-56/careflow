-- =====================================================================
-- CareFlow HMS - V2: patients, facility (wards/rooms/beds), admissions
-- =====================================================================

CREATE TABLE patients (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id           UUID         NOT NULL REFERENCES organizations(id),
    mrn                       VARCHAR(32)  NOT NULL,
    first_name                VARCHAR(100) NOT NULL,
    last_name                 VARCHAR(100) NOT NULL,
    date_of_birth             DATE         NOT NULL,
    gender                    VARCHAR(16)  NOT NULL CHECK (gender IN ('MALE','FEMALE','OTHER','UNDISCLOSED')),
    blood_group               VARCHAR(8),
    phone                     VARCHAR(32),
    email                     VARCHAR(200),
    address_line              VARCHAR(500),
    city                      VARCHAR(100),
    state                     VARCHAR(100),
    pincode                   VARCHAR(12),
    emergency_contact_name    VARCHAR(200),
    emergency_contact_phone   VARCHAR(32),
    emergency_contact_relation VARCHAR(50),
    abha_number               VARCHAR(32),
    preferred_language        VARCHAR(50),
    user_id                   UUID REFERENCES users(id),
    status                    VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED','DECEASED')),
    created_by                UUID REFERENCES users(id),
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                   BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, mrn)
);
CREATE UNIQUE INDEX ux_patients_user ON patients (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ix_patients_name ON patients (organization_id, lower(last_name), lower(first_name));
CREATE INDEX ix_patients_phone ON patients (organization_id, phone);

-- Caregivers / guardians explicitly authorized to view a patient's records in the portal.
CREATE TABLE patient_access_grants (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID        NOT NULL REFERENCES organizations(id),
    patient_id       UUID        NOT NULL REFERENCES patients(id),
    grantee_user_id  UUID        NOT NULL REFERENCES users(id),
    relationship     VARCHAR(50) NOT NULL,
    scope            VARCHAR(16) NOT NULL DEFAULT 'FULL' CHECK (scope IN ('FULL','BILLING_ONLY','CLINICAL_ONLY')),
    granted_by       UUID REFERENCES users(id),
    granted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,
    revoked_at       TIMESTAMPTZ,
    revoked_by       UUID REFERENCES users(id),
    notes            VARCHAR(500)
);
CREATE UNIQUE INDEX ux_grants_active ON patient_access_grants (patient_id, grantee_user_id) WHERE revoked_at IS NULL;

CREATE TABLE allergies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    patient_id      UUID         NOT NULL REFERENCES patients(id),
    allergen        VARCHAR(200) NOT NULL,
    allergy_type    VARCHAR(16)  NOT NULL DEFAULT 'DRUG' CHECK (allergy_type IN ('DRUG','FOOD','ENVIRONMENTAL','OTHER')),
    reaction        VARCHAR(500),
    severity        VARCHAR(20)  NOT NULL CHECK (severity IN ('MILD','MODERATE','SEVERE','LIFE_THREATENING')),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','ENTERED_IN_ERROR')),
    recorded_by     UUID REFERENCES users(id),
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_allergies_patient ON allergies (patient_id);

CREATE TABLE medical_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    patient_id      UUID         NOT NULL REFERENCES patients(id),
    category        VARCHAR(16)  NOT NULL CHECK (category IN ('MEDICAL','SURGICAL','FAMILY','SOCIAL','MEDICATION')),
    condition_name  VARCHAR(300) NOT NULL,
    details         VARCHAR(2000),
    onset_date      DATE,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RESOLVED','ENTERED_IN_ERROR')),
    recorded_by     UUID REFERENCES users(id),
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_history_patient ON medical_history (patient_id);

-- ---------------------------------------------------------------------
-- Facility
-- ---------------------------------------------------------------------
CREATE TABLE wards (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    department_id   UUID REFERENCES departments(id),
    code            VARCHAR(32)  NOT NULL,
    name            VARCHAR(200) NOT NULL,
    ward_type       VARCHAR(20)  NOT NULL CHECK (ward_type IN ('GENERAL','ICU','HDU','PRIVATE','PAEDIATRIC','MATERNITY','ISOLATION','DAYCARE')),
    floor           VARCHAR(20),
    gender_policy   VARCHAR(16)  NOT NULL DEFAULT 'MIXED' CHECK (gender_policy IN ('MIXED','MALE','FEMALE')),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, code)
);

CREATE TABLE rooms (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    ward_id         UUID         NOT NULL REFERENCES wards(id),
    room_number     VARCHAR(20)  NOT NULL,
    room_type       VARCHAR(20)  NOT NULL CHECK (room_type IN ('GENERAL','SEMI_PRIVATE','PRIVATE','DELUXE','ICU','ISOLATION')),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (ward_id, room_number)
);

CREATE TABLE beds (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID         NOT NULL REFERENCES organizations(id),
    ward_id            UUID         NOT NULL REFERENCES wards(id),
    room_id            UUID         NOT NULL REFERENCES rooms(id),
    bed_number         VARCHAR(20)  NOT NULL,
    bed_type           VARCHAR(20)  NOT NULL DEFAULT 'STANDARD' CHECK (bed_type IN ('STANDARD','ICU','ISOLATION','PAEDIATRIC','BARIATRIC','MATERNITY')),
    status             VARCHAR(16)  NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','OCCUPIED','RESERVED','CLEANING','MAINTENANCE')),
    isolation_capable  BOOLEAN      NOT NULL DEFAULT FALSE,
    oxygen_supported   BOOLEAN      NOT NULL DEFAULT FALSE,
    notes              VARCHAR(500),
    status_changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (room_id, bed_number)
);
CREATE INDEX ix_beds_ward_status ON beds (ward_id, status);

CREATE TABLE bed_status_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    bed_id          UUID        NOT NULL REFERENCES beds(id),
    from_status     VARCHAR(16),
    to_status       VARCHAR(16) NOT NULL,
    reason          VARCHAR(500),
    changed_by      UUID REFERENCES users(id),
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_bed_status_history_bed ON bed_status_history (bed_id, changed_at DESC);

-- Nurses (and other staff) assigned to wards.
CREATE TABLE staff_ward_assignments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    user_id         UUID        NOT NULL REFERENCES users(id),
    ward_id         UUID        NOT NULL REFERENCES wards(id),
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by     UUID REFERENCES users(id),
    ended_at        TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_staff_ward_active ON staff_ward_assignments (user_id, ward_id) WHERE active;

-- ---------------------------------------------------------------------
-- Admissions (patient identity is separate; a patient can have many admissions)
-- ---------------------------------------------------------------------
CREATE TABLE admissions (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id          UUID         NOT NULL REFERENCES organizations(id),
    patient_id               UUID         NOT NULL REFERENCES patients(id),
    admission_number         VARCHAR(32)  NOT NULL,
    status                   VARCHAR(24)  NOT NULL CHECK (status IN ('PENDING_BED','ADMITTED','DISCHARGE_IN_PROGRESS','DISCHARGED','CANCELLED')),
    admission_type           VARCHAR(20)  NOT NULL CHECK (admission_type IN ('ELECTIVE','EMERGENCY','TRANSFER_IN','DAYCARE')),
    admitted_at              TIMESTAMPTZ  NOT NULL,
    admission_reason         VARCHAR(2000) NOT NULL,
    department_id            UUID         NOT NULL REFERENCES departments(id),
    attending_doctor_id      UUID         NOT NULL REFERENCES users(id),
    referring_doctor         VARCHAR(200),
    bed_requirements         VARCHAR(500),
    requires_isolation       BOOLEAN      NOT NULL DEFAULT FALSE,
    expected_discharge_date  DATE,
    discharged_at            TIMESTAMPTZ,
    discharge_disposition    VARCHAR(24) CHECK (discharge_disposition IN ('HOME','TRANSFERRED','LAMA','DECEASED','ABSCONDED','OTHER')),
    cancellation_reason      VARCHAR(500),
    created_by               UUID REFERENCES users(id),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                  BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, admission_number)
);
-- A patient can only have one open admission at a time.
CREATE UNIQUE INDEX ux_admissions_one_open_per_patient ON admissions (patient_id)
    WHERE status IN ('PENDING_BED','ADMITTED','DISCHARGE_IN_PROGRESS');
CREATE INDEX ix_admissions_org_status ON admissions (organization_id, status);
CREATE INDEX ix_admissions_doctor ON admissions (attending_doctor_id, status);
CREATE INDEX ix_admissions_patient ON admissions (patient_id, admitted_at DESC);

CREATE TABLE admission_care_team (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    admission_id    UUID        NOT NULL REFERENCES admissions(id),
    user_id         UUID        NOT NULL REFERENCES users(id),
    care_role       VARCHAR(20) NOT NULL CHECK (care_role IN ('ATTENDING','CONSULTANT','RESIDENT','PRIMARY_NURSE')),
    added_by        UUID REFERENCES users(id),
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_care_team_active ON admission_care_team (admission_id, user_id) WHERE removed_at IS NULL;
CREATE INDEX ix_care_team_user ON admission_care_team (user_id) WHERE removed_at IS NULL;

CREATE TABLE transfers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID        NOT NULL REFERENCES organizations(id),
    admission_id     UUID        NOT NULL REFERENCES admissions(id),
    from_bed_id      UUID REFERENCES beds(id),
    to_bed_id        UUID REFERENCES beds(id),
    to_ward_id       UUID REFERENCES wards(id),
    status           VARCHAR(16) NOT NULL CHECK (status IN ('REQUESTED','APPROVED','COMPLETED','CANCELLED','REJECTED')),
    reason           VARCHAR(1000) NOT NULL,
    priority         VARCHAR(16) NOT NULL DEFAULT 'ROUTINE' CHECK (priority IN ('ROUTINE','URGENT')),
    requested_by     UUID        NOT NULL REFERENCES users(id),
    requested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by       UUID REFERENCES users(id),
    decided_at       TIMESTAMPTZ,
    decision_notes   VARCHAR(1000),
    completed_by     UUID REFERENCES users(id),
    completed_at     TIMESTAMPTZ,
    version          BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_transfers_admission ON transfers (admission_id);
CREATE INDEX ix_transfers_org_status ON transfers (organization_id, status);

-- Bed assignment history. DB-level guarantees against conflicting assignments:
--   * a bed can hold at most one open assignment
--   * an admission can hold at most one open assignment
CREATE TABLE bed_assignments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    admission_id    UUID        NOT NULL REFERENCES admissions(id),
    bed_id          UUID        NOT NULL REFERENCES beds(id),
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at     TIMESTAMPTZ,
    assigned_by     UUID REFERENCES users(id),
    released_by     UUID REFERENCES users(id),
    release_reason  VARCHAR(32) CHECK (release_reason IN ('TRANSFER','DISCHARGE','CANCELLED','CORRECTION')),
    transfer_id     UUID REFERENCES transfers(id),
    notes           VARCHAR(500),
    CHECK (released_at IS NULL OR released_at >= assigned_at)
);
CREATE UNIQUE INDEX ux_bed_assignments_open_bed ON bed_assignments (bed_id) WHERE released_at IS NULL;
CREATE UNIQUE INDEX ux_bed_assignments_open_admission ON bed_assignments (admission_id) WHERE released_at IS NULL;
CREATE INDEX ix_bed_assignments_admission ON bed_assignments (admission_id, assigned_at);
