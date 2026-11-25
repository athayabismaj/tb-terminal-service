# Security Hardening (branch `security-hardening`)

Ringkasan perbaikan keamanan backend beserta dampaknya ke aplikasi Android
(`tb-terminal-app`). Perubahan skema ada di migrasi `V45`
(refresh token, lingkup lockout) dan `V46` (waktu perubahan pajak dan pembulatan).

## 1. Sync checkout offline tidak lagi percaya angka dari perangkat

Endpoint: `POST /api/sales/checkout/sync`, kecuali disebutkan lain.

| Data dari perangkat | Sebelum | Sesudah |
|---|---|---|
| `priceAtTransaction` | diterima asal konsisten | wajib cocok dengan harga server: eceran/kontraktor/grosir saat ini, harga lama yang berubah di dalam jendela tepercaya, atau harga × faktor satuan sekunder (faktor saat ini, atau faktor lama yang berubah di dalam jendela tepercaya; V47) |
| `cogsAtTransaction` | diterima | diabaikan; server memakai `price_buy` per satuan dasar |
| `productNameSnapshot` | dipakai di struk/laporan | diabaikan; server memakai nama produk |
| `roundingAmount` | tanpa batas | \|nilai\| < 1000, total hasil pembulatan wajib kelipatan 100/500/1000, dan bila pengaturan pembulatan tidak berubah di dalam jendela tepercaya: \|nilai\| < unit pembulatan toko |
| `taxEnabled/taxMode/taxRate` | bebas | wajib sama dengan pengaturan toko, kecuali pengaturan pajak diubah di dalam jendela tepercaya |
| `occurredAt` (juga `POST /api/sales/cash-expenses/sync`) | bebas | wajib di antara pembukaan sesi kas (−5 menit) dan waktu server (+5 menit) |
| `openedAt` (`POST /api/sales/sessions/sync/open`) | bebas | paling lama `OFFLINE_MAX_SESSION_AGE_HOURS` (default 72) jam sebelum, dan paling jauh 5 menit sesudah, waktu server |
| `closedAt` (`POST /api/sales/sessions/sync/close`) | bebas | wajib di antara pembukaan sesi kas (−5 menit) dan waktu server (+5 menit) |

Pelanggaran dijawab **HTTP 409** dengan kode `OFFLINE_PRICE_CONFLICT`,
`OFFLINE_ROUNDING_CONFLICT`, `OFFLINE_TAX_CONFLICT`,
`OFFLINE_OCCURRED_AT_INVALID`, `OFFLINE_OPENED_AT_INVALID`,
`OFFLINE_CLOSED_AT_INVALID`, atau `OFFLINE_UNIT_CONFLICT` (lihat `API_CHANGES.md`). Semua pesan memuat frasa "business rule" (dijamin `offlineConflict(...)`) sehingga
`SyncErrorClassifier` di aplikasi otomatis menandainya **CONFLICT**.

**Jendela tepercaya.** Riwayat harga, riwayat faktor konversi, dan perubahan pengaturan hanya dilihat mulai
dari `max(openedAt, waktu server saat sesi dibuat/di-sync − OFFLINE_MAX_SESSION_AGE_HOURS)`. Jadi sesi yang
`openedAt`-nya terlanjur dimundurkan sebelum aturan ini berlaku tetap tidak bisa
memakai harga lama atau melewati cek pajak dan pembulatan.

**Penolakan ambigu (Fase 5b).** Untuk item tanpa `unitId`, seluruh riwayat faktor dan harga (juga di luar jendela)
dipakai hanya untuk menolak: lebih dari satu faktor yang bisa menjelaskan harga → 409 `OFFLINE_UNIT_CONFLICT`. Riwayat di
luar jendela tidak pernah mengesahkan harga atau faktor. Celah yang tersisa (satuan dasar diganti) ada di `BACKLOG.md`.

**Perubahan pengaturan.** Cek pajak hanya longgar bila `tax_enabled`, `tax_rate`, atau `tax_mode`
berubah di dalam jendela; cek pembulatan hanya bila `cash_rounding_unit` atau `cash_rounding_rule`
berubah. Waktunya dicatat trigger database di `tax_settings_updated_at` dan
`rounding_settings_updated_at` (V46), yang tidak bisa ditulis langsung. Mengubah nama toko, telepon,
teks struk, label pajak, atau NPWP tidak lagi melonggarkan cek apa pun. Saat V46 dijalankan, kedua
kolom diisi dari `updated_at`, jadi sesi yang sedang berjalan diperlakukan seperti sebelumnya.

