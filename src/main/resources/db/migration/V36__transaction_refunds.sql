-- Batch 3B: immutable full-refund aggregate and compensating references.

ALTER TYPE system.trx_status ADD VALUE IF NOT EXISTS 'refunded';
ALTER TYPE system.stock_movement_type ADD VALUE IF NOT EXISTS 'REFUND';

CREATE SEQUENCE IF NOT EXISTS sales.refund_number_seq;

CREATE OR REPLACE FUNCTION sales.next_refund_number()
RETURNS VARCHAR AS $$
BEGIN
    RETURN 'RFD-' || TO_CHAR(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Jakarta', 'YYYYMMDD') ||
        '-' || LPAD(NEXTVAL('sales.refund_number_seq')::TEXT, 8, '0');
END;
$$ LANGUAGE plpgsql VOLATILE;

CREATE TABLE sales.transaction_refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES sales.transactions(id) ON DELETE RESTRICT,
    refund_number VARCHAR(50) NOT NULL DEFAULT sales.next_refund_number(),
    reason TEXT NOT NULL,
    transaction_amount NUMERIC(15,2) NOT NULL,
    refunded_amount NUMERIC(15,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    return_disposition VARCHAR(30) NOT NULL,
    requested_by_user_id UUID NOT NULL REFERENCES system.users(id) ON DELETE RESTRICT,
    approved_by_user_id UUID REFERENCES system.users(id) ON DELETE RESTRICT,
    manager_approval_id UUID REFERENCES system.manager_approvals(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_transaction_refund_transaction UNIQUE (transaction_id),
    CONSTRAINT uq_transaction_refund_number UNIQUE (refund_number),
    CONSTRAINT uq_transaction_refund_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_transaction_refund_reason
        CHECK (CHAR_LENGTH(BTRIM(reason)) BETWEEN 5 AND 1000),
    CONSTRAINT ck_transaction_refund_amounts
        CHECK (transaction_amount >= 0 AND refunded_amount >= 0 AND refunded_amount <= transaction_amount),
    CONSTRAINT ck_transaction_refund_status CHECK (status = 'COMPLETED'),
    CONSTRAINT ck_transaction_refund_disposition
        CHECK (return_disposition IN ('RETURN_TO_STOCK', 'NOT_RETURNED', 'DAMAGED')),
    CONSTRAINT ck_transaction_refund_approval_pair CHECK (
        (manager_approval_id IS NULL AND approved_by_user_id IS NULL) OR
        (manager_approval_id IS NOT NULL AND approved_by_user_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_transaction_refund_manager_approval
    ON sales.transaction_refunds(manager_approval_id)
    WHERE manager_approval_id IS NOT NULL;
CREATE INDEX idx_transaction_refunds_created_at
    ON sales.transaction_refunds(created_at DESC);

ALTER TABLE sales.payments
    ADD COLUMN transaction_refund_id UUID
        REFERENCES sales.transaction_refunds(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_sales_payment_single_compensation CHECK (
        NOT (transaction_void_id IS NOT NULL AND transaction_refund_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_sales_payment_refund_compensation CHECK (
        transaction_refund_id IS NULL OR amount < 0
    );

CREATE INDEX idx_sales_payments_transaction_refund
    ON sales.payments(transaction_refund_id)
    WHERE transaction_refund_id IS NOT NULL;

ALTER TABLE inventory.stock_adjustments
    DROP CONSTRAINT chk_stock_adjustment_source,
    ADD CONSTRAINT chk_stock_adjustment_source CHECK (
        source IN ('manual', 'opening_balance', 'csv_import', 'transaction_void', 'transaction_refund')
    );

CREATE UNIQUE INDEX uq_stock_adjustment_transaction_refund_product
    ON inventory.stock_adjustments(reference_id, product_id)
    WHERE source = 'transaction_refund';

CREATE OR REPLACE FUNCTION inventory.fn_apply_stock_adjustment()
RETURNS TRIGGER AS $$
DECLARE
    v_current_stock NUMERIC(10,2);
    v_unit_id UUID;
    v_movement_type system.stock_movement_type;
BEGIN
    SELECT s.quantity, s.unit_id INTO v_current_stock, v_unit_id
    FROM inventory.stock s WHERE s.product_id = NEW.product_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Stock record not found for product_id %', NEW.product_id; END IF;
    IF v_current_stock <> NEW.qty_before THEN
        RAISE EXCEPTION 'Stale stock adjustment. Current: %, expected: %', v_current_stock, NEW.qty_before;
    END IF;

    UPDATE inventory.stock SET quantity = NEW.qty_after, updated_at = NOW()
    WHERE product_id = NEW.product_id;

    v_movement_type := CASE
        WHEN NEW.source IN ('opening_balance', 'csv_import') THEN 'OPENING_BALANCE'::system.stock_movement_type
        WHEN NEW.source = 'transaction_void' THEN 'VOID'::system.stock_movement_type
        WHEN NEW.source = 'transaction_refund' THEN 'REFUND'::system.stock_movement_type
        WHEN NEW.type = 'opname' THEN 'OPNAME'::system.stock_movement_type
        WHEN NEW.type = 'koreksi' THEN 'CORRECTION'::system.stock_movement_type
        ELSE 'DAMAGE'::system.stock_movement_type
    END;
    INSERT INTO inventory.stock_movements (
        product_id, unit_id, movement_type, balance_before, qty_in, qty_out,
        balance_after, reference_type, reference_id, reference_number, user_id, occurred_at
    ) VALUES (
        NEW.product_id, v_unit_id, v_movement_type, NEW.qty_before,
        GREATEST(NEW.qty_after - NEW.qty_before, 0), GREATEST(NEW.qty_before - NEW.qty_after, 0),
        NEW.qty_after, COALESCE(NEW.reference_type, 'STOCK_ADJUSTMENT'), NEW.id,
        NEW.reference_id::TEXT, NEW.user_id,
        NEW.occurred_on::TIMESTAMP AT TIME ZONE 'Asia/Jakarta'
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_transaction_refund_mutation
BEFORE UPDATE OR DELETE ON sales.transaction_refunds
FOR EACH ROW EXECUTE FUNCTION sales.fn_prevent_sales_detail_mutation();
