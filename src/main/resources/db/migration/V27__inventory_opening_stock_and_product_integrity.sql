-- Batch 2: enforce product numeric integrity and make stock adjustments the
-- single write path for manual/opening stock changes.

ALTER TABLE inventory.products
    ADD CONSTRAINT chk_products_price_buy_non_negative CHECK (price_buy >= 0),
    ADD CONSTRAINT chk_products_price_retail_non_negative CHECK (price_retail >= 0),
    ADD CONSTRAINT chk_products_price_contractor_non_negative CHECK (price_contractor >= 0),
    ADD CONSTRAINT chk_products_discount_non_negative CHECK (discount >= 0),
    ADD CONSTRAINT chk_products_min_stock_non_negative CHECK (min_stock >= 0);

CREATE UNIQUE INDEX uq_products_sku_case_insensitive
    ON inventory.products (UPPER(sku));

CREATE UNIQUE INDEX uq_categories_name_case_insensitive
    ON inventory.categories (LOWER(name));
CREATE UNIQUE INDEX uq_units_name_case_insensitive
    ON inventory.units (LOWER(name));
CREATE UNIQUE INDEX uq_units_symbol_case_insensitive
    ON inventory.units (LOWER(symbol));

ALTER TABLE inventory.stock_adjustments
    ADD COLUMN source VARCHAR(30) NOT NULL DEFAULT 'manual',
    ADD COLUMN occurred_on DATE NOT NULL DEFAULT CURRENT_DATE;

ALTER TABLE inventory.stock_adjustments
    ADD CONSTRAINT chk_stock_adjustment_source
        CHECK (source IN ('manual', 'opening_balance', 'csv_import')),
    ADD CONSTRAINT chk_stock_adjustment_quantities_non_negative
        CHECK (qty_before >= 0 AND qty_after >= 0);

CREATE UNIQUE INDEX uq_stock_adjustments_one_opening_balance
    ON inventory.stock_adjustments (product_id)
    WHERE source IN ('opening_balance', 'csv_import');

CREATE OR REPLACE FUNCTION inventory.fn_apply_stock_adjustment()
RETURNS TRIGGER AS $$
DECLARE
    v_current_stock NUMERIC(10,2);
BEGIN
    SELECT quantity INTO v_current_stock
    FROM inventory.stock
    WHERE product_id = NEW.product_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Stock record not found for product_id %', NEW.product_id;
    END IF;

    IF v_current_stock <> NEW.qty_before THEN
        RAISE EXCEPTION 'Stale stock adjustment. Current: %, expected: %',
            v_current_stock, NEW.qty_before;
    END IF;

    UPDATE inventory.stock
    SET quantity = NEW.qty_after,
        updated_at = NOW()
    WHERE product_id = NEW.product_id;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_apply_stock_adjustment
BEFORE INSERT ON inventory.stock_adjustments
FOR EACH ROW EXECUTE FUNCTION inventory.fn_apply_stock_adjustment();
