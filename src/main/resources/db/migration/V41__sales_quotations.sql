-- Tahap 2C: Mode Penawaran Harga / Quotation (RAB) tanpa memotong stok.

CREATE TABLE sales.quotations (
    id                          UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_number            VARCHAR(50)    NOT NULL UNIQUE,
    user_id                     UUID           NOT NULL,
    customer_id                 UUID,
    customer_name_snapshot      VARCHAR(150),
    status                      VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    valid_until                 TIMESTAMPTZ,
    notes                       TEXT,
    gross_subtotal              NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    item_discount_total         NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    transaction_discount_type   VARCHAR(20),
    transaction_discount_value  NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    transaction_discount_amount NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    total_discount_amount       NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    tax_enabled                 BOOLEAN        NOT NULL DEFAULT FALSE,
    tax_mode                    VARCHAR(20),
    tax_rate                    NUMERIC(5,2)   NOT NULL DEFAULT 0.00,
    tax_label                   VARCHAR(50),
    taxable_base                NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    tax_amount                  NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    total                       NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    converted_transaction_id    UUID,
    converted_at                TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_sales_quotations_status
        CHECK (status IN ('DRAFT', 'SENT', 'CONVERTED', 'CANCELLED')),
    CONSTRAINT ck_sales_quotations_amounts
        CHECK (
            gross_subtotal >= 0
            AND item_discount_total >= 0
            AND transaction_discount_value >= 0
            AND transaction_discount_amount >= 0
            AND total_discount_amount = item_discount_total + transaction_discount_amount
            AND taxable_base >= 0
            AND tax_rate BETWEEN 0 AND 100
            AND tax_amount >= 0
            AND total >= 0
        )
);

CREATE TABLE sales.quotation_items (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id          UUID           NOT NULL REFERENCES sales.quotations(id) ON DELETE CASCADE,
    product_id            UUID           NOT NULL,
    unit_id               UUID           NOT NULL,
    product_name_snapshot VARCHAR(200)   NOT NULL,
    unit_name_snapshot    VARCHAR(50),
    quantity              NUMERIC(10,2)  NOT NULL,
    unit_price            NUMERIC(15,2)  NOT NULL,
    discount_type         VARCHAR(20),
    discount_value        NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    discount_amount       NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    subtotal              NUMERIC(15,2)  NOT NULL,
    note                  TEXT,
    CONSTRAINT ck_sales_quotation_items_qty
        CHECK (quantity > 0),
    CONSTRAINT ck_sales_quotation_items_money
        CHECK (unit_price >= 0 AND discount_value >= 0 AND discount_amount >= 0 AND subtotal >= 0),
    CONSTRAINT ck_sales_quotation_items_note_len
        CHECK (note IS NULL OR CHAR_LENGTH(note) <= 300)
);

CREATE INDEX idx_sales_quotations_created_at
    ON sales.quotations (created_at DESC);

CREATE INDEX idx_sales_quotations_status_user
    ON sales.quotations (status, user_id);

CREATE INDEX idx_sales_quotation_items_quotation_id
    ON sales.quotation_items (quotation_id);
