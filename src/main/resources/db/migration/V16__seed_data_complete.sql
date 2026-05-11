-- =============================================
-- V16: Production Seed Data
--   - Default units (satuan)
--   - Default categories (kategori produk)
--   - Default store_settings (singleton)
-- =============================================

-- ─── 1. Satuan (Units) ──────────────────────────────────
INSERT INTO inventory.units (name, symbol) VALUES
    ('Pieces', 'pcs'),
    ('Dus', 'dus'),
    ('Sak', 'sak'),
    ('Meter', 'm'),
    ('Kilogram', 'kg'),
    ('Liter', 'L'),
    ('Batang', 'btg'),
    ('Lembar', 'lbr'),
    ('Roll', 'roll'),
    ('Galon', 'galon'),
    ('Meter Kubik', 'm3'),
    ('Rit', 'rit'),
    ('Meter Lari', 'm1')
ON CONFLICT (name) DO NOTHING;

-- ─── 2. Kategori (Categories) ───────────────────────────
INSERT INTO inventory.categories (name) VALUES
    ('Semen'),
    ('Besi & Baja'),
    ('Cat & Pelitur'),
    ('Keramik & Granit'),
    ('Pipa & Sanitasi'),
    ('Listrik'),
    ('Kayu'),
    ('Atap & Genteng'),
    ('Pasir & Batu'),
    ('Perkakas'),
    ('Alat Tukang'),
    ('Bahan Fondasi')
ON CONFLICT (name) DO NOTHING;

-- ─── 3. Store Settings (Singleton) ──────────────────────
-- Karena tabel store_settings dilindungi trigger fn_enforce_singleton(),
-- kita insert 1 baris initial data. Pastikan belum ada data sebelumnya
-- agar tidak memicu error dari trigger.
INSERT INTO system.store_settings (store_name, address, phone, printer_size)
SELECT 'TB Terminal (Placeholder)', 'Jl. Placeholder No.1', '081234567890', '58mm'
WHERE NOT EXISTS (SELECT 1 FROM system.store_settings);