**Dampak operasional.** Sesi kas offline yang baru tersambung setelah lebih dari
`OFFLINE_MAX_SESSION_AGE_HOURS` (default 72, rentang 1–168) ditolak, dan transaksinya ikut tidak bisa di-sync karena membutuhkan
`serverCashSessionId`. Sesi yang sudah pernah tersimpan tetap dijawab `DUPLICATE`
saat diulang, berapa pun umurnya. Hal yang sama berlaku untuk pengeluaran (`DUPLICATE`) dan tutup
sesi (`DUPLICATE`/`ALREADY_CLOSED`). Perangkat yang jamnya lebih dari 5 menit lebih cepat mendapat
409 sampai waktu server menyusul; retry setelah itu diterima.

## 2. Lockout kredensial per user (bukan per deviceId)

- Penghitung gagal kini per **user + lingkup** (`scope:pin`, `scope:password`,
  `scope:manager-approval`). Mengganti `deviceId`/`X-Device-Id` tidak lagi
  mereset hitungan. `deviceId` hanya metadata audit (karakter di luar
  `[A-Za-z0-9._:-]` diganti `_`).
- Percobaan **dicatat sebagai gagal sebelum bcrypt** (reservasi), dalam satu pernyataan
  (`INSERT ... ON CONFLICT DO UPDATE ... WHERE kunci tidak aktif ... RETURNING`). Percobaan yang
  mencapai ambang langsung memasang kunci, dan selama kunci aktif tidak ada reservasi baru. Jadi
  tebakan paralel yang benar-benar diperiksa tidak pernah melebihi ambang.
- Reservasi dilepas hanya bila kredensial benar. Pelepasan tidak menghapus kunci aktif yang dipasang
  percobaan lain; kunci yang dipasang percobaan benar itu sendiri (mis. percobaan ke-5 benar) ikut
  dilepas. Bcrypt yang gagal atau request yang dibatalkan tetap terhitung sebagai kegagalan.
- Selama bcrypt percobaan yang mencapai ambang berjalan (± 0,1 detik), request paralel dapat menerima
  jawaban terkunci walau percobaan itu ternyata benar.
- Pada batas total, sesi dicabut dulu baru hitungan dimulai dari nol.
- Ganti PIN dan reset PIN hanya mereset `scope:pin` dan `scope:manager-approval`. Sebelumnya semua
  lingkup ikut terhapus, sehingga orang yang tahu PIN bisa menebak password tanpa batas dengan
  berulang kali mengganti PIN. Reset PIN oleh owner tetap mereset semua lingkup.
- Pemeriksaan PIN lama (`PUT /api/system/users/me/pin`) memakai penghitung yang
  sama dengan `/api/auth/unlock`. Pemeriksaan password (`/api/auth/reset-pin`,
  `PUT /api/system/users/me/password`) memakai lingkup `scope:password`.
- Saat terkunci, endpoint ganti/reset kredensial menjawab **HTTP 429**
  `TOO_MANY_ATTEMPTS` + header `Retry-After`. Saat batas total terlampaui,
  semua sesi dicabut dan dijawab **HTTP 401**.
- `/api/auth/unlock` tetap memakai kode lama (`INVALID_PIN`, `PIN_LOCKED`,
  `PIN_MAX_ATTEMPTS_EXCEEDED`).
- JSON audit kini dibuat dengan `buildJsonObject` (tidak bisa rusak oleh input).

## 3. Manager approval

- PIN approver dikunci per approver: setelah 3 salah → 1 menit, lalu 5, 15, 30
  menit per percobaan sampai PIN benar. Jawaban: **HTTP 429**
  `MANAGER_APPROVER_LOCKED`.
- Role approver baru diperiksa setelah PIN benar, sehingga kasir tidak bisa
  memetakan username mana yang manager tanpa mengetahui PIN.
- Username tidak dikenal tetap menjalankan bcrypt (waktu respons seragam).

## 4. Sesi dan refresh token

- Refresh token kini **sekali pakai** (rotasi). Setiap `POST /api/auth/refresh`
  mengembalikan refresh token baru; token lama langsung tidak berlaku.
