-- =============================================
-- V12: Indexes for Performance
--   - B-Tree indexes for all Foreign Keys (prevent slow JOINs)
--   - GIN indexes (pg_trgm) for ILIKE search optimizations
--   - B-Tree indexes for date ranges (created_at)
-- =============================================

-- Enable pg_trgm extension for GIN index text search optimization
CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public;

-- ═══════════════════════════════════════════════
-- INVENTORY SCHEMA INDEXES
-- ═══════════════════════════════════════════════

-- Foreign Key Indexes
CREATE INDEX idx_products_category ON inventory.products(category_id);
CREATE INDEX idx_products_unit ON inventory.products(base_unit_id);
CREATE INDEX idx_unit_conv_product ON inventory.unit_conversions(product_id);
CREATE INDEX idx_unit_conv_from ON inventory.unit_conversions(from_unit_id);
CREATE INDEX idx_unit_conv_to ON inventory.unit_conversions(to_unit_id);
CREATE INDEX idx_stock_unit ON inventory.stock(unit_id);
CREATE INDEX idx_stock_adj_product ON inventory.stock_adjustments(product_id);
CREATE INDEX idx_stock_adj_user ON inventory.stock_adjustments(user_id);
CREATE INDEX idx_price_hist_product ON inventory.price_history(product_id);
CREATE INDEX idx_price_hist_user ON inventory.price_history(changed_by);

-- Text Search Optimization (pg_trgm GIN)
CREATE INDEX idx_products_name_gin ON inventory.products USING GIN (name public.gin_trgm_ops);
CREATE INDEX idx_products_sku ON inventory.products(sku); -- B-Tree is enough for exact match

-- ═══════════════════════════════════════════════
-- SALES SCHEMA INDEXES
-- ═══════════════════════════════════════════════

-- Foreign Key Indexes
CREATE INDEX idx_cash_sessions_user ON sales.cash_sessions(user_id);
CREATE INDEX idx_transactions_session ON sales.transactions(session_id);
CREATE INDEX idx_transactions_customer ON sales.transactions(customer_id);
CREATE INDEX idx_transactions_user ON sales.transactions(user_id);
CREATE INDEX idx_trx_items_trx ON sales.transaction_items(transaction_id);
CREATE INDEX idx_trx_items_product ON sales.transaction_items(product_id);
CREATE INDEX idx_trx_items_unit ON sales.transaction_items(unit_id);
CREATE INDEX idx_payments_trx ON sales.payments(transaction_id);

-- Date Range Optimization
CREATE INDEX idx_transactions_date ON sales.transactions(created_at);
CREATE INDEX idx_cash_sessions_date ON sales.cash_sessions(opened_at);

-- ═══════════════════════════════════════════════
-- RECEIVABLE SCHEMA INDEXES
-- ═══════════════════════════════════════════════

-- Foreign Key Indexes
CREATE INDEX idx_receivables_customer ON receivable.receivables(customer_id);
CREATE INDEX idx_receivables_trx ON receivable.receivables(transaction_id);
CREATE INDEX idx_recv_payments_recv ON receivable.receivable_payments(receivable_id);
CREATE INDEX idx_recv_payments_user ON receivable.receivable_payments(user_id);

-- Text Search Optimization (pg_trgm GIN)
CREATE INDEX idx_customers_name_gin ON receivable.customers USING GIN (name public.gin_trgm_ops);

-- Date Range Optimization
CREATE INDEX idx_receivables_due_date ON receivable.receivables(due_date);

-- ═══════════════════════════════════════════════
-- PURCHASING SCHEMA INDEXES
-- ═══════════════════════════════════════════════

-- Foreign Key Indexes
CREATE INDEX idx_purchases_supplier ON purchasing.purchases(supplier_id);
CREATE INDEX idx_purchases_user ON purchasing.purchases(user_id);
CREATE INDEX idx_purch_items_purch ON purchasing.purchase_items(purchase_id);
CREATE INDEX idx_purch_items_product ON purchasing.purchase_items(product_id);
CREATE INDEX idx_purch_items_unit ON purchasing.purchase_items(unit_id);
CREATE INDEX idx_supp_payables_supplier ON purchasing.supplier_payables(supplier_id);
CREATE INDEX idx_supp_payables_purch ON purchasing.supplier_payables(purchase_id);
CREATE INDEX idx_supp_payments_payable ON purchasing.supplier_payments(supplier_payable_id);
CREATE INDEX idx_supp_payments_user ON purchasing.supplier_payments(user_id);

-- Text Search Optimization (pg_trgm GIN)
CREATE INDEX idx_suppliers_name_gin ON purchasing.suppliers USING GIN (name public.gin_trgm_ops);

-- Date Range Optimization
CREATE INDEX idx_purchases_date ON purchasing.purchases(created_at);
