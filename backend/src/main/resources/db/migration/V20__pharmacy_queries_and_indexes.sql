-- =====================================================================
-- CareFlow HMS - V20: pharmacy prescription queries and reporting indexes
-- =====================================================================

-- A pharmacist's clarification request to the prescriber about a medication order.
-- Raising or resolving a query never changes the prescription itself.
CREATE TABLE pharmacy_order_queries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organizations(id),
    order_id         UUID          NOT NULL REFERENCES medication_orders(id),
    admission_id     UUID          NOT NULL REFERENCES admissions(id),
    patient_id       UUID          NOT NULL REFERENCES patients(id),
    message          VARCHAR(1000) NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED')),
    raised_by        UUID          NOT NULL REFERENCES users(id),
    raised_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_by      UUID REFERENCES users(id),
    resolved_at      TIMESTAMPTZ,
    resolution       VARCHAR(1000),
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_pharmacy_queries_order ON pharmacy_order_queries (order_id, raised_at DESC);
-- At most one open query per order.
CREATE UNIQUE INDEX ux_pharmacy_queries_open ON pharmacy_order_queries (order_id) WHERE status = 'OPEN';

CREATE INDEX ix_dispensations_org_time ON dispensations (organization_id, dispensed_at DESC);
CREATE INDEX ix_stock_movements_medication ON stock_movements (medication_id, performed_at DESC);
CREATE INDEX ix_stock_movements_reference ON stock_movements (reference_type, reference_id);
CREATE INDEX ix_batches_org_status_expiry ON inventory_batches (organization_id, status, expiry_date);
