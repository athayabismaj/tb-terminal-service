ALTER TABLE sales.transaction_items
    ADD CONSTRAINT ck_sales_transaction_items_quantity_positive
        CHECK (quantity > 0),
    ADD CONSTRAINT ck_sales_transaction_items_price_nonnegative
        CHECK (price_at_transaction >= 0),
    ADD CONSTRAINT ck_sales_transaction_items_discount_range
        CHECK (discount >= 0 AND discount <= price_at_transaction),
    ADD CONSTRAINT ck_sales_transaction_items_subtotal_nonnegative
        CHECK (subtotal >= 0),
    ADD CONSTRAINT ck_sales_transaction_items_subtotal_formula
        CHECK (subtotal = ROUND(quantity * (price_at_transaction - discount), 2));

ALTER TABLE sales.transactions
    ADD CONSTRAINT ck_sales_transactions_total_nonnegative
        CHECK (total >= 0),
    ADD CONSTRAINT ck_sales_transactions_dp_nonnegative
        CHECK (dp_amount >= 0),
    ADD CONSTRAINT ck_sales_transactions_paid_nonnegative
        CHECK (paid_amount >= 0),
    ADD CONSTRAINT ck_sales_transactions_paid_not_above_total
        CHECK (paid_amount <= total);

ALTER TABLE receivable.receivables
    ADD CONSTRAINT ck_receivables_amount_nonnegative
        CHECK (amount >= 0),
    ADD CONSTRAINT ck_receivables_paid_nonnegative
        CHECK (paid_amount >= 0),
    ADD CONSTRAINT ck_receivables_paid_not_above_amount
        CHECK (paid_amount <= amount);

CREATE UNIQUE INDEX uq_cash_sessions_one_open_per_user
    ON sales.cash_sessions (user_id)
    WHERE closed_at IS NULL;
