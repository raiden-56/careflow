-- =====================================================================
-- CareFlow HMS - V30 (billing module)
--  * claim_settlements: an insurance/third-party claim can be settled in several parts; each part
--    is its own row and its own INSURANCE_SETTLEMENT ledger entry (unique reference).
--  * payments.is_advance: payment accepted beyond the outstanding balance (explicit advance deposit).
--  * invoices reopen trail: who reopened a finalized invoice and why.
-- =====================================================================

CREATE TABLE claim_settlements (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    claim_id         UUID          NOT NULL REFERENCES insurance_claims(id),
    invoice_id       UUID          NOT NULL REFERENCES invoices(id),
    amount           NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    settlement_reference VARCHAR(100) NOT NULL,
    notes            VARCHAR(500),
    settled_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    recorded_by      UUID          NOT NULL REFERENCES users(id),
    recorded_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (claim_id, settlement_reference)
);
CREATE INDEX ix_claim_settlements_claim ON claim_settlements (claim_id, settled_at);
-- Settlements are financial history: never updated or deleted.
CREATE TRIGGER trg_claim_settlements_immutable BEFORE UPDATE OR DELETE ON claim_settlements
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

ALTER TABLE payments ADD COLUMN is_advance BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE invoices ADD COLUMN reopened_by UUID REFERENCES users(id);
ALTER TABLE invoices ADD COLUMN reopened_at TIMESTAMPTZ;
ALTER TABLE invoices ADD COLUMN reopen_reason VARCHAR(500);

CREATE INDEX ix_ledger_org_type_time ON ledger_entries (organization_id, entry_type, created_at);
CREATE INDEX ix_adjustments_org_status ON financial_adjustments (organization_id, status, requested_at DESC);
CREATE INDEX ix_claims_org_status ON insurance_claims (organization_id, status);
CREATE INDEX ix_billing_requests_org_status ON billing_requests (organization_id, status, raised_at DESC);
