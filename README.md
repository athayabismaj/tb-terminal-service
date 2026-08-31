# TB Terminal Service — POS & ERP Backend 🏪

Sistem backend (REST API) modern dan tangguh yang menggerakkan operasional Point of Sale (POS) dan Enterprise Resource Planning (ERP) untuk Toko Bangunan. Dibangun murni menggunakan **Kotlin** dan **Ktor**, dengan jaminan integritas data tingkat tinggi melalui **PostgreSQL**.

![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![Ktor](https://img.shields.io/badge/Ktor-Server-0095D5?logo=ktor&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![Exposed](https://img.shields.io/badge/Exposed-ORM-FF6347)
![Flyway](https://img.shields.io/badge/Flyway-Migrations-CC0200?logo=flyway&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-yellow)

---

## ✨ Fitur Utama

- 🔐 **Two-Tier Authentication** — Keamanan standar tinggi dengan kombinasi *Password* untuk login awal dan *PIN* (Quick Unlock) untuk operasional kasir. Dilindungi oleh enkripsi **BCrypt** dan otorisasi **JWT**.
- 📦 **Inventory Engine** — Pengelolaan master produk, kategori, dan satuan. Dilengkapi *database triggers* PostgreSQL untuk mencegah stok minus dan sinkronisasi HPP (Harga Pokok Penjualan) secara otomatis.
- 🛒 **Sales Transaction (POS)** — Mesin transaksi penjualan atomik. Mendukung pembayaran Tunai, DP, dan Hutang. Menggunakan *row-level locking* (`FOR UPDATE`) untuk mencegah *race condition* saat kasir memproses pesanan ganda.
- 💳 **Receivable Management** — Sistem manajemen piutang pelanggan dengan pelacakan jatuh tempo dan pelunasan bertahap yang dilindungi oleh *Overpayment Guard*.
- 🏢 **Purchasing & Payables** — Mengelola pembelian barang ke *Supplier*, pembaruan HPP secara *real-time*, dan pencatatan hutang toko.
- 📊 **Business Intelligence (Analytics)** — Mengekspos *Database Views* (contoh: `v_daily_sales`, `v_receivables_active`) untuk dasbor agregasi *real-time* tanpa membebani komputasi di sisi aplikasi Kotlin.
- 🛠 **System Management & Audit** — Pengaturan profil toko (*Singleton Settings*), Role-Based Access Control (RBAC) untuk Owner, Admin, dan Kasir, serta *Immutable Audit Logs* di level database.

---

## 🏗️ Arsitektur

Aplikasi dipecah ke dalam modul-modul *domain-driven* menggunakan pola arsitektur bersih (*Clean Architecture*) dan disatukan dengan **Koin** (Dependency Injection).

```
src/main/kotlin/com/service/tbterminal/
├── analytics/           # BI & Dashboard API (DB Views mapping)
├── di/                  # Koin Dependency Injection
├── inventory/           # Manajemen Produk, Kategori, Unit
├── plugins/             # Ktor configs (Routing, Security, CORS)
├── purchasing/          # Transaksi Pembelian & Hutang Supplier
├── receivable/          # Pelanggan & Piutang Penjualan
├── sales/               # Transaksi POS & Penjualan
├── shared/              # Utilitas, Exceptions, ApiResponse, Role Enum
└── system/              # Two-Tier Auth, Users, Roles, Store Settings
```

Setiap modul beroperasi secara independen melalui *layering*:
`Models (Exposed Table + DTO) ➡️ Repository (Data Access) ➡️ Service (Business Logic) ➡️ Routes (REST API)`

---

## 🛠️ Tech Stack

| Layer | Teknologi |
|---|---|
| **Language** | Kotlin 2.0 |
| **Framework** | Ktor Server (Netty) |
| **Database** | PostgreSQL |
| **ORM / Query Builder** | JetBrains Exposed |
| **Database Migration** | Flyway |
| **Dependency Injection** | Koin |
| **Security / Auth** | JWT Auth + jBCrypt |
| **Serialization** | `kotlinx.serialization` |
| **Build System** | Gradle (Kotlin DSL) |

---

## 📋 Prasyarat

- **JDK 21** atau lebih baru
- **PostgreSQL 14+** (Pastikan berjalan di port default `5432` atau sesuaikan `.env` / `application.yaml`)
- **Git**

---

## 🚀 Instalasi & Menjalankan

```bash
# 1. Clone repository
git clone https://github.com/athayabismaj/tb-terminal-service.git
cd tb-terminal-service

# 2. Siapkan Database PostgreSQL
# Buat database kosong bernama `tb_terminal_db`
# (Pastikan username dan password postgres sesuai dengan `application.yaml`)

# 3. Jalankan aplikasi (Flyway akan otomatis mengeksekusi seluruh migrasi sampai V34)
./gradlew run
```

Server Ktor akan berjalan di `http://0.0.0.0:8080`.

Migrasi development memiliki akun seed untuk pengujian lokal. Nilainya tidak boleh
dipakai saat deployment. Pada startup production pertama, berikan
`BOOTSTRAP_OWNER_PASSWORD` dan `BOOTSTRAP_OWNER_PIN` melalui secret manager;
hapus keduanya setelah owner berhasil login. Guard production menolak akun aktif
dengan kredensial seed/default dan menolak identitas toko placeholder.

---

## 📦 Perintah yang Tersedia

| Perintah | Deskripsi |
|---|---|
| `./gradlew run` | Menjalankan server aplikasi di mode *development* |
| `./gradlew build` | Kompilasi proyek dan menjalankan *tests* |
| `./gradlew test` | Menjalankan *Unit Tests* |
| `./gradlew buildFatJar`| Membuat *Fat JAR* untuk *production deployment* |
| `./gradlew clean` | Membersihkan *build cache* |

### Backup dan restore PostgreSQL

Backup server memerlukan `pg_dump`/`pg_restore` yang versinya kompatibel dengan
PostgreSQL server. Konfigurasikan `BACKUP_DIRECTORY` ke volume privat di luar
repository, lalu aktifkan jadwal dengan `BACKUP_ENABLED=true`. Retensi dan interval
dikontrol oleh `BACKUP_RETENTION_DAYS` dan `BACKUP_INTERVAL_HOURS`; setiap dump
custom PostgreSQL dicatat bersama ukuran dan checksum SHA-256.

Seluruh list, create, download, validasi, dan konfirmasi restore hanya tersedia bagi
**OWNER**. Alurnya wajib dua tahap: unggah ke endpoint
validasi, lalu kirim token 10 menit, frasa `RESTORE <job-id>`, dan pengakuan downtime
ke endpoint konfirmasi. Create backup dan confirm restore menghasilkan `202 Accepted`;
poll endpoint detail job sampai `SUCCEEDED` atau `FAILED`. Set `RESTORE_ENABLED=true`
hanya dalam jendela maintenance; server membuat safety backup baru sebelum
`pg_restore` dijalankan. Jangan menaruh password database pada command line,
repository, log, atau file dump.

---

## 🔒 Keamanan & RBAC

API ini menerapkan *Role-Based Access Control* ketat:
- **OWNER**: Seluruh operasi, termasuk pengguna/role, konfigurasi keamanan, dan backup/restore database.
- **ADMIN**: Master data, stok, pembelian, piutang, sesi kas, void, audit, dan laporan. Tidak dapat mengelola pengguna/role, keamanan sistem, atau backup/restore.
- **KASIR**: Operasi POS/sesi sendiri, akses piutang operasional yang memang dibutuhkan, dan baca profil toko. Tidak dapat mengubah master data atau melihat laporan manajemen.

Matriks ringkas:

| Area | OWNER | ADMIN | KASIR |
|---|:---:|:---:|:---:|
| Pengguna dan role | Kelola | Ditolak | Ditolak |
| Backup dan restore database | Kelola | Ditolak | Ditolak |
| Security/system settings | Baca | Ditolak | Ditolak |
| Profil toko | Baca/ubah | Baca/ubah | Baca |
| Audit dan laporan | Baca | Baca | Ditolak |
| Produk, kategori, satuan, stok, impor | Kelola | Kelola | Baca |
| Pembelian dan hutang supplier | Kelola | Kelola | Ditolak |
| POS dan sesi kas sendiri | Operasional | Operasional | Operasional |
| Meminta manager approval | Ya | Ya | Ya |
| Menjadi approver | Ya | Ya | Ditolak |

Otorisasi route dan guard service menggunakan permission terpusat:
```kotlin
call.requirePermission(Permission.MANAGE_INVENTORY)
```

Endpoint tanpa JWT memperoleh `401 Unauthorized`; JWT valid tanpa permission
memperoleh `403 Forbidden`. Operasi sensitif pengguna/role, backup/restore, dan
security settings memeriksa permission kembali di service sebelum repository.

### Pemisahan settings

| Konfigurasi | Endpoint/sumber | Akses |
|---|---|---|
| Profil toko global | `GET/PUT /api/system/store-profile` | baca semua role; ubah OWNER/ADMIN |
| Ringkasan keamanan tersanitasi | `GET /api/system/security-settings` | OWNER |
| Operasional server | environment variable sesuai deployment | OWNER melalui konfigurasi server |
| Printer, paper size, auto-print, auto-lock | penyimpanan lokal aplikasi Android | bukan global backend |

`GET/PUT /api/system/settings` masih dipertahankan sementara untuk kompatibilitas
klien lama. Endpoint legacy tersebut memuat `printerSize`, tetapi nilai input itu tidak
lagi dipersistensikan sebagai konfigurasi global; integrasi baru wajib memakai
`/store-profile` dan menyimpan konfigurasi perangkat secara lokal. Respons
`/security-settings` tidak pernah memuat JWT secret, password database, lokasi backup,
atau credential lain.

### Manager Approval

Manager Approval adalah grant otorisasi sementara untuk satu requester, action, dan
resource tertentu. Grant bukan JWT baru, bukan login kedua, dan tidak menaikkan role
requester. Requester selalu berasal dari JWT; approver dicari dari database berdasarkan
username, harus aktif, memiliki `APPROVE_SENSITIVE_ACTION`, dan membuktikan PIN valid.

Alur infrastructure:

```text
Requester JWT → Approval Route → Approval Service → Verifikasi approver + BCrypt PIN
→ Approval Repository → system.manager_approvals + immutable audit log
```

- TTL default 5 menit, dapat diatur dengan `MANAGER_APPROVAL_TTL_MINUTES` dalam batas 1–15 menit.
- Grant hanya berlaku sekali: `APPROVED → USED`; grant kedaluwarsa menjadi `EXPIRED`.
- Konsumsi memakai conditional update atomik sehingga dua request tidak dapat memakai grant yang sama.
- Action berasal dari enum backend; resource type dan UUID harus cocok dengan action.
- Self-approval ditolak dan Kasir tidak dapat menjadi approver.
- PIN hanya diverifikasi dengan BCrypt pada dispatcher I/O dan tidak disimpan pada grant, audit, respons, atau log.

Endpoint pembuatan grant:

```http
POST /api/system/manager-approvals
Authorization: Bearer <requester-access-token>
Content-Type: application/json
```

```json
{
  "action": "VOID_TRANSACTION",
  "resourceType": "TRANSACTION",
  "resourceId": "11111111-2222-3333-4444-555555555555",
  "approverUsername": "manager01",
  "approverPin": "<PIN_MANAGER>"
}
```

Respons tidak memuat credential atau identitas keamanan approver:

```json
{
  "success": true,
  "data": {
    "approvalId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    "action": "VOID_TRANSACTION",
    "resourceType": "TRANSACTION",
    "resourceId": "11111111-2222-3333-4444-555555555555",
    "status": "APPROVED",
    "createdAt": "2026-08-30T05:00:00Z",
    "expiresAt": "2026-08-30T05:05:00Z"
  }
}
```

### Otorisasi Void Transaction

Void adalah pembatalan transaksi yang masih mempertahankan header, item, dan payment
original sebagai histori. Void **bukan Refund**; Refund/Return tidak termasuk Batch 3A.

| Actor | Otorisasi Void |
|---|---|
| OWNER | Langsung melalui permission `VOID_TRANSACTION` |
| ADMIN | Langsung melalui permission `VOID_TRANSACTION` |
| KASIR | Manager Approval `VOID_TRANSACTION` yang scoped ke transaksi target |

Endpoint tetap backward compatible:

```http
POST /api/sales/transactions/{transactionId}/void
Authorization: Bearer <access-token>
Content-Type: application/json
```

OWNER/ADMIN tidak mengirim approval:

```json
{
  "idempotencyKey": "void-device-001",
  "reason": "Kesalahan input transaksi"
}
```

KASIR membuat grant melalui endpoint Manager Approval, lalu mengirim ID grant saja
(PIN manager tidak pernah dikirim ke endpoint Void):

```json
{
  "idempotencyKey": "void-device-002",
  "reason": "Kesalahan input transaksi",
  "managerApprovalId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
}
```

```text
OWNER / ADMIN → direct permission → Void
KASIR → create scoped approval → Void → approval consumed
```

Untuk Kasir, requester grant harus sama dengan actor JWT, action harus
`VOID_TRANSACTION`, resource harus `TRANSACTION` dengan ID transaksi target, dan
grant harus `APPROVED` serta belum kedaluwarsa. Row transaksi dan grant dikunci;
reversal stok/pembayaran/piutang/kas, status `VOIDED`, konsumsi grant, dan audit
berjalan dalam satu transaksi database. Kegagalan langkah mana pun me-rollback
seluruh perubahan dan tidak menghabiskan approval. Retry dengan idempotency key,
reason, transaksi, dan approval yang sama mengembalikan hasil Void sebelumnya.

### Refund / Return Transaction

Refund adalah pengembalian transaksi setelah penjualan terjadi. Refund berbeda dari
Void: keduanya menghasilkan histori kompensasi yang immutable dan tidak pernah
menghapus header, item, atau pembayaran original, tetapi disimpan pada event dan
status terminal yang berbeda (`REFUNDED` versus `VOIDED`). Transaksi yang sudah
Refund tidak dapat di-Void dan sebaliknya.

Batch 3B hanya mendukung **full refund**. Partial refund, penukaran barang, store
credit, voucher, promo, dan integrasi payment gateway belum diimplementasikan.
Nominal tidak diterima dari client; backend menghitung uang yang benar-benar pernah
diterima. Karena itu transaksi HUTANG tanpa pembayaran memiliki `refundedAmount = 0`,
sedangkan DP hanya mengembalikan DP dan cicilan yang sudah tercatat. Sisa piutang
dibatalkan melalui ledger/reversal dan histori lama tetap utuh.

```http
POST /api/sales/transactions/{transactionId}/refund
Authorization: Bearer <access-token>
Content-Type: application/json
```

```json
{
  "idempotencyKey": "refund-device-001",
  "reason": "Barang dikembalikan pelanggan",
  "returnDisposition": "RETURN_TO_STOCK",
  "managerApprovalId": null
}
```

Disposisi yang tersedia:

| Nilai | Dampak stok |
|---|---|
| `RETURN_TO_STOCK` | Barang diterima fisik dan masuk kembali melalui mutasi `REFUND` |
| `NOT_RETURNED` | Barang tidak kembali; stok tidak berubah |
| `DAMAGED` | Barang kembali dalam kondisi rusak/tidak saleable; stok jual tidak berubah |

OWNER dan ADMIN dapat Refund langsung melalui permission `REFUND_TRANSACTION`.
KASIR wajib memakai Manager Approval action `REFUND_TRANSACTION` yang scoped ke
transaksi target. Idempotency diperiksa sebelum status approval `USED`, sehingga
retry payload identik mengembalikan hasil yang sama. Refund record, kompensasi
pembayaran, reversal piutang, koreksi kas, mutasi stok, status transaksi, konsumsi
approval, dan audit ditulis atomik. Pembayaran nontunai hanya dicatat sebagai
kompensasi internal; backend tidak memanggil payment gateway eksternal.

Laporan memisahkan Void dan Refund. Setelah Batch 3C rumusnya adalah
`grossRevenue - discountAmount - refundAmount = netRevenue`;
`financialRefundAmount` menunjukkan uang yang benar-benar dikembalikan, sedangkan
nilai penjualan yang dibatalkan tetap tersedia untuk rekonsiliasi pendapatan.

### Discount (Batch 3C)

Discount adalah pengurangan nilai jual, bukan promotion engine. Scope yang didukung
hanya `ITEM` (field `discountRequest` pada item) dan `TRANSACTION` (field
`transactionDiscount`). Tipe yang didukung hanya `PERCENTAGE` dan `FIXED_AMOUNT`.
Fixed item discount berlaku satu kali pada **line total**, bukan per unit. Field
legacy `discount` tetap diterima sebagai fixed per-unit untuk kompatibilitas dan
dinormalisasi server menjadi snapshot nominal per line.

```text
server selling price x quantity = gross line
gross line - item discount = net line
sum(net line) = subtotal after item discount
subtotal after item discount - transaction discount = final payable amount
```

Client hanya mengirim product, quantity, type, dan value. Harga produk, nominal
discount, effective discount, serta final total selalu dihitung ulang oleh backend
dengan `BigDecimal`, scale uang 2, dan `HALF_UP`. Snapshot transaction item menyimpan
harga asli, gross line, type/value/amount discount, dan net line; header menyimpan
gross subtotal, total item discount, transaction discount, total discount, dan net
total. Histori tidak berubah saat harga atau limit kemudian berubah.

Limit Kasir berada di singleton `system.store_settings.cashier_discount_limit_percent`
(default `10.00`). Effective discount dihitung dari total gabungan item dan transaksi:

```text
effectiveDiscountPercent = totalDiscountAmount / grossSubtotal x 100
```

| Role | Sampai limit Kasir | Di atas limit Kasir |
|---|---|---|
| OWNER | Langsung | Langsung |
| ADMIN | Langsung | Langsung |
| KASIR | Langsung | Manager Approval `DISCOUNT_OVERRIDE` |

Untuk Discount Override, Kasir memanggil preview terlebih dahulu:

```http
POST /api/sales/checkout/preview
```

```json
{
  "items": [{
    "productId": "11111111-2222-3333-4444-555555555555",
    "qty": 2,
    "discountRequest": { "type": "PERCENTAGE", "value": 10 }
  }],
  "transactionDiscount": { "type": "FIXED_AMOUNT", "value": 5000 }
}
```

Preview mengembalikan `checkoutAttemptId`, fingerprint server, nilai kalkulasi,
limit, `approvalRequired`, dan expiry. Jika approval diperlukan, buat Manager
Approval action `DISCOUNT_OVERRIDE`, resource type `TRANSACTION`, resource ID sama
dengan `checkoutAttemptId`, lalu kirim keduanya saat checkout. Final checkout
menghitung ulang harga dan fingerprint; perubahan harga, quantity, atau intent
discount menolak approval lama. Approval dan attempt baru menjadi `USED` setelah
checkout, stok, payment, piutang, kas, dan audit berhasil commit. Retry sukses dengan
idempotency key dan payload identik tetap me-replay hasil sebelum status approval
diperiksa.

Checkout tanpa discount tidak berubah. Contoh transaction-level discount:

```json
{
  "idempotencyKey": "checkout-device-001",
  "items": [{ "productId": "11111111-2222-3333-4444-555555555555", "qty": 1 }],
  "transactionDiscount": { "type": "PERCENTAGE", "value": 5 },
  "paymentMethod": "tunai",
  "amountPaid": 95000
}
```

Piutang dan kas menggunakan net total. Discount bukan cash expense. Full Refund
maksimal sebesar pembayaran aktual/net dan stock return tetap memakai quantity.
Void juga mengompensasi pembayaran aktual/net, bukan gross. Export transaksi dan
detail penjualan menyertakan snapshot gross/discount/net.

Belum didukung: voucher, coupon, promo engine, loyalty, membership pricing, dynamic
pricing, promo berdasarkan waktu/quantity, bundling, tiered pricing, dan partial
refund. Discount tidak didukung pada offline sync karena harga dan approval harus
divalidasi server secara online.

---

## 🤝 Kontribusi

1. Fork repository ini
2. Buat branch fitur (`git checkout -b fitur/nama-fitur`)
3. Tulis *clean code* dengan memastikan seluruh transaksi atomik berada dalam blok `newSuspendedTransaction { }`
4. Commit perubahan (`git commit -m 'feat: Tambah fitur X'`)
5. Push ke branch (`git push origin fitur/nama-fitur`)
6. Buat *Pull Request*

---

## 📄 Lisensi

Proyek ini dilisensikan di bawah [MIT License](LICENSE).
