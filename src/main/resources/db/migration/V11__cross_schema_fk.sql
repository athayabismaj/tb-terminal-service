-- =============================================
-- V11: Cross-Schema Foreign Keys
-- Semua FK yang melintasi batas schema dikumpulkan di sini
-- untuk menghindari circular dependency saat migrasi awal.
-- =============================================

-- ═══════════════════════════════════════════════
-- INVENTORY → SYSTEM
-- ═══════════════════════════════════════════════

ALTER TABLE inventory.stock_adjustments
    ADD CONSTRAINT fk_stock_adj_user
    FOREIGN KEY (user_id) REFERENCES system.users(id) ON DELETE RESTRICT;

ALTER TABLE inventory.price_history
    ADD CONSTRAINT fk_price_history_user
    FOREIGN KEY (changed_by) REFERENCES system.users(id) ON DELETE RESTRICT;

-- ═══════════════════════════════════════════════
-- SALES → SYSTEM
-- ═══════════════════════════════════════════════

ALTER TABLE sales.cash_sessions
    ADD CONSTRAINT fk_cash_sessions_user
    FOREIGN KEY (user_id) REFERENCES system.users(id) ON DELETE RESTRICT;

ALTER TABLE sales.transactions
    ADD CONSTRAINT fk_transactions_user
    FOREIGN KEY (user_id) REFERENCES system.users(id) ON DELETE RESTRICT;

-- ═══════════════════════════════════════════════
-- SALES → RECEIVABLE
-- ═══════════════════════════════════════════════

ALTER TABLE sales.transactions
    ADD CONSTRAINT fk_transactions_customer
    FOREIGN KEY (customer_id) REFERENCES receivable.customers(id) ON DELETE RESTRICT;

-- ═══════════════════════════════════════════════
-- SALES → INVENTORY
-- ═══════════════════════════════════════════════

ALTER TABLE sales.transaction_items
    ADD CONSTRAINT fk_trx_items_product
    FOREIGN KEY (product_id) REFERENCES inventory.products(id) ON DELETE RESTRICT;

ALTER TABLE sales.transaction_items
    ADD CONSTRAINT fk_trx_items_unit
    FOREIGN KEY (unit_id) REFERENCES inventory.units(id) ON DELETE RESTRICT;

-- ═══════════════════════════════════════════════
-- RECEIVABLE → SALES
-- ═══════════════════════════════════════════════

ALTER TABLE receivable.receivables
    ADD CONSTRAINT fk_receivables_transaction
    FOREIGN KEY (transaction_id) REFERENCES sales.transactions(id) ON DELETE RESTRICT;

-- ═══════════════════════════════════════════════
-- RECEIVABLE → SYSTEM
-- ═══════════════════════════════════════════════

ALTER TABLE receivable.receivable_payments
    ADD CONSTRAINT fk_recv_payments_user
    FOREIGN KEY (user_id) REFERENCES system.users(id) ON DELETE RESTRICT;

-- ═══════════════════════════════════════════════
-- PURCHASING → SYSTEM
-- ═══════════════════════════════════════════════

ALTER TABLE purchasing.purchases
    ADD CONSTRAINT fk_purchases_user
    FOREIGN KEY (user_id) REFERENCES system.users(id) ON DELETE RESTRICT;

ALTER TABLE purchasing.supplier_payments
    ADD CONSTRAINT fk_supplier_payments_user
    FOREIGN KEY (user_id) REFERENCES system.users(id) ON DELETE RESTRICT;

-- ═══════════════════════════════════════════════
-- PURCHASING → INVENTORY
-- ═══════════════════════════════════════════════

ALTER TABLE purchasing.purchase_items
    ADD CONSTRAINT fk_purch_items_product
    FOREIGN KEY (product_id) REFERENCES inventory.products(id) ON DELETE RESTRICT;

ALTER TABLE purchasing.purchase_items
    ADD CONSTRAINT fk_purch_items_unit
    FOREIGN KEY (unit_id) REFERENCES inventory.units(id) ON DELETE RESTRICT;
