-- =============================================
-- V6: Remaining system tables
--   - audit_logs   (IMMUTABLE — protected by RULE)
--   - store_settings (SINGLETON — max 1 row)
-- =============================================

-- Table: system.audit_logs
-- IMMUTABLE: no UPDATE or DELETE allowed (enforced by RULE below)
CREATE TABLE IF NOT EXISTS system.audit_logs (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        REFERENCES system.users(id) ON DELETE SET NULL,
    action      system.audit_action NOT NULL,
    schema_name VARCHAR(50)  NOT NULL,
    table_name  VARCHAR(100) NOT NULL,
    record_id   UUID,
    old_data    JSONB,
    new_data    JSONB,
    ip_address  VARCHAR(45),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Protect audit_logs from UPDATE and DELETE
CREATE OR REPLACE RULE prevent_audit_update AS
    ON UPDATE TO system.audit_logs DO INSTEAD NOTHING;

CREATE OR REPLACE RULE prevent_audit_delete AS
    ON DELETE TO system.audit_logs DO INSTEAD NOTHING;


-- Table: system.store_settings
-- SINGLETON: only 1 row allowed (enforced by trigger in Batch 3)
CREATE TABLE IF NOT EXISTS system.store_settings (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    store_name     VARCHAR(150) NOT NULL DEFAULT 'Toko Bangunan',
    address        TEXT,
    phone          VARCHAR(20),
    receipt_header TEXT,
    receipt_footer TEXT,
    printer_size   system.printer_size NOT NULL DEFAULT '80mm',
    updated_by     UUID         REFERENCES system.users(id) ON DELETE SET NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
