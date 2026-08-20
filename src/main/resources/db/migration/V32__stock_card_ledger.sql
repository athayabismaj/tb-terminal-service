-- Batch 6: unified stock card ledger for all inventory mutations.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE n.nspname = 'system' AND t.typname = 'stock_movement_type'
    ) THEN
        CREATE TYPE system.stock_movement_type AS ENUM (
            'OPENING_BALANCE', 'PURCHASE', 'SALE', 'OPNAME',
            'CORRECTION', 'DAMAGE', 'VOID', 'LEGACY_BASELINE'
        );
    END IF;
END $$;

ALTER TABLE inventory.stock_adjustments
    DROP CONSTRAINT chk_stock_adjustment_source,
    ADD COLUMN reference_type VARCHAR(40),
    ADD COLUMN reference_id UUID,
    ADD CONSTRAINT chk_stock_adjustment_source
        CHECK (source IN ('manual', 'opening_balance', 'csv_import', 'transaction_void'));

CREATE UNIQUE INDEX uq_stock_adjustment_transaction_void_product
    ON inventory.stock_adjustments(reference_id, product_id)
    WHERE source = 'transaction_void';

CREATE TABLE inventory.stock_movements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence_no BIGSERIAL NOT NULL UNIQUE,
    product_id UUID NOT NULL REFERENCES inventory.products(id) ON DELETE RESTRICT,
    unit_id UUID NOT NULL REFERENCES inventory.units(id) ON DELETE RESTRICT,
    movement_type system.stock_movement_type NOT NULL,
    balance_before NUMERIC(10,2) NOT NULL,
    qty_in NUMERIC(10,2) NOT NULL DEFAULT 0,
    qty_out NUMERIC(10,2) NOT NULL DEFAULT 0,
    balance_after NUMERIC(10,2) NOT NULL,
    reference_type VARCHAR(40) NOT NULL,
    reference_id UUID NOT NULL,
    reference_number VARCHAR(100),
    user_id UUID REFERENCES system.users(id) ON DELETE RESTRICT,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_stock_movement_flow CHECK (qty_in >= 0 AND qty_out >= 0),
    CONSTRAINT ck_stock_movement_single_direction CHECK (qty_in = 0 OR qty_out = 0),
    CONSTRAINT ck_stock_movement_balance CHECK (balance_after = balance_before + qty_in - qty_out),
    CONSTRAINT uq_stock_movement_reference UNIQUE (reference_type, reference_id)
);

CREATE INDEX idx_stock_movements_product_occurred
    ON inventory.stock_movements(product_id, sequence_no DESC);
CREATE INDEX idx_stock_movements_type_occurred
    ON inventory.stock_movements(movement_type, occurred_at DESC);

WITH raw AS (
    SELECT sa.product_id,
           p.base_unit_id AS unit_id,
           CASE
               WHEN sa.source IN ('opening_balance', 'csv_import') THEN 'OPENING_BALANCE'::system.stock_movement_type
               WHEN sa.type = 'opname' THEN 'OPNAME'::system.stock_movement_type
               WHEN sa.type = 'koreksi' THEN 'CORRECTION'::system.stock_movement_type
               ELSE 'DAMAGE'::system.stock_movement_type
           END AS movement_type,
           sa.qty_after - sa.qty_before AS delta,
           'STOCK_ADJUSTMENT'::VARCHAR AS reference_type,
           sa.id AS reference_id,
           NULL::VARCHAR AS reference_number,
           sa.user_id,
           (sa.occurred_on::TIMESTAMP AT TIME ZONE 'Asia/Jakarta') AS occurred_at
    FROM inventory.stock_adjustments sa
    JOIN inventory.products p ON p.id = sa.product_id
    UNION ALL
    SELECT pi.product_id, pi.unit_id, 'PURCHASE'::system.stock_movement_type,
           pi.quantity, 'PURCHASE_ITEM', pi.id, pu.invoice_no, pu.user_id, pu.received_at
    FROM purchasing.purchase_items pi
    JOIN purchasing.purchases pu ON pu.id = pi.purchase_id
    UNION ALL
    SELECT ti.product_id, ti.unit_id, 'SALE'::system.stock_movement_type,
           -ti.quantity, 'SALES_ITEM', ti.id, t.receipt_number, t.user_id, t.created_at
    FROM sales.transaction_items ti
    JOIN sales.transactions t ON t.id = ti.transaction_id
), totals AS (
    SELECT product_id, SUM(delta) AS total_delta, MIN(occurred_at) AS first_at
    FROM raw GROUP BY product_id
), baselines AS (
    SELECT s.product_id, s.unit_id, s.quantity - COALESCE(t.total_delta, 0) AS opening_balance,
           COALESCE(t.first_at - INTERVAL '1 second', p.created_at) AS occurred_at
    FROM inventory.stock s
    JOIN inventory.products p ON p.id = s.product_id
    LEFT JOIN totals t ON t.product_id = s.product_id
)
INSERT INTO inventory.stock_movements (
    product_id, unit_id, movement_type, balance_before, qty_in, qty_out,
    balance_after, reference_type, reference_id, reference_number, occurred_at
)
SELECT product_id, unit_id, 'LEGACY_BASELINE', 0,
       GREATEST(opening_balance, 0), GREATEST(-opening_balance, 0), opening_balance,
       'LEGACY_BASELINE', product_id, 'Migrasi saldo sebelum ledger', occurred_at
