# Prompt Agent — Security Hardening & Refactor Bertahap

## Cara pakai

1. Pilih jalur:
   - Agent yang membaca file dari repo (misalnya Claude Code): simpan file ini di `docs/AGENT_PROMPT_REFACTOR.md`, lalu tulis: *"Baca docs/AGENT_PROMPT_REFACTOR.md. Kerjakan Fase 0 saja."*
   - Agent tanpa akses file (chat): tempel **Prompt Utama** dulu, lalu tempel satu blok **Fase** per sesi.
2. Kerjakan **satu fase = satu branch/PR**. Review dan merge dulu sebelum lanjut ke fase berikutnya.
3. Fase B1–B3 (Android) dijalankan di repo `tb-terminal-app`, di sesi terpisah.

---

## PROMPT UTAMA (selalu berlaku)

```
Kamu adalah senior Kotlin engineer yang bekerja di repo tb-terminal-service
(backend POS toko bangunan: Ktor 3.4, Exposed 0.44, PostgreSQL, Flyway, Koin,
JWT). Klien satu-satunya adalah aplikasi Android tb-terminal-app.

SEBELUM MENULIS KODE
- Baca AGENT.md (aturan wajib proyek), README.md, dan docs/SECURITY_HARDENING.md.
- Baca file yang akan diubah secara utuh. Jangan menebak struktur.
- Jalankan `./gradlew test` sekali untuk mencatat kondisi awal (baseline).

ATURAN GLOBAL
1. Kerjakan HANYA fase yang diminta. Kalau menemukan masalah di luar fase,
   catat di bagian "Temuan" pada laporan akhir. Jangan diperbaiki diam-diam.
2. Refactor TIDAK BOLEH mengubah perilaku. Kontrak API dengan Android harus
   tetap persis sama: path, method, nama field JSON, HTTP status, kode error
   (mis. VALIDATION_ERROR, PIN_LOCKED, OFFLINE_PRICE_CONFLICT), dan teks
   pesan error. Kalau sebuah perubahan terpaksa mengubah kontrak, BERHENTI
   dan tanyakan dulu.
3. Jangan mengubah file migrasi Flyway yang sudah ada (V1–V45). Perubahan
   skema selalu lewat file V berikutnya.
4. Pindahkan kode dulu, baru perbaiki. Commit "move/extract" dipisah dari
   commit "rename/cleanup" supaya diff mudah direview.
5. Setiap langkah harus lulus `./gradlew test`. Jangan menghapus, melewati
   (@Ignore), atau melemahkan test supaya lulus. Kalau sebuah test memang
   salah, jelaskan alasannya di laporan.
6. Konvensi: pesan untuk user dalam Bahasa Indonesia; identifier dalam bahasa
   Inggris; KDoc singkat dalam Bahasa Indonesia untuk aturan bisnis yang
   tidak terlihat dari kodenya. Jangan menambah komentar yang hanya
   mengulang isi kode.
7. Target keterbacaan: satu file berisi satu tanggung jawab, idealnya
   < 400 baris; fungsi < 60 baris; tanpa nama kelas fully-qualified di
   badan kode (pakai import).
8. Jangan menambah dependency baru tanpa persetujuan.

FORMAT LAPORAN DI AKHIR SETIAP FASE
- Ringkasan perubahan (maksimal 10 poin)
- Tabel file: dibuat / dipindah / diubah / dihapus
- Hasil `./gradlew test`: jumlah lulus/gagal, sebelum vs sesudah
- Konfirmasi: "Kontrak API tidak berubah", atau daftar perubahan beserta alasannya
- Temuan di luar scope
- Usulan pesan commit/PR
```

---

## FASE 0 — Terapkan patch keamanan & pastikan build hijau

