-- =====================================================================
-- CareFlow HMS - V3: clinical records, nursing, medication, pharmacy
-- Clinical records are never hard-deleted. Corrections use status
-- ENTERED_IN_ERROR / amendments that reference the original row.
-- =====================================================================

CREATE TABLE diagnoses (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    admission_id    UUID         NOT NULL REFERENCES admissions(id),
    patient_id      UUID         NOT NULL REFERENCES patients(id),
    icd_code        VARCHAR(16),
    description     VARCHAR(500) NOT NULL,
    diagnosis_type  VARCHAR(16)  NOT NULL CHECK (diagnosis_type IN ('PROVISIONAL','CONFIRMED','DIFFERENTIAL')),
    is_primary      BOOLEAN      NOT NULL DEFAULT FALSE,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RESOLVED','ENTERED_IN_ERROR')),
    notes           VARCHAR(1000),
    recorded_by     UUID         NOT NULL REFERENCES users(id),
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_diagnoses_admission ON diagnoses (admission_id);

-- Treatment plans are versioned: a revision supersedes the previous ACTIVE plan.
CREATE TABLE treatment_plans (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    admission_id    UUID         NOT NULL REFERENCES admissions(id),
    plan_version    INT          NOT NULL,
    goals           TEXT         NOT NULL,
    plan_details    TEXT         NOT NULL,
    monitoring      TEXT,
    diet            VARCHAR(500),
    activity        VARCHAR(500),
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE','SUPERSEDED','COMPLETED')),
    patient_summary TEXT,
    released_to_patient BOOLEAN  NOT NULL DEFAULT FALSE,
    created_by      UUID         NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    superseded_by_id UUID REFERENCES treatment_plans(id),
    version         BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (admission_id, plan_version)
);
CREATE UNIQUE INDEX ux_treatment_plan_active ON treatment_plans (admission_id) WHERE status = 'ACTIVE';

CREATE TABLE clinical_notes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID         NOT NULL REFERENCES organizations(id),
    admission_id        UUID         NOT NULL REFERENCES admissions(id),
    patient_id          UUID         NOT NULL REFERENCES patients(id),
    note_type           VARCHAR(32)  NOT NULL CHECK (note_type IN ('ADMISSION_ASSESSMENT','PROGRESS','DOCTOR_REVIEW','NURSING_ASSESSMENT','NURSING_OBSERVATION','PROCEDURE','CONSULTATION','EDUCATION')),
    title               VARCHAR(300) NOT NULL,
    -- Structured SOAP-style sections (all optional; content is required).
    subjective          TEXT,
    objective           TEXT,
    assessment          TEXT,
    plan                TEXT,
    content             TEXT         NOT NULL,
    patient_summary     TEXT,
    released_to_patient BOOLEAN      NOT NULL DEFAULT FALSE,
    released_by         UUID REFERENCES users(id),
    released_at         TIMESTAMPTZ,
    status              VARCHAR(20)  NOT NULL DEFAULT 'FINAL' CHECK (status IN ('FINAL','AMENDED','ENTERED_IN_ERROR')),
    amends_note_id      UUID REFERENCES clinical_notes(id),
    correction_reason   VARCHAR(1000),
    author_id           UUID         NOT NULL REFERENCES users(id),
    authored_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_notes_admission ON clinical_notes (admission_id, authored_at DESC);

CREATE TABLE vital_signs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID         NOT NULL REFERENCES organizations(id),
    admission_id      UUID         NOT NULL REFERENCES admissions(id),
    patient_id        UUID         NOT NULL REFERENCES patients(id),
    recorded_at       TIMESTAMPTZ  NOT NULL,
    recorded_by       UUID         NOT NULL REFERENCES users(id),
    temperature_c     NUMERIC(4,1) CHECK (temperature_c BETWEEN 25 AND 45),
    pulse_bpm         INT          CHECK (pulse_bpm BETWEEN 0 AND 300),
    respiratory_rate  INT          CHECK (respiratory_rate BETWEEN 0 AND 80),
    systolic_bp       INT          CHECK (systolic_bp BETWEEN 0 AND 300),
    diastolic_bp      INT          CHECK (diastolic_bp BETWEEN 0 AND 200),
    spo2              INT          CHECK (spo2 BETWEEN 0 AND 100),
    on_oxygen         BOOLEAN      NOT NULL DEFAULT FALSE,
    pain_score        INT          CHECK (pain_score BETWEEN 0 AND 10),
    blood_glucose     NUMERIC(5,1) CHECK (blood_glucose BETWEEN 0 AND 1000),
    weight_kg         NUMERIC(5,1) CHECK (weight_kg BETWEEN 0 AND 500),
    consciousness     VARCHAR(16)  CHECK (consciousness IN ('ALERT','VOICE','PAIN','UNRESPONSIVE','CONFUSED')),
    notes             VARCHAR(1000),
    entered_in_error  BOOLEAN      NOT NULL DEFAULT FALSE,
    error_reason      VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_vitals_admission_time ON vital_signs (admission_id, recorded_at);

-- Hospital-configured observation thresholds (decision support, not diagnosis).
CREATE TABLE threshold_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    parameter       VARCHAR(32)  NOT NULL CHECK (parameter IN ('TEMPERATURE_C','PULSE_BPM','RESPIRATORY_RATE','SYSTOLIC_BP','DIASTOLIC_BP','SPO2','PAIN_SCORE','BLOOD_GLUCOSE')),
    min_value       NUMERIC(8,2),
    max_value       NUMERIC(8,2),
    severity        VARCHAR(16)  NOT NULL CHECK (severity IN ('WARNING','CRITICAL')),
    description     VARCHAR(300) NOT NULL,
    notify_role     VARCHAR(32)  REFERENCES roles(code),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CHECK (min_value IS NOT NULL OR max_value IS NOT NULL)
);

