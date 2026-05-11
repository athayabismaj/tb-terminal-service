-- =============================================
-- V10: Purchasing Schema Tables
-- 5 tabel: suppliers, purchases, purchase_items,
--          supplier_payables, supplier_payments
--
-- CONSTRAINT:
--   - Soft delete: suppliers menggunakan is_active
--   - Semua harga NUMERIC, BUKAN FLOAT/DOUBLE
--   - purchase_items WAJIB snapshot:
--       price_at_transaction = harga beli saat pembelian
--       cogs_at_transaction  = HPP sebelumnya (sebelum update)
--   - Cross-schema FK ditunda ke V11
-- =============================================

-- ─── suppliers ──────────────────────────────────────────
-- Soft delete: is_active = FALSE → supplier dinonaktifkan
CREATE TABLE purchasing.suppliers (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(150) NOT NULL,
    phone             VARCHAR(20),
    address           TEXT,
    payment_term_days INT          NOT NULL DEFAULT 30, -- Termin bayar default dalam hari
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─── purchases ──────────────────────────────────────────
-- user_id FK ke system.users ditunda ke V11 (cross-schema)
CREATE TABLE purchasing.purchases (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id UUID          NOT NULL REFERENCES purchasing.suppliers(id) ON DELETE RESTRICT,
    user_id     UUID          NOT NULL, -- FK → system.users.id (V11)
    invoice_no  VARCHAR(100),           -- Nomor nota dari supplier
    total       NUMERIC(15,2) NOT NULL DEFAULT 0,
    received_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    notes       TEXT,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ─── purchase_items ─────────────────────────────────────
-- SNAPSHOTTING: price_at_transaction & cogs_at_transaction
--   → Tidak boleh JOIN ke products untuk hitung total riwayat
-- product_id & unit_id FK ditunda ke V11 (cross-schema)
CREATE TABLE purchasing.purchase_items (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_id          UUID          NOT NULL REFERENCES purchasing.purchases(id) ON DELETE CASCADE,
    product_id           UUID          NOT NULL, -- FK → inventory.products.id (V11)
    unit_id              UUID          NOT NULL, -- FK → inventory.units.id (V11)
    quantity             NUMERIC(10,2) NOT NULL,
    price_at_transaction NUMERIC(15,2) NOT NULL, -- Snapshot harga beli per unit saat pembelian
    cogs_at_transaction  NUMERIC(15,2) NOT NULL DEFAULT 0, -- Snapshot HPP sebelumnya (price_buy lama)
    subtotal             NUMERIC(15,2) NOT NULL  -- quantity × price_at_transaction
);

-- ─── supplier_payables ──────────────────────────────────
-- Hutang ke supplier dari setiap pembelian
CREATE TABLE purchasing.supplier_payables (
    id          UUID                  PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id UUID                  NOT NULL REFERENCES purchasing.suppliers(id) ON DELETE RESTRICT,
    purchase_id UUID                  NOT NULL REFERENCES purchasing.purchases(id) ON DELETE RESTRICT,
    amount      NUMERIC(15,2)         NOT NULL, -- Total hutang awal
    paid_amount NUMERIC(15,2)         NOT NULL DEFAULT 0, -- Auto-update via trigger
    due_date    DATE                  NOT NULL,
    status      system.payable_status NOT NULL DEFAULT 'belum_lunas', -- Auto-update
    created_at  TIMESTAMPTZ           NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ           NOT NULL DEFAULT NOW()
);

-- ─── supplier_payments ──────────────────────────────────
-- Pembayaran hutang ke supplier
-- user_id FK ke system.users ditunda ke V11 (cross-schema)
CREATE TABLE purchasing.supplier_payments (
    id                  UUID                  PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_payable_id UUID                  NOT NULL REFERENCES purchasing.supplier_payables(id) ON DELETE RESTRICT,
    user_id             UUID                  NOT NULL, -- FK → system.users.id (V11)
    amount              NUMERIC(15,2)         NOT NULL,
    method              system.payment_method NOT NULL,
    reference           VARCHAR(100),
    notes               TEXT,
    paid_at             TIMESTAMPTZ           NOT NULL DEFAULT NOW()
);
