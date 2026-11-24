CREATE TABLE IF NOT EXISTS system.pin_verification_attempts (
    user_id         UUID NOT NULL REFERENCES system.users(id) ON DELETE CASCADE,
    device_id       VARCHAR(128) NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, device_id)
);