CREATE TABLE clinical_alerts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID         NOT NULL REFERENCES organizations(id),
    admission_id      UUID         NOT NULL REFERENCES admissions(id),
    patient_id        UUID         NOT NULL REFERENCES patients(id),
    alert_type        VARCHAR(24)  NOT NULL CHECK (alert_type IN ('VITAL_THRESHOLD','ESCALATION','MEDICATION','ALLERGY','LAB_CRITICAL','OTHER')),
    severity          VARCHAR(16)  NOT NULL CHECK (severity IN ('INFO','WARNING','CRITICAL')),
    title             VARCHAR(300) NOT NULL,
    message           VARCHAR(2000) NOT NULL,
    source_type       VARCHAR(32),
    source_id         UUID,
    parameter         VARCHAR(32),
    observed_value    VARCHAR(50),
    threshold_rule_id UUID REFERENCES threshold_rules(id),
    threshold_text    VARCHAR(200),
    status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
    assigned_to       UUID REFERENCES users(id),
    raised_by         UUID REFERENCES users(id),
    raised_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    acknowledged_by   UUID REFERENCES users(id),
    acknowledged_at   TIMESTAMPTZ,
    resolved_by       UUID REFERENCES users(id),
    resolved_at       TIMESTAMPTZ,
    resolution_notes  VARCHAR(2000),
    escalation_level  INT          NOT NULL DEFAULT 0,
    last_escalated_at TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_alerts_admission ON clinical_alerts (admission_id, raised_at DESC);
CREATE INDEX ix_alerts_open ON clinical_alerts (organization_id, status) WHERE status <> 'RESOLVED';

-- ---------------------------------------------------------------------
-- Service tariffs (needed by investigations/procedures for charge capture)
-- ---------------------------------------------------------------------
CREATE TABLE service_tariffs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID          NOT NULL REFERENCES organizations(id),
    code            VARCHAR(32)   NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    category        VARCHAR(20)   NOT NULL CHECK (category IN ('ROOM','PROCEDURE','LAB','PHARMACY','CONSULTATION','NURSING','IMAGING','CONSUMABLE','OTHER')),
    unit_price      NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    tax_rate        NUMERIC(5,2)  NOT NULL DEFAULT 0 CHECK (tax_rate >= 0 AND tax_rate <= 100),
    billing_unit    VARCHAR(16)   NOT NULL DEFAULT 'PER_UNIT' CHECK (billing_unit IN ('PER_UNIT','PER_DAY','PER_VISIT','PER_HOUR')),
    room_type       VARCHAR(20),
    department_id   UUID REFERENCES departments(id),
    description     VARCHAR(500),
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    effective_from  DATE          NOT NULL DEFAULT CURRENT_DATE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (organization_id, code)
);

