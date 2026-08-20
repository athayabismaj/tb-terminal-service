# Laporan Final Audit Non-Production Batch 8C

Tanggal verifikasi: 20 Agustus 2026 (Asia/Jakarta)

## Keputusan

`FEATURE COMPLETE: YES` untuk masuk tahap Production Release, dengan syarat UAT perangkat/staging ditandatangani dan seluruh konfigurasi/secret/signing production diberikan melalui proses release terpisah. Audit ini tidak melakukan deployment, signed release, restore production, atau akses database production.

## Hasil otomatis

- Backend: 63 test lulus, 0 gagal, 0 error, 0 skip.
- Backend integration/E2E: 30 test lulus pada PostgreSQL 18 test terisolasi; database dan dump sementara dihapus otomatis.
- Mobile: 46 unit test lulus, 0 gagal, 0 error, 0 skip.
- APK debug: `E:\tbterminalapp\app\build\outputs\apk\debug\app-debug.apk`, 77.090.572 byte, SHA-256 `C42333DDADA5B7B91098B5FAD63F6D075BEB6BB62828E79D5761496B0E2D683D`.
- Fat JAR: `E:\tbterminal_backend\build\libs\tb-terminal-service-1.0.0-all.jar`, 34.140.549 byte, SHA-256 `B926F12CF6E1B177506E933DC5680A6F5C77D6DE26FE6A56C7D1803383830B40`.
- Scan source/artifact: tidak ditemukan private key, credential URL PostgreSQL, JWT literal, keystore, dump, backup, environment file, `local.properties`, atau log di file yang masih ada/artifact.

## Bug/regresi yang diperbaiki pada Batch 8C

1. Kartu stok dapat HTTP 500 karena join ORM ke `units` ambigu antara unit produk dan unit mutasi. Join sekarang eksplisit memakai `stock_movements.unit_id` dan diregresikan sampai saldo rekonsiliasi benar.
2. Audit buka/tutup sesi kas reguler dan sync offline sebelumnya ditulis setelah transaksi bisnis melalui route, sehingga kegagalan audit dapat tertelan dan meninggalkan perubahan tanpa jejak. Audit INSERT/UPDATE sekarang berada dalam transaksi database yang sama; pencatatan route yang duplikat dihapus.
3. Validasi tanggal saldo awal/import stok memakai timezone JVM, sehingga dapat salah menolak/menerima tanggal di sekitar tengah malam Jakarta. Sumber tanggal sekarang eksplisit `Asia/Jakarta`.
4. Empat runtime log backend tersimpan sebagai tracked file di repository mobile. File dihapus dan pola log ditambahkan ke `.gitignore`.
5. Runner PostgreSQL test mematikan backup sehingga E2E backup/restore tidak benar-benar dapat lewat. Runner sekarang memakai environment test, backup/restore aktif hanya pada database/direktori sementara, daemon/configuration cache dimatikan, dan cleanup tetap pada `finally`.
6. Cakupan regression belum eksplisit menguji close session, saldo awal/import/kartu stok, adjustment, matriks RBAC lintas modul, tiga tipe offline sync, timeout, malformed response, dan empty body. Test ditambahkan tanpa menambah fitur bisnis.

## Cakupan regression/E2E

Autentikasi login-refresh-logout-revocation/rate limit; RBAC owner/admin/kasir; sesi kas dan concurrency; POS/idempotency/stock lock/payment validation/rollback; produk-SKU-saldo awal-import preview/atomic commit/kartu stok; piutang-opening-adjustment-credit limit; partial/paid/duplicate/concurrent payment dan reversal; void/idempotency/concurrency/rollback; filter histori; laporan aktif versus VOIDED dan CSV formula injection; readiness DB; timeout/invalid response mobile; offline queue boundary; backup-create-download-validate-confirm-duplicate confirm-restore dan audit metadata.

## Known limitations

- Offline hanya untuk `TRANSACTION`, `CASH_SESSION`, dan `CASH_EXPENSE`; master data serta fitur keuangan lain tetap online-only.
- Pengujian Android pada audit ini berupa unit test dan debug build. Print Framework, Storage Access Framework, auto-lock, lifecycle jaringan, dan instalasi APK tetap perlu UAT pada perangkat fisik.
- Restore hanya diverifikasi pada PostgreSQL local/test terisolasi. Production restore, deployment, monitoring eksternal, HTTPS final, secret injection, dan signed APK sengaja belum dijalankan.
- Multi-satuan, barcode, pengiriman, multi-gudang, multi-cabang, dan perluasan offline master data berada di luar scope.

## Pengujian manual minimum

Gunakan `docs/UAT_CHECKLIST.md` dengan akun owner, admin, dan kasir berbeda. Verifikasi readiness saat DB test naik/turun; seluruh role/menu dan 403 langsung; checkout double tap/timeout; import preview dan kegagalan atomik; rekonsiliasi kartu stok; adjustment/pembayaran/reversal; void; laporan/CSV; persistensi pengaturan; print; offline boundary; lalu backup/download/checksum dan restore hanya pada database staging/test. Confirm restore yang timeout tidak boleh diulang otomatis.

Panduan terkait: `README.md`, `docs/TESTING.md`, `docs/BACKUP_RESTORE.md`, `docs/USER_GUIDE.md`, `docs/TROUBLESHOOTING.md`, dan `docs/UAT_CHECKLIST.md`.
