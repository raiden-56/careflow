-- =====================================================================
-- CareFlow HMS - V4: billing ledger, discharge, follow-up, notifications,
-- timeline, consent, patient requests, incidents, documents
-- =====================================================================

-- ---------------------------------------------------------------------
-- Billing
-- Authoritative balance = SUM(ledger_entries.amount) for the invoice.
-- Positive ledger amounts increase what is owed (charges, refunds paid out);
-- negative amounts decrease it (payments, discounts, insurance settlements).
-- ---------------------------------------------------------------------
CREATE TABLE invoices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    patient_id      UUID         NOT NULL REFERENCES patients(id),
    admission_id    UUID REFERENCES admissions(id),
    invoice_number  VARCHAR(32)  NOT NULL,
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('OPEN','FINALIZED','CANCELLED')),
    payer_type      VARCHAR(16)  NOT NULL DEFAULT 'SELF' CHECK (payer_type IN ('SELF','INSURANCE','CORPORATE','GOVERNMENT')),
    notes           VARCHAR(1000),
    created_by      UUID         NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finalized_by    UUID REFERENCES users(id),
    finalized_at    TIMESTAMPTZ,
    reviewed_by     UUID REFERENCES users(id),
    reviewed_at     TIMESTAMPTZ,
    cancelled_by    UUID REFERENCES users(id),
    cancelled_at    TIMESTAMPTZ,
    cancel_reason   VARCHAR(500),
    room_charges_posted_until DATE,
    version         BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, invoice_number)
);
CREATE UNIQUE INDEX ux_invoices_one_active_per_admission ON invoices (admission_id) WHERE status <> 'CANCELLED' AND admission_id IS NOT NULL;
CREATE INDEX ix_invoices_patient ON invoices (patient_id, created_at DESC);
CREATE INDEX ix_invoices_org_status ON invoices (organization_id, status);

-- Posted charges. Never updated in place except to mark a line as reversed;
-- a reversal is a new negative line that references the original.
CREATE TABLE invoice_line_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    invoice_id       UUID          NOT NULL REFERENCES invoices(id),
    tariff_id        UUID REFERENCES service_tariffs(id),
    category         VARCHAR(20)   NOT NULL CHECK (category IN ('ROOM','PROCEDURE','LAB','PHARMACY','CONSULTATION','NURSING','IMAGING','CONSUMABLE','OTHER')),
    description      VARCHAR(500)  NOT NULL,
    service_date     DATE          NOT NULL,
    quantity         NUMERIC(10,2) NOT NULL,
    unit_price       NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    tax_rate         NUMERIC(5,2)  NOT NULL DEFAULT 0,
    net_amount       NUMERIC(14,2) NOT NULL,
    tax_amount       NUMERIC(14,2) NOT NULL,
    total_amount     NUMERIC(14,2) NOT NULL,
    source_type      VARCHAR(24)   NOT NULL CHECK (source_type IN ('BED_DAY','LAB_ORDER','PROCEDURE','DISPENSATION','CONSULTATION','MANUAL','REVERSAL')),
    source_id        UUID,
    status           VARCHAR(16)   NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED','REVERSED','REVERSAL')),
    reverses_line_id UUID REFERENCES invoice_line_items(id),
    reason           VARCHAR(500),
    posted_by        UUID          NOT NULL REFERENCES users(id),
    posted_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_line_items_invoice ON invoice_line_items (invoice_id, service_date);
-- Prevent double charge capture for the same clinical source (e.g. one lab order, one bed-day).
CREATE UNIQUE INDEX ux_line_items_source ON invoice_line_items (invoice_id, source_type, source_id, service_date)
    WHERE source_id IS NOT NULL AND status = 'POSTED';

-- Estimates are informational only and never part of the authoritative balance.
CREATE TABLE estimated_charges (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    invoice_id       UUID          NOT NULL REFERENCES invoices(id),
    category         VARCHAR(20)   NOT NULL,
    description      VARCHAR(500)  NOT NULL,
    estimated_amount NUMERIC(14,2) NOT NULL CHECK (estimated_amount >= 0),
    basis            VARCHAR(500),
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by       UUID          NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);

