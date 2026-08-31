-- Batch 3C: server-authoritative discount snapshots and checkout approval attempts.

ALTER TABLE system.store_settings
    ADD COLUMN cashier_discount_limit_percent NUMERIC(5,2) NOT NULL DEFAULT 10.00,
    ADD CONSTRAINT ck_store_settings_cashier_discount_limit
        CHECK (cashier_discount_limit_percent BETWEEN 0 AND 100);

ALTER TABLE sales.transaction_items
    DROP CONSTRAINT ck_sales_transaction_items_discount_range,
    DROP CONSTRAINT ck_sales_transaction_items_subtotal_formula,
    ADD COLUMN gross_line_total NUMERIC(15,2),
    ADD COLUMN discount_type VARCHAR(20),
    ADD COLUMN discount_value NUMERIC(15,2);

-- V31 protects historical sale details from mutation. Temporarily remove only
-- that guard while the migration backfills the new immutable snapshot fields.
-- Flyway runs this migration transactionally, so a failed backfill also rolls
-- back the trigger removal.
DROP TRIGGER IF EXISTS trg_prevent_transaction_item_mutation
    ON sales.transaction_items;

-- Legacy `discount` was per-unit. Convert it once to the new line-level amount.
UPDATE sales.transaction_items
SET gross_line_total = ROUND(quantity * price_at_transaction, 2),
    discount_type = CASE WHEN discount > 0 THEN 'FIXED_AMOUNT' ELSE NULL END,
    discount_value = CASE WHEN discount > 0 THEN ROUND(discount * quantity, 2) ELSE 0 END,
    discount = ROUND(discount * quantity, 2);

CREATE TRIGGER trg_prevent_transaction_item_mutation
BEFORE UPDATE OR DELETE ON sales.transaction_items
FOR EACH ROW EXECUTE FUNCTION sales.fn_prevent_sales_detail_mutation();