-- ---------------------------------------------------------------------
-- Investigations
-- ---------------------------------------------------------------------
CREATE TABLE lab_orders (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    admission_id     UUID         NOT NULL REFERENCES admissions(id),
    patient_id       UUID         NOT NULL REFERENCES patients(id),
    order_number     VARCHAR(32)  NOT NULL,
    tariff_id        UUID REFERENCES service_tariffs(id),
    test_code        VARCHAR(32)  NOT NULL,
    test_name        VARCHAR(200) NOT NULL,
    category         VARCHAR(16)  NOT NULL DEFAULT 'LAB' CHECK (category IN ('LAB','IMAGING','OTHER')),
    specimen         VARCHAR(100),
    priority         VARCHAR(16)  NOT NULL DEFAULT 'ROUTINE' CHECK (priority IN ('ROUTINE','URGENT','STAT')),
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('ORDERED','COLLECTED','IN_PROGRESS','RESULTED','REVIEWED','CANCELLED')),
    clinical_info    VARCHAR(1000),
    ordered_by       UUID         NOT NULL REFERENCES users(id),
    ordered_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    due_at           TIMESTAMPTZ,
    collected_by     UUID REFERENCES users(id),
    collected_at     TIMESTAMPTZ,
    cancelled_by     UUID REFERENCES users(id),
    cancelled_at     TIMESTAMPTZ,
    cancel_reason    VARCHAR(500),
    charge_posted    BOOLEAN      NOT NULL DEFAULT FALSE,
    version          BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, order_number)
);
CREATE INDEX ix_lab_orders_admission ON lab_orders (admission_id, ordered_at DESC);
CREATE INDEX ix_lab_orders_status ON lab_orders (organization_id, status);