CREATE TABLE payments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    invoice_id       UUID          NOT NULL REFERENCES invoices(id),
    patient_id       UUID          NOT NULL REFERENCES patients(id),
    receipt_number   VARCHAR(32)   NOT NULL,
    amount           NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    method           VARCHAR(16)   NOT NULL CHECK (method IN ('CASH','CARD','UPI','NET_BANKING','CHEQUE','DEMAND_DRAFT','ONLINE')),
    method_reference VARCHAR(100),
    payer_name       VARCHAR(200),
    idempotency_key  VARCHAR(100)  NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED','REVERSED')),
    notes            VARCHAR(500),
    received_by      UUID          NOT NULL REFERENCES users(id),
    received_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    reversed_by      UUID REFERENCES users(id),
    reversed_at      TIMESTAMPTZ,
    reversal_reason  VARCHAR(500),
    UNIQUE (organization_id, receipt_number),
    UNIQUE (organization_id, idempotency_key)
);
CREATE INDEX ix_payments_invoice ON payments (invoice_id);
CREATE INDEX ix_payments_org_time ON payments (organization_id, received_at DESC);

-- Discounts, write-offs, credit notes and refunds. Posted to the ledger only once APPROVED.
CREATE TABLE financial_adjustments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID          NOT NULL REFERENCES organizations(id),
    invoice_id        UUID          NOT NULL REFERENCES invoices(id),
    adjustment_number VARCHAR(32)   NOT NULL,
    adjustment_type   VARCHAR(16)   NOT NULL CHECK (adjustment_type IN ('DISCOUNT','WRITE_OFF','CREDIT_NOTE','REFUND','DEBIT_NOTE')),
    amount            NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    reason            VARCHAR(1000) NOT NULL,
    related_payment_id UUID REFERENCES payments(id),
    refund_method     VARCHAR(16),
    status            VARCHAR(16)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    requested_by      UUID          NOT NULL REFERENCES users(id),
    requested_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_by        UUID REFERENCES users(id),
    decided_at        TIMESTAMPTZ,
    decision_notes    VARCHAR(1000),
    version           BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (organization_id, adjustment_number)
);
CREATE INDEX ix_adjustments_invoice ON financial_adjustments (invoice_id);

CREATE TABLE insurance_claims (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    invoice_id       UUID          NOT NULL REFERENCES invoices(id),
    patient_id       UUID          NOT NULL REFERENCES patients(id),
    claim_number     VARCHAR(50)   NOT NULL,
    payer_name       VARCHAR(200)  NOT NULL,
    payer_type       VARCHAR(16)   NOT NULL DEFAULT 'INSURER' CHECK (payer_type IN ('INSURER','TPA','CORPORATE','GOVERNMENT')),
    policy_number    VARCHAR(100)  NOT NULL,
    pre_auth_number  VARCHAR(100),
    claimed_amount   NUMERIC(14,2) NOT NULL CHECK (claimed_amount >= 0),
    approved_amount  NUMERIC(14,2) CHECK (approved_amount >= 0),
    settled_amount   NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (settled_amount >= 0),
    status           VARCHAR(20)   NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','APPROVED','PARTIALLY_APPROVED','REJECTED','SETTLED')),
    submitted_at     TIMESTAMPTZ,
    decided_at       TIMESTAMPTZ,
    settled_at       TIMESTAMPTZ,
    rejection_reason VARCHAR(1000),
    notes            VARCHAR(1000),
    created_by       UUID          NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (organization_id, claim_number)
);
CREATE INDEX ix_claims_invoice ON insurance_claims (invoice_id);

-- Append-only financial ledger.
CREATE TABLE ledger_entries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    invoice_id       UUID          NOT NULL REFERENCES invoices(id),
    patient_id       UUID          NOT NULL REFERENCES patients(id),
    entry_type       VARCHAR(24)   NOT NULL CHECK (entry_type IN ('CHARGE','CHARGE_REVERSAL','PAYMENT','PAYMENT_REVERSAL','DISCOUNT','WRITE_OFF','CREDIT_NOTE','DEBIT_NOTE','REFUND','INSURANCE_SETTLEMENT')),
    amount           NUMERIC(14,2) NOT NULL,
    reference_type   VARCHAR(32)   NOT NULL,
    reference_id     UUID          NOT NULL,
    reference_number VARCHAR(50),
    description      VARCHAR(500)  NOT NULL,
    created_by       UUID          NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_ledger_invoice ON ledger_entries (invoice_id, created_at);
