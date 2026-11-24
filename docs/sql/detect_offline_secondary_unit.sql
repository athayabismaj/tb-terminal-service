-- =====================================================================
-- DETEKSI (hanya baca): transaksi offline lama yang stoknya berkurang dalam
-- satuan sekunder. BUKAN migrasi Flyway dan tidak mengubah data apa pun.
--
--   psql "$DB_URL" -f docs/sql/detect_offline_secondary_unit.sql
--   (CSV: psql "$DB_URL" --csv -f ... > laporan.csv)
--
-- tingkat:
--   PASTI    harga cocok tepat satu faktor, bukan harga satuan dasar, dan HPP juga harga beli x faktor
--   MUNGKIN  seperti PASTI, tetapi HPP tidak bisa menguatkan (mis. HPP 0 atau harga beli berubah)
--   TERDUGA  tidak ada konversi yang cocok sekarang, tetapi harga DAN HPP sama-sama kelipatan yang sama
--            (konversi produk mungkin sudah diubah atau dihapus)
--   AMBIGU   harga cocok lebih dari satu satuan; periksa struk
--
-- kelebihan_stok = stok sistem yang seharusnya sudah keluar (seharusnya - tercatat).
--   0 bila transaksi di-void atau direfund dengan barang kembali: yang dikembalikan sama dengan yang dipotong.
-- opname_setelahnya = true: opname sesudah sync kemungkinan sudah menyerap selisih; JANGAN dikoreksi dua kali.
--   Bila transaksinya juga di-void/direfund SETELAH opname itu, periksa manual: angka 0 bisa keliru.
--
-- Faktor lama ikut dipakai bila tabel inventory.unit_conversion_history (V47) ada: hanya faktor yang
-- berubah sejak sesi kas transaksi itu dibuka, jadi masih mungkin dipakai perangkat. Skrip tetap jalan
-- di database sebelum V47.
--
-- Keterbatasan: perubahan faktor sebelum V47 dan perubahan harga grosir tidak disimpan server, jadi baris
-- yang konversi/harganya sudah berubah bisa tidak terdeteksi. Item yang disinkronkan setelah perbaikan
-- punya audit OFFLINE_ITEM_UNIT dan otomatis dilewati.
-- Wajib dijalankan dengan psql (memakai \gset).
-- =====================================================================
BEGIN TRANSACTION READ ONLY;

SELECT CASE
           WHEN to_regclass('inventory.unit_conversion_history') IS NOT NULL
               THEN 'inventory.unit_conversion_history'
           ELSE '(SELECT NULL::uuid AS product_id, NULL::uuid AS from_unit_id, NULL::uuid AS to_unit_id, '
               || 'NULL::numeric AS old_factor, NULL::timestamptz AS changed_at WHERE FALSE)'
       END AS factor_history
\gset