```
TUJUAN
Menerapkan security-hardening.patch dan memastikan seluruh build dan test
lulus. Patch ini ditulis TANPA sempat dikompilasi terhadap dependency asli,
jadi kemungkinan ada error kompilasi kecil.

LANGKAH
1. git checkout -b security-hardening && git am security-hardening.patch
2. ./gradlew compileKotlin compileTestKotlin, lalu perbaiki semua error.
   Titik yang paling mungkin bermasalah (cek API-nya untuk versi yang dipakai):
   - SystemRepository.incrementPinFailure: exec(..., args = listOf(
     UUIDColumnType() to ..., VarCharColumnType(128) to ...),
     explicitStatementType = StatementType.SELECT)
   - SystemRepository: insertIgnore, operator `less` di dalam deleteWhere,
     forUpdate() pada consumeRefreshToken
   - OfflinePricingSupport.kt: Query.groupBy(keySelector = ...) dan `inList`
   - Security.kt / Extensions.kt: call.request.origin.remoteAddress
   - Monitoring.kt: XForwardedHeaders { useLastProxy() }
   - SystemService.refresh: decoded.id (jti dari auth0 java-jwt)
3. ./gradlew test, lalu perbaiki yang gagal. Perhatian khusus:
   - IntegrationTest yang memanggil /api/sales/sessions/sync/open memakai
     loginAsOwner(), padahal route ini hanya mengizinkan role KASIR. Buat
     helper loginAsCashier() (buat user kasir lewat fixture) dan pakai di
     test sync offline. Ini masalah lama, bukan dari patch.
   - PinVerificationServiceTest: lockout kini per user (bukan per deviceId).
4. Hapus file sampah temp_repo.txt (dump kode UTF-16 yang ikut ter-commit).

SELESAI BILA
- Semua test hijau, termasuk test baru: OfflineCheckoutGuardTest,
  CredentialAttemptGuardTest, RefreshTokenRotationPolicyTest,
  test_offline_sync_rejects_price_not_in_server_price_list,
  test_refresh_token_is_single_use_and_reuse_revokes_family.
- Migrasi V45 jalan di PostgreSQL kosong maupun di database yang sudah
  berisi data V44.

JANGAN
- Mengubah logika keamanan di patch, kecuali diperlukan supaya bisa
  dikompilasi. Kalau ada logika yang menurutmu salah, tulis di "Temuan".
```

---

## FASE 1 — Helper bersama & bersih-bersih ringan

```
TUJUAN
Menghapus duplikasi kecil yang membuat kode sulit dibaca, tanpa memindahkan
logika bisnis.

LANGKAH
1. Uang: buat shared/Money.kt berisi
     const val MONEY_SCALE = 2
     fun BigDecimal.toMoney(): BigDecimal = setScale(MONEY_SCALE, RoundingMode.HALF_UP)
   Ganti 6 salinan lokal (normalizeMoney/money) di: SalesRepository,
   SalesService, RefundRepository, DiscountCalculator,
   TaxAndRoundingCalculator, OfflineCheckoutGuard.
2. UUID: buat shared/Uuids.kt berisi
     fun String.toUuidOrThrow(label: String): UUID  // lempar ValidationException("Format $label tidak valid")
   Ganti fungsi parseUuid/parseUUID privat di SalesService, SystemService,
   ReceivableService, PurchasingService, QuotationValidation, dan
   pemanggilan UUID.fromString(...) mentah yang menerima input user.
   Teks pesan error harus tetap sama dengan yang lama (cek tiap tempat).
3. Route: buat helper
     fun ApplicationCall.requireUuidParam(name: String, label: String): UUID
   untuk menggantikan sekitar 18 pola `call.parameters["id"] ?: return@... respond(...)`.
   Status dan kode error yang dikirim ke klien harus tetap sama.
4. Ganti nama fully-qualified di badan kode dengan import: sekitar 195
   pemakaian com.service.tbterminal.shared.* dan sekitar 288 pemakaian
   java.math.BigDecimal.
5. Hapus import yang tidak terpakai dan kode mati (fungsi @Deprecated tanpa
   pemanggil, mis. JwtHelper.generateToken lama, kalau memang tidak dipakai).

SELESAI BILA
- Tidak ada lagi definisi normalizeMoney/money/parseUuid lokal.
- `grep -rn "java.math.BigDecimal" src/main/kotlin | grep -v import` kosong.
- Test hijau; diff hanya berisi perubahan mekanis.
```

---

## FASE 2 — Pecah SalesRepository (2.330 baris)

