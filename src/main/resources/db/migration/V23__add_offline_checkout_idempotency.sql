ALTER TABLE sales.transactions
    ADD COLUMN client_generated_id VARCHAR(100),
    ADD COLUMN device_id VARCHAR(100),
    ADD COLUMN occurred_at TIMESTAMPTZ,
    ADD COLUMN synced_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_sales_transactions_offline_idempotency
    ON sales.transactions (device_id, client_generated_id)
    WHERE device_id IS NOT NULL
      AND client_generated_id IS NOT NULL;
