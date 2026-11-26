# Deteksi data terkena bug input uang Android v1.0.6

Skrip: `docs/sql/detect_money_input_bug.sql`. **Hanya baca**: berjalan di `BEGIN TRANSACTION READ ONLY`,
diakhiri `ROLLBACK`, tanpa INSERT/UPDATE/DELETE. Bukan migrasi Flyway. Koreksi diputuskan pemilik setelah meninjau.

## Latar

Android v1.0.6 membaca titik ribuan sebagai titik desimal di layar Rekonsiliasi/Sesi Kas dan dialog diskon:

| Diketik | Tersimpan |
|---|---|
| `20.000` atau tombol cepat `+20rb` | `20` |
| `20.500` | `20.5` |
| `1.250.000` (dua titik) | ditolak aplikasi, tidak tersimpan |

Karena input dua titik ditolak, nilai yang terkena selalu **di bawah 1.000**. Server menerima nilai itu apa adanya.
Diperbaiki di APK 1.0.8.

## Kolom yang diperiksa

| `jenis` | Tabel.kolom | Jalur server |
|---|---|---|
| `PENGELUARAN_KAS` | `sales.cash_expenses.amount` | `addExpense` (online), `syncCashExpense` (offline) |
| `MODAL_AWAL` | `sales.cash_sessions.opening_cash` | `openSession`, `syncOpenCashSession` |
| `KAS_FISIK_TUTUP` | `sales.cash_sessions.closing_cash` | `closeSession`, `syncCloseCashSession` |
| `DISKON_ITEM` | `sales.transaction_items.discount_value` (`discount_type = 'FIXED_AMOUNT'`) | checkout online & offline |
| `DISKON_TRANSAKSI` | `sales.transactions.transaction_discount_value` (`'FIXED_AMOUNT'`) | checkout online & offline |
| `LAIN_BAYAR_PIUTANG` | `receivable.receivable_payments.amount` (`entry_type = 'PAYMENT'`) | pembayaran piutang |
| `LAIN_BAYAR_HUTANG` | `purchasing.supplier_payments.amount` | pembayaran hutang supplier |

Dampak ke kas sesi: pengeluaran dan modal awal mengubah `system_cash` (kas seharusnya), jadi bug ini juga membuat
`difference` sesi salah. Pengeluaran 1000x terlalu kecil membuat selisih negatif sekitar `-999 x nilai`;
modal awal 1000x terlalu kecil membuat selisih positif sekitar `+999 x nilai`.

**Jalur lain yang relevan (tidak disebut di laporan bug):** pembayaran piutang dan hutang supplier menerima nominal
yang diketik di Android dan mengizinkan pembayaran sebagian, jadi nilai 20 akan diterima. Skrip menampilkannya sebagai
`LAIN_*` agar pemilik memastikan apakah layar itu memakai komponen input yang sama. Pembayaran piutang tunai juga
mengubah `system_cash` sesi penerima. Jalur yang **tidak** terdampak penyimpanan: uang diterima/split payment di
checkout (nominal kecil gagal validasi total, jadi ditolak) dan refund (nominal dihitung server). Diskon penawaran
(`sales.quotations`, `sales.quotation_items`) tidak dipindai; penawaran yang dikonversi membawa diskonnya ke transaksi
dan ikut terdeteksi sebagai `DISKON_*`.

## Cara pakai

```bash
# Tabel di terminal
psql "$DB_URL" -q -v since='2026-10-01' -f docs/sql/detect_money_input_bug.sql

# CSV untuk ditinjau di spreadsheet (-q wajib agar baris BEGIN/ROLLBACK tidak ikut)
psql "$DB_URL" -q --csv -v since='2026-10-01' -f docs/sql/detect_money_input_bug.sql > laporan_bug_uang.csv
```

- Wajib `psql` (memakai `\if` dan variabel psql).
- `since` / `until` (opsional, timestamptz): isi `since` dengan tanggal v1.0.6 mulai terpasang. Tanpa `since` seluruh
  riwayat diperiksa dan data lama yang memang kecil ikut muncul sebagai SEDANG.
- Toleransi ada di CTE `params` (`tol_min` Rp5.000, `tol_pct` 1%); ubah salinan lokal bila perlu.

## Tingkat keyakinan