- Bila token yang sudah dirotasi dipakai lagi, seluruh keluarga token dicabut,
  `token_version` user dinaikkan (**semua** sesi user di semua perangkat berakhir,
  termasuk access token yang belum kedaluwarsa), dan dicatat di audit
  (`refresh_token_reuse`). Token yang sama tidak bisa memicunya dua kali.
- Refresh token lama (diterbitkan sebelum deploy, tanpa `jti`) ditolak:
  **semua user perlu login ulang sekali setelah deploy**.
- Ganti password sendiri, owner mereset password user, atau perubahan role kini
  menaikkan `token_version`, jadi semua sesi lama user tersebut berakhir.
- Password minimal 8 karakter (maksimal 72 karena batas bcrypt). PIN wajib 4–6
  digit angka di semua endpoint (sebelumnya buat/ubah user menerima huruf).
- Login tidak lagi membocorkan status "akun tidak aktif" sebelum password benar.

## 5. Rate limit dan IP klien

- Kunci rate limit Ktor kini `request.origin.remoteAddress` (IP klien asli),
  bukan `request.local.remoteHost` (yang di belakang nginx selalu IP nginx,
  sehingga 5 login gagal dari satu orang memblokir semua user).
- `XForwardedHeaders` memakai `useLastProxy()`, nginx menimpa
  `X-Forwarded-For` dengan `$remote_addr`. IP di audit log tidak bisa lagi
  dipalsukan lewat header.
- Rate limit baru: `refresh` 30/menit/IP, `credential-check` 30/menit/IP untuk
  unlock, reset PIN, ganti PIN, ganti password.
- **Syarat deploy:** port 8080 backend tidak boleh dibuka ke publik; hanya nginx
  yang boleh menjangkau backend (sudah begitu di `docker-compose.production.yml`).

## 6. Pembayaran hutang transaksi

`POST /api/sales/transactions/{id}/pay`

- Sebelumnya endpoint ini selalu gagal (500) karena kolom
  `receivable_payments.idempotency_key` wajib diisi tetapi tidak di-set.
- Kini menerima field opsional `idempotencyKey`. Bila dikirim, retry dengan key
  yang sama mengembalikan hasil yang sama tanpa mencatat pembayaran dua kali.
- Retry dianggap sama bila entri itu pembayaran (bukan reversal) serta transaksi, nominal, dan
  metode bayarnya sama. Selain itu
  dijawab 400 `VALIDATION_ERROR` "Idempotency key sudah dipakai untuk pembayaran lain".
- ID transaksi yang formatnya salah kini dijawab 400, bukan 500.

## 7. Perubahan kontrak di luar keamanan

`GET /api/analytics/sales/report`: `totals.netRevenue` kini selalu dikirim.
Sebelumnya field ini hilang dari JSON saat nilainya sama dengan
`grossRevenue − discountAmount − refundAmount`. Perubahan bersifat aditif;
aplikasi Android tidak perlu diubah.

## 8. Perubahan teks pesan

Dibandingkan `main`. Status dan kode tidak berubah kecuali disebutkan.

