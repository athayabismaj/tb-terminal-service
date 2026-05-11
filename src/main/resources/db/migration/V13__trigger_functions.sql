-- =============================================
-- V13: Trigger Functions
--   - fn_update_timestamp: Updates updated_at column
--   - fn_enforce_singleton: Ensures only 1 row exists
--   - fn_sync_stock: Updates inventory.stock from sales/purchasing
--   - fn_log_price_history: Logs changes in product prices
-- =============================================

-- ─── 1. fn_update_timestamp ─────────────────────────────
CREATE OR REPLACE FUNCTION system.fn_update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ─── 2. fn_enforce_singleton ────────────────────────────
CREATE OR REPLACE FUNCTION system.fn_enforce_singleton()
RETURNS TRIGGER AS $$
BEGIN
    IF (SELECT COUNT(*) FROM system.store_settings) > 0 AND TG_OP = 'INSERT' THEN
        RAISE EXCEPTION 'Table store_settings can only have one row (Singleton)';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ─── 3. fn_sync_stock ───────────────────────────────────
-- Called from sales.transaction_items & purchasing.purchase_items
-- Handles INSERT, UPDATE, DELETE. Uses FOR UPDATE to prevent race condition.
CREATE OR REPLACE FUNCTION inventory.fn_sync_stock()
RETURNS TRIGGER AS $$
DECLARE
    v_product_id UUID;
    v_qty_diff NUMERIC(10,2) := 0;
    v_current_stock NUMERIC(10,2);
BEGIN
    -- Determine the target product ID and quantity difference based on operation
    IF TG_OP = 'INSERT' THEN
        v_product_id := NEW.product_id;
        IF TG_TABLE_SCHEMA = 'sales' AND TG_TABLE_NAME = 'transaction_items' THEN
            v_qty_diff := -NEW.quantity;
        ELSIF TG_TABLE_SCHEMA = 'purchasing' AND TG_TABLE_NAME = 'purchase_items' THEN
            v_qty_diff := NEW.quantity;
        END IF;
    ELSIF TG_OP = 'DELETE' THEN
        v_product_id := OLD.product_id;
        IF TG_TABLE_SCHEMA = 'sales' AND TG_TABLE_NAME = 'transaction_items' THEN
            v_qty_diff := OLD.quantity;
        ELSIF TG_TABLE_SCHEMA = 'purchasing' AND TG_TABLE_NAME = 'purchase_items' THEN
            v_qty_diff := -OLD.quantity;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        v_product_id := NEW.product_id;
        -- If product changed (rare but possible), we should revert old and apply new.
        -- For simplicity in POS, assuming product_id cannot change.
        IF OLD.product_id != NEW.product_id THEN
            RAISE EXCEPTION 'Cannot change product_id in transaction item';
        END IF;
        
        IF TG_TABLE_SCHEMA = 'sales' AND TG_TABLE_NAME = 'transaction_items' THEN
            v_qty_diff := OLD.quantity - NEW.quantity;
        ELSIF TG_TABLE_SCHEMA = 'purchasing' AND TG_TABLE_NAME = 'purchase_items' THEN
            v_qty_diff := NEW.quantity - OLD.quantity;
        END IF;
    END IF;

    -- Lock the stock row to prevent race condition
    SELECT quantity INTO v_current_stock 
    FROM inventory.stock 
    WHERE product_id = v_product_id 
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Stock record not found for product_id %', v_product_id;
    END IF;

    -- Check if stock would go negative (only for sales, we allow negative for adjustments but not sales)
    IF v_current_stock + v_qty_diff < 0 THEN
        RAISE EXCEPTION 'Insufficient stock. Current: %, Requested change: %', v_current_stock, v_qty_diff;
    END IF;

    -- Update the stock
    UPDATE inventory.stock
    SET quantity = quantity + v_qty_diff,
        updated_at = NOW()
    WHERE product_id = v_product_id;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    ELSE
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- ─── 4. fn_log_price_history ────────────────────────────
-- Called BEFORE UPDATE on inventory.products to log if prices changed
CREATE OR REPLACE FUNCTION inventory.fn_log_price_history()
RETURNS TRIGGER AS $$
BEGIN
    -- Only log if a price has actually changed
    IF NEW.price_buy != OLD.price_buy OR 
       NEW.price_retail != OLD.price_retail OR 
       NEW.price_contractor != OLD.price_contractor THEN
       
        INSERT INTO inventory.price_history (
            product_id, 
            changed_by, 
            old_price_buy, new_price_buy, 
            old_price_retail, new_price_retail, 
            old_price_contractor, new_price_contractor
        ) VALUES (
            NEW.id,
            COALESCE(
                NULLIF(current_setting('app.current_user_id', true), ''), 
                (SELECT id::text FROM system.users ORDER BY created_at LIMIT 1)
            )::uuid,
            OLD.price_buy, NEW.price_buy,
            OLD.price_retail, NEW.price_retail,
            OLD.price_contractor, NEW.price_contractor
        );
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
