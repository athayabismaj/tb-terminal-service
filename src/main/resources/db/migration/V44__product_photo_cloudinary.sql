DROP VIEW IF EXISTS inventory.v_stock_detail;

ALTER TABLE inventory.products
    ALTER COLUMN photo_filename TYPE TEXT;

ALTER TABLE inventory.products
    ADD COLUMN IF NOT EXISTS photo_public_id VARCHAR(255) NULL;

CREATE OR REPLACE VIEW inventory.v_stock_detail AS
SELECT
    p.id AS product_id,
    p.sku,
    p.name AS product_name,
    c.name AS category_name,
    u.name AS unit_name,
    COALESCE(s.quantity, 0) AS quantity,
    p.min_stock,
    p.price_buy,
    p.price_retail,
    p.price_contractor,
    p.discount,
    p.is_active,
    p.photo_filename
FROM inventory.products p
JOIN inventory.categories c ON p.category_id = c.id
JOIN inventory.units u ON p.base_unit_id = u.id
LEFT JOIN inventory.stock s ON p.id = s.product_id;
