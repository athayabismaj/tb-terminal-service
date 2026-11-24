# CLAUDE.md — tb-terminal-service

Backend REST untuk POS toko bangunan. Klien satu-satunya: aplikasi Android
`tb-terminal-app`. Bahasa pesan user: Indonesia. Identifier kode: Inggris.

## Stack

Kotlin · Ktor 3.4 (Netty) · Exposed 0.44 · PostgreSQL 16 · Flyway · HikariCP ·
Koin · auth0 java-jwt · jBCrypt · kotlinx.serialization · Cloudinary (foto produk).

## Perintah

```bash
./gradlew run                          # server dev (butuh local.properties, lihat local.properties.example)
./gradlew test --no-configuration-cache # unit + integration (PostgreSQL via Testcontainers, butuh Docker)
./gradlew clean test buildFatJar --no-configuration-cache   # artefak production
```

- Windows tanpa Docker: `.\scripts\run-postgres-tests.ps1` (PostgreSQL lokal).
- `IntegrationTest.kt` adalah test end-to-end utama. Jalankan sebelum menyatakan pekerjaan selesai.

## Struktur

```
src/main/kotlin/com/service/tbterminal/
  plugins/     Ktor setup: Security (JWT + rate limit), StatusPages, Monitoring (XForwardedHeaders), Database, Cors
  di/          AppModule.kt: SATU-SATUNYA modul Koin
  shared/      ApiResponse, Exceptions, RoleConstants (Role + Permission + AccessPolicy), JwtHelper,
               EnvironmentConfig, Extensions (getUserId, requirePermission, clientIpAddress)
  system/      auth, user, PIN/lockout (CredentialAttemptGuard), manager approval, refresh token, audit, store settings
  inventory/   produk, kategori, satuan, stok, harga grosir, import CSV, foto (ProductMediaStorage)
  sales/       sesi kas, checkout online/offline (OfflineCheckoutGuard), void, refund, diskon, quotation
  receivable/  pelanggan & piutang    purchasing/  supplier & pembelian
  analytics/   laporan & CSV          backup/      pg_dump/pg_restore
src/main/resources/db/migration/   Flyway V1..V46
docs/                               DEPLOYMENT, TESTING, BACKUP_RESTORE, SECURITY_HARDENING, UAT_CHECKLIST
```

Alur per modul: `*Routes.kt` → `*Service.kt` (validasi + izin) → `*Repository.kt` (Exposed).
Route tipis; aturan bisnis di Service; SQL hanya di Repository.

## Aturan wajib

### Database
- Semua fungsi repository: `suspend` + `newSuspendedTransaction(Dispatchers.IO) { }`. Jangan pakai `transaction {}`.
- JANGAN meng-UPDATE langsung `inventory.stock`, `receivable.receivables.paid_amount/status`,
  atau `supplier_payables.paid_amount/status`. Trigger database yang menanganinya.
- `system.audit_logs` immutable (dilarang UPDATE/DELETE). `system.store_settings` singleton.
- `SET LOCAL app.current_user_id = ...` sebelum UPDATE harga produk (dipakai trigger price_history).
- Perubahan skema = file Flyway baru `V{n+1}__deskripsi.sql`. JANGAN edit migrasi lama.
- Saat memakai `exec()` manual: tulis nama tabel lengkap dengan schema (`inventory.products`), dan
  kirim nilai lewat `args` (parameter binding). JANGAN menyisipkan nilai lewat string interpolation.
- Urutan lock (`forUpdate`) di checkout/void/refund harus dijaga agar tidak terjadi deadlock.

### Keamanan
- Semua endpoint di dalam `authenticate("jwt-auth")`, kecuali `/health`, `/ready`, `/api/readiness`,
  `/api/auth/login`, dan `/api/auth/refresh`.
- Izin lewat `call.requirePermission(Permission.X)` di route DAN `AccessPolicy.require(...)` di service.
  Role hanya dari `Role.OWNER/ADMIN/KASIR`, jangan hardcode string.
