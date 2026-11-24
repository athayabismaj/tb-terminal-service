-- =============================================
-- V47: Riwayat faktor konversi satuan produk
-- =============================================
-- Sync offline perlu mengenali faktor lama yang masih dipakai perangkat selama sesi kas berjalan,
-- sama seperti price_history untuk harga. HANYA diisi trigger.

CREATE TABLE inventory.unit_conversion_history (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id   UUID          NOT NULL REFERENCES inventory.products(id) ON DELETE CASCADE,
    -- Tanpa FK ke units: riwayat tidak boleh menghalangi penghapusan satuan, dan tidak boleh ikut hilang.
    from_unit_id UUID          NOT NULL,
    to_unit_id   UUID          NOT NULL,
    old_factor   NUMERIC(10,4) NOT NULL,
    changed_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_unit_conv_history_product_changed
    ON inventory.unit_conversion_history (product_id, changed_at);

CREATE FUNCTION inventory.fn_log_unit_conversion_history()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        AND (NEW.product_id, NEW.from_unit_id, NEW.to_unit_id, NEW.factor)
            IS NOT DISTINCT FROM (OLD.product_id, OLD.from_unit_id, OLD.to_unit_id, OLD.factor) THEN
        RETURN NULL;
    END IF;

    -- SELECT kosong bila produknya sendiri sedang dihapus (ON DELETE CASCADE).
    INSERT INTO inventory.unit_conversion_history (product_id, from_unit_id, to_unit_id, old_factor)
    SELECT p.id, OLD.from_unit_id, OLD.to_unit_id, OLD.factor
    FROM inventory.products p
    WHERE p.id = OLD.product_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_unit_conversions_history
AFTER UPDATE OR DELETE ON inventory.unit_conversions
FOR EACH ROW EXECUTE FUNCTION inventory.fn_log_unit_conversion_history();
