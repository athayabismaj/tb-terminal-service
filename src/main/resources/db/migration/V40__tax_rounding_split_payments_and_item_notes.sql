-- Tahap 1: Pengaturan Pajak (PPN), Pembulatan Tunai, Split Payment, dan Catatan per Item.

-- 1. Konfigurasi Toko: Pajak (default OFF) & Pembulatan Tunai (default OFF)
ALTER TABLE system.store_settings
    ADD COLUMN tax_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN tax_rate NUMERIC(5,2) NOT NULL DEFAULT 11.00,
    ADD COLUMN tax_mode VARCHAR(20) NOT NULL DEFAULT 'EXCLUDE',
    ADD COLUMN tax_label VARCHAR(50) NOT NULL DEFAULT 'PPN',
    ADD COLUMN store_npwp VARCHAR(50),
    ADD COLUMN cash_rounding_unit INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN cash_rounding_rule VARCHAR(20) NOT NULL DEFAULT 'NEAREST',
    ADD CONSTRAINT ck_store_settings_tax_rate
        CHECK (tax_rate BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_store_settings_tax_mode
        CHECK (tax_mode IN ('EXCLUDE', 'INCLUDE')),
    ADD CONSTRAINT ck_store_settings_tax_label
        CHECK (CHAR_LENGTH(BTRIM(tax_label)) BETWEEN 1 AND 50),
    ADD CONSTRAINT ck_store_settings_cash_rounding_unit
        CHECK (cash_rounding_unit IN (0, 100, 500, 1000)),
    ADD CONSTRAINT ck_store_settings_cash_rounding_rule
        CHECK (cash_rounding_rule IN ('NEAREST', 'DOWN', 'UP'));

-- 2. Catatan Khusus per Item Keranjang (potong ukuran, warna, info gudang)
ALTER TABLE sales.transaction_items
    ADD COLUMN note TEXT,
    ADD CONSTRAINT ck_sales_transaction_items_note_length
        CHECK (note IS NULL OR CHAR_LENGTH(note) <= 300);

-- 3. Snapshot Pajak & Pembulatan Tunai per Transaksi
ALTER TABLE sales.transactions
    ADD COLUMN tax_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN tax_mode VARCHAR(20),
    ADD COLUMN tax_rate NUMERIC(5,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN tax_label VARCHAR(50),
    ADD COLUMN taxable_base NUMERIC(15,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN tax_amount NUMERIC(15,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN rounding_amount NUMERIC(15,2) NOT NULL DEFAULT 0.00;

ALTER TABLE sales.transactions
    DROP CONSTRAINT ck_sales_transaction_discount_snapshot,
    ADD CONSTRAINT ck_sales_transaction_discount_snapshot CHECK (
        gross_subtotal >= 0
        AND item_discount_total >= 0
        AND transaction_discount_value >= 0
        AND transaction_discount_amount >= 0
        AND total_discount_amount = item_discount_total + transaction_discount_amount
        AND taxable_base >= 0
        AND tax_rate BETWEEN 0 AND 100
        AND tax_amount >= 0
        AND (
            (tax_enabled = FALSE AND tax_mode IS NULL AND tax_rate = 0 AND taxable_base = 0 AND tax_amount = 0)
            OR (
                tax_enabled = TRUE
                AND tax_mode = 'EXCLUDE'
                AND taxable_base = gross_subtotal - total_discount_amount
                AND tax_amount = ROUND(taxable_base * tax_rate / 100, 2)
            )
            OR (
                tax_enabled = TRUE
                AND tax_mode = 'INCLUDE'
                AND taxable_base + tax_amount = gross_subtotal - total_discount_amount
            )
        )
        AND total = (
            gross_subtotal
            - total_discount_amount
            + CASE WHEN tax_enabled AND tax_mode = 'EXCLUDE' THEN tax_amount ELSE 0 END
            + rounding_amount
        )
        AND (transaction_discount_type IS NULL OR transaction_discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT'))
        AND (transaction_discount_type <> 'PERCENTAGE' OR transaction_discount_value <= 100)
        AND (
            (transaction_discount_type IS NULL AND transaction_discount_value = 0 AND transaction_discount_amount = 0)
            OR (transaction_discount_type = 'FIXED_AMOUNT' AND transaction_discount_amount = transaction_discount_value)
            OR (
                transaction_discount_type = 'PERCENTAGE'
                AND transaction_discount_amount = ROUND(
                    (gross_subtotal - item_discount_total) * transaction_discount_value / 100,
                    2
                )
            )
        )
    );

-- 4. Sumber Pembayaran untuk membedakan pembayaran awal checkout (termasuk split payment)
-- dari pelunasan piutang maupun entri kompensasi void/refund.
ALTER TABLE sales.payments
    ADD COLUMN payment_source VARCHAR(30) NOT NULL DEFAULT 'CHECKOUT',
    ADD CONSTRAINT ck_sales_payments_source
        CHECK (payment_source IN ('CHECKOUT', 'RECEIVABLE', 'VOID_REVERSAL', 'REFUND_REVERSAL'));

DROP TRIGGER IF EXISTS trg_prevent_sales_payment_mutation
    ON sales.payments;

UPDATE sales.payments
SET payment_source = CASE
    WHEN transaction_void_id IS NOT NULL THEN 'VOID_REVERSAL'
    WHEN transaction_refund_id IS NOT NULL THEN 'REFUND_REVERSAL'
    WHEN reference LIKE 'RECEIVABLE_PAYMENT:%' OR reference LIKE 'REVERSAL:%' THEN 'RECEIVABLE'
    ELSE 'CHECKOUT'
END;

CREATE TRIGGER trg_prevent_sales_payment_mutation
BEFORE UPDATE OR DELETE ON sales.payments
FOR EACH ROW EXECUTE FUNCTION sales.fn_prevent_sales_detail_mutation();
