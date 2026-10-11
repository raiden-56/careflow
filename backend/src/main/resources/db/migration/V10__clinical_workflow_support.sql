-- =====================================================================
-- CareFlow HMS - V10: clinical & medication workflow support columns.
-- Status changes on allergies/history/diagnoses keep a documented reason;
-- holds on medication orders are traceable; the MAR overdue escalation level is
-- persisted so the escalation job never re-notifies the same level.
-- =====================================================================

ALTER TABLE allergies
    ADD COLUMN status_reason     VARCHAR(500),
    ADD COLUMN status_changed_by UUID REFERENCES users(id),
    ADD COLUMN status_changed_at TIMESTAMPTZ;

ALTER TABLE medical_history
    ADD COLUMN status_reason     VARCHAR(500),
    ADD COLUMN status_changed_by UUID REFERENCES users(id),
    ADD COLUMN status_changed_at TIMESTAMPTZ;

ALTER TABLE diagnoses
    ADD COLUMN status_reason     VARCHAR(1000),
    ADD COLUMN status_changed_by UUID REFERENCES users(id),
    ADD COLUMN status_changed_at TIMESTAMPTZ;

ALTER TABLE treatment_plans
    ADD COLUMN revision_reason VARCHAR(1000),
    ADD COLUMN released_by     UUID REFERENCES users(id),
    ADD COLUMN released_at     TIMESTAMPTZ;

ALTER TABLE medication_orders
    ADD COLUMN hold_reason VARCHAR(1000),
    ADD COLUMN held_by     UUID REFERENCES users(id),
    ADD COLUMN held_at     TIMESTAMPTZ;

ALTER TABLE medication_administrations
    ADD COLUMN overdue_escalation_level INT NOT NULL DEFAULT 0;

ALTER TABLE handover_items
    ADD COLUMN pending_medications TEXT;

CREATE INDEX ix_care_tasks_assigned ON care_tasks (assigned_to) WHERE status IN ('OPEN','IN_PROGRESS');
CREATE INDEX ix_care_tasks_source ON care_tasks (source_type, source_id);
CREATE INDEX ix_mar_order_status ON medication_administrations (order_id, status, scheduled_at);
CREATE INDEX ix_alerts_source ON clinical_alerts (source_type, source_id);
CREATE INDEX ix_lab_results_released ON lab_results (patient_id) WHERE released_to_patient;
CREATE INDEX ix_handovers_status ON handovers (organization_id, status);