```
TUJUAN
SalesRepository.kt menangani terlalu banyak hal: sesi kas, pengeluaran,
checkout online, sync offline, riwayat, void, dan bayar hutang. Pecah per
tanggung jawab supaya tiap file bisa dibaca utuh.

STRUKTUR TARGET (package com.service.tbterminal.sales)
  repository/
    SalesRepository.kt            -> interface + data class command (tetap; SalesService tidak berubah)
    SalesRepositoryImpl.kt        -> facade tipis: hanya mendelegasikan
    CashSessionStore.kt           -> getActiveSession, getSessionById, getPaginatedSessions,
                                     openSession, closeSession, sync open/close sesi
    CashExpenseStore.kt           -> addExpense, syncCashExpense, getExpenses, getPaginatedExpenses
    CheckoutStore.kt              -> executeCheckout, findCheckoutByIdempotencyKey,
                                     resolveItemsForCheckout, lockAndValidateStocks
    OfflineCheckoutStore.kt       -> findOfflineSyncedCheckout, executeOfflineCheckoutSync,
                                     resolveItemForOfflineSync, lockAndValidateOfflineStocks
    TransactionQueryStore.kt      -> getTransactionById, getPaginatedTransactions, mapper row -> response
    TransactionVoidStore.kt       -> voidTransaction
    DebtPaymentStore.kt           -> payTransactionDebt
    SalesAuditSupport.kt          -> insertCheckoutAudit, insertDiscountAudit
    SalesPaymentSupport.kt        -> resolveCheckoutPayments, lockCustomerAndValidateCredit
(Nama file boleh disesuaikan, asalkan tiap file punya satu tanggung jawab.)

LANGKAH
1. Commit 1: PINDAHKAN kode apa adanya ke class internal baru;
   SalesRepositoryImpl hanya mendelegasikan. Belum ada rename sama sekali.
2. Commit 2: rapikan: helper privat yang dipakai lebih dari satu store pindah
   ke *Support.kt; pecah fungsi > 60 baris (terutama executeCheckout dan
   executeOfflineCheckoutSync) menjadi langkah bernama, mis.
   validateSession -> lockStocks -> resolveItems -> resolvePayments ->
   insertTransaction -> insertItems -> insertPayments -> insertReceivable ->
   updateCashSession -> audit.
3. Urutan lock database (customer, produk, stok, sesi) HARUS sama persis
   dengan sebelumnya, untuk mencegah deadlock. Tulis urutannya di KDoc.
4. Konstruktor SalesRepositoryImpl di AppModule tidak berubah.

SELESAI BILA
- Tidak ada file di sales/ yang > 500 baris.
- IntegrationTest checkout/void/refund/offline sync/bayar hutang hijau.
- Diff commit 1 bisa direview sebagai "pure move".
```

---

## FASE 3 — Pecah service & repository besar lainnya

```
TUJUAN
Menerapkan pola Fase 2 pada file besar lain.

TARGET
1. SystemService.kt (623) -> AuthService (login, refresh, logout, unlock,
   reset PIN, ganti password/PIN sendiri), UserManagementService (CRUD user,
   role), StoreSettingsService (settings & store profile),
   AuditLogService. Bisa dimulai dengan menjadikan SystemService facade agar
   route tidak perlu langsung berubah, lalu memindahkan route ke service yang
   tepat di commit berikutnya.
2. SystemRepository.kt (669) -> UserRepository, CredentialAttemptRepository
   (implementasi PinAttemptStore), RefreshTokenRepository,
   StoreSettingsRepository, AuditLogRepository.
3. ReceivableRepository.kt (818) -> CustomerStore, ReceivableStore,
   ReceivablePaymentStore.
4. SalesService.kt (696) -> CashSessionService, CheckoutService,
   OfflineSyncService, TransactionService.
5. SalesModels.kt (608) & InventoryModels.kt (400): pisahkan Exposed Table
   (…Tables.kt) dari DTO request/response (…Dto.kt).

ATURAN
- Satu target = satu commit (atau lebih), urutan seperti di atas.
- Daftarkan class baru di AppModule (Koin). Jangan memakai service locator
  di dalam class.
- Route cukup ganti dependency; isi handler tidak berubah.
```

---

## FASE 4 — Ganti SQL string mentah dengan parameter binding

