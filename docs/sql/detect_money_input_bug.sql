-- =====================================================================
-- DETEKSI (hanya baca): nominal uang yang terbaca 1000x lebih kecil oleh Android v1.0.6.
-- BUKAN migrasi Flyway dan tidak mengubah data apa pun (transaksi READ ONLY, diakhiri ROLLBACK).
--
-- Bug: kolom uang di Rekonsiliasi/Sesi Kas dan dialog diskon membaca titik ribuan sebagai titik desimal.
--   "20.000" -> 20, "20.500" -> 20.5, tombol cepat "+20rb" -> 20. Input dua titik ("1.250.000")
--   ditolak aplikasi, jadi nilai yang terkena selalu < 1000. Diperbaiki di APK 1.0.8.
--
--   psql "$DB_URL" -q -v since='2026-10-01' -f docs/sql/detect_money_input_bug.sql
--   (CSV: psql "$DB_URL" -q --csv -v since=... -f ... > laporan.csv; -q agar BEGIN/ROLLBACK tidak ikut)
--   Variabel opsional: since / until (timestamptz). Isi `since` dengan tanggal v1.0.6 mulai dipasang;
--   tanpa itu seluruh riwayat diperiksa dan data lama bisa ikut muncul.
--
-- jenis (layar yang disebut di laporan bug):
--   PENGELUARAN_KAS   sales.cash_expenses.amount             (online & sync offline)
--   MODAL_AWAL        sales.cash_sessions.opening_cash       (online & sync offline)
--   KAS_FISIK_TUTUP   sales.cash_sessions.closing_cash       (online & sync offline)
--   DISKON_ITEM       sales.transaction_items.discount_value (discount_type = 'FIXED_AMOUNT')
--   DISKON_TRANSAKSI  sales.transactions.transaction_discount_value ('FIXED_AMOUNT')
-- jenis LAIN_* (TIDAK disebut di laporan bug, nominal diketik di Android dan pembayaran sebagian diterima;
-- tampil agar pemilik bisa memastikan apakah layar itu memakai input yang sama):
--   LAIN_BAYAR_PIUTANG receivable.receivable_payments.amount (entry_type = 'PAYMENT')
--   LAIN_BAYAR_HUTANG  purchasing.supplier_payments.amount
--
-- tingkat:
--   KUAT    nilai punya pecahan (20.5, 20.05): rupiah yang diketik normalnya bulat, ATAU
--           sesi kas sudah ditutup dan selisih kasnya hilang (|selisih| <= toleransi) bila nilai ini x1000
--           (untuk KAS_FISIK_TUTUP: kas fisik x1000 ~ kas seharusnya), ATAU
--           selisih hilang bila SEMUA kandidat di sesi yang sama x1000.
--   SEDANG  nominal bulat 1..999 di kolom yang biasanya ribuan dan tidak ada penguat dari selisih sesi.
--           Untuk diskon, hanya bila diskon x1000 masih <= dasar diskonnya (selain itu tidak mungkin bug ini).
--   toleransi = MAX(Rp5.000, 1% dari kas seharusnya setelah koreksi).
--
-- Kolom selisih (sesi kas): selisih_tercatat = closing_cash - system_cash saat ini;
--   selisih_jika_ini_x1000 = selisih bila hanya baris ini dikoreksi;
--   selisih_jika_semua_x1000 = selisih bila semua kandidat sesi (modal awal, pengeluaran, kas fisik) dikoreksi.
--   Pengeluaran yang terlalu kecil membuat kas seharusnya terlalu besar -> selisih negatif ~ -999 x nilai.
--   Modal awal yang terlalu kecil membuat kas seharusnya terlalu kecil -> selisih positif ~ +999 x nilai.
--
-- Keterbatasan: lihat docs/MONEY_INPUT_BUG_DETECTION.md. Ringkas: nilai 3 desimal ("20.505") dibulatkan
-- ke 2 desimal saat disimpan (atau ditolak), jadi nilai_x1000 bisa meleset s.d. Rp5; selisih sesi yang
-- kebetulan ~999 x nilai bisa menaikkan pengeluaran kecil yang sah ke KUAT; pembayaran piutang tunai
-- yang ikut terkena juga menggeser selisih sesi dan tidak ikut dihitung di koreksi sesi.
-- Wajib dijalankan dengan psql (memakai \if dan variabel psql).
-- =====================================================================
\if :{?since}
\else
\set since '-infinity'
\endif
\if :{?until}
\else
\set until 'infinity'
\endif

