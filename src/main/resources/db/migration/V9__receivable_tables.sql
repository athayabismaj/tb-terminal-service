-- =============================================
-- V9: Receivable Schema Tables
-- 3 tabel: customers, receivables, receivable_payments
--
-- CONSTRAINT:
--   - Soft delete: customers menggunakan is_active
--   - Semua harga NUMERIC, BUKAN FLOAT/DOUBLE
--   - Cross-schema FK ditunda ke V11
-- =============================================

-- ─── customers ──────────────────────────────────────────
-- Soft delete: is_active = FALSE → pelanggan dinonaktifkan
CREATE TABLE receivable.customers (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(150)  NOT NULL,
    phone             VARCHAR(20),
    address           TEXT,
    is_contractor     BOOLEAN       NOT NULL DEFAULT FALSE, -- TRUE = dapat harga kontraktor
    credit_limit      NUMERIC(15,2) NOT NULL DEFAULT 0,     -- 0 = tidak ada limit
    payment_term_days INT           NOT NULL DEFAULT 0,     -- Termin bayar · 0 = wajib tunai
    is_active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ─── receivables ────────────────────────────────────────
-- Piutang pelanggan dari transaksi hutang/DP
-- transaction_id FK ke sales.transactions ditunda ke V11 (cross-schema)
CREATE TABLE receivable.receivables (
    id             UUID                     PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id    UUID                     NOT NULL REFERENCES receivable.customers(id) ON DELETE RESTRICT,
    transaction_id UUID                     NOT NULL, -- FK → sales.transactions.id (V11)
    amount         NUMERIC(15,2)            NOT NULL, -- Total hutang awal
    paid_amount    NUMERIC(15,2)            NOT NULL DEFAULT 0, -- Auto-update via trigger
    due_date       DATE                     NOT NULL,
    status         system.receivable_status NOT NULL DEFAULT 'belum_lunas', -- Auto-update via trigger
    created_at     TIMESTAMPTZ              NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ              NOT NULL DEFAULT NOW()
);

-- ─── receivable_payments ────────────────────────────────
-- Cicilan pembayaran piutang
-- user_id FK ke system.users ditunda ke V11 (cross-schema)
CREATE TABLE receivable.receivable_payments (
    id            UUID                  PRIMARY KEY DEFAULT gen_random_uuid(),
    receivable_id UUID                  NOT NULL REFERENCES receivable.receivables(id) ON DELETE RESTRICT,
    user_id       UUID                  NOT NULL, -- FK → system.users.id (V11)
    amount        NUMERIC(15,2)         NOT NULL,
    method        system.payment_method NOT NULL,
    reference     VARCHAR(100),
    notes         TEXT,
    paid_at       TIMESTAMPTZ           NOT NULL DEFAULT NOW()
);