FROM baselines
WHERE opening_balance <> 0;

WITH raw AS (
    SELECT sa.product_id, p.base_unit_id AS unit_id,
           CASE
               WHEN sa.source IN ('opening_balance', 'csv_import') THEN 'OPENING_BALANCE'::system.stock_movement_type
               WHEN sa.type = 'opname' THEN 'OPNAME'::system.stock_movement_type
               WHEN sa.type = 'koreksi' THEN 'CORRECTION'::system.stock_movement_type
               ELSE 'DAMAGE'::system.stock_movement_type
           END AS movement_type,
           sa.qty_after - sa.qty_before AS delta, 'STOCK_ADJUSTMENT'::VARCHAR AS reference_type,
           sa.id AS reference_id, NULL::VARCHAR AS reference_number, sa.user_id,
           (sa.occurred_on::TIMESTAMP AT TIME ZONE 'Asia/Jakarta') AS occurred_at
    FROM inventory.stock_adjustments sa JOIN inventory.products p ON p.id = sa.product_id
    UNION ALL
    SELECT pi.product_id, pi.unit_id, 'PURCHASE', pi.quantity, 'PURCHASE_ITEM', pi.id,
           pu.invoice_no, pu.user_id, pu.received_at
    FROM purchasing.purchase_items pi JOIN purchasing.purchases pu ON pu.id = pi.purchase_id
    UNION ALL
    SELECT ti.product_id, ti.unit_id, 'SALE', -ti.quantity, 'SALES_ITEM', ti.id,
           t.receipt_number, t.user_id, t.created_at
    FROM sales.transaction_items ti JOIN sales.transactions t ON t.id = ti.transaction_id
), annotated AS (
    SELECT r.*,
           s.quantity - SUM(r.delta) OVER (PARTITION BY r.product_id) +
               COALESCE(SUM(r.delta) OVER (
                   PARTITION BY r.product_id ORDER BY r.occurred_at, r.reference_id
                   ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
               ), 0) AS balance_before
    FROM raw r JOIN inventory.stock s ON s.product_id = r.product_id
)
INSERT INTO inventory.stock_movements (
    product_id, unit_id, movement_type, balance_before, qty_in, qty_out,
    balance_after, reference_type, reference_id, reference_number, user_id, occurred_at
)
SELECT product_id, unit_id, movement_type, balance_before,
       GREATEST(delta, 0), GREATEST(-delta, 0), balance_before + delta,
       reference_type, reference_id, reference_number, user_id, occurred_at
FROM annotated
ORDER BY product_id, occurred_at, reference_id;

CREATE OR REPLACE FUNCTION inventory.fn_sync_stock()
RETURNS TRIGGER AS $$
DECLARE
    v_product_id UUID;
    v_qty_diff NUMERIC(10,2) := 0;
    v_current_stock NUMERIC(10,2);
    v_unit_id UUID;
    v_user_id UUID;
    v_occurred_at TIMESTAMPTZ;
    v_reference_number VARCHAR(100);
    v_movement_type system.stock_movement_type;
    v_reference_type VARCHAR(40);