CREATE TABLE lab_results (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID         NOT NULL REFERENCES organizations(id),
    lab_order_id        UUID         NOT NULL REFERENCES lab_orders(id),
    admission_id        UUID         NOT NULL REFERENCES admissions(id),
    patient_id          UUID         NOT NULL REFERENCES patients(id),
    component           VARCHAR(200) NOT NULL,
    value_text          VARCHAR(500) NOT NULL,
    value_numeric       NUMERIC(12,4),
    unit                VARCHAR(50),
    reference_range     VARCHAR(100),
    flag                VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' CHECK (flag IN ('NORMAL','LOW','HIGH','CRITICAL_LOW','CRITICAL_HIGH','ABNORMAL')),
    interpretation      TEXT,
    resulted_by         UUID         NOT NULL REFERENCES users(id),
    resulted_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source              VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','LIS_ADAPTER')),
    reviewed_by         UUID REFERENCES users(id),
    reviewed_at         TIMESTAMPTZ,
    review_comment      VARCHAR(2000),
    released_to_patient BOOLEAN      NOT NULL DEFAULT FALSE,
    released_at         TIMESTAMPTZ,
    released_by         UUID REFERENCES users(id),
    status              VARCHAR(20)  NOT NULL DEFAULT 'FINAL' CHECK (status IN ('PRELIMINARY','FINAL','CORRECTED','ENTERED_IN_ERROR')),
    version             BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_lab_results_order ON lab_results (lab_order_id);
CREATE INDEX ix_lab_results_admission ON lab_results (admission_id, resulted_at DESC);

CREATE TABLE procedures (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    admission_id     UUID         NOT NULL REFERENCES admissions(id),
    patient_id       UUID         NOT NULL REFERENCES patients(id),
    tariff_id        UUID REFERENCES service_tariffs(id),
    name             VARCHAR(300) NOT NULL,
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('PLANNED','COMPLETED','CANCELLED')),
    scheduled_at     TIMESTAMPTZ,
    performed_at     TIMESTAMPTZ,
    performed_by     UUID REFERENCES users(id),
    anaesthesia      VARCHAR(100),
    findings         TEXT,
    complications    TEXT,
    notes            TEXT,
    created_by       UUID         NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    charge_posted    BOOLEAN      NOT NULL DEFAULT FALSE,
    version          BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_procedures_admission ON procedures (admission_id);

-- ---------------------------------------------------------------------
-- Nursing tasks and handover
-- ---------------------------------------------------------------------
CREATE TABLE care_tasks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID         NOT NULL REFERENCES organizations(id),
    admission_id      UUID         NOT NULL REFERENCES admissions(id),
    patient_id        UUID         NOT NULL REFERENCES patients(id),
    task_type         VARCHAR(24)  NOT NULL CHECK (task_type IN ('LAB_COLLECTION','WOUND_CARE','MOBILISATION','OBSERVATION','EDUCATION','HYGIENE','DISCHARGE_PREP','TRANSFER','OTHER')),
    title             VARCHAR(300) NOT NULL,
    description       VARCHAR(2000),
    priority          VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
    status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','DONE','CANCELLED')),
    due_at            TIMESTAMPTZ,
    assigned_to       UUID REFERENCES users(id),
    assigned_role     VARCHAR(32)  REFERENCES roles(code),
    source_type       VARCHAR(32),
    source_id         UUID,
    created_by        UUID         NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_by      UUID REFERENCES users(id),
    completed_at      TIMESTAMPTZ,
    completion_notes  VARCHAR(2000),
    version           BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_care_tasks_admission ON care_tasks (admission_id);
CREATE INDEX ix_care_tasks_open ON care_tasks (organization_id, status, due_at) WHERE status IN ('OPEN','IN_PROGRESS');

CREATE TABLE handovers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    ward_id          UUID         NOT NULL REFERENCES wards(id),
    shift_date       DATE         NOT NULL,
    shift            VARCHAR(16)  NOT NULL CHECK (shift IN ('MORNING','EVENING','NIGHT')),
    from_user_id     UUID         NOT NULL REFERENCES users(id),
    to_user_id       UUID REFERENCES users(id),
    general_notes    TEXT,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SUBMITTED','ACKNOWLEDGED')),
    requires_ack     BOOLEAN      NOT NULL DEFAULT TRUE,
    submitted_at     TIMESTAMPTZ,
    acknowledged_by  UUID REFERENCES users(id),
    acknowledged_at  TIMESTAMPTZ,
    ack_comment      VARCHAR(1000),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_handovers_ward ON handovers (ward_id, shift_date DESC);

CREATE TABLE handover_items (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handover_id             UUID         NOT NULL REFERENCES handovers(id),
    admission_id            UUID         NOT NULL REFERENCES admissions(id),
    patient_id              UUID         NOT NULL REFERENCES patients(id),
    condition_summary       TEXT,
    important_observations  TEXT,
    pending_tasks           TEXT,
    outstanding_items       TEXT,
    escalations             TEXT,
    priority                VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL','WATCH','CRITICAL'))
);
CREATE INDEX ix_handover_items_handover ON handover_items (handover_id);

-- Optional AI/automated drafts. Always drafts; require clinician review before publication.
CREATE TABLE ai_drafts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID         NOT NULL REFERENCES organizations(id),
    admission_id      UUID         NOT NULL REFERENCES admissions(id),
    draft_type        VARCHAR(24)  NOT NULL CHECK (draft_type IN ('PROGRESS_SUMMARY','HANDOVER','DISCHARGE_SUMMARY','MISSING_INFO')),
    generator         VARCHAR(50)  NOT NULL,
    content           TEXT         NOT NULL,
    source_refs       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACCEPTED','REJECTED')),
    created_by        UUID         NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_by       UUID REFERENCES users(id),
    reviewed_at       TIMESTAMPTZ,
    published_note_id UUID REFERENCES clinical_notes(id),
    version           BIGINT       NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------
-- Medication catalogue, orders and administration (MAR)
-- ---------------------------------------------------------------------
CREATE TABLE medications (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    code             VARCHAR(32)   NOT NULL,
    generic_name     VARCHAR(200)  NOT NULL,
    brand_name       VARCHAR(200),
    form             VARCHAR(20)   NOT NULL CHECK (form IN ('TABLET','CAPSULE','SYRUP','INJECTION','INFUSION','INHALER','OINTMENT','DROPS','SUPPOSITORY','PATCH','OTHER')),
    strength         VARCHAR(50)   NOT NULL,
    default_route    VARCHAR(16)   NOT NULL CHECK (default_route IN ('ORAL','IV','IM','SC','INHALED','TOPICAL','SUBLINGUAL','RECTAL','NASAL','OPHTHALMIC','OTIC')),
    drug_class       VARCHAR(100),
    allergen_group   VARCHAR(100),
    is_high_alert    BOOLEAN       NOT NULL DEFAULT FALSE,
    is_controlled    BOOLEAN       NOT NULL DEFAULT FALSE,
    unit_price       NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (unit_price >= 0),
    dispense_unit    VARCHAR(20)   NOT NULL DEFAULT 'UNIT',
    reorder_level    INT           NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (organization_id, code)
);
CREATE INDEX ix_medications_name ON medications (organization_id, lower(generic_name));

CREATE TABLE medication_orders (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id        UUID         NOT NULL REFERENCES organizations(id),
    admission_id           UUID         NOT NULL REFERENCES admissions(id),
    patient_id             UUID         NOT NULL REFERENCES patients(id),
    medication_id          UUID         NOT NULL REFERENCES medications(id),
    dose                   VARCHAR(50)  NOT NULL,
    dose_unit              VARCHAR(20)  NOT NULL,
    route                  VARCHAR(16)  NOT NULL,
    frequency              VARCHAR(16)  NOT NULL CHECK (frequency IN ('ONCE','STAT','OD','BD','TDS','QID','Q4H','Q6H','Q8H','Q12H','HS','PRN','WEEKLY')),
    schedule_times         VARCHAR(100),
    start_at               TIMESTAMPTZ  NOT NULL,
    end_at                 TIMESTAMPTZ,
    duration_days          INT          CHECK (duration_days > 0),
    indication             VARCHAR(500),
    instructions           VARCHAR(1000),
    patient_instructions   VARCHAR(1000),
    is_discharge_medication BOOLEAN     NOT NULL DEFAULT FALSE,
    status                 VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE','ON_HOLD','DISCONTINUED','COMPLETED')),
    allergy_override_reason VARCHAR(1000),
    prescribed_by          UUID         NOT NULL REFERENCES users(id),
    prescribed_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    verified_by_pharmacist UUID REFERENCES users(id),
    verified_at            TIMESTAMPTZ,
    discontinued_by        UUID REFERENCES users(id),
    discontinued_at        TIMESTAMPTZ,
    discontinue_reason     VARCHAR(1000),
    replaces_order_id      UUID REFERENCES medication_orders(id),
    released_to_patient    BOOLEAN      NOT NULL DEFAULT FALSE,
    version                BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_med_orders_admission ON medication_orders (admission_id, status);
CREATE INDEX ix_med_orders_org_status ON medication_orders (organization_id, status);

-- One row per scheduled dose (generated from the order) or PRN/STAT dose.
CREATE TABLE medication_administrations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID         NOT NULL REFERENCES organizations(id),
    order_id          UUID         NOT NULL REFERENCES medication_orders(id),
    admission_id      UUID         NOT NULL REFERENCES admissions(id),
    patient_id        UUID         NOT NULL REFERENCES patients(id),
    scheduled_at      TIMESTAMPTZ  NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('SCHEDULED','ADMINISTERED','DELAYED','REFUSED','MISSED','HELD','CANCELLED')),
    administered_at   TIMESTAMPTZ,
    dose_given        VARCHAR(50),
    route_given       VARCHAR(16),
    site              VARCHAR(100),
    reason            VARCHAR(1000),
    notes             VARCHAR(1000),
    recorded_by       UUID REFERENCES users(id),
    recorded_at       TIMESTAMPTZ,
    witness_user_id   UUID REFERENCES users(id),
    overdue_flagged_at TIMESTAMPTZ,
    follow_up_status  VARCHAR(16)  NOT NULL DEFAULT 'NONE' CHECK (follow_up_status IN ('NONE','OPEN','IN_PROGRESS','RESOLVED')),
    follow_up_notes   VARCHAR(2000),
    follow_up_by      UUID REFERENCES users(id),
    follow_up_at      TIMESTAMPTZ,
    responsible_team  VARCHAR(100),
    version           BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (order_id, scheduled_at)
);
CREATE INDEX ix_mar_admission_time ON medication_administrations (admission_id, scheduled_at);
CREATE INDEX ix_mar_org_status_time ON medication_administrations (organization_id, status, scheduled_at);

