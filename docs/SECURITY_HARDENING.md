# Security Hardening (branch `security-hardening`)

Ringkasan perbaikan keamanan backend beserta dampaknya ke aplikasi Android
(`tb-terminal-app`). Semua perubahan memakai migrasi `V45`.

## 1. Sync checkout offline tidak lagi percaya angka dari perangkat

Endpoint: `POST /api/sales/checkout/sync`

| Data dari perangkat | Sebelum | Sesudah |
|---|---|---|
| `priceAtTransaction` | diterima asal konsisten | wajib cocok dengan harga server: eceran/kontraktor/grosir saat ini, harga lama yang berubah selama sesi kas berjalan, atau harga × faktor satuan sekunder |
| `cogsAtTransaction` | diterima | diabaikan; server memakai `price_buy` × faktor satuan |
| `productNameSnapshot` | dipakai di struk/laporan | diabaikan; server memakai nama produk |
| `roundingAmount` | tanpa batas | \|nilai\| < 1000, total hasil pembulatan wajib kelipatan 100/500/1000, dan bila pengaturan toko tidak berubah selama sesi: \|nilai\| < unit pembulatan toko |
| `taxEnabled/taxMode/taxRate` | bebas | wajib sama dengan pengaturan toko, kecuali pengaturan diubah setelah sesi kas dibuka |
| `occurredAt` | bebas | wajib di antara pembukaan sesi kas (−5 menit) dan waktu server (+5 menit) |

Pelanggaran dijawab **HTTP 409** dengan kode `OFFLINE_PRICE_CONFLICT`,
`OFFLINE_ROUNDING_CONFLICT`, `OFFLINE_TAX_CONFLICT`, atau
`OFFLINE_OCCURRED_AT_INVALID`. Pesan memuat frasa "business rule" sehingga
`SyncErrorClassifier` di aplikasi otomatis menandainya **CONFLICT**.

## 2. Lockout kredensial per user (bukan per deviceId)

- Penghitung gagal kini per **user + lingkup** (`scope:pin`, `scope:password`,
  `scope:manager-approval`). Mengganti `deviceId`/`X-Device-Id` tidak lagi
  mereset hitungan. `deviceId` hanya metadata audit (karakter di luar
  `[A-Za-z0-9._:-]` diganti `_`).
- Penambahan hitungan atomik (`INSERT ... ON CONFLICT DO UPDATE ... RETURNING`).
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
- Bila token yang sudah dirotasi dipakai lagi, seluruh keluarga token dicabut
  dan dicatat di audit (`refresh_token_reuse`).
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
- ID transaksi yang formatnya salah kini dijawab 400, bukan 500.

## 7. Perubahan kontrak di luar keamanan

`GET /api/analytics/sales/report`: `totals.netRevenue` kini selalu dikirim.
Sebelumnya field ini hilang dari JSON saat nilainya sama dengan
`grossRevenue − discountAmount − refundAmount`. Perubahan bersifat aditif;
aplikasi Android tidak perlu diubah.

## Yang perlu disesuaikan di aplikasi Android

1. **Refresh token** — `RefreshTokenAuthenticator` sudah menyimpan refresh token
   baru setiap refresh dan sudah `synchronized`, jadi kompatibel. Pastikan tidak
   ada jalur lain (worker sync, proses lain) yang memakai refresh token secara
   paralel di luar lock tersebut, karena pemakaian ganda dianggap pencurian dan
   memaksa login ulang.
2. **Ganti password** — setelah respons sukses, arahkan user ke layar login
   (sesi lama sudah dicabut di server).
3. **Sync offline** — tangani HTTP 409 kode `OFFLINE_*` sebagai konflik yang
   perlu ditinjau (sudah otomatis lewat kata kunci "business rule").
4. **Satuan sekunder di sync offline** — perangkat mengirim `quantity` dalam
   satuan sekunder (mis. dus) sementara server mencatat stok dalam satuan dasar,
   jadi stok berkurang terlalu sedikit. Server kini mengenali harganya, tetapi
   kontraknya perlu diperbaiki: kirim kuantitas dan harga dalam satuan dasar
   (seperti checkout online), atau tambahkan `unitId`/`baseQuantity` ke payload.
5. **Pembayaran hutang** — kirim `idempotencyKey` (UUID per tombol "Bayar",
   dipakai ulang saat retry) di `PayDebtRequestDto`.
6. **HTTP 429** — tampilkan pesan "coba lagi nanti" untuk `TOO_MANY_ATTEMPTS`
   dan `MANAGER_APPROVER_LOCKED`.
