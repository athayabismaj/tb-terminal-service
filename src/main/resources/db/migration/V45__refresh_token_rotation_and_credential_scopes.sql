-- =============================================
-- V45: Rotasi refresh token sekali pakai + lingkup lockout kredensial per user
-- =============================================

-- 1. Refresh token sekali pakai. Token mentah tidak disimpan; hanya jti (id) dari JWT.
--    Pemakaian ulang token yang sudah dirotasi mencabut seluruh keluarga (family_id).
CREATE TABLE IF NOT EXISTS system.refresh_tokens (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES system.users(id) ON DELETE CASCADE,
    family_id     UUID        NOT NULL,
    token_version INTEGER     NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    used_at       TIMESTAMPTZ,
    replaced_by   UUID,
    revoked_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family ON system.refresh_tokens (family_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_expires ON system.refresh_tokens (user_id, expires_at);

-- 2. Penghitung lockout kini per user + lingkup ('scope:pin', 'scope:password',
--    'scope:manager-approval'), bukan per deviceId kiriman klien. Baris lama per
--    perangkat tidak dipakai lagi.
DELETE FROM system.pin_verification_attempts WHERE device_id NOT LIKE 'scope:%';