CREATE INDEX ix_ledger_org_time ON ledger_entries (organization_id, created_at);
CREATE UNIQUE INDEX ux_ledger_reference ON ledger_entries (entry_type, reference_type, reference_id);
CREATE TRIGGER trg_ledger_immutable BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

-- Patient billing queries / disputes.
CREATE TABLE billing_requests (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    invoice_id       UUID          NOT NULL REFERENCES invoices(id),
    patient_id       UUID          NOT NULL REFERENCES patients(id),
    line_item_id     UUID REFERENCES invoice_line_items(id),
    request_type     VARCHAR(20)   NOT NULL CHECK (request_type IN ('DISPUTE','QUERY','ITEMIZATION','PAYMENT_PLAN')),
    subject          VARCHAR(300)  NOT NULL,
    description      VARCHAR(2000) NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_REVIEW','RESOLVED','REJECTED')),
    raised_by        UUID          NOT NULL REFERENCES users(id),
    raised_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    response         VARCHAR(2000),
    responded_by     UUID REFERENCES users(id),
    responded_at     TIMESTAMPTZ,
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_billing_requests_invoice ON billing_requests (invoice_id);

-- ---------------------------------------------------------------------
-- Discharge workflow (configurable steps)
-- ---------------------------------------------------------------------
CREATE TABLE discharge_step_templates (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    code             VARCHAR(40)  NOT NULL,
    step_order       INT          NOT NULL,
    name             VARCHAR(200) NOT NULL,
    description      VARCHAR(1000),
    category         VARCHAR(16)  NOT NULL CHECK (category IN ('CLINICAL','NURSING','PHARMACY','ADMINISTRATIVE','FINANCIAL','PATIENT')),
    responsible_role VARCHAR(32)  NOT NULL REFERENCES roles(code),
    required         BOOLEAN      NOT NULL DEFAULT TRUE,
    auto_check       VARCHAR(40),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    version          BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, code)
);

