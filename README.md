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

# 3. Jalankan aplikasi (Flyway akan otomatis mengeksekusi 16 script migrasi dan seed data)
./gradlew run
```

Server Ktor akan berjalan di `http://0.0.0.0:8080`.

> [!NOTE]
> Saat pertama kali dijalankan, migrasi Flyway akan memasukkan satu akun *Owner* default:
> **Username**: `owner`
> **Password**: `owner123`
> **PIN**: `123456`

---

## 📦 Perintah yang Tersedia

| Perintah | Deskripsi |
|---|---|
| `./gradlew run` | Menjalankan server aplikasi di mode *development* |
| `./gradlew build` | Kompilasi proyek dan menjalankan *tests* |
| `./gradlew test` | Menjalankan *Unit Tests* |
| `./gradlew shadowJar`| Membuat *Fat JAR* untuk *production deployment* |
| `./gradlew clean` | Membersihkan *build cache* |

---

## 🔒 Keamanan & RBAC

API ini menerapkan *Role-Based Access Control* ketat:
- **OWNER**: Akses absolut ke seluruh sistem (termasuk menghapus kasir dan melihat laba rugi).
- **ADMIN**: Mengelola master data, stok, dan pembelian, tanpa hak modifikasi akun *Owner*.
- **KASIR**: Terbatas pada pembuatan struk penjualan, manajemen *cart*, dan pembaruan pengaturan printer lokal. Ditolak akses ke analitik omset.

Otorisasi dikendalikan melalui sistem dekorator di Ktor:
```kotlin
call.requireRole(Role.ADMIN, Role.OWNER)
```

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
