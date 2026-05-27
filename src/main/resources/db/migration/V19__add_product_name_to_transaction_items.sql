-- =============================================
-- V19: Add Product Name Snapshot to Transaction Items
--
-- PURPOSE:
--   To guarantee data immutability for historical
--   receipts, we must snapshot the product name
--   at the time of the transaction.
-- =============================================

ALTER TABLE sales.transaction_items
ADD COLUMN product_name VARCHAR(255) NOT NULL DEFAULT '';

-- Optional: Populate existing data (if there are active products with matching UUIDs)
UPDATE sales.transaction_items ti
SET product_name = (
    SELECT name
    FROM inventory.products p
    WHERE p.id = ti.product_id
)
WHERE product_name = '';