- **KUAT**
  - nilai punya pecahan (`20.5`, `20.05`): rupiah yang diketik normalnya bulat; atau
  - sesi kas sudah ditutup dan selisihnya hilang (`|selisih| <= toleransi`) bila nilai baris ini dikalikan 1000.
    Untuk `KAS_FISIK_TUTUP` artinya kas fisik x1000 ~ kas seharusnya; atau
  - selisih hilang bila **semua** kandidat di sesi yang sama dikalikan 1000 (mis. modal awal dan pengeluaran
    sama-sama terkena).
- **SEDANG**: nominal bulat 1..999 tanpa penguat dari selisih sesi. Untuk diskon hanya bila diskon x1000 masih
  `<=` dasar diskonnya (bruto baris, atau subtotal setelah diskon item); di atas itu tidak mungkin bug ini, jadi tidak
  ditampilkan.
- `toleransi = MAX(Rp5.000, 1% x kas seharusnya setelah koreksi)`.

Contoh normal yang **tidak** masuk KUAT: parkir Rp500 dengan selisih sesi ~0 tetap SEDANG, termasuk bila sesi yang sama
juga punya pengeluaran yang terkena bug. Nominal benar (Rp20.000, diskon Rp15.000) tidak muncul sama sekali.

## Kolom hasil

| Kolom | Isi |
|---|---|
| `tingkat`, `jenis`, `alasan` | klasifikasi dan alasannya |
| `waktu` | `occurred_at` (offline) atau `created_at`; untuk sesi: `opened_at` / `closed_at` |
| `kasir`, `username` | pembuat baris |
| `sesi_id`, `sesi_dibuka`, `sesi_ditutup` | sesi kas terkait (kosong untuk `LAIN_*`) |
| `sumber`, `perangkat` | `OFFLINE` bila punya `client_generated_id`, beserta `device_id`; selain itu `ONLINE` |
| `status` | status transaksi (`voided`, dll.) atau `dibatalkan` untuk pembayaran piutang yang sudah di-reverse |
| `record_id`, `keterangan` | id baris dan konteks (nomor struk, produk, kategori, pelanggan/supplier) |
| `nilai_tercatat`, `nilai_x1000` | nilai di DB dan dugaan nilai yang dimaksud |
| `selisih_tercatat` | `closing_cash - system_cash` saat ini |
| `selisih_jika_ini_x1000` | selisih bila hanya baris ini dikoreksi |
| `selisih_jika_semua_x1000` | selisih bila semua kandidat sesi (modal, pengeluaran, kas fisik) dikoreksi |

## Meninjau dan mengoreksi

1. Mulai dari KUAT, cocokkan dengan nota/struk dan kasir yang bertugas.
2. SEDANG wajib dicek manual: nominal kecil bisa sah (parkir, uang receh).
3. Satu koreksi bisa berantai: mengubah pengeluaran atau modal awal mengubah `system_cash` dan `difference` sesi;
   mengubah diskon mengubah total, pajak, pembayaran, dan piutang transaksi. Jangan UPDATE tabel langsung; koreksi
   dibuat sebagai pekerjaan terpisah yang disetujui pemilik (aturan trigger dan imutabilitas di `CLAUDE.md` berlaku).

## Keterbatasan

- Server tidak mencatat versi aplikasi. Baris online tidak punya `device_id`; batasi dengan `since`.
- Input tiga desimal (`20.505`) ditolak (tutup sesi online) atau dibulatkan ke 2 desimal saat disimpan, jadi
  `nilai_x1000` bisa meleset sampai Rp5.
- Penguat dari selisih hanya untuk sesi yang sudah ditutup dan punya `system_cash`. Selisih kas asli yang besar bisa
  menutupi bug (tetap SEDANG); selisih asli yang kebetulan ~999 x nilai bisa menaikkan nominal kecil yang sah ke KUAT.
- Koreksi gabungan sesi tidak memasukkan pembayaran piutang tunai yang mungkin juga terkena.
- Diskon tidak punya penguat (kasir menagih lebih besar dari seharusnya, bukan kas selisih), jadi diskon bulat hanya
  bisa SEDANG.
- Kas fisik di atas Rp999.999 yang diketik dengan titik ditolak; yang tersimpan berarti diketik tanpa titik (benar)
  atau dengan satu titik (di bawah Rp1 juta, terdeteksi).