ALTER TABLE sales.transaction_items
    ALTER COLUMN gross_line_total SET NOT NULL,
    ALTER COLUMN discount_value SET NOT NULL,
    ALTER COLUMN gross_line_total SET DEFAULT 0,
    ALTER COLUMN discount_value SET DEFAULT 0,
    ADD CONSTRAINT ck_sales_transaction_items_gross_formula
        CHECK (gross_line_total = ROUND(quantity * price_at_transaction, 2)),
    ADD CONSTRAINT ck_sales_transaction_items_discount_snapshot CHECK (
        discount >= 0
        AND discount <= gross_line_total
        AND discount_value >= 0
        AND (discount_type IS NULL OR discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT'))
        AND (discount_type <> 'PERCENTAGE' OR discount_value <= 100)
        AND (
            (discount_type IS NULL AND discount_value = 0 AND discount = 0)
            OR (discount_type = 'FIXED_AMOUNT' AND discount = discount_value)
            OR (discount_type = 'PERCENTAGE' AND discount = ROUND(gross_line_total * discount_value / 100, 2))
        )
        AND subtotal = gross_line_total - discount
    );

ALTER TABLE sales.transactions
    ADD COLUMN gross_subtotal NUMERIC(15,2),
    ADD COLUMN item_discount_total NUMERIC(15,2),
    ADD COLUMN transaction_discount_type VARCHAR(20),
    ADD COLUMN transaction_discount_value NUMERIC(15,2),
    ADD COLUMN transaction_discount_amount NUMERIC(15,2),
    ADD COLUMN total_discount_amount NUMERIC(15,2),
    ADD COLUMN discount_manager_approval_id UUID
        REFERENCES system.manager_approvals(id) ON DELETE RESTRICT;

WITH item_totals AS (
    SELECT transaction_id,
           COALESCE(SUM(gross_line_total), 0)::NUMERIC(15,2) AS gross_subtotal,
           COALESCE(SUM(discount), 0)::NUMERIC(15,2) AS item_discount_total
    FROM sales.transaction_items
    GROUP BY transaction_id
)
UPDATE sales.transactions t
SET gross_subtotal = COALESCE(i.gross_subtotal, t.total),
    item_discount_total = COALESCE(i.item_discount_total, 0),
    transaction_discount_value = 0,
    transaction_discount_amount = 0,
    total_discount_amount = COALESCE(i.item_discount_total, 0)
FROM item_totals i
WHERE i.transaction_id = t.id;

UPDATE sales.transactions
SET gross_subtotal = total,
    item_discount_total = 0,
    transaction_discount_value = 0,
    transaction_discount_amount = 0,
    total_discount_amount = 0
WHERE gross_subtotal IS NULL;

ALTER TABLE sales.transactions
    ALTER COLUMN gross_subtotal SET NOT NULL,
    ALTER COLUMN item_discount_total SET NOT NULL,
    ALTER COLUMN transaction_discount_value SET NOT NULL,
    ALTER COLUMN transaction_discount_amount SET NOT NULL,
    ALTER COLUMN total_discount_amount SET NOT NULL,
    ALTER COLUMN gross_subtotal SET DEFAULT 0,
    ALTER COLUMN item_discount_total SET DEFAULT 0,
    ALTER COLUMN transaction_discount_value SET DEFAULT 0,
    ALTER COLUMN transaction_discount_amount SET DEFAULT 0,
    ALTER COLUMN total_discount_amount SET DEFAULT 0,
    ADD CONSTRAINT ck_sales_transaction_discount_snapshot CHECK (
        gross_subtotal >= 0
        AND item_discount_total >= 0
        AND transaction_discount_value >= 0
        AND transaction_discount_amount >= 0
        AND total_discount_amount = item_discount_total + transaction_discount_amount
        AND total = gross_subtotal - total_discount_amount
        AND (transaction_discount_type IS NULL OR transaction_discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT'))
        AND (transaction_discount_type <> 'PERCENTAGE' OR transaction_discount_value <= 100)
        AND (
            (transaction_discount_type IS NULL AND transaction_discount_value = 0 AND transaction_discount_amount = 0)
            OR (transaction_discount_type = 'FIXED_AMOUNT' AND transaction_discount_amount = transaction_discount_value)
            OR (
                transaction_discount_type = 'PERCENTAGE'
                AND transaction_discount_amount = ROUND(
                    (gross_subtotal - item_discount_total) * transaction_discount_value / 100,
                    2
                )
            )
        )
    );

CREATE UNIQUE INDEX uq_sales_transaction_discount_manager_approval
    ON sales.transactions(discount_manager_approval_id)
    WHERE discount_manager_approval_id IS NOT NULL;

CREATE TABLE sales.checkout_discount_attempts (
    id UUID PRIMARY KEY,
    requested_by_user_id UUID NOT NULL REFERENCES system.users(id) ON DELETE RESTRICT,
    discount_fingerprint CHAR(64) NOT NULL,
    gross_subtotal NUMERIC(15,2) NOT NULL,
    total_discount_amount NUMERIC(15,2) NOT NULL,
    effective_discount_percent NUMERIC(7,4) NOT NULL,
    cashier_limit_percent NUMERIC(5,2) NOT NULL,
    approval_required BOOLEAN NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    transaction_id UUID REFERENCES sales.transactions(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_checkout_discount_attempt_amounts CHECK (
        gross_subtotal >= 0
        AND total_discount_amount >= 0
        AND total_discount_amount <= gross_subtotal
        AND effective_discount_percent BETWEEN 0 AND 100
        AND cashier_limit_percent BETWEEN 0 AND 100
    ),
    CONSTRAINT ck_checkout_discount_attempt_consumption CHECK (
        (consumed_at IS NULL AND transaction_id IS NULL)
        OR (consumed_at IS NOT NULL AND transaction_id = id)
    )
);

CREATE INDEX idx_checkout_discount_attempt_expiry
    ON sales.checkout_discount_attempts(expires_at)
    WHERE consumed_at IS NULL;

CREATE TRIGGER trg_prevent_checkout_discount_attempt_delete
BEFORE DELETE ON sales.checkout_discount_attempts
FOR EACH ROW EXECUTE FUNCTION sales.fn_prevent_sales_history_delete();
