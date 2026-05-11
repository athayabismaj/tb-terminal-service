-- =============================================
-- V14: Triggers
--   - Attach update_timestamp to all tables with updated_at
--   - Attach singleton enforcement to store_settings
--   - Attach stock sync to transaction_items and purchase_items
--   - Attach price history logging to products
-- =============================================

-- ─── 1. Timestamp Triggers ──────────────────────────────
-- System
CREATE TRIGGER trg_store_settings_timestamp
BEFORE UPDATE ON system.store_settings
FOR EACH ROW EXECUTE FUNCTION system.fn_update_timestamp();

-- Inventory
CREATE TRIGGER trg_products_timestamp
BEFORE UPDATE ON inventory.products
FOR EACH ROW EXECUTE FUNCTION system.fn_update_timestamp();

CREATE TRIGGER trg_stock_timestamp
BEFORE UPDATE ON inventory.stock
FOR EACH ROW EXECUTE FUNCTION system.fn_update_timestamp();

-- Receivable
CREATE TRIGGER trg_customers_timestamp
BEFORE UPDATE ON receivable.customers
FOR EACH ROW EXECUTE FUNCTION system.fn_update_timestamp();

CREATE TRIGGER trg_receivables_timestamp
BEFORE UPDATE ON receivable.receivables
FOR EACH ROW EXECUTE FUNCTION system.fn_update_timestamp();

-- Purchasing
CREATE TRIGGER trg_suppliers_timestamp
BEFORE UPDATE ON purchasing.suppliers
FOR EACH ROW EXECUTE FUNCTION system.fn_update_timestamp();

CREATE TRIGGER trg_supplier_payables_timestamp
BEFORE UPDATE ON purchasing.supplier_payables
FOR EACH ROW EXECUTE FUNCTION system.fn_update_timestamp();

-- ─── 2. Singleton Trigger ───────────────────────────────
CREATE TRIGGER trg_store_settings_singleton
BEFORE INSERT ON system.store_settings
FOR EACH ROW EXECUTE FUNCTION system.fn_enforce_singleton();

-- ─── 3. Stock Sync Triggers ─────────────────────────────
-- Sales
CREATE TRIGGER trg_sales_sync_stock
AFTER INSERT OR UPDATE OR DELETE ON sales.transaction_items
FOR EACH ROW EXECUTE FUNCTION inventory.fn_sync_stock();

-- Purchasing
CREATE TRIGGER trg_purchasing_sync_stock
AFTER INSERT OR UPDATE OR DELETE ON purchasing.purchase_items
FOR EACH ROW EXECUTE FUNCTION inventory.fn_sync_stock();

-- ─── 4. Price History Trigger ───────────────────────────
CREATE TRIGGER trg_products_price_history
BEFORE UPDATE ON inventory.products
FOR EACH ROW EXECUTE FUNCTION inventory.fn_log_price_history();
