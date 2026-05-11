-- =============================================
-- V15: Views for Reporting and Quick Access
--   - v_stock_detail: Join product, categories, units, and stock quantity
--   - v_receivables_active: Active receivables (status != lunas)
--   - v_daily_sales: Daily sales aggregation
-- =============================================

-- ─── 1. v_stock_detail ──────────────────────────────────
CREATE VIEW inventory.v_stock_detail AS
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
    p.is_active
FROM inventory.products p
JOIN inventory.categories c ON p.category_id = c.id
JOIN inventory.units u ON p.base_unit_id = u.id
LEFT JOIN inventory.stock s ON p.id = s.product_id;

-- ─── 2. v_receivables_active ────────────────────────────
CREATE VIEW receivable.v_receivables_active AS
SELECT 
    r.id AS receivable_id, 
    r.customer_id, 
    c.name AS customer_name, 
    r.amount AS total_debt, 
    r.paid_amount, 
    (r.amount - r.paid_amount) AS remaining_debt, 
    r.due_date, 
    r.status
FROM receivable.receivables r
JOIN receivable.customers c ON r.customer_id = c.id
WHERE r.status != 'lunas';

-- ─── 3. v_daily_sales ───────────────────────────────────
CREATE VIEW sales.v_daily_sales AS
SELECT 
    date_trunc('day', created_at)::date AS sale_date, 
    COUNT(id) AS total_transactions, 
    SUM(total) AS total_revenue, 
    SUM(dp_amount) AS total_dp
FROM sales.transactions
-- As per trx_status ENUM: lunas, dp, hutang. 
-- We consider all these as valid sales transactions.
-- No 'batal' status exists currently.
GROUP BY date_trunc('day', created_at)::date;