```
TUJUAN
Saat ini aman karena input sudah di-whitelist/escape manual, tetapi polanya
rapuh. Ganti semua SQL yang menyisipkan nilai lewat string interpolation.

LOKASI
- backup/BackupRepository.kt: seluruh fungsi (sqlText(), sqlUuid(),
  sqlTimestamp(), dan update(id, values: String)). Ganti dengan Exposed
  DSL (buat object DatabaseBackupJobsTable) atau
  exec(sql, args = listOf(ColumnType() to value)).
- analytics/AnalyticsRepository.kt: buildSalesWhere / buildPaymentWhere
  (toSqlUuid, toSqlTimestamptz, status/paymentMethod). Ubah builder agar
  menghasilkan Pair(sqlDenganPlaceholder, args).
- receivable/ReceivableRepository.lockIdempotencyKey:
  exec("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", args = ...).

SELESAI BILA
- `grep -rn "'\$" src/main/kotlin` tidak menemukan nilai yang disisipkan ke SQL.
- Test analytics, laporan CSV, backup/restore metadata hijau.
- Tambah 1 test per repository yang membuktikan input berisi ' dan \ aman.
```

---

## FASE 5 — Perbaiki kontrak satuan sekunder di sync offline (backend + Android)

```
MASALAH
Saat offline, Android mengirim `quantity` dalam satuan sekunder (mis. dus)
dan `priceAtTransaction` = harga dasar × faktor. Server mencatat
transaction_items dengan base_unit_id dan trigger fn_sync_stock mengurangi
stok sebesar `quantity`. Akibatnya, jual 2 dus (isi 12) hanya mengurangi
stok 2 pcs. Checkout online sudah benar karena mengirim kuantitas dasar.

LANGKAH (BACKEND)
1. Tambah field opsional di OfflineCheckoutItemRequest:
   `baseQuantity` (BigDecimal?) dan `unitId` (String?).
2. Kalau field itu dikirim: validasi unitId = base unit atau secondary unit
   produk, validasi baseQuantity = quantity × faktor, lalu simpan item dalam
   satuan dasar (qty = baseQuantity, harga = harga dasar). Subtotal harus
   tetap persis sama. Kalau pembulatan 2 desimal membuat subtotal tidak
   bisa dijaga, tolak dengan 409 OFFLINE_UNIT_CONFLICT.
3. Kalau field tidak dikirim (klien lama) DAN OfflineCheckoutGuard mendeteksi
   faktor ≠ 1: tolak dengan 409 OFFLINE_UNIT_CONFLICT (pesan memuat frasa
   "business rule"), jangan diterima diam-diam.
4. Test: jual 2 dus (faktor 12), stok berkurang 24, subtotal sama.
5. Siapkan migrasi data opsional (skrip SQL terpisah di docs/, BUKAN
   migrasi Flyway) untuk mendeteksi transaksi offline lama yang terkena bug.

LANGKAH (ANDROID): lihat Fase B1 poin 4.
```

---

## FASE 6 — Dokumentasi

```
1. Perbarui AGENT.md:
   - Bagian 4 (Struktur Folder) sesuai hasil Fase 2–3.
   - Bagian 5.3 (Aturan Keamanan): lockout per user + scope,
     CredentialAttemptGuard, rotasi refresh token, OfflineCheckoutGuard,
     larangan membaca X-Forwarded-For langsung (pakai call.clientIpAddress()),
     larangan SQL string interpolation.
   - Bagian 7 (Roadmap): tandai fase yang selesai.
   - Bagian 11 (Checklist Sebelum Commit): tambahkan poin "kontrak API tidak
     berubah" dan "urutan lock DB dijaga".
2. README.md: catatan deploy (user wajib login ulang setelah V45; port 8080
   tidak boleh publik).
3. Tambah docs/API_CHANGES.md: daftar perubahan kontrak untuk tim Android,
   diurutkan per versi.
```

---

## (Opsional) FASE 7 — Hapus API Exposed yang deprecated

