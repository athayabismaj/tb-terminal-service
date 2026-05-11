-- =============================================
-- V7: Inventory Schema Tables
-- 7 tabel: categories, units, products, unit_conversions,
--          stock, stock_adjustments, price_history
--
-- CONSTRAINT:
--   - Semua harga menggunakan NUMERIC, BUKAN FLOAT/DOUBLE
--   - products menggunakan soft delete (is_active)
--   - products.sku UNIQUE
--   - Cross-schema FK (ke system.users) ditunda ke V11
-- =============================================

-- ─── categories ─────────────────────────────────────────
CREATE TABLE inventory.categories (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─── units ──────────────────────────────────────────────
CREATE TABLE inventory.units (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(50) NOT NULL UNIQUE,
    symbol     VARCHAR(20) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─── products ───────────────────────────────────────────
-- Soft delete: is_active = FALSE → produk tidak bisa dijual
-- photo_filename akan di-rename ke photo_public_id di V12
CREATE TABLE inventory.products (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id      UUID          NOT NULL REFERENCES inventory.categories(id) ON DELETE RESTRICT,
    base_unit_id     UUID          NOT NULL REFERENCES inventory.units(id) ON DELETE RESTRICT,
    sku              VARCHAR(50)   NOT NULL UNIQUE,
    name             VARCHAR(200)  NOT NULL,
    price_buy        NUMERIC(15,2) NOT NULL DEFAULT 0,
    price_retail     NUMERIC(15,2) NOT NULL DEFAULT 0,
    price_contractor NUMERIC(15,2) NOT NULL DEFAULT 0,
    min_stock        NUMERIC(10,2) NOT NULL DEFAULT 0,
    photo_filename   VARCHAR(255),
    is_active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ─── unit_conversions ───────────────────────────────────
-- Contoh: 1 dus = 50 pcs → from_unit = dus, to_unit = pcs, factor = 50
CREATE TABLE inventory.unit_conversions (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id   UUID          NOT NULL REFERENCES inventory.products(id) ON DELETE CASCADE,
    from_unit_id UUID          NOT NULL REFERENCES inventory.units(id) ON DELETE RESTRICT,
    to_unit_id   UUID          NOT NULL REFERENCES inventory.units(id) ON DELETE RESTRICT,
    factor       NUMERIC(10,4) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE(product_id, from_unit_id, to_unit_id)
);

-- ─── stock ──────────────────────────────────────────────
-- 1 produk = 1 baris stok (UNIQUE product_id)
-- quantity dikelola HANYA oleh trigger — JANGAN update manual
CREATE TABLE inventory.stock (
    id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID          NOT NULL UNIQUE REFERENCES inventory.products(id) ON DELETE CASCADE,
    unit_id    UUID          NOT NULL REFERENCES inventory.units(id) ON DELETE RESTRICT,
    quantity   NUMERIC(10,2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ─── stock_adjustments ──────────────────────────────────
-- Opname / koreksi / retur supplier
-- user_id FK ke system.users ditunda ke V11 (cross-schema)
CREATE TABLE inventory.stock_adjustments (
    id         UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID            NOT NULL REFERENCES inventory.products(id) ON DELETE RESTRICT,
    user_id    UUID            NOT NULL, -- FK → system.users.id (V11)
    type       system.adj_type NOT NULL DEFAULT 'opname',
    qty_before NUMERIC(10,2)   NOT NULL,
    qty_after  NUMERIC(10,2)   NOT NULL,
    reason     TEXT            NOT NULL,
    created_at TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ─── price_history ──────────────────────────────────────
-- HANYA diisi via trigger — tidak pernah ditulis manual
-- changed_by FK ke system.users ditunda ke V11 (cross-schema)
CREATE TABLE inventory.price_history (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id           UUID          NOT NULL REFERENCES inventory.products(id) ON DELETE RESTRICT,
    changed_by           UUID          NOT NULL, -- FK → system.users.id (V11)
    old_price_buy        NUMERIC(15,2) NOT NULL,
    new_price_buy        NUMERIC(15,2) NOT NULL,
    old_price_retail     NUMERIC(15,2) NOT NULL,
    new_price_retail     NUMERIC(15,2) NOT NULL,
    old_price_contractor NUMERIC(15,2) NOT NULL,
    new_price_contractor NUMERIC(15,2) NOT NULL,
    changed_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