| Endpoint / kondisi | Sebelum | Sesudah |
|---|---|---|
| `/api/auth/reset-pin`, format PIN salah | PIN baru harus terdiri dari 4 sampai 6 digit | PIN baru harus terdiri dari 4 sampai 6 digit angka |
| `PUT /api/system/users/me/pin`, format PIN salah | PIN baru minimal 4 karakter | PIN baru harus terdiri dari 4 sampai 6 digit angka |
| Buat/ubah user, PIN salah format | PIN tidak boleh kosong / PIN minimal 4 karakter | PIN harus terdiri dari 4 sampai 6 digit angka |
| Buat/ubah user, password pendek | Password minimal 6 karakter | Password minimal 8 karakter |
| Buat/ubah user dan ganti password, terlalu panjang | (diterima) | Password maksimal 72 karakter |
| `PUT /api/system/users/me/password`, kosong | Password baru minimal 6 karakter | Password baru wajib diisi |
| `PUT /api/system/users/me/password`, pendek | Password baru minimal 6 karakter | Password minimal 8 karakter |
| `PUT /api/system/users/me/password`, sukses | Password berhasil diubah | Password berhasil diubah. Silakan login kembali dengan password baru. |
| Ganti PIN, ganti password, reset PIN; terkunci | (tidak ada) | 429 `TOO_MANY_ATTEMPTS`: Terlalu banyak percobaan gagal. Coba lagi setelah {waktu} |
| Ganti PIN, ganti password, reset PIN; batas total | (tidak ada) | 401 `UNAUTHORIZED`: Batas percobaan terlampaui. Silakan login kembali. |
| `/api/auth/refresh`, token tanpa `jti`, sudah dipakai, atau dicabut | 200 (diterima) | 401 `UNAUTHORIZED`: Sesi telah berakhir. Silakan login kembali. |
| Manager approval, approver terkunci | 403 `MANAGER_APPROVAL_INVALID` | 429 `MANAGER_APPROVER_LOCKED`: Terlalu banyak PIN manager salah. Persetujuan untuk akun ini dikunci sementara |
| `/transactions/{id}/pay`, ID salah format | 500 | 400 `VALIDATION_ERROR`: Format Transaction ID tidak valid |
| `/transactions/{id}/pay`, `idempotencyKey` salah format | (field diabaikan) | 400 `VALIDATION_ERROR`: Idempotency key harus 8-100 karakter dan hanya berisi huruf, angka, titik, garis, titik dua, atau underscore |
| `/transactions/{id}/pay`, key bentrok | (field diabaikan) | 400 `VALIDATION_ERROR`: Idempotency key sudah dipakai untuk pembayaran lain |
| Refresh dan cek kredensial, batas per IP | (tidak ada) | 429 `RATE_LIMITED` |

Pesan 409 sync offline (semuanya baru):

| Kode | Pesan |
|---|---|
| `OFFLINE_OPENED_AT_INVALID` | Pelanggaran business rule: Waktu buka sesi kas offline berada di masa depan |
| | Pelanggaran business rule: Waktu buka sesi kas offline lebih dari {OFFLINE_MAX_SESSION_AGE_HOURS} jam yang lalu |
| `OFFLINE_OCCURRED_AT_INVALID` | Pelanggaran business rule: Waktu transaksi offline lebih awal dari pembukaan sesi kas (session invalid) |
| | Pelanggaran business rule: Waktu transaksi offline berada di masa depan |
| | Pelanggaran business rule: Waktu pengeluaran kas offline lebih awal dari pembukaan sesi kas (session invalid) |
| | Pelanggaran business rule: Waktu pengeluaran kas offline berada di masa depan |
| `OFFLINE_CLOSED_AT_INVALID` | Pelanggaran business rule: Waktu tutup sesi kas offline lebih awal dari pembukaan sesi kas (session invalid) |
| | Pelanggaran business rule: Waktu tutup sesi kas offline berada di masa depan |
| `OFFLINE_ROUNDING_CONFLICT` | Pelanggaran business rule: {alasan pembulatan} |
| `OFFLINE_TAX_CONFLICT` | Pelanggaran business rule pajak: {status, mode, atau tarif} pajak transaksi offline berbeda dari pengaturan toko |
| `OFFLINE_PRICE_CONFLICT` | Pelanggaran business rule harga: harga {produk} (Rp {harga}) tidak sesuai daftar harga server |
| `OFFLINE_UNIT_CONFLICT` | Pelanggaran business rule satuan: {produk}: {alasan} (daftar alasan di `API_CHANGES.md`) |

Pada commit awal branch ini `OFFLINE_OCCURRED_AT_INVALID` belum berawalan
"Pelanggaran business rule: ".

## 9. Perubahan urutan error

Bila satu request melanggar beberapa aturan, error yang dikirim bisa berbeda dari `main`:

- **Login:** password diperiksa sebelum status akun. Akun nonaktif + password salah kini
  "Username atau Password salah" (sebelumnya "Akun tidak aktif").
- **Manager approval:** PIN → role. Approver tanpa wewenang + PIN salah kini
  `MANAGER_APPROVAL_INVALID` (sebelumnya `MANAGER_APPROVER_FORBIDDEN`). Kunci approver
  diperiksa sebelum PIN, jadi PIN benar saat terkunci tetap 429.
- **Reset PIN, ganti PIN, ganti password:** format → user → kunci → kredensial lama. Saat terkunci,
  kredensial lama yang benar pun dijawab 429.
