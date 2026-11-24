-- =============================================
-- V46: Waktu perubahan khusus untuk pengaturan pajak dan pembulatan
-- =============================================
-- Validasi sync offline perlu tahu kapan aturan pajak/pembulatan terakhir berubah.
-- updated_at tidak cukup: ia ikut berubah saat nama toko, telepon, atau struk diedit.

ALTER TABLE system.store_settings
    ADD COLUMN tax_settings_updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN rounding_settings_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Nilai awal = perubahan terakhir yang diketahui. Trigger timestamp dimatikan sebentar
-- supaya updated_at (tampil di API) tidak ikut bergeser.
ALTER TABLE system.store_settings DISABLE TRIGGER trg_store_settings_timestamp;
UPDATE system.store_settings
SET tax_settings_updated_at = updated_at,
    rounding_settings_updated_at = updated_at;
ALTER TABLE system.store_settings ENABLE TRIGGER trg_store_settings_timestamp;

-- Kolom ini hanya bergeser bila field yang memengaruhi perhitungan benar-benar berubah,
-- dan tidak bisa ditulis langsung. tax_label dan store_npwp hanya teks struk.
CREATE FUNCTION system.fn_track_store_settings_changes()
RETURNS TRIGGER AS $$
BEGIN
    IF (NEW.tax_enabled, NEW.tax_rate, NEW.tax_mode)
        IS DISTINCT FROM (OLD.tax_enabled, OLD.tax_rate, OLD.tax_mode) THEN
        NEW.tax_settings_updated_at = NOW();
    ELSE
        NEW.tax_settings_updated_at = OLD.tax_settings_updated_at;
    END IF;

    IF (NEW.cash_rounding_unit, NEW.cash_rounding_rule)
        IS DISTINCT FROM (OLD.cash_rounding_unit, OLD.cash_rounding_rule) THEN
        NEW.rounding_settings_updated_at = NOW();
    ELSE
        NEW.rounding_settings_updated_at = OLD.rounding_settings_updated_at;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_store_settings_track_changes
BEFORE UPDATE ON system.store_settings
FOR EACH ROW EXECUTE FUNCTION system.fn_track_store_settings_changes();
