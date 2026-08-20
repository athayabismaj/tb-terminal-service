ALTER TABLE sales.cash_sessions
    ADD COLUMN close_client_generated_id VARCHAR(100),
    ADD COLUMN close_device_id VARCHAR(100),
    ADD COLUMN close_offline_synced_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_sales_cash_sessions_offline_close_idempotency
    ON sales.cash_sessions (close_device_id, close_client_generated_id)
    WHERE close_device_id IS NOT NULL
      AND close_client_generated_id IS NOT NULL;