BEGIN
    IF TG_OP = 'INSERT' THEN
        v_product_id := NEW.product_id;
        v_unit_id := NEW.unit_id;
        IF TG_TABLE_SCHEMA = 'sales' THEN
            v_qty_diff := -NEW.quantity;
            v_movement_type := 'SALE';
            v_reference_type := 'SALES_ITEM';
            SELECT user_id, created_at, receipt_number INTO v_user_id, v_occurred_at, v_reference_number
            FROM sales.transactions WHERE id = NEW.transaction_id;
        ELSE
            v_qty_diff := NEW.quantity;
            v_movement_type := 'PURCHASE';
            v_reference_type := 'PURCHASE_ITEM';
            SELECT user_id, received_at, invoice_no INTO v_user_id, v_occurred_at, v_reference_number
            FROM purchasing.purchases WHERE id = NEW.purchase_id;
        END IF;
    ELSIF TG_OP = 'DELETE' THEN
        v_product_id := OLD.product_id;
        v_qty_diff := CASE WHEN TG_TABLE_SCHEMA = 'sales' THEN OLD.quantity ELSE -OLD.quantity END;
        v_unit_id := OLD.unit_id;
        IF TG_TABLE_SCHEMA = 'purchasing' THEN
            v_movement_type := 'PURCHASE';
            v_reference_type := 'PURCHASE_ITEM_DELETE';
            SELECT user_id, received_at, invoice_no INTO v_user_id, v_occurred_at, v_reference_number
            FROM purchasing.purchases WHERE id = OLD.purchase_id;
        END IF;
    ELSE
        v_product_id := NEW.product_id;
        IF OLD.product_id <> NEW.product_id THEN RAISE EXCEPTION 'Cannot change product_id in transaction item'; END IF;
        v_qty_diff := CASE WHEN TG_TABLE_SCHEMA = 'sales' THEN OLD.quantity - NEW.quantity ELSE NEW.quantity - OLD.quantity END;
        v_unit_id := NEW.unit_id;
        IF TG_TABLE_SCHEMA = 'purchasing' THEN
            v_movement_type := 'PURCHASE';
            v_reference_type := 'PURCHASE_ITEM_UPDATE';
            SELECT user_id, received_at, invoice_no INTO v_user_id, v_occurred_at, v_reference_number
            FROM purchasing.purchases WHERE id = NEW.purchase_id;
        END IF;
    END IF;

    SELECT quantity INTO v_current_stock FROM inventory.stock
    WHERE product_id = v_product_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Stock record not found for product_id %', v_product_id; END IF;
    IF v_current_stock + v_qty_diff < 0 THEN
        RAISE EXCEPTION 'Insufficient stock. Current: %, Requested change: %', v_current_stock, v_qty_diff;
    END IF;

    UPDATE inventory.stock SET quantity = quantity + v_qty_diff, updated_at = NOW()
    WHERE product_id = v_product_id;

    IF TG_OP = 'INSERT' OR TG_TABLE_SCHEMA = 'purchasing' THEN
        INSERT INTO inventory.stock_movements (
            product_id, unit_id, movement_type, balance_before, qty_in, qty_out,
            balance_after, reference_type, reference_id, reference_number, user_id, occurred_at
        ) VALUES (
            v_product_id, v_unit_id, v_movement_type, v_current_stock,
            GREATEST(v_qty_diff, 0), GREATEST(-v_qty_diff, 0), v_current_stock + v_qty_diff,
            v_reference_type,
            CASE WHEN TG_OP = 'INSERT' THEN NEW.id ELSE gen_random_uuid() END,
            v_reference_number, v_user_id, COALESCE(v_occurred_at, NOW())
        );
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION inventory.fn_apply_stock_adjustment()
RETURNS TRIGGER AS $$
DECLARE
    v_current_stock NUMERIC(10,2);
    v_unit_id UUID;
    v_movement_type system.stock_movement_type;
BEGIN
    SELECT s.quantity, s.unit_id INTO v_current_stock, v_unit_id
    FROM inventory.stock s WHERE s.product_id = NEW.product_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Stock record not found for product_id %', NEW.product_id; END IF;
    IF v_current_stock <> NEW.qty_before THEN
        RAISE EXCEPTION 'Stale stock adjustment. Current: %, expected: %', v_current_stock, NEW.qty_before;
    END IF;

    UPDATE inventory.stock SET quantity = NEW.qty_after, updated_at = NOW()
    WHERE product_id = NEW.product_id;

    v_movement_type := CASE
        WHEN NEW.source IN ('opening_balance', 'csv_import') THEN 'OPENING_BALANCE'::system.stock_movement_type
        WHEN NEW.source = 'transaction_void' THEN 'VOID'::system.stock_movement_type
        WHEN NEW.type = 'opname' THEN 'OPNAME'::system.stock_movement_type
        WHEN NEW.type = 'koreksi' THEN 'CORRECTION'::system.stock_movement_type
        ELSE 'DAMAGE'::system.stock_movement_type
    END;
    INSERT INTO inventory.stock_movements (
        product_id, unit_id, movement_type, balance_before, qty_in, qty_out,
        balance_after, reference_type, reference_id, reference_number, user_id, occurred_at
    ) VALUES (
        NEW.product_id, v_unit_id, v_movement_type, NEW.qty_before,
        GREATEST(NEW.qty_after - NEW.qty_before, 0), GREATEST(NEW.qty_before - NEW.qty_after, 0),
        NEW.qty_after, COALESCE(NEW.reference_type, 'STOCK_ADJUSTMENT'), NEW.id,
        NEW.reference_id::TEXT, NEW.user_id,
        NEW.occurred_on::TIMESTAMP AT TIME ZONE 'Asia/Jakarta'
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION inventory.fn_prevent_stock_movement_mutation()
RETURNS TRIGGER AS $$ BEGIN
    RAISE EXCEPTION 'Stock movement history is immutable';
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_stock_movement_mutation
BEFORE UPDATE OR DELETE ON inventory.stock_movements
FOR EACH ROW EXECUTE FUNCTION inventory.fn_prevent_stock_movement_mutation();
