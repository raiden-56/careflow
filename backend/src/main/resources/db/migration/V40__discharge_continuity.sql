-- =====================================================================
-- CareFlow HMS - V40: smart discharge workflow, summaries and follow-up continuity
-- (discharge module; additive changes only)
-- =====================================================================

-- Nurses record the actual departure (DISCHARGE_COMPLETE) - align already-provisioned hospitals with the
-- default permission matrix (Permissions.DEFAULT_TEMPLATES). Hospitals may still revoke it in role management.
INSERT INTO role_permissions (organization_id, role_code, permission_code)
SELECT o.id, 'NURSE', 'DISCHARGE_COMPLETE' FROM organizations o WHERE NOT o.is_platform
ON CONFLICT DO NOTHING;

-- Steps keep a snapshot of their template's description and automatic check so that later
-- template edits never change the rules of a workflow that is already running.
ALTER TABLE discharge_steps ADD COLUMN description VARCHAR(1000);
ALTER TABLE discharge_steps ADD COLUMN auto_check VARCHAR(40);
ALTER TABLE discharge_steps ADD COLUMN not_applicable_reason VARCHAR(1000);
CREATE INDEX ix_discharge_steps_workflow ON discharge_steps (workflow_id, step_order);
CREATE INDEX ix_discharge_steps_blocked ON discharge_steps (organization_id, status) WHERE status = 'BLOCKED';

CREATE INDEX ix_discharge_workflows_admission ON discharge_workflows (admission_id, initiated_at DESC);
CREATE INDEX ix_discharge_workflows_org_status ON discharge_workflows (organization_id, status);

-- Discharge summary release / edit tracking.
ALTER TABLE discharge_summaries ADD COLUMN released_by UUID REFERENCES users(id);
ALTER TABLE discharge_summaries ADD COLUMN released_at TIMESTAMPTZ;
ALTER TABLE discharge_summaries ADD COLUMN updated_by UUID REFERENCES users(id);
ALTER TABLE discharge_summaries ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Amendments to a finalized summary keep the previous content (append-only history).
CREATE TABLE discharge_summary_amendments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    summary_id       UUID          NOT NULL REFERENCES discharge_summaries(id),
    reason           VARCHAR(1000) NOT NULL,
    previous_content TEXT          NOT NULL,
    amended_by       UUID          NOT NULL REFERENCES users(id),
    amended_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_summary_amendments_summary ON discharge_summary_amendments (summary_id, amended_at);
CREATE TRIGGER trg_summary_amendments_immutable BEFORE UPDATE OR DELETE ON discharge_summary_amendments
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

-- Follow-ups: explicit hand-over of an outstanding investigation to a post-discharge result review.
ALTER TABLE follow_ups ADD COLUMN lab_order_id UUID REFERENCES lab_orders(id);
ALTER TABLE follow_ups ADD COLUMN outcome_recorded_by UUID REFERENCES users(id);
ALTER TABLE follow_ups ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX ix_follow_ups_responsible ON follow_ups (responsible_user_id, status, scheduled_at);
CREATE INDEX ix_follow_ups_clinician ON follow_ups (clinician_id, status, scheduled_at);
CREATE INDEX ix_follow_ups_admission ON follow_ups (admission_id);
CREATE INDEX ix_follow_ups_lab_order ON follow_ups (lab_order_id) WHERE lab_order_id IS NOT NULL;

-- Per-follow-up delivery log for patient communications (in-app, email, SMS). External channels go
-- through ExternalChannelGateway (development outbox unless a real provider is configured).
CREATE TABLE follow_up_deliveries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    follow_up_id     UUID          NOT NULL REFERENCES follow_ups(id),
    event            VARCHAR(16)   NOT NULL CHECK (event IN ('SCHEDULED','RESCHEDULED','REMINDER','CANCELLED','RESENT')),
    channel          VARCHAR(16)   NOT NULL CHECK (channel IN ('IN_APP','EMAIL','SMS')),
    provider         VARCHAR(50)   NOT NULL,
    destination      VARCHAR(200),
    status           VARCHAR(16)   NOT NULL CHECK (status IN ('PENDING','SENT','DELIVERED','FAILED','SKIPPED')),
    error_message    VARCHAR(1000),
    notification_id  UUID REFERENCES notifications(id),
    dedupe_key       VARCHAR(200)  NOT NULL,
    attempted_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_follow_up_deliveries_follow_up ON follow_up_deliveries (follow_up_id, attempted_at DESC);
CREATE UNIQUE INDEX ux_follow_up_deliveries_dedupe ON follow_up_deliveries (follow_up_id, channel, dedupe_key);