BEGIN TRANSACTION READ ONLY;

WITH params AS (
    SELECT :'since'::timestamptz AS since, :'until'::timestamptz AS until,
           5000::numeric AS tol_min, 0.01::numeric AS tol_pct
), sessions AS (
    SELECT cs.id, cs.user_id, cs.opened_at, cs.closed_at, cs.opening_cash, cs.closing_cash, cs.system_cash,
           cs.client_generated_id IS NOT NULL AS opened_offline, cs.device_id,
           cs.close_client_generated_id IS NOT NULL AS closed_offline, cs.close_device_id,
           cs.closing_cash - cs.system_cash AS diff_now
    FROM sales.cash_sessions cs
), small_expenses AS (
    -- Semua pengeluaran 1..999 (tanpa filter waktu) supaya koreksi sesi tetap utuh di batas since/until.
    SELECT e.id, e.session_id, e.user_id, e.amount, e.description, e.category,
           COALESCE(e.occurred_at, e.created_at) AS occurred_at,
           e.client_generated_id IS NOT NULL AS offline, e.device_id
    FROM sales.cash_expenses e
    WHERE e.amount > 0 AND e.amount < 1000
), session_fix AS (
    -- Koreksi gabungan per sesi bila semua kandidat sesi dianggap x1000.
    SELECT s.*,
           CASE WHEN s.opening_cash > 0 AND s.opening_cash < 1000 THEN s.opening_cash * 999 ELSE 0 END AS opening_fix,
           COALESCE((SELECT SUM(se.amount) * 999 FROM small_expenses se WHERE se.session_id = s.id), 0) AS expense_fix,
           CASE WHEN s.closing_cash > 0 AND s.closing_cash < 1000 THEN s.closing_cash * 1000
                ELSE s.closing_cash END AS closing_fixed
    FROM sessions s
), session_eval AS (
    SELECT f.*,
           f.system_cash + f.opening_fix - f.expense_fix AS system_fixed,
           f.closing_fixed - (f.system_cash + f.opening_fix - f.expense_fix) AS diff_all,
           GREATEST(p.tol_min, p.tol_pct * ABS(f.system_cash + f.opening_fix - f.expense_fix)) AS tol
    FROM session_fix f, params p
), cash_candidates AS (
    SELECT 'PENGELUARAN_KAS' AS jenis, se.id AS record_id, se.session_id, se.user_id, se.occurred_at AS waktu,
           CASE WHEN se.offline THEN 'OFFLINE' ELSE 'ONLINE' END AS sumber, se.device_id,
           COALESCE(se.category || ': ', '') || se.description AS keterangan, se.amount AS nilai,
           s.diff_now + se.amount * 999 AS diff_single, NULL::text AS status
    FROM small_expenses se JOIN session_eval s ON s.id = se.session_id, params p
    WHERE se.occurred_at >= p.since AND se.occurred_at < p.until
    UNION ALL
    SELECT 'MODAL_AWAL', s.id, s.id, s.user_id, s.opened_at,
           CASE WHEN s.opened_offline THEN 'OFFLINE' ELSE 'ONLINE' END, s.device_id,
           'modal awal sesi', s.opening_cash,
           s.diff_now - s.opening_cash * 999, NULL
    FROM session_eval s, params p
    WHERE s.opening_cash > 0 AND s.opening_cash < 1000
      AND s.opened_at >= p.since AND s.opened_at < p.until
    UNION ALL
    SELECT 'KAS_FISIK_TUTUP', s.id, s.id, s.user_id, s.closed_at,
           CASE WHEN s.closed_offline THEN 'OFFLINE' ELSE 'ONLINE' END, s.close_device_id,
           'kas fisik saat tutup; kas seharusnya ' || s.system_cash, s.closing_cash,
           s.closing_cash * 1000 - s.system_cash, NULL
    FROM session_eval s, params p
    WHERE s.closing_cash > 0 AND s.closing_cash < 1000
      AND s.closed_at >= p.since AND s.closed_at < p.until
), cash_classified AS (
    SELECT c.*, s.opened_at AS sesi_dibuka, s.closed_at AS sesi_ditutup, s.diff_now, s.diff_all, s.tol,
           CASE
               WHEN c.nilai <> TRUNC(c.nilai) THEN 'KUAT'
               WHEN s.closed_at IS NOT NULL AND ABS(s.diff_now) > s.tol
                    AND (ABS(c.diff_single) <= s.tol OR ABS(s.diff_all) <= s.tol) THEN 'KUAT'
               ELSE 'SEDANG'
           END AS tingkat,
           CASE
               WHEN c.nilai <> TRUNC(c.nilai) THEN 'nilai pecahan'
               WHEN s.closed_at IS NOT NULL AND ABS(s.diff_now) > s.tol AND ABS(c.diff_single) <= s.tol
                   THEN CASE WHEN c.jenis = 'KAS_FISIK_TUTUP' THEN 'kas fisik x1000 ~ kas seharusnya'
                             ELSE 'selisih sesi hilang bila nilai ini x1000' END
               WHEN s.closed_at IS NOT NULL AND ABS(s.diff_now) > s.tol AND ABS(s.diff_all) <= s.tol
                   THEN 'selisih sesi hilang bila semua kandidat sesi x1000'
               WHEN s.closed_at IS NULL THEN 'nominal bulat < 1000; sesi belum ditutup'
               ELSE 'nominal bulat < 1000'
           END AS alasan
    FROM cash_candidates c JOIN session_eval s ON s.id = c.session_id
), discount_candidates AS (
    SELECT 'DISKON_ITEM' AS jenis, ti.id AS record_id, t.session_id, t.user_id,
           COALESCE(t.occurred_at, t.created_at) AS waktu,
           CASE WHEN t.client_generated_id IS NOT NULL THEN 'OFFLINE' ELSE 'ONLINE' END AS sumber, t.device_id,
           COALESCE(t.receipt_number, t.id::text) || ' / ' || COALESCE(ti.product_name, ti.product_id::text)
               || '; bruto baris ' || ti.gross_line_total AS keterangan,
           ti.discount_value AS nilai, ti.gross_line_total AS dasar, t.status::text AS status
    FROM sales.transaction_items ti
    JOIN sales.transactions t ON t.id = ti.transaction_id, params p
    WHERE ti.discount_type = 'FIXED_AMOUNT' AND ti.discount_value > 0 AND ti.discount_value < 1000
      AND COALESCE(t.occurred_at, t.created_at) >= p.since AND COALESCE(t.occurred_at, t.created_at) < p.until
    UNION ALL
    SELECT 'DISKON_TRANSAKSI', t.id, t.session_id, t.user_id, COALESCE(t.occurred_at, t.created_at),
           CASE WHEN t.client_generated_id IS NOT NULL THEN 'OFFLINE' ELSE 'ONLINE' END, t.device_id,
           COALESCE(t.receipt_number, t.id::text) || '; dasar diskon ' || (t.gross_subtotal - t.item_discount_total),
           t.transaction_discount_value, t.gross_subtotal - t.item_discount_total, t.status::text
    FROM sales.transactions t, params p
    WHERE t.transaction_discount_type = 'FIXED_AMOUNT'
      AND t.transaction_discount_value > 0 AND t.transaction_discount_value < 1000
      AND COALESCE(t.occurred_at, t.created_at) >= p.since AND COALESCE(t.occurred_at, t.created_at) < p.until
), other_candidates AS (
    SELECT 'LAIN_BAYAR_PIUTANG' AS jenis, rp.id AS record_id, rp.user_id, rp.paid_at AS waktu,
           COALESCE(rp.payment_number, rp.id::text) || ' / ' || c.name || ' / ' || rp.method::text AS keterangan,
           rp.amount AS nilai,
           CASE WHEN EXISTS (SELECT 1 FROM receivable.receivable_payments r2 WHERE r2.reversed_payment_id = rp.id)
                THEN 'dibatalkan' END AS status
    FROM receivable.receivable_payments rp
    JOIN receivable.receivables r ON r.id = rp.receivable_id
    JOIN receivable.customers c ON c.id = r.customer_id, params p
    WHERE rp.entry_type = 'PAYMENT' AND rp.amount > 0 AND rp.amount < 1000
      AND rp.paid_at >= p.since AND rp.paid_at < p.until
    UNION ALL
    SELECT 'LAIN_BAYAR_HUTANG', sp.id, sp.user_id, sp.paid_at,
           COALESCE(sp.reference, sp.id::text) || ' / ' || s.name || ' / ' || sp.method::text,
           sp.amount, NULL
    FROM purchasing.supplier_payments sp
    JOIN purchasing.supplier_payables pb ON pb.id = sp.supplier_payable_id
    JOIN purchasing.suppliers s ON s.id = pb.supplier_id, params p
    WHERE sp.amount > 0 AND sp.amount < 1000
      AND sp.paid_at >= p.since AND sp.paid_at < p.until
), report AS (
    SELECT tingkat, jenis, waktu, user_id, session_id, sesi_dibuka, sesi_ditutup, sumber, device_id, status,
           record_id, keterangan, nilai, alasan,
           diff_now AS selisih_tercatat,
           CASE WHEN sesi_ditutup IS NOT NULL THEN diff_single END AS selisih_jika_ini_x1000,
           CASE WHEN sesi_ditutup IS NOT NULL THEN diff_all END AS selisih_jika_semua_x1000
    FROM cash_classified
    UNION ALL
    SELECT CASE WHEN d.nilai <> TRUNC(d.nilai) THEN 'KUAT' ELSE 'SEDANG' END,
           d.jenis, d.waktu, d.user_id, d.session_id, cs.opened_at, cs.closed_at, d.sumber, d.device_id, d.status,
           d.record_id, d.keterangan, d.nilai,
           CASE WHEN d.nilai <> TRUNC(d.nilai) THEN 'nilai pecahan'
                ELSE 'nominal bulat < 1000; x1000 masih <= dasar diskon' END,
           NULL, NULL, NULL
    FROM discount_candidates d
    LEFT JOIN sales.cash_sessions cs ON cs.id = d.session_id
    WHERE d.nilai <> TRUNC(d.nilai) OR d.nilai * 1000 <= d.dasar
    UNION ALL
    SELECT CASE WHEN o.nilai <> TRUNC(o.nilai) THEN 'KUAT' ELSE 'SEDANG' END,
           o.jenis, o.waktu, o.user_id, NULL, NULL, NULL, 'ONLINE', NULL, o.status,
           o.record_id, o.keterangan, o.nilai,
           CASE WHEN o.nilai <> TRUNC(o.nilai) THEN 'nilai pecahan; layar tidak disebut di laporan bug'
                ELSE 'nominal bulat < 1000; layar tidak disebut di laporan bug' END,
           NULL, NULL, NULL
    FROM other_candidates o
)
SELECT r.tingkat, r.jenis, r.waktu, u.name AS kasir, u.username, r.session_id AS sesi_id,
       r.sesi_dibuka, r.sesi_ditutup, r.sumber, r.device_id AS perangkat, r.status,
       r.record_id, r.keterangan, r.nilai AS nilai_tercatat, r.nilai * 1000 AS nilai_x1000,
       r.selisih_tercatat, r.selisih_jika_ini_x1000, r.selisih_jika_semua_x1000, r.alasan
FROM report r
LEFT JOIN system.users u ON u.id = r.user_id
ORDER BY CASE r.tingkat WHEN 'KUAT' THEN 0 ELSE 1 END, r.jenis LIKE 'LAIN_%', r.waktu, r.record_id;

ROLLBACK;
