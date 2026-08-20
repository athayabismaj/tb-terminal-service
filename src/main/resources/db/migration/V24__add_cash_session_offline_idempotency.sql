ALTER TABLE sales.cash_sessions
    ADD COLUMN client_generated_id VARCHAR(100),
    ADD COLUMN device_id VARCHAR(100),
    ADD COLUMN offline_synced_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_sales_cash_sessions_offline_idempotency
    ON sales.cash_sessions (device_id, client_generated_id)
    WHERE device_id IS NOT NULL
      AND client_generated_id IS NOT NULL;
