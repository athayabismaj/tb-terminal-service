-- Batch 4: standalone receivable opening balances, stable source metadata,
-- database-enforced payment totals/status, and immutable receivable history.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE n.nspname = 'system' AND t.typname = 'receivable_source'
    ) THEN
        CREATE TYPE system.receivable_source AS ENUM ('SALE', 'OPENING_BALANCE', 'ADJUSTMENT');
    END IF;
END $$;

ALTER TABLE receivable.receivables
    ALTER COLUMN transaction_id DROP NOT NULL,
    ADD COLUMN source system.receivable_source NOT NULL DEFAULT 'SALE',
    ADD COLUMN debt_date DATE,
    ADD COLUMN legacy_invoice_number VARCHAR(100),
    ADD COLUMN notes TEXT,
    ADD COLUMN created_by UUID,
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE receivable.receivables r
SET debt_date = (r.created_at AT TIME ZONE 'Asia/Jakarta')::date,
    created_by = t.user_id
FROM sales.transactions t
WHERE r.transaction_id = t.id
  AND (r.debt_date IS NULL OR r.created_by IS NULL);

UPDATE receivable.receivables
SET debt_date = COALESCE(debt_date, (created_at AT TIME ZONE 'Asia/Jakarta')::date),
    created_by = COALESCE(created_by, (SELECT id FROM system.users ORDER BY created_at LIMIT 1));

ALTER TABLE receivable.receivables
    ALTER COLUMN debt_date SET NOT NULL,
    ALTER COLUMN debt_date SET DEFAULT CURRENT_DATE,
    ALTER COLUMN created_by SET NOT NULL,
    ADD CONSTRAINT fk_receivables_created_by
        FOREIGN KEY (created_by) REFERENCES system.users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_receivables_amount_positive CHECK (amount > 0),
    ADD CONSTRAINT ck_receivables_due_not_before_debt CHECK (due_date >= debt_date),
    ADD CONSTRAINT ck_receivables_source_transaction CHECK (
        (source = 'SALE' AND transaction_id IS NOT NULL) OR
        (source IN ('OPENING_BALANCE', 'ADJUSTMENT') AND transaction_id IS NULL)
    );

CREATE UNIQUE INDEX uq_receivables_sale_transaction
    ON receivable.receivables (transaction_id)
    WHERE transaction_id IS NOT NULL;

CREATE INDEX idx_receivables_customer_due_active
    ON receivable.receivables (customer_id, due_date)
    WHERE is_active = TRUE AND status <> 'lunas';

CREATE OR REPLACE FUNCTION receivable.fn_apply_receivable_payment()
RETURNS TRIGGER AS $$
DECLARE
    v_amount NUMERIC(15,2);
    v_paid NUMERIC(15,2);
    v_new_paid NUMERIC(15,2);
BEGIN
    IF NEW.amount <= 0 THEN
        RAISE EXCEPTION 'Receivable payment amount must be positive';
    END IF;

    SELECT amount, paid_amount
    INTO v_amount, v_paid
    FROM receivable.receivables
    WHERE id = NEW.receivable_id AND is_active = TRUE
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Receivable not found or inactive';
    END IF;

    v_new_paid := v_paid + NEW.amount;
    IF v_new_paid > v_amount THEN
        RAISE EXCEPTION 'Receivable payment exceeds remaining amount';
    END IF;

    UPDATE receivable.receivables
    SET paid_amount = v_new_paid,
        status = CASE
            WHEN v_new_paid = 0 THEN 'belum_lunas'::system.receivable_status
            WHEN v_new_paid < v_amount THEN 'sebagian'::system.receivable_status
            ELSE 'lunas'::system.receivable_status
        END,
        updated_at = NOW()
    WHERE id = NEW.receivable_id;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_apply_receivable_payment
AFTER INSERT ON receivable.receivable_payments
FOR EACH ROW EXECUTE FUNCTION receivable.fn_apply_receivable_payment();

CREATE OR REPLACE FUNCTION receivable.fn_prevent_receivable_hard_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Receivable records are immutable and cannot be permanently deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_receivable_delete
BEFORE DELETE ON receivable.receivables
FOR EACH ROW EXECUTE FUNCTION receivable.fn_prevent_receivable_hard_delete();

CREATE TRIGGER trg_prevent_receivable_payment_mutation
BEFORE UPDATE OR DELETE ON receivable.receivable_payments
FOR EACH ROW EXECUTE FUNCTION receivable.fn_prevent_receivable_hard_delete();
