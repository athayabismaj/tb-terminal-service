-- Tahap 2D: Aturan Harga Grosir Bertingkat per Qty (Tiered Pricing).

CREATE TABLE inventory.product_price_tiers (
    id         UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID           NOT NULL REFERENCES inventory.products(id) ON DELETE CASCADE,
    min_qty    NUMERIC(10,2)  NOT NULL CHECK (min_qty > 0),
    unit_price NUMERIC(15,2)  NOT NULL CHECK (unit_price >= 0),
    created_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_product_price_tiers_product_min_qty UNIQUE (product_id, min_qty)
);

CREATE INDEX idx_product_price_tiers_product_min_qty
    ON inventory.product_price_tiers (product_id, min_qty ASC);
