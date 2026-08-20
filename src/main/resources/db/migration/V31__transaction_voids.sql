-- Batch 6: immutable, idempotent transaction void events.

ALTER TYPE system.trx_status ADD VALUE IF NOT EXISTS 'voided';

CREATE TABLE sales.transaction_voids (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES sales.transactions(id) ON DELETE RESTRICT,
    voided_by UUID NOT NULL REFERENCES system.users(id) ON DELETE RESTRICT,
    reason TEXT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_transaction_void_reason CHECK (CHAR_LENGTH(BTRIM(reason)) BETWEEN 5 AND 1000),
    CONSTRAINT uq_transaction_void_transaction UNIQUE (transaction_id),
    CONSTRAINT uq_transaction_void_idempotency UNIQUE (idempotency_key)
);

ALTER TABLE sales.payments
    ADD COLUMN transaction_void_id UUID REFERENCES sales.transaction_voids(id) ON DELETE RESTRICT;

CREATE INDEX idx_transaction_voids_created_at ON sales.transaction_voids(created_at DESC);

CREATE OR REPLACE FUNCTION sales.fn_prevent_sales_history_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Sales history is immutable and cannot be deleted';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION sales.fn_prevent_sales_detail_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Sales detail history is immutable; use void or compensating entries';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_transaction_delete
BEFORE DELETE ON sales.transactions
FOR EACH ROW EXECUTE FUNCTION sales.fn_prevent_sales_history_delete();

CREATE TRIGGER trg_prevent_transaction_item_mutation
BEFORE UPDATE OR DELETE ON sales.transaction_items
FOR EACH ROW EXECUTE FUNCTION sales.fn_prevent_sales_detail_mutation();

CREATE TRIGGER trg_prevent_sales_payment_mutation
BEFORE UPDATE OR DELETE ON sales.payments
FOR EACH ROW EXECUTE FUNCTION sales.fn_prevent_sales_detail_mutation();

CREATE TRIGGER trg_prevent_transaction_void_mutation
BEFORE UPDATE OR DELETE ON sales.transaction_voids
FOR EACH ROW EXECUTE FUNCTION sales.fn_prevent_sales_detail_mutation();
