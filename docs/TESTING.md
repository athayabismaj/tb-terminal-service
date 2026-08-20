# Pengujian

## Backend

Dengan Docker aktif, `gradlew test --no-configuration-cache` memakai PostgreSQL Testcontainers. Pada Windows tanpa Docker tetapi dengan PostgreSQL lokal, jalankan:

```powershell
.\scripts\run-postgres-tests.ps1
```

Script membuat database bernama acak, menjalankan seluruh unit/integration/E2E termasuk backup/restore, lalu menghapus database dan direktori dump sementara dalam `finally`. Jangan menunjuk `TEST_DB_URL` ke database development atau production.

Build artifact: `gradlew clean test buildFatJar --no-configuration-cache`.

## Mobile

Unit test/debug build: `gradlew :app:testDebugUnitTest :app:assembleDebug`. Release build memerlukan URL HTTPS dan keystore production sebagaimana dijelaskan di repository mobile.

## Cakupan E2E

Suite melintasi autentikasi/role, sesi kas, POS dan idempotensi, stok/kartu stok, piutang, pembayaran/reversal, void/rollback, laporan/CSV, readiness database, serta backup-download-validasi-restore dan persistensi metadata.