WITH offline_items AS (
    SELECT ti.id, ti.transaction_id, ti.product_id, ti.quantity, ti.price_at_transaction AS price,
           ti.cogs_at_transaction AS cogs, t.receipt_number, t.status::text AS status,
           COALESCE(t.occurred_at, t.created_at) AS occurred_at,
           COALESCE(t.synced_at, t.created_at) AS synced_at,
           cs.opened_at AS session_opened_at,
           p.sku, p.name AS product_name, p.base_unit_id, p.price_buy, p.price_retail, p.price_contractor
    FROM sales.transaction_items ti
    JOIN sales.transactions t ON t.id = ti.transaction_id
    JOIN sales.cash_sessions cs ON cs.id = t.session_id
    JOIN inventory.products p ON p.id = ti.product_id
    WHERE t.client_generated_id IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM system.audit_logs a
          WHERE a.record_id = ti.id AND a.schema_name = 'sales' AND a.table_name = 'transaction_items'
            AND a.new_data ->> 'event' = 'OFFLINE_ITEM_UNIT'
      )
), affected_products AS (
    SELECT DISTINCT product_id, price_buy, price_retail, price_contractor FROM offline_items
), known_prices AS (
    SELECT product_id, price_retail AS price FROM affected_products
    UNION SELECT product_id, price_contractor FROM affected_products WHERE price_contractor > 0
    UNION SELECT t.product_id, t.unit_price FROM inventory.product_price_tiers t
          JOIN affected_products USING (product_id)
    UNION SELECT h.product_id, v.price
          FROM inventory.price_history h
          JOIN affected_products USING (product_id)
          CROSS JOIN LATERAL (VALUES (h.old_price_retail), (h.new_price_retail),
                                     (NULLIF(h.old_price_contractor, 0)), (NULLIF(h.new_price_contractor, 0))) v(price)
          WHERE v.price IS NOT NULL
), known_costs AS (
    SELECT product_id, price_buy AS cost FROM affected_products
    UNION SELECT h.product_id, v.cost
          FROM inventory.price_history h
          JOIN affected_products USING (product_id)
          CROSS JOIN LATERAL (VALUES (h.old_price_buy), (h.new_price_buy)) v(cost)
), factor_matches AS (
    SELECT i.id AS item_id, f.factor,
           MAX(f.unit_name) AS unit_name,
           BOOL_OR(i.cogs > 0 AND ROUND(c.cost * f.factor, 2) = i.cogs) AS cogs_supports
    FROM offline_items i
    JOIN LATERAL (
        SELECT 1::numeric AS factor, NULL::text AS unit_name
        UNION ALL
        SELECT uc.factor, u.name
        FROM inventory.unit_conversions uc JOIN inventory.units u ON u.id = uc.from_unit_id
        WHERE uc.product_id = i.product_id AND uc.to_unit_id = i.base_unit_id AND uc.factor <> 1
        UNION ALL
        SELECT h.old_factor, u.name
        FROM :factor_history h LEFT JOIN inventory.units u ON u.id = h.from_unit_id
        WHERE h.product_id = i.product_id AND h.to_unit_id = i.base_unit_id AND h.old_factor <> 1
          AND h.changed_at >= i.session_opened_at
    ) f ON TRUE
    JOIN known_prices k ON k.product_id = i.product_id AND ROUND(k.price * f.factor, 2) = i.price
    LEFT JOIN known_costs c ON c.product_id = i.product_id
    GROUP BY i.id, f.factor
), classified AS (
    SELECT i.id AS item_id,
           CASE WHEN COUNT(*) > 1 THEN 'AMBIGU'
                WHEN BOOL_OR(m.cogs_supports) THEN 'PASTI'
                ELSE 'MUNGKIN' END AS tingkat,
           CASE WHEN COUNT(*) = 1 THEN MAX(m.factor) END AS factor,
           MAX(m.unit_name) AS unit_name
    FROM offline_items i JOIN factor_matches m ON m.item_id = i.id
    GROUP BY i.id
    HAVING BOOL_OR(m.factor <> 1)
    UNION ALL
    -- Harga dan HPP naik dengan kelipatan yang sama, tetapi konversinya tidak dikenal.
    SELECT i.id, 'TERDUGA',
           CASE WHEN COUNT(DISTINCT i.price / k.price) = 1 THEN ROUND(MAX(i.price / k.price), 4) END, NULL
    FROM offline_items i
    JOIN known_prices k ON k.product_id = i.product_id AND k.price > 0 AND i.price > k.price
    JOIN known_costs c ON c.product_id = i.product_id AND c.cost > 0 AND i.price * c.cost = i.cogs * k.price
    WHERE NOT EXISTS (SELECT 1 FROM factor_matches m WHERE m.item_id = i.id)
    GROUP BY i.id
), report AS (
    SELECT i.*, c.tingkat, c.factor, c.unit_name,
           CASE
               WHEN c.factor IS NULL THEN NULL
               WHEN i.status = 'voided' THEN 0
               WHEN EXISTS (
                   SELECT 1 FROM sales.transaction_refunds r
                   WHERE r.transaction_id = i.transaction_id AND r.return_disposition = 'RETURN_TO_STOCK'
               ) THEN 0
               ELSE ROUND(i.quantity * (c.factor - 1), 2)
           END AS kelebihan_stok,
           EXISTS (
               SELECT 1 FROM inventory.stock_adjustments sa
               WHERE sa.product_id = i.product_id AND sa.type::text = 'opname' AND sa.created_at > i.synced_at
           ) AS opname_setelahnya
    FROM offline_items i JOIN classified c ON c.item_id = i.id
)
SELECT tingkat, receipt_number AS nomor_transaksi, transaction_id, occurred_at AS waktu, status,
       sku, product_name AS produk, unit_name AS satuan_sekunder, factor AS faktor,
       quantity AS qty_tercatat, ROUND(quantity * factor, 2) AS qty_dasar_seharusnya,
       price AS harga_tercatat, kelebihan_stok, opname_setelahnya,
       SUM(kelebihan_stok) FILTER (WHERE tingkat = 'PASTI' AND NOT opname_setelahnya)
           OVER (PARTITION BY product_id) AS total_produk_pasti_tanpa_opname
FROM report
ORDER BY sku, occurred_at, transaction_id, id;

ROLLBACK;
