-- =============================================
-- V8: Sales Schema Tables
-- 4 tabel: cash_sessions, transactions,
--          transaction_items, payments
--
-- CONSTRAINT:
--   - Semua harga NUMERIC, BUKAN FLOAT/DOUBLE
--   - transaction_items WAJIB snapshot harga:
--       price_at_transaction = harga jual saat transaksi
--       cogs_at_transaction  = HPP (price_buy) saat transaksi
--   - Cross-schema FK ditunda ke V11
-- =============================================

-- ─── cash_sessions ──────────────────────────────────────
-- Satu sesi per shift kasir
-- user_id FK ke system.users ditunda ke V11 (cross-schema)
CREATE TABLE sales.cash_sessions (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID          NOT NULL, -- FK → system.users.id (V11)
    opened_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    closed_at    TIMESTAMPTZ,                       -- NULL = shift masih berjalan
    opening_cash NUMERIC(15,2) NOT NULL DEFAULT 0,  -- Uang awal di laci
    closing_cash NUMERIC(15,2),                     -- Uang fisik saat tutup shift
    system_cash  NUMERIC(15,2),                     -- Kas menurut catatan sistem
    difference   NUMERIC(15,2),                     -- closing_cash - system_cash
    notes        TEXT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ─── transactions ───────────────────────────────────────
-- user_id & customer_id FK ditunda ke V11 (cross-schema)
CREATE TABLE sales.transactions (
    id          UUID              PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID              NOT NULL REFERENCES sales.cash_sessions(id) ON DELETE RESTRICT,
    customer_id UUID,                                    -- FK → receivable.customers.id (V11) · NULL = pelanggan umum
    user_id     UUID              NOT NULL,              -- FK → system.users.id (V11)
    type        system.trx_type   NOT NULL DEFAULT 'penjualan',
    status      system.trx_status NOT NULL DEFAULT 'lunas',
    total       NUMERIC(15,2)     NOT NULL DEFAULT 0,
    dp_amount   NUMERIC(15,2)     NOT NULL DEFAULT 0,    -- Uang muka
    paid_amount NUMERIC(15,2)     NOT NULL DEFAULT 0,    -- Total yang sudah dibayar
    notes       TEXT,
    created_at  TIMESTAMPTZ       NOT NULL DEFAULT NOW()
);

-- ─── transaction_items ──────────────────────────────────
-- SNAPSHOTTING: price_at_transaction & cogs_at_transaction
--   → Tidak boleh JOIN ke products untuk hitung total riwayat
-- product_id & unit_id FK ditunda ke V11 (cross-schema)
CREATE TABLE sales.transaction_items (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id       UUID          NOT NULL REFERENCES sales.transactions(id) ON DELETE CASCADE,
    product_id           UUID          NOT NULL, -- FK → inventory.products.id (V11)
    unit_id              UUID          NOT NULL, -- FK → inventory.units.id (V11)
    quantity             NUMERIC(10,2) NOT NULL,
    price_at_transaction NUMERIC(15,2) NOT NULL, -- Snapshot harga jual saat transaksi
    cogs_at_transaction  NUMERIC(15,2) NOT NULL DEFAULT 0, -- Snapshot HPP (price_buy) saat transaksi
    discount             NUMERIC(15,2) NOT NULL DEFAULT 0,
    subtotal             NUMERIC(15,2) NOT NULL  -- (quantity × price_at_transaction) - discount
);

-- ─── payments ───────────────────────────────────────────
-- Satu transaksi bisa multi-payment (tunai + transfer, dll.)
CREATE TABLE sales.payments (
    id             UUID                 PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID                 NOT NULL REFERENCES sales.transactions(id) ON DELETE CASCADE,
    method         system.payment_method NOT NULL,
    amount         NUMERIC(15,2)        NOT NULL,
    reference      VARCHAR(100),                    -- Nomor referensi transfer / QRIS
    paid_at        TIMESTAMPTZ          NOT NULL DEFAULT NOW()
);