- **Refresh:** `jti` → user → status/`token_version` → status token. Setelah reuse terdeteksi,
  pemakaian berikutnya dijawab "Sesi telah berakhir" (tanpa "Silakan login kembali.").
- **`sync/open`:** key dan kepemilikan → duplikat → `openedAt` → kasir → sesi aktif. Sesi yang terlalu
  lama ditolak 409 walau kasir masih punya sesi terbuka (sebelumnya 400).
- **`checkout/sync`:** key dan kepemilikan → duplikat → format dan aritmetika → kasir → sesi → `occurredAt` →
  pembulatan → pajak → baris stok ada → item → harga/satuan → stok cukup → total → pembayaran. Artinya konflik
  waktu, pembulatan, pajak, harga, dan satuan muncul lebih dulu daripada stok tidak cukup, karena kecukupan stok
  baru bisa dinilai setelah semua item diubah ke satuan dasar. Urutan lock tetap stok → produk.
- **`cash-expenses/sync`:** key dan kepemilikan → format → duplikat → sesi → sesi sudah ditutup → `occurredAt`.
- **`sessions/sync/close`:** format → sesi → kepemilikan → sudah ditutup (`DUPLICATE`/`ALREADY_CLOSED`) → `closedAt`.
- **`/transactions/{id}/pay`:** idempotensi diperiksa sebelum status lunas dan sisa hutang, jadi retry
  yang terlambat dijawab sukses walau transaksinya sudah lunas.

## Urutan rilis backend 5b (wajib)

1. **(a)** Rilis APK B1c yang tetap kompatibel dengan backend v1.0.7 (fallback `/products` bila `baseUnitId` tidak ada).
2. **(b)** Perbarui semua HP ke B1c.
3. **(c)** Baru deploy backend 5b (V47).

Jangan men-deploy 5b selama masih ada HP v1.0.6. Respons stok baru memunculkan satuan sekunder di v1.0.6, yang memotong
kuantitas checkout online dengan `toInt()` (faktor 2,5 menjadi 2: harga dan stok salah) dan mengirim penjualan offline
tanpa `unitId`. `main` Android sesudah B1b masih memakai `toInt()` yang sama, jadi B1c wajib memperbaikinya. Rincian ada
di `API_CHANGES.md`.

## Yang perlu disesuaikan di aplikasi Android

1. **Refresh token** — `RefreshTokenAuthenticator` sudah menyimpan refresh token
   baru setiap refresh dan sudah `synchronized`, jadi kompatibel. Pastikan tidak
   ada jalur lain (worker sync, proses lain) yang memakai refresh token secara
   paralel di luar lock tersebut, karena pemakaian ganda dianggap pencurian dan
   memaksa login ulang di **semua** perangkat user. Hal yang sama terjadi bila
   respons refresh hilang di jaringan lalu diulang dengan token lama.
2. **Ganti password** — setelah respons sukses, arahkan user ke layar login
   (sesi lama sudah dicabut di server).
3. **Sync offline** — tangani HTTP 409 kode `OFFLINE_*` sebagai konflik yang
   perlu ditinjau (sudah otomatis lewat kata kunci "business rule"), termasuk
   `OFFLINE_OPENED_AT_INVALID` dari `sessions/sync/open` dan `OFFLINE_CLOSED_AT_INVALID` dari
   `sessions/sync/close`. Sesi offline wajib di-sync dalam `OFFLINE_MAX_SESSION_AGE_HOURS` (default 72 jam); beri peringatan ke kasir sebelum batas itu terlewati.
4. **Satuan sekunder di sync offline** — kirim `unitId` dan `baseQuantity` per item; kontrak lengkap dan contoh
   payload ada di `API_CHANGES.md`. Klien lama dikonversi otomatis hanya bila faktornya tunggal dan hasilnya eksak,
   selain itu 409 `OFFLINE_UNIT_CONFLICT`. Transaksi lama yang terkena bug dideteksi dengan
   `docs/sql/detect_offline_secondary_unit.sql` (hanya baca).
5. **Pembayaran hutang** — kirim `idempotencyKey` (UUID per tombol "Bayar",
   dipakai ulang saat retry) di `PayDebtRequestDto`.
6. **HTTP 429** — tampilkan pesan "coba lagi nanti" untuk `TOO_MANY_ATTEMPTS`
   dan `MANAGER_APPROVER_LOCKED`.
