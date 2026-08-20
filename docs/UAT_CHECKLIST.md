# Checklist UAT TB Terminal — Batch 8C

Lingkungan local/staging test: __________  Backend: __________  APK debug: __________  Tanggal: __________

## Owner

| ID | Skenario dan hasil yang diharapkan | Pass/Fail | Bukti/Catatan |
|---|---|---|---|
| OWN-01 | Login, refresh, profil `/api/auth/me`, ganti password/PIN, logout, dan session expired berjalan; credential tidak tampil di log | | |
| OWN-02 | Menu owner lengkap; user management dan audit terlihat; endpoint owner berhasil | | |
| OWN-03 | Produk valid tersimpan; field kosong/SKU invalid atau duplikat ditolak | | |
| OWN-04 | Preview CSV menunjukkan baris valid/gagal; commit invalid tidak menyimpan data parsial | | |
| OWN-05 | Saldo awal stok menghasilkan mutasi bertanggal; saldo akhir kartu stok sama dengan stok produk | | |
| OWN-06 | Sesi kas hanya dapat dibuka sekali dan tutup sesi memvalidasi kas fisik serta menulis audit | | |
| OWN-07 | Double tap/retry checkout menghasilkan satu transaksi; stok tidak cukup/produk nonaktif/data desimal invalid ditolak | | |
| OWN-08 | Penjualan DP/hutang, saldo awal, adjustment beralasan, ringkasan dan jatuh tempo piutang konsisten | | |
| OWN-09 | Pembayaran parsial/pelunasan, duplicate retry, reversal, bukti lihat/cetak, dan saldo sebelum/sesudah konsisten | | |
| OWN-10 | Void dengan alasan mengubah status ke VOIDED, mengompensasi stok/kas/piutang, dan void ulang tidak menggandakan | | |
| OWN-11 | Laporan memisahkan aktif/VOIDED; seluruh filter dan ekspor CSV sesuai, formula berbahaya dinetralkan | | |
| OWN-12 | Backup lokal perangkat dan backup database server tampil terpisah | | |
| OWN-13 | Create/download backup server selesai melalui job polling; ukuran dan SHA-256 cocok | | |
| OWN-14 | Restore hanya pada DB test: invalid file/token/phrase ditolak; confirm ganda tidak menjalankan dua job; audit tercatat | | |

## Admin

| ID | Skenario dan hasil yang diharapkan | Pass/Fail | Bukti/Catatan |
|---|---|---|---|
| ADM-01 | Login/profil/ganti credential sendiri berhasil; menu user management dan audit khusus owner tidak terlihat, endpoint mengembalikan 403 | | |
| ADM-02 | Produk, impor, saldo awal, kartu stok, laporan, adjustment piutang, void, dan backup server dapat diakses | | |
| ADM-03 | Adjustment dengan pelanggan nonaktif, nominal/tanggal invalid, referensi/alasan kosong ditolak tanpa perubahan saldo | | |
| ADM-04 | Restore test memerlukan validasi, phrase, acknowledgement, token belum kedaluwarsa; timeout confirm tidak auto-retry | | |

## Kasir

| ID | Skenario dan hasil yang diharapkan | Pass/Fail | Bukti/Catatan |
|---|---|---|---|
| KSR-01 | Hanya menu kasir yang terlihat; user, audit, laporan admin, impor, saldo awal, adjustment, void, dan backup server tersembunyi serta endpoint 403 | | |
| KSR-02 | Buka sesi, POS, pembayaran, struk Android Print Framework, histori yang diizinkan, dan tutup sesi berhasil | | |
| KSR-03 | Tombol checkout nonaktif selama request; timeout aman; retry mempertahankan idempotency key | | |
| KSR-04 | Pengaturan auto print, paper size, cash tolerance, dan auto-lock tetap ada setelah aplikasi dibuka ulang | | |
| KSR-05 | Status online mengikuti `/api/readiness`; `/health` 200 dengan DB gagal tetap dianggap offline | | |
| KSR-06 | Offline hanya mengantrekan `TRANSACTION`, `CASH_SESSION`, `CASH_EXPENSE`; entity lain ditolak/dikarantina | | |

## Regression dan operasional

| ID | Skenario dan hasil yang diharapkan | Pass/Fail | Bukti/Catatan |
|---|---|---|---|
| REG-01 | `/health`, `/ready`, `/api/readiness` 200; readiness menjadi 503 saat DB test dihentikan dan pulih kembali | | |
| REG-02 | Concurrency checkout, pembayaran, void, saldo awal/credit limit, dan sesi kas tidak membuat duplikasi/oversell/overpayment | | |
| REG-03 | Paksa kegagalan di tengah checkout/void/restore test; seluruh perubahan atomik rollback atau status job gagal jelas | | |
| REG-04 | APK debug dan fat JAR lokal berhasil dibangun; scan artifact tidak menemukan secret, keystore, dump, credential, atau data klien | | |
| REG-05 | Tidak ada deployment, signed release, secret production, atau akses database production pada pengujian ini | | |

Known limitations/pengecualian UAT: __________________________________________________________

Keputusan: [ ] Diterima  [ ] Diterima dengan catatan  [ ] Ditolak

Nama & tanda tangan klien: ____________________  Tanggal: __________

Nama & tanda tangan penyedia: _________________  Tanggal: __________
