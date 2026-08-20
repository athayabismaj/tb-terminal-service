ALTER TABLE sales.cash_expenses
    ADD COLUMN category VARCHAR(100),
    ADD COLUMN client_generated_id VARCHAR(100),
    ADD COLUMN device_id VARCHAR(100),
    ADD COLUMN occurred_at TIMESTAMPTZ,
    ADD COLUMN offline_synced_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_sales_cash_expenses_offline_idempotency
    ON sales.cash_expenses (device_id, client_generated_id)
    WHERE device_id IS NOT NULL
      AND client_generated_id IS NOT NULL;

CREATE INDEX idx_sales_cash_expenses_occurred_at ON sales.cash_expenses (occurred_at);