```
Ada sekitar 164 pemakaian `Table.select { ... }` (deprecated di Exposed
versi baru). Kerjakan HANYA setelah Fase 0–4 selesai dan test hijau.
1. Upgrade Exposed ke versi stabil terbaru yang kompatibel dengan Kotlin
   dan Flyway proyek. Tanyakan dulu sebelum upgrade.
2. Ganti dengan `selectAll().where { ... }` per modul, satu commit per modul.
3. Jangan mencampur dengan perubahan logika.
```

---

# BAGIAN B — Android (`tb-terminal-app`)

## PROMPT UTAMA ANDROID

```
Kamu adalah senior Android engineer (Kotlin, Jetpack Compose, Room, Retrofit)
di repo tb-terminal-app, klien untuk backend tb-terminal-service. Baca
README.md dan docs/ repo ini, serta docs/SECURITY_HARDENING.md dari repo
backend.

ATURAN: sama dengan Prompt Utama backend, poin 1, 4, 5, 6, 8.
Refactor UI tidak boleh mengubah tampilan atau alur, kecuali diminta.
Jalankan `./gradlew :app:testDebugUnitTest :app:assembleDebug` di setiap
langkah. Format laporan sama dengan backend.
```

## FASE B1 — Ikuti perubahan backend

```
1. PayDebtRequestDto: tambahkan `idempotencyKey: String`. Buat UUID sekali
   saat dialog bayar dibuka dan pakai ulang saat retry.
2. Setelah PUT /api/system/users/me/password sukses: bersihkan sesi lalu
   arahkan ke login dengan pesan "Password diubah, silakan login kembali".
3. Tangani HTTP 429 (TOO_MANY_ATTEMPTS, MANAGER_APPROVER_LOCKED, RATE_LIMITED):
   tampilkan pesan tunggu; gunakan header Retry-After bila ada.
4. Sync offline satuan sekunder (pasangan Fase 5 backend): kirim
   `unitId` dan `baseQuantity` per item. Persist baseQuantity & conversionFactor
   di LocalTransactionItemEntity (butuh migrasi Room baru).
5. Refresh token sekali pakai: pastikan SEMUA pemanggil /api/auth/refresh
   lewat RefreshTokenAuthenticator yang di-synchronize, termasuk WorkManager
   atau worker sync. Pemanggilan paralel dengan token lama akan dianggap
   pencurian dan memaksa logout.
6. Kode konflik baru (OFFLINE_PRICE_CONFLICT, OFFLINE_ROUNDING_CONFLICT,
   OFFLINE_TAX_CONFLICT, OFFLINE_OCCURRED_AT_INVALID, OFFLINE_UNIT_CONFLICT):
   tampilkan rekomendasi spesifik di SyncCenterScreen.
Tambahkan unit test untuk tiap poin.
```

## FASE B2 — Pecah ViewModel raksasa

```
Target utama: ui/checkout/CheckoutViewModel.kt (2.648 baris).
1. Ekstrak logika non-UI ke use case/kelas murni yang bisa diuji:
   CartPricingCalculator (tiered/contractor/secondary unit),
   SplitPaymentValidator, LocalCheckoutCommandBuilder (pembentuk command
   offline), OnlineCheckoutRequestBuilder.
2. ViewModel hanya mengurus state dan event; target < 600 baris.
3. Pindahkan test yang ada dan tambah test untuk kelas baru.
Setelah itu lakukan hal yang sama untuk ViewModel lain yang > 800 baris.
```

## FASE B3 — Pecah layar Compose raksasa

```
Target (urut dari terbesar): SharedSettingsScreen (2.922),
AdminReceiptDetailScreen (2.771), CashierPaymentScreen (2.724),
ReceivableTable (2.484), AdminCustomerScreens (2.394),
PurchaseHistoryScreen (2.299).
Untuk tiap layar:
1. Pisahkan menjadi: <Nama>Route.kt (ambil state dari ViewModel),
   <Nama>Screen.kt (stateless, menerima state + lambda), dan
   components/<Nama>*.kt (bagian-bagian layar).
2. Setiap composable publik yang stateless diberi @Preview.
3. Tidak ada perubahan visual. Bandingkan screenshot sebelum/sesudah
   (Paparazzi/Roborazzi kalau sudah ada; kalau belum, cukup manual).
Satu layar = satu commit.
```