CREATE TABLE discharge_workflows (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID         NOT NULL REFERENCES organizations(id),
    admission_id                UUID         NOT NULL REFERENCES admissions(id),
    status                      VARCHAR(16)  NOT NULL CHECK (status IN ('IN_PROGRESS','READY','COMPLETED','CANCELLED')),
    planned_discharge_at        TIMESTAMPTZ,
    initiated_by                UUID         NOT NULL REFERENCES users(id),
    initiated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    clinical_decision           VARCHAR(16)  CHECK (clinical_decision IN ('AUTHORIZED','REVOKED')),
    clinical_authorized_by      UUID REFERENCES users(id),
    clinical_authorized_at      TIMESTAMPTZ,
    clinical_notes              VARCHAR(2000),
    financial_clearance         VARCHAR(16)  CHECK (financial_clearance IN ('CLEARED','EXCEPTION')),
    financial_cleared_by        UUID REFERENCES users(id),
    financial_cleared_at        TIMESTAMPTZ,
    financial_exception_reason  VARCHAR(1000),
    balance_at_clearance        NUMERIC(14,2),
    discharge_disposition       VARCHAR(24),
    actual_departure_at         TIMESTAMPTZ,
    completed_by                UUID REFERENCES users(id),
    completed_at                TIMESTAMPTZ,
    cancelled_reason            VARCHAR(500),
    version                     BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_discharge_open ON discharge_workflows (admission_id) WHERE status IN ('IN_PROGRESS','READY');

CREATE TABLE discharge_steps (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    workflow_id      UUID         NOT NULL REFERENCES discharge_workflows(id),
    template_code    VARCHAR(40)  NOT NULL,
    step_order       INT          NOT NULL,
    name             VARCHAR(200) NOT NULL,
    category         VARCHAR(16)  NOT NULL,
    responsible_role VARCHAR(32)  NOT NULL,
    required         BOOLEAN      NOT NULL DEFAULT TRUE,
    status           VARCHAR(16)  NOT NULL DEFAULT 'NOT_STARTED' CHECK (status IN ('NOT_STARTED','IN_PROGRESS','BLOCKED','COMPLETED','NOT_APPLICABLE')),
    priority         VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
    assigned_to      UUID REFERENCES users(id),
    blocker_reason   VARCHAR(1000),
    next_action      VARCHAR(500),
    notes            VARCHAR(2000),
    updated_by       UUID REFERENCES users(id),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_by     UUID REFERENCES users(id),
    completed_at     TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (workflow_id, template_code)
);

CREATE TABLE discharge_summaries (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         UUID         NOT NULL REFERENCES organizations(id),
    admission_id            UUID         NOT NULL REFERENCES admissions(id),
    status                  VARCHAR(16)  NOT NULL CHECK (status IN ('DRAFT','FINAL','AMENDED')),
    presenting_complaint    TEXT,
    diagnoses_text          TEXT,
    treatment_summary       TEXT,
    procedures_text         TEXT,
    relevant_results        TEXT,
    condition_at_discharge  TEXT,
    discharge_medications   TEXT,
    discharge_instructions  TEXT,
    follow_up_plan          TEXT,
    warning_signs           TEXT,
    prepared_by             UUID         NOT NULL REFERENCES users(id),
    prepared_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finalized_by            UUID REFERENCES users(id),
    finalized_at            TIMESTAMPTZ,
    released_to_patient     BOOLEAN      NOT NULL DEFAULT FALSE,
    version                 BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_discharge_summary_admission ON discharge_summaries (admission_id);

CREATE TABLE follow_ups (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID         NOT NULL REFERENCES organizations(id),
    admission_id        UUID REFERENCES admissions(id),
    patient_id          UUID         NOT NULL REFERENCES patients(id),
    follow_up_type      VARCHAR(20)  NOT NULL CHECK (follow_up_type IN ('APPOINTMENT','RESULT_REVIEW','INSTRUCTION','TASK','TELECONSULT')),
    title               VARCHAR(300) NOT NULL,
    description         VARCHAR(2000),
    scheduled_at        TIMESTAMPTZ,
    department_id       UUID REFERENCES departments(id),
    clinician_id        UUID REFERENCES users(id),
    responsible_user_id UUID REFERENCES users(id),
    location            VARCHAR(200),
    status              VARCHAR(16)  NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','SCHEDULED','COMPLETED','MISSED','CANCELLED')),
    patient_notified_at TIMESTAMPTZ,
    reminder_sent_at    TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    outcome_notes       VARCHAR(2000),
    created_by          UUID         NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_follow_ups_patient ON follow_ups (patient_id, scheduled_at);
CREATE INDEX ix_follow_ups_org_status ON follow_ups (organization_id, status, scheduled_at);

-- ---------------------------------------------------------------------
-- Notifications
-- ---------------------------------------------------------------------
CREATE TABLE notifications (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID         NOT NULL REFERENCES organizations(id),
    recipient_user_id  UUID         NOT NULL REFERENCES users(id),
    category           VARCHAR(32)  NOT NULL,
    priority           VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW','NORMAL','HIGH','CRITICAL')),
    title              VARCHAR(300) NOT NULL,
    message            VARCHAR(2000) NOT NULL,
    link               VARCHAR(500),
    entity_type        VARCHAR(32),
    entity_id          UUID,
    dedupe_key         VARCHAR(200),
    requires_ack       BOOLEAN      NOT NULL DEFAULT FALSE,
    read_at            TIMESTAMPTZ,
    acknowledged_at    TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_recipient ON notifications (recipient_user_id, created_at DESC);
CREATE INDEX ix_notifications_unread ON notifications (recipient_user_id) WHERE read_at IS NULL;
CREATE UNIQUE INDEX ux_notifications_dedupe ON notifications (recipient_user_id, dedupe_key) WHERE dedupe_key IS NOT NULL;

CREATE TABLE notification_deliveries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id  UUID         NOT NULL REFERENCES notifications(id),
    channel          VARCHAR(16)  NOT NULL CHECK (channel IN ('IN_APP','EMAIL','SMS','PUSH')),
    provider         VARCHAR(50)  NOT NULL,
    destination      VARCHAR(200),
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING','SENT','DELIVERED','FAILED','SKIPPED')),
    attempted_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    error_message    VARCHAR(1000)
);
CREATE INDEX ix_deliveries_notification ON notification_deliveries (notification_id);

-- Development-only outbox for email/SMS adapters (no external provider is contacted).
CREATE TABLE dev_outbox (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID REFERENCES organizations(id),
    channel          VARCHAR(16)  NOT NULL,
    destination      VARCHAR(200) NOT NULL,
    subject          VARCHAR(300),
    body             TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE notification_templates (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    code             VARCHAR(64)  NOT NULL,
    channel          VARCHAR(16)  NOT NULL CHECK (channel IN ('IN_APP','EMAIL','SMS')),
    subject          VARCHAR(300) NOT NULL,
    body             TEXT         NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_by       UUID REFERENCES users(id),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, code, channel)
);

CREATE TABLE escalation_rules (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID         NOT NULL REFERENCES organizations(id),
    event_type         VARCHAR(32)  NOT NULL CHECK (event_type IN ('MEDICATION_OVERDUE','CRITICAL_OBSERVATION','ALERT_UNACKNOWLEDGED','DISCHARGE_BLOCKED','HANDOVER_UNACKNOWLEDGED','LAB_RESULT_UNREVIEWED')),
    threshold_minutes  INT          NOT NULL CHECK (threshold_minutes >= 0),
    escalate_to_role   VARCHAR(32)  NOT NULL REFERENCES roles(code),
    escalation_level   INT          NOT NULL DEFAULT 1,
    priority           VARCHAR(16)  NOT NULL DEFAULT 'HIGH',
    description        VARCHAR(500) NOT NULL,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, event_type, escalation_level)
);

-- ---------------------------------------------------------------------
-- Shared admission timeline (role-aware visibility)
-- ---------------------------------------------------------------------
CREATE TABLE timeline_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    patient_id       UUID         NOT NULL REFERENCES patients(id),
    admission_id     UUID REFERENCES admissions(id),
    event_type       VARCHAR(40)  NOT NULL,
    category         VARCHAR(16)  NOT NULL CHECK (category IN ('ADMISSION','CLINICAL','MEDICATION','OBSERVATION','INVESTIGATION','NURSING','BILLING','DISCHARGE','TRANSFER','SAFETY')),
    title            VARCHAR(300) NOT NULL,
    detail           VARCHAR(2000),
    status           VARCHAR(32),
    visibility       VARCHAR(16)  NOT NULL CHECK (visibility IN ('PATIENT','CARE_TEAM','BILLING','INTERNAL')),
    entity_type      VARCHAR(32),
    entity_id        UUID,
    actor_user_id    UUID REFERENCES users(id),
    actor_name       VARCHAR(200),
    actor_role       VARCHAR(32),
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_timeline_admission ON timeline_events (admission_id, occurred_at DESC);
CREATE INDEX ix_timeline_patient ON timeline_events (patient_id, occurred_at DESC);

-- ---------------------------------------------------------------------
-- Consent, patient requests, incidents, documents
-- ---------------------------------------------------------------------
CREATE TABLE consent_records (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    patient_id       UUID         NOT NULL REFERENCES patients(id),
    admission_id     UUID REFERENCES admissions(id),
    consent_type     VARCHAR(24)  NOT NULL CHECK (consent_type IN ('TREATMENT','PROCEDURE','DATA_SHARING','CAREGIVER_ACCESS','COMMUNICATION','RESEARCH','INSURANCE_SHARING')),
    purpose          VARCHAR(500) NOT NULL,
    shared_with      VARCHAR(200),
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('GRANTED','WITHDRAWN','EXPIRED','DECLINED')),
    granted_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,
    withdrawn_at     TIMESTAMPTZ,
    withdrawal_reason VARCHAR(500),
    captured_by      UUID REFERENCES users(id),
    capture_method   VARCHAR(16)  NOT NULL DEFAULT 'WRITTEN' CHECK (capture_method IN ('WRITTEN','VERBAL','ELECTRONIC','PORTAL')),
    notes            VARCHAR(1000),
    version          BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_consents_patient ON consent_records (patient_id);

CREATE TABLE patient_requests (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    patient_id       UUID         NOT NULL REFERENCES patients(id),
    request_type     VARCHAR(24)  NOT NULL CHECK (request_type IN ('RECORD_COPY','CORRECTION','RESTRICT_SHARING','WITHDRAW_CONSENT','ACCESS_REVIEW','OTHER')),
    subject          VARCHAR(300) NOT NULL,
    description      VARCHAR(2000) NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_REVIEW','COMPLETED','REJECTED')),
    raised_by        UUID         NOT NULL REFERENCES users(id),
    raised_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    response         VARCHAR(2000),
    responded_by     UUID REFERENCES users(id),
    responded_at     TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE incident_reports (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID         NOT NULL REFERENCES organizations(id),
    incident_number    VARCHAR(32)  NOT NULL,
    incident_type      VARCHAR(24)  NOT NULL CHECK (incident_type IN ('MEDICATION_ERROR','NEAR_MISS','FALL','PRESSURE_INJURY','EQUIPMENT','INFECTION','PATIENT_IDENTIFICATION','BEHAVIOURAL','OTHER')),
    severity           VARCHAR(16)  NOT NULL CHECK (severity IN ('NO_HARM','MINOR','MODERATE','SEVERE','DEATH')),
    occurred_at        TIMESTAMPTZ  NOT NULL,
    location           VARCHAR(200),
    ward_id            UUID REFERENCES wards(id),
    patient_id         UUID REFERENCES patients(id),
    admission_id       UUID REFERENCES admissions(id),
    medication_administration_id UUID REFERENCES medication_administrations(id),
    description        TEXT         NOT NULL,
    immediate_actions  TEXT,
    status             VARCHAR(20)  NOT NULL DEFAULT 'REPORTED' CHECK (status IN ('REPORTED','UNDER_REVIEW','ACTION_REQUIRED','CLOSED')),
    reported_by        UUID         NOT NULL REFERENCES users(id),
    reported_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewer_id        UUID REFERENCES users(id),
    review_notes       TEXT,
    corrective_actions TEXT,
    closed_at          TIMESTAMPTZ,
    version            BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, incident_number)
);
CREATE INDEX ix_incidents_org_status ON incident_reports (organization_id, status, reported_at DESC);

CREATE TABLE documents (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID         NOT NULL REFERENCES organizations(id),
    patient_id          UUID         NOT NULL REFERENCES patients(id),
    admission_id        UUID REFERENCES admissions(id),
    document_type       VARCHAR(24)  NOT NULL CHECK (document_type IN ('LAB_REPORT','IMAGING','DISCHARGE_SUMMARY','CONSENT_FORM','ID_PROOF','INSURANCE','REFERRAL','OTHER')),
    title               VARCHAR(300) NOT NULL,
    file_name           VARCHAR(300) NOT NULL,
    content_type        VARCHAR(100) NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    sha256              VARCHAR(64)  NOT NULL,
    storage_key         VARCHAR(500) NOT NULL,
    released_to_patient BOOLEAN      NOT NULL DEFAULT FALSE,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
    uploaded_by         UUID         NOT NULL REFERENCES users(id),
    uploaded_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_documents_patient ON documents (patient_id, uploaded_at DESC);

-- Integration boundary registry (ABDM / FHIR / LIS / payment gateway / SMS). Configuration only.
CREATE TABLE integration_configs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organizations(id),
    integration_type VARCHAR(32)  NOT NULL CHECK (integration_type IN ('ABDM','FHIR','LIS','PAYMENT_GATEWAY','SMS','EMAIL','INSURANCE_TPA')),
    mode             VARCHAR(16)  NOT NULL DEFAULT 'DEV_ADAPTER' CHECK (mode IN ('DISABLED','DEV_ADAPTER','SANDBOX','LIVE')),
    endpoint_url     VARCHAR(500),
    settings         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    updated_by       UUID REFERENCES users(id),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, integration_type)
);
