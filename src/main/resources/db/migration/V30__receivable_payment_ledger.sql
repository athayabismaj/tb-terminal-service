-- Batch 5: immutable receivable payment ledger with idempotency, balance
-- snapshots, printable receipt numbers, and auditable reversals.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE n.nspname = 'system' AND t.typname = 'receivable_payment_entry_type'
    ) THEN
        CREATE TYPE system.receivable_payment_entry_type AS ENUM ('PAYMENT', 'REVERSAL');
    END IF;
END $$;

CREATE SEQUENCE IF NOT EXISTS receivable.receivable_payment_number_seq;

CREATE OR REPLACE FUNCTION receivable.next_receivable_payment_number()
RETURNS VARCHAR AS $$
BEGIN
    RETURN 'RCP-' || TO_CHAR(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Jakarta', 'YYYYMMDD') ||
        '-' || LPAD(NEXTVAL('receivable.receivable_payment_number_seq')::TEXT, 8, '0');
END;
$$ LANGUAGE plpgsql VOLATILE;

ALTER TABLE receivable.receivable_payments
    ADD COLUMN payment_number VARCHAR(48),
    ADD COLUMN payment_date DATE,
    ADD COLUMN entry_type system.receivable_payment_entry_type NOT NULL DEFAULT 'PAYMENT',
    ADD COLUMN idempotency_key VARCHAR(100),
    ADD COLUMN reversed_payment_id UUID,
    ADD COLUMN balance_before NUMERIC(15,2),
    ADD COLUMN balance_after NUMERIC(15,2),
    ADD COLUMN status_after system.receivable_status;

-- V29 makes payment rows immutable. Temporarily remove that guard only while
-- existing rows are backfilled, then restore it immediately afterward.
DROP TRIGGER IF EXISTS trg_prevent_receivable_payment_mutation
    ON receivable.receivable_payments;

WITH snapshots AS (
    SELECT p.id,
           r.amount - COALESCE(
               SUM(p.amount) OVER (
                   PARTITION BY p.receivable_id
                   ORDER BY p.paid_at, p.id
                   ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
               ), 0
           ) AS balance_before,
           r.amount - SUM(p.amount) OVER (
               PARTITION BY p.receivable_id
               ORDER BY p.paid_at, p.id
               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
           ) AS balance_after
    FROM receivable.receivable_payments p
    JOIN receivable.receivables r ON r.id = p.receivable_id
)
UPDATE receivable.receivable_payments p
SET payment_number = 'LEGACY-' || REPLACE(p.id::TEXT, '-', ''),
    payment_date = (p.paid_at AT TIME ZONE 'Asia/Jakarta')::DATE,
    idempotency_key = 'legacy-' || p.id::TEXT,
    balance_before = GREATEST(s.balance_before, 0),
    balance_after = GREATEST(s.balance_after, 0),
    status_after = CASE
        WHEN GREATEST(s.balance_after, 0) = 0 THEN 'lunas'::system.receivable_status
        WHEN GREATEST(s.balance_after, 0) < r.amount THEN 'sebagian'::system.receivable_status
        ELSE 'belum_lunas'::system.receivable_status
    END
FROM snapshots s, receivable.receivables r
WHERE s.id = p.id
  AND r.id = p.receivable_id;

CREATE TRIGGER trg_prevent_receivable_payment_mutation
BEFORE UPDATE OR DELETE ON receivable.receivable_payments
FOR EACH ROW EXECUTE FUNCTION receivable.fn_prevent_receivable_hard_delete();

ALTER TABLE receivable.receivable_payments
    ALTER COLUMN payment_number SET DEFAULT receivable.next_receivable_payment_number(),
    ALTER COLUMN payment_number SET NOT NULL,
    ALTER COLUMN payment_date SET DEFAULT ((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Jakarta')::DATE),
    ALTER COLUMN payment_date SET NOT NULL,
    ALTER COLUMN idempotency_key SET NOT NULL,
    ALTER COLUMN balance_before SET NOT NULL,
    ALTER COLUMN balance_after SET NOT NULL,
    ALTER COLUMN status_after SET NOT NULL,
    ADD CONSTRAINT fk_receivable_payment_reversal
        FOREIGN KEY (reversed_payment_id)
        REFERENCES receivable.receivable_payments(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_receivable_payment_amount_positive CHECK (amount > 0),
    ADD CONSTRAINT ck_receivable_payment_balance_nonnegative
        CHECK (balance_before >= 0 AND balance_after >= 0),
    ADD CONSTRAINT ck_receivable_payment_entry_shape CHECK (
        (entry_type = 'PAYMENT' AND reversed_payment_id IS NULL AND balance_after <= balance_before) OR
        (entry_type = 'REVERSAL' AND reversed_payment_id IS NOT NULL AND balance_after >= balance_before)
    ),
    ADD CONSTRAINT ck_receivable_payment_supported_method
        CHECK (method IN ('tunai', 'transfer', 'qris')) NOT VALID;

CREATE UNIQUE INDEX uq_receivable_payment_number
    ON receivable.receivable_payments(payment_number);

CREATE UNIQUE INDEX uq_receivable_payment_idempotency
    ON receivable.receivable_payments(idempotency_key);

CREATE UNIQUE INDEX uq_receivable_payment_single_reversal
    ON receivable.receivable_payments(reversed_payment_id)
    WHERE reversed_payment_id IS NOT NULL;

CREATE INDEX idx_receivable_payment_history_filters
    ON receivable.receivable_payments(payment_date, method, user_id, receivable_id);

DROP TRIGGER IF EXISTS trg_apply_receivable_payment ON receivable.receivable_payments;

CREATE OR REPLACE FUNCTION receivable.fn_apply_receivable_payment()
RETURNS TRIGGER AS $$
DECLARE
    v_amount NUMERIC(15,2);
    v_paid NUMERIC(15,2);
    v_new_paid NUMERIC(15,2);
    v_original receivable.receivable_payments%ROWTYPE;
BEGIN
    IF NEW.amount <= 0 THEN
        RAISE EXCEPTION 'Receivable payment amount must be positive';
    END IF;
    IF NEW.method NOT IN ('tunai', 'transfer', 'qris') THEN
        RAISE EXCEPTION 'Unsupported receivable payment method';
    END IF;

    SELECT amount, paid_amount
    INTO v_amount, v_paid
    FROM receivable.receivables
    WHERE id = NEW.receivable_id AND is_active = TRUE
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Receivable not found or inactive';
    END IF;

    IF NEW.entry_type = 'PAYMENT' THEN
        IF NEW.reversed_payment_id IS NOT NULL THEN
            RAISE EXCEPTION 'Payment cannot reference a reversed payment';
        END IF;
        v_new_paid := v_paid + NEW.amount;
        IF v_new_paid > v_amount THEN
            RAISE EXCEPTION 'Receivable payment exceeds remaining amount';
        END IF;
    ELSIF NEW.entry_type = 'REVERSAL' THEN
        IF NEW.reversed_payment_id IS NULL THEN
            RAISE EXCEPTION 'Reversal must reference an original payment';
        END IF;

        SELECT * INTO v_original
        FROM receivable.receivable_payments
        WHERE id = NEW.reversed_payment_id
        FOR UPDATE;

        IF NOT FOUND OR v_original.entry_type <> 'PAYMENT' THEN
            RAISE EXCEPTION 'Original payment is invalid for reversal';
        END IF;
        IF v_original.receivable_id <> NEW.receivable_id OR v_original.amount <> NEW.amount THEN
            RAISE EXCEPTION 'Reversal must match the original receivable and amount';
        END IF;
        IF EXISTS (
            SELECT 1 FROM receivable.receivable_payments
            WHERE reversed_payment_id = NEW.reversed_payment_id
        ) THEN
            RAISE EXCEPTION 'Original payment has already been reversed';
        END IF;

        v_new_paid := v_paid - NEW.amount;
        IF v_new_paid < 0 THEN
            RAISE EXCEPTION 'Reversal exceeds receivable paid amount';
        END IF;
    ELSE
        RAISE EXCEPTION 'Unsupported receivable payment entry type';
    END IF;

    NEW.balance_before := v_amount - v_paid;
    NEW.balance_after := v_amount - v_new_paid;
    NEW.status_after := CASE
        WHEN v_new_paid = 0 THEN 'belum_lunas'::system.receivable_status
        WHEN v_new_paid < v_amount THEN 'sebagian'::system.receivable_status
        ELSE 'lunas'::system.receivable_status
    END;

    UPDATE receivable.receivables
    SET paid_amount = v_new_paid,
        status = NEW.status_after,
        updated_at = NOW()
    WHERE id = NEW.receivable_id;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_apply_receivable_payment
BEFORE INSERT ON receivable.receivable_payments
FOR EACH ROW EXECUTE FUNCTION receivable.fn_apply_receivable_payment();
