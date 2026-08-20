-- Batch 3: durable online checkout idempotency, globally unique receipt number,
-- and explicit tender/change snapshots.

CREATE SEQUENCE IF NOT EXISTS sales.receipt_number_seq;

ALTER TABLE sales.transactions
    ADD COLUMN receipt_number VARCHAR(50),
    ADD COLUMN idempotency_key VARCHAR(100),
    ADD COLUMN request_fingerprint CHAR(64),
    ADD COLUMN amount_tendered NUMERIC(15,2) NOT NULL DEFAULT 0,
    ADD COLUMN change_amount NUMERIC(15,2) NOT NULL DEFAULT 0;

UPDATE sales.transactions
SET receipt_number = 'TRX-' || UPPER(REPLACE(id::text, '-', '')),
    amount_tendered = paid_amount,
    change_amount = 0
WHERE receipt_number IS NULL;

ALTER TABLE sales.transactions
    ALTER COLUMN receipt_number SET NOT NULL,
    ALTER COLUMN receipt_number SET DEFAULT (
        'TRX-' || TO_CHAR(CURRENT_DATE, 'YYYYMMDD') || '-' ||
        LPAD(NEXTVAL('sales.receipt_number_seq')::text, 10, '0')
    );

CREATE UNIQUE INDEX uq_sales_transactions_receipt_number
    ON sales.transactions (receipt_number);

CREATE UNIQUE INDEX uq_sales_transactions_online_idempotency
    ON sales.transactions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER TABLE sales.transactions
    ADD CONSTRAINT ck_sales_transactions_idempotency_pair
        CHECK ((idempotency_key IS NULL) = (request_fingerprint IS NULL)),
    ADD CONSTRAINT ck_sales_transactions_amount_tendered_nonnegative
        CHECK (amount_tendered >= 0),
    ADD CONSTRAINT ck_sales_transactions_change_nonnegative
        CHECK (change_amount >= 0),
    ADD CONSTRAINT ck_sales_transactions_change_not_above_tender
        CHECK (change_amount <= amount_tendered);