-- ---------------------------------------------------------------------
-- Pharmacy inventory and dispensing (separate from bedside administration)
-- ---------------------------------------------------------------------
CREATE TABLE inventory_batches (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    medication_id    UUID          NOT NULL REFERENCES medications(id),
    batch_number     VARCHAR(50)   NOT NULL,
    expiry_date      DATE          NOT NULL,
    quantity_received INT          NOT NULL CHECK (quantity_received >= 0),
    quantity_on_hand INT           NOT NULL CHECK (quantity_on_hand >= 0),
    unit_cost        NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (unit_cost >= 0),
    supplier         VARCHAR(200),
    received_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    received_by      UUID REFERENCES users(id),
    status           VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','QUARANTINED','EXPIRED','DEPLETED')),
    version          BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (organization_id, medication_id, batch_number)
);
CREATE INDEX ix_batches_med_expiry ON inventory_batches (medication_id, expiry_date);

CREATE TABLE dispensations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID         NOT NULL REFERENCES organizations(id),
    order_id          UUID         NOT NULL REFERENCES medication_orders(id),
    admission_id      UUID         NOT NULL REFERENCES admissions(id),
    patient_id        UUID         NOT NULL REFERENCES patients(id),
    medication_id     UUID         NOT NULL REFERENCES medications(id),
    batch_id          UUID         NOT NULL REFERENCES inventory_batches(id),
    quantity          INT          NOT NULL CHECK (quantity > 0),
    returned_quantity INT          NOT NULL DEFAULT 0 CHECK (returned_quantity >= 0),
    status            VARCHAR(20)  NOT NULL DEFAULT 'DISPENSED' CHECK (status IN ('DISPENSED','PARTIALLY_RETURNED','RETURNED')),
    dispensed_by      UUID         NOT NULL REFERENCES users(id),
    dispensed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    notes             VARCHAR(1000),
    charge_line_id    UUID,
    version           BIGINT       NOT NULL DEFAULT 0,
    CHECK (returned_quantity <= quantity)
);
CREATE INDEX ix_dispensations_order ON dispensations (order_id);
CREATE INDEX ix_dispensations_patient ON dispensations (patient_id, dispensed_at DESC);

CREATE TABLE stock_movements (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    batch_id         UUID         NOT NULL REFERENCES inventory_batches(id),
    medication_id    UUID         NOT NULL REFERENCES medications(id),
    movement_type    VARCHAR(20)  NOT NULL CHECK (movement_type IN ('RECEIPT','DISPENSE','RETURN','ADJUSTMENT_IN','ADJUSTMENT_OUT','EXPIRY_WRITE_OFF','DAMAGE')),
    quantity         INT          NOT NULL,
    balance_after    INT          NOT NULL,
    reference_type   VARCHAR(32),
    reference_id     UUID,
    reason           VARCHAR(500),
    performed_by     UUID         NOT NULL REFERENCES users(id),
    performed_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_stock_movements_batch ON stock_movements (batch_id, performed_at DESC);
CREATE TRIGGER trg_stock_movements_immutable BEFORE UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();