- BCrypt selalu di dalam `withContext(Dispatchers.IO)`. Jangan me-log PIN, password, atau token.
- Verifikasi PIN/password apa pun WAJIB lewat `PinVerificationService` / `CredentialAttemptGuard`
  (lockout per user + scope), dengan urutan `reserve` → periksa → `release`. Jangan memanggil
  `BCrypt.checkpw` untuk kredensial user sebelum reservasi.
- IP klien: `call.clientIpAddress()`. JANGAN membaca header `X-Forwarded-For` secara langsung.
- Nilai uang dari klien (terutama sync offline) tidak boleh dipercaya. Harga, HPP, pajak, dan pembulatan
  harus bisa dijelaskan oleh data server (lihat `OfflineCheckoutGuard`).
- Perubahan password atau role user → naikkan `token_version` (cabut semua sesi).
- Refresh token sekali pakai (tabel `system.refresh_tokens`). Jangan menerbitkan refresh token
  tanpa mencatat `jti`-nya.
- Jangan pernah mengembalikan `pin_hash`, `password_hash`, atau stack trace ke klien.

### Kontrak API (Android bergantung padanya)
- Semua respons memakai `ApiResponse` (`success`, `data`, `message`, `error`, `code`, `meta`).
- JANGAN mengubah path, nama field JSON, HTTP status, kode error, atau teks pesan error yang sudah ada
  tanpa persetujuan eksplisit. Perubahan kontrak wajib dicatat di `docs/SECURITY_HARDENING.md`
  (atau `docs/API_CHANGES.md`).
- Endpoint yang bisa di-retry (checkout, pembayaran, void, refund, sync offline) wajib idempotent.
- Konflik data offline dijawab 409 dengan pesan yang memuat frasa "business rule", agar
  `SyncErrorClassifier` di Android menandainya CONFLICT.

### DI & konfigurasi
- Repository/Service didaftarkan sebagai `single { }` di `AppModule.kt`; route memakai `by inject()`.
  Repository dan service tidak boleh menyimpan state.
- Konfigurasi dan secret hanya dibaca lewat `EnvironmentConfig` (env var → system property →
  `local.properties`). Jangan hardcode credential atau URL.

## AGENT.md vs kode

`AGENT.md` berisi aturan rinci dan roadmap, tetapi sebagian sudah tertinggal dari kode.
Bila keduanya bertentangan, **kode adalah acuan**:
- Validasi memakai fungsi validasi buatan sendiri (`*Validation.kt`), bukan Konform.
- Env dibaca lewat `EnvironmentConfig`, bukan dotenv `env[...]`.
- Foto produk lewat `ProductMediaStorage` / `CloudinaryMediaStorage`, bukan `CloudinaryHelper`.
- Cek izin memakai `requirePermission(Permission.X)`, bukan `requireRole()`.
- JWT: access token 15 menit + refresh token 7 hari (bukan 24 jam).

## Cara bekerja

1. Baca file yang akan diubah secara utuh, dan cek `shared/` sebelum membuat helper baru.
2. Perubahan kecil dan terfokus. Refactor (pindah kode) dipisah dari perubahan perilaku.
3. Tambah atau ubah test untuk setiap perubahan perilaku. Logika murni dipisah ke kelas tanpa
   dependency (contoh: `OfflineCheckoutGuard`, `CredentialAttemptGuard`) agar mudah diuji.
4. Jalankan `./gradlew test` sebelum menyatakan selesai, dan laporkan hasilnya apa adanya.
5. File yang sudah besar (`sales/SalesRepository.kt` > 2.000 baris) jangan ditambah lagi:
   ekstrak ke file baru per tanggung jawab.
6. Rencana refactor bertahap: `docs/AGENT_PROMPT_REFACTOR.md` (kalau sudah ditambahkan).
