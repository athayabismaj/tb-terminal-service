# AGENT.md — TB Terminal Backend

> Dokumen ini adalah panduan wajib bagi AI agent (Claude Code, GitHub Copilot, Cursor, dll.)
> sebelum menyentuh satu baris kode pun di project ini.
> **Baca seluruh dokumen ini terlebih dahulu. Jangan skip.**
>
> Versi  : 1.0.0
> Update : Perbaikan kritis — Flyway, suspend repository, Cloudinary, BCrypt async

---

## 📋 Daftar Isi

1. [Identitas Project](#1-identitas-project)
2. [Tech Stack](#2-tech-stack)
3. [Struktur Database](#3-struktur-database)
4. [Struktur Folder](#4-struktur-folder)
5. [Aturan Wajib AI Agent](#5-aturan-wajib-ai-agent)
6. [Konvensi Kode](#6-konvensi-kode)
7. [Roadmap Backend](#7-roadmap-backend)
8. [Endpoint API](#8-endpoint-api)
9. [Pola yang Digunakan](#9-pola-yang-digunakan)
10. [Yang Dilarang](#10-yang-dilarang)
11. [Checklist Sebelum Commit](#11-checklist-sebelum-commit)

---

## 1. Identitas Project

```
Nama Aplikasi : tb-terminal-service
Package Name  : com.service.tbterminal
Tipe          : REST API Backend
Framework     : Ktor (Kotlin)
Database      : PostgreSQL (Multi-Schema — 5 schema)
Migration     : Flyway (WAJIB — bukan SQL manual)
Target Client : Android (Kotlin Jetpack Compose)
Foto Produk   : Cloudinary (bukan file lokal device)
Skala         : UMKM Toko Bangunan — single toko, 5 user aktif
```

---

## 2. Tech Stack

```
Language        : Kotlin 1.9.22
Framework       : Ktor 2.3.7
Server Engine   : Netty (async non-blocking)
ORM             : Exposed 0.44.1
      WAJIB pakai newSuspendedTransaction(Dispatchers.IO)
      bukan transaction {} biasa — lihat Bagian 6.3
Database        : PostgreSQL 15+
Migration       : Flyway 10.x (WAJIB — bukan SQL manual)
Connection Pool : HikariCP 5.0.1
DI              : Koin 3.5.3
Auth            : JWT (ktor-auth-jwt)
PIN Hashing     : BCrypt (jbcrypt 0.4)
      WAJIB dibungkus withContext(Dispatchers.IO)
      lihat Bagian 5.3
Rate Limiting   : ktor-server-rate-limit
Env Management  : dotenv-kotlin 6.4.1
Request Tracing : ktor-server-call-id
Logging         : Logback + ktor-server-call-logging
Validation      : Konform 0.4.0
PDF Generator   : iText 7.2.5
Foto Produk     : Cloudinary SDK (bukan storage lokal)
Build Tool      : Gradle (Kotlin DSL)
JDK             : 17 atau 21
```

### Dependency tambahan yang wajib ada di build.gradle.kts

```kotlin
// Flyway — database migration
implementation("org.flywaydb:flyway-core:10.4.1")
implementation("org.flywaydb:flyway-database-postgresql:10.4.1")

// Cloudinary — foto produk
implementation("com.cloudinary:cloudinary-http44:1.34.0")

// Exposed coroutine support (untuk newSuspendedTransaction)
implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
// Pastikan versi Exposed >= 0.41.1 untuk newSuspendedTransaction
```

---

## 3. Struktur Database

```
Database  : tb_terminal
Schema    : 5 schema
Tabel     : 22 tabel
ENUM      : 9 types (semua di schema system)
Trigger   : 11 trigger (business logic otomatis)
View      : 5 views (siap pakai untuk laporan)
Migration : dikelola Flyway — JANGAN edit manual
```

```
schema: system
  ├── roles              → role pengguna (owner|admin|kasir)
  ├── users              → akun karyawan + bcrypt pin_hash
  ├── audit_logs         → IMMUTABLE — log semua perubahan data
  └── store_settings     → SINGLETON — konfigurasi toko

schema: inventory
  ├── categories         → kategori produk
  ├── units              → satuan (pcs, dus, sak, meter, kg, dll.)
  ├── products           → produk dengan 3 harga (beli, retail, kontraktor)
  │                        Foto menggunakan photo_public_id dari Cloudinary
  │                        BUKAN file lokal device
  ├── unit_conversions   → konversi satuan max 2 level (1 dus = 50 pcs)
  ├── stock              → stok real-time — JANGAN update manual
  ├── stock_adjustments  → opname, koreksi, retur supplier
  └── price_history      → log otomatis via trigger saat harga berubah

schema: sales
  ├── cash_sessions      → shift kasir (buka & tutup)
  ├── transactions       → transaksi penjualan
  ├── transaction_items  → item per transaksi
  └── payments           → pembayaran (tunai|transfer|qris|hutang|dp)

schema: receivable
  ├── customers          → pelanggan + limit kredit
  ├── receivables        → piutang aktif
  └── receivable_payments → cicilan bayar hutang

schema: purchasing
  ├── suppliers          → data supplier/distributor
  ├── purchases          → pembelian barang masuk
  ├── purchase_items     → item pembelian
  ├── supplier_payables  → hutang ke supplier
  └── supplier_payments  → cicilan bayar ke supplier
```

### Trigger penting yang harus dipahami

| Trigger | Dipicu oleh | Efek otomatis |
|---|---|---|
| `trg_transaction_items_update_stock` | INSERT transaction_items | Stok berkurang/bertambah di inventory.stock |
| `trg_purchase_items_update_stock` | INSERT purchase_items | Stok bertambah + price_buy terupdate |
| `trg_receivable_payments_update_status` | INSERT receivable_payments | paid_amount + status piutang terupdate |
| `trg_supplier_payments_update_status` | INSERT supplier_payments | paid_amount + status hutang terupdate |
| `trg_products_log_price_change` | UPDATE products (harga) | INSERT otomatis ke price_history |

> **PERHATIAN AGENT:** Karena trigger berjalan di level database,
> TIDAK PERLU menulis kode update stok atau status pembayaran secara manual.
> Cukup INSERT ke tabel yang benar. Trigger yang menangani sisanya.

### search_path wajib

```sql
SET search_path TO system,inventory,sales,receivable,purchasing,public
```

Ini sudah diset di HikariCP `connectionInitSql`. Jangan set ulang kecuali ada
alasan yang sangat kuat dan sudah dikonfirmasi.

### Flyway Migration — Konvensi Penamaan File

```
src/main/resources/db/migration/
  ├── V1__init_schemas_and_enums.sql
  ├── V2__system_tables.sql
  ├── V3__inventory_tables.sql
  ├── V4__sales_tables.sql
  ├── V5__receivable_tables.sql
  ├── V6__purchasing_tables.sql
  ├── V7__indexes.sql
  ├── V8__trigger_functions.sql
  ├── V9__triggers.sql
  ├── V10__views.sql
  ├── V11__seed_data.sql
  └── V12__add_cloudinary_column.sql  ← contoh perubahan kolom
```

**Aturan file migration Flyway:**
- Format: `V{nomor}__{deskripsi_dengan_underscore}.sql`
- Dua underscore antara nomor dan deskripsi
- Nomor harus berurutan dan TIDAK BOLEH diubah setelah commit
- TIDAK BOLEH edit file migration yang sudah di-apply ke production
- Jika ada perubahan schema → buat file `V{n+1}` baru, jangan edit yang lama
- Setiap file harus idempotent jika memungkinkan

---

## 4. Struktur Folder

```
src/
├── main/
│   ├── kotlin/com/sipos/tbterminal/
│   │   │
│   │   ├── Application.kt              ← entry point, urutan plugin HARUS diikuti
│   │   │
│   │   ├── di/
│   │   │   └── AppModule.kt            ← Koin DI — semua Repository & Service di sini
│   │   │
│   │   ├── plugins/
│   │   │   ├── Database.kt             ← HikariCP + Flyway migration runner
│   │   │   ├── Security.kt             ← JWT auth + rate limiting
│   │   │   ├── Routing.kt              ← register semua route group
│   │   │   ├── Serialization.kt        ← setup JSON
│   │   │   ├── Monitoring.kt           ← CallId + Call Logging
│   │   │   ├── StatusPages.kt          ← centralized error handling
│   │   │   └── Cors.kt                 ← CORS config
│   │   │
│   │   ├── system/
│   │   │   ├── SystemRoutes.kt
│   │   │   ├── SystemService.kt
│   │   │   ├── SystemRepository.kt
│   │   │   ├── SystemTable.kt          ← Exposed Table objects
│   │   │   └── SystemModel.kt          ← data class request/response
│   │   │
│   │   ├── inventory/
│   │   │   ├── InventoryRoutes.kt
│   │   │   ├── InventoryService.kt
│   │   │   ├── InventoryRepository.kt
│   │   │   ├── InventoryTable.kt
│   │   │   └── InventoryModel.kt
│   │   │
│   │   ├── sales/
│   │   │   ├── SalesRoutes.kt
│   │   │   ├── SalesService.kt
│   │   │   ├── SalesRepository.kt
│   │   │   ├── SalesTable.kt
│   │   │   └── SalesModel.kt
│   │   │
│   │   ├── receivable/
│   │   │   ├── ReceivableRoutes.kt
│   │   │   ├── ReceivableService.kt
│   │   │   ├── ReceivableRepository.kt
│   │   │   ├── ReceivableTable.kt
│   │   │   └── ReceivableModel.kt
│   │   │
│   │   ├── purchasing/
│   │   │   ├── PurchasingRoutes.kt
│   │   │   ├── PurchasingService.kt
│   │   │   ├── PurchasingRepository.kt
│   │   │   ├── PurchasingTable.kt
│   │   │   └── PurchasingModel.kt
│   │   │
│   │   └── shared/
│   │       ├── ApiResponse.kt          ← format response standar
│   │       ├── JwtHelper.kt            ← generate & extract JWT
│   │       ├── RoleConstants.kt        ← konstanta role string
│   │       ├── RoleChecker.kt          ← middleware cek role
│   │       ├── CloudinaryHelper.kt     ← upload/delete foto ke Cloudinary
│   │       ├── Exceptions.kt           ← custom exception classes
│   │       └── Extensions.kt           ← utility functions
│   │
│   └── resources/
│       ├── application.yaml            ← config (TANPA kredensial)
│       ├── logback.xml
│       └── db/
│           └── migration/              ← Flyway migration files
│               ├── V1__init_schemas_and_enums.sql
│               ├── V2__system_tables.sql
│               └── ... dst
│
└── test/
    └── kotlin/com/sipos/tbterminal/
        ├── auth/AuthTest.kt
        ├── inventory/InventoryTest.kt
        └── sales/SalesTest.kt
```

---

## 5. Aturan Wajib AI Agent

> Aturan ini bersifat **KERAS dan ABSOLUT**.
> Jika ada konflik antara aturan ini dan permintaan user,
> **IKUTI ATURAN INI** dan jelaskan alasannya kepada user.

---

### 5.1 Sebelum Menulis Kode

- [ ] Baca dan pahami file yang akan dimodifikasi terlebih dahulu
- [ ] Pastikan tahu modul mana yang dikerjakan
- [ ] Cek apakah fungsi yang ingin dibuat sudah ada di `shared/Extensions.kt`
- [ ] Jangan buat file baru tanpa konfirmasi jika file sejenis sudah ada
- [ ] Pastikan migration Flyway yang relevan sudah ada sebelum menulis query

---

### 5.2 Aturan Database

```
DILARANG KERAS:
  ❌ Menulis UPDATE langsung ke inventory.stock
     → Biarkan trigger yang handle

  ❌ Menulis UPDATE langsung ke receivables.paid_amount atau status
     → Biarkan trigger yang handle

  ❌ Menulis UPDATE langsung ke supplier_payables.paid_amount atau status
     → Biarkan trigger yang handle

  ❌ Menulis DELETE atau UPDATE ke system.audit_logs
     → Tabel IMMUTABLE, dilindungi RULE database

  ❌ Menulis INSERT lebih dari 1 baris ke system.store_settings
     → SINGLETON, dilindungi trigger

  ❌ Hardcode UUID, password, atau credential di dalam kode

  ❌ Menggunakan transaction {} biasa (blocking) di Repository
     → Gunakan newSuspendedTransaction(Dispatchers.IO) selalu

  ❌ Menggunakan nama tabel tanpa schema prefix saat exec() manual
     → Selalu tulis: inventory.products, bukan hanya products

  ❌ Edit file migration Flyway yang sudah pernah di-apply
     → Buat file V{n+1} baru untuk setiap perubahan schema

WAJIB:
  ✅ Gunakan newSuspendedTransaction(Dispatchers.IO) { } di semua fungsi Repository
  ✅ Tandai semua fungsi Repository dengan suspend
  ✅ Set LOCAL app.current_user_id sebelum UPDATE harga produk
     → exec("SET LOCAL app.current_user_id = '$userId'")
  ✅ Selalu gunakan COALESCE untuk nullable numeric di query
  ✅ Selalu sertakan pagination (limit + offset) untuk semua endpoint list
  ✅ Perubahan schema → buat file migration Flyway baru, bukan edit SQL manual
```

---

### 5.3 Aturan Keamanan

```
WAJIB:
  ✅ Semua endpoint kecuali /api/auth/login dan /health HARUS
     dibungkus authenticate("jwt-auth") { }

  ✅ Endpoint login HARUS dibungkus rateLimit(RateLimitName("login")) { }

  ✅ Cek role sebelum eksekusi logika bisnis di Service layer

  ✅ Validasi SEMUA input dari request body dengan Konform
     sebelum menyentuh database

  ✅ Jangan return stack trace ke client — return pesan error yang aman

  ✅ Jangan log nilai PIN atau password meski dalam kondisi error

  ✅ Eksekusi fungsi BCrypt (hashing dan verifikasi PIN) HARUS dibungkus
     dengan withContext(Dispatchers.IO) { } agar tidak memblokir
     Netty worker thread. Ini wajib tanpa pengecualian.

     Contoh benar:
     val isValid = withContext(Dispatchers.IO) {
         BCrypt.checkpw(inputPin, storedHash)
     }

     val hash = withContext(Dispatchers.IO) {
         BCrypt.hashpw(pin, BCrypt.gensalt(12))
     }

DILARANG:
  ❌ Membuat endpoint publik tanpa autentikasi selain /health dan /api/auth/login
  ❌ Menyimpan plain text PIN di database
  ❌ Bypass role check dengan alasan "sementara untuk testing"
  ❌ Return data user lain berdasarkan manipulasi request
  ❌ Memanggil BCrypt.hashpw atau BCrypt.checkpw langsung tanpa Dispatchers.IO
```

---

### 5.4 Aturan Koin DI

```
WAJIB:
  ✅ Semua Repository dan Service HARUS didaftarkan di AppModule.kt
  ✅ Gunakan single { } (bukan factory { }) untuk Repository dan Service
  ✅ Inject dependency via get() di AppModule, bukan konstruktor manual
  ✅ Di Route, inject service dengan: val service: XService by inject()

DILARANG:
  ❌ Membuat instance Repository atau Service dengan konstruktor langsung
     → val repo = ProductRepository()  ← SALAH, DILARANG
  ❌ Menyimpan state/mutable data di dalam Repository atau Service
     → Repository dan Service harus stateless sepenuhnya
  ❌ Membuat lebih dari satu Koin module
     → Semua masuk AppModule.kt
```

---

### 5.5 Aturan Response Format

```
SEMUA response HARUS menggunakan ApiResponse standar:

// Sukses — single data
{ "success": true, "data": { ... }, "message": "Berhasil" }

// Sukses — list dengan pagination
{
  "success": true,
  "data": [ ... ],
  "meta": { "total": 100, "page": 1, "per_page": 20, "total_pages": 5 }
}

// Error
{
  "success": false,
  "error": "Pesan error aman untuk client",
  "code": "ERROR_CODE_SCREAMING_SNAKE"
}

Kode error yang sudah ditetapkan:
  VALIDATION_ERROR     → input tidak valid
  NOT_FOUND            → data tidak ditemukan
  UNAUTHORIZED         → belum login
  FORBIDDEN            → role tidak punya akses
  STOCK_INSUFFICIENT   → stok tidak cukup saat transaksi
  CREDIT_LIMIT_EXCEEDED → limit kredit pelanggan terlampaui
  SKU_DUPLICATE        → SKU produk sudah dipakai
  USERNAME_TAKEN       → username sudah dipakai
  RATE_LIMITED         → terlalu banyak request
  SESSION_NOT_FOUND    → shift kasir tidak aktif
  INTERNAL_ERROR       → error server (jangan bocorkan detail)
```

---

### 5.6 Aturan Cloudinary (Foto Produk)

```
Foto produk TIDAK disimpan di storage lokal device atau server.
Semua gambar menggunakan Cloudinary.

WAJIB:
  ✅ Kolom yang dipakai di tabel products adalah photo_public_id
     → Simpan Public ID Cloudinary (contoh: "tb-terminal/products/abc123")
     → BUKAN URL lengkap — URL dibangun di sisi client dari Public ID

  ✅ Proses upload gambar HARUS dilakukan di Dispatchers.IO
     → Cloudinary upload adalah operasi I/O blocking

  ✅ Saat produk dihapus (soft delete), photo_public_id TIDAK perlu dihapus
     → Biarkan sampai ada proses cleanup terjadwal

  ✅ Gunakan CloudinaryHelper.kt untuk semua operasi Cloudinary
     → Jangan panggil Cloudinary SDK langsung dari Route atau Service

DILARANG:
  ❌ Menyimpan URL lengkap Cloudinary di database
     → URL bisa berubah jika konfigurasi berubah. Simpan Public ID saja.
  ❌ Upload gambar langsung dari Route handler
     → Delegasikan ke Service → CloudinaryHelper
  ❌ Hardcode Cloudinary cloud_name, api_key, api_secret di kode
     → Baca dari env["CLOUDINARY_CLOUD_NAME"], dst.
```

---

### 5.7 Aturan Environment & Kredensial

```
DILARANG KERAS:
  ❌ Hardcode URL database, password, JWT secret, Cloudinary key di kode
  ❌ Hardcode di application.yaml langsung tanpa env variable
  ❌ Print atau log nilai dari env[] yang bersifat rahasia

WAJIB:
  ✅ Semua nilai rahasia dibaca dari env["KEY_NAME"] via dotenv
  ✅ Tambahkan key baru ke .env.example dengan komentar penjelasan
  ✅ File .env TIDAK BOLEH di-commit ke Git (sudah ada di .gitignore)
```

---

## 6. Konvensi Kode

### 6.1 Konstanta Role

```kotlin
// shared/RoleConstants.kt
// WAJIB gunakan konstanta ini — JANGAN hardcode string role di tempat lain
object Role {
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val KASIR = "kasir"
}
```

### 6.2 Penamaan

```kotlin
// File → PascalCase
InventoryRoutes.kt
InventoryService.kt
InventoryRepository.kt
InventoryTable.kt
InventoryModel.kt

// Class
class InventoryService(private val repo: InventoryRepository)
class InventoryRepository

// Data class request/response → camelCase field
@Serializable
data class CreateProductRequest(
    val name: String,
    val sku: String,
    val categoryId: String,       // camelCase — bukan category_id
    val baseUnitId: String,
    val priceRetail: Double,
    val priceContractor: Double,
    val priceBuy: Double,
    val minStock: Double,
    val photoPublicId: String?    // Cloudinary public ID, opsional
)

// Fungsi Repository — deskriptif + suspend
suspend fun findAllProducts(page: Int, perPage: Int): List<ProductRow>
suspend fun findProductById(id: UUID): ProductRow?
suspend fun createProduct(req: CreateProductRequest): UUID
suspend fun updateProduct(id: UUID, req: UpdateProductRequest): Boolean
suspend fun deactivateProduct(id: UUID): Boolean  // soft delete, bukan DELETE

// Fungsi Service — return ApiResponse
suspend fun getAllProducts(page: Int, perPage: Int): ApiResponse<List<ProductDto>>
suspend fun getProductById(id: String): ApiResponse<ProductDto>
```

### 6.3 Struktur Repository

```kotlin
// WAJIB: Semua fungsi Repository adalah suspend function
// WAJIB: Semua operasi DB dibungkus newSuspendedTransaction(Dispatchers.IO)
// DILARANG: Menggunakan transaction {} biasa — akan memblokir Netty worker thread

class InventoryRepository {

    suspend fun findAllProducts(page: Int, perPage: Int): List<ProductRow> =
        newSuspendedTransaction(Dispatchers.IO) {
            ProductTable
                .select { ProductTable.isActive eq true }
                .orderBy(ProductTable.name)
                .limit(perPage, offset = ((page - 1) * perPage).toLong())
                .map { ProductRow.fromResultRow(it) }
        }

    suspend fun countProducts(): Int =
        newSuspendedTransaction(Dispatchers.IO) {
            ProductTable
                .select { ProductTable.isActive eq true }
                .count()
                .toInt()
        }

    suspend fun findProductById(id: UUID): ProductRow? =
        newSuspendedTransaction(Dispatchers.IO) {
            ProductTable
                .select { ProductTable.id eq id }
                .singleOrNull()
                ?.let { ProductRow.fromResultRow(it) }
        }

    suspend fun existsBySku(sku: String): Boolean =
        newSuspendedTransaction(Dispatchers.IO) {
            ProductTable
                .select { ProductTable.sku eq sku }
                .count() > 0
        }

    suspend fun createProduct(req: CreateProductRequest): UUID =
        newSuspendedTransaction(Dispatchers.IO) {
            ProductTable.insert {
                it[name]            = req.name
                it[sku]             = req.sku
                it[categoryId]      = UUID.fromString(req.categoryId)
                it[baseUnitId]      = UUID.fromString(req.baseUnitId)
                it[priceRetail]     = req.priceRetail.toBigDecimal()
                it[priceContractor] = req.priceContractor.toBigDecimal()
                it[priceBuy]        = req.priceBuy.toBigDecimal()
                it[minStock]        = req.minStock.toBigDecimal()
                it[photoPublicId]   = req.photoPublicId
            } get ProductTable.id
        }

    // WAJIB: Set user ID sebelum update harga agar trigger price_history bekerja
    suspend fun updateProductPrice(
        id: UUID,
        userId: UUID,
        req: UpdatePriceRequest
    ): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        exec("SET LOCAL app.current_user_id = '$userId'")
        ProductTable.update({ ProductTable.id eq id }) {
            it[priceRetail]     = req.priceRetail.toBigDecimal()
            it[priceContractor] = req.priceContractor.toBigDecimal()
            it[priceBuy]        = req.priceBuy.toBigDecimal()
        } > 0
        // Trigger trg_products_log_price_change auto-INSERT ke price_history
    }
}
```

### 6.4 Struktur Service

```kotlin
// Service berisi logika bisnis — JANGAN tulis query DB di Service
// Semua fungsi Service adalah suspend function

class InventoryService(private val repo: InventoryRepository) {

    suspend fun getAllProducts(page: Int, perPage: Int): ApiResponse<List<ProductDto>> {
        val products = repo.findAllProducts(page, perPage)
        val total    = repo.countProducts()
        return ApiResponse.success(
            data = products.map { ProductDto.from(it) },
            meta = PaginationMeta(
                total      = total,
                page       = page,
                perPage    = perPage,
                totalPages = ceil(total.toDouble() / perPage).toInt()
            )
        )
    }

    suspend fun createProduct(req: CreateProductRequest): ApiResponse<ProductDto> {
        // 1. Validasi input
        val errors = validateCreateProduct(req)
        if (errors.isNotEmpty()) {
            return ApiResponse.error("Validasi gagal: ${errors.joinToString()}", "VALIDATION_ERROR")
        }

        // 2. Cek duplikat SKU
        if (repo.existsBySku(req.sku)) {
            return ApiResponse.error("SKU '${req.sku}' sudah digunakan produk lain", "SKU_DUPLICATE")
        }

        // 3. Simpan ke database
        val id      = repo.createProduct(req)
        val product = repo.findProductById(id)
            ?: return ApiResponse.error("Gagal membuat produk", "INTERNAL_ERROR")

        return ApiResponse.success(ProductDto.from(product), "Produk berhasil dibuat")
    }
}
```

### 6.5 Struktur Route

```kotlin
fun Application.inventoryRoutes() {
    val service: InventoryService by inject()

    routing {
        authenticate("jwt-auth") {
            route("/api/inventory") {

                // GET list — dengan pagination wajib
                get("/products") {
                    val page    = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val perPage = call.request.queryParameters["per_page"]?.toIntOrNull()
                        ?.coerceIn(1, 100) ?: 20   // max 100 per page
                    call.respond(service.getAllProducts(page, perPage))
                }

                get("/products/{id}") {
                    val id = call.parameters["id"]
                        ?: return@get call.badRequest("ID tidak boleh kosong")
                    call.respond(service.getProductById(id))
                }

                post("/products") {
                    call.requireRole(Role.ADMIN, Role.OWNER)
                    val req = call.receive<CreateProductRequest>()
                    call.respond(HttpStatusCode.Created, service.createProduct(req))
                }

                put("/products/{id}") {
                    call.requireRole(Role.ADMIN, Role.OWNER)
                    val id  = call.parameters["id"] ?: return@put call.badRequest()
                    val req = call.receive<UpdateProductRequest>()
                    call.respond(service.updateProduct(id, req))
                }

                delete("/products/{id}") {
                    call.requireRole(Role.OWNER)
                    val id = call.parameters["id"] ?: return@delete call.badRequest()
                    call.respond(service.deactivateProduct(id))
                }
            }
        }
    }
}
```

### 6.6 Flyway Setup di Database.kt

```kotlin
fun Application.configureDatabase() {
    val config = HikariConfig().apply {
        jdbcUrl         = env["DB_URL"]
        username        = env["DB_USER"]
        password        = env["DB_PASSWORD"]
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = env["DB_MAX_POOL"].toIntOrNull() ?: 10
        connectionInitSql = """
            SET search_path TO system,inventory,sales,receivable,purchasing,public
        """.trimIndent()
    }

    val dataSource = HikariDataSource(config)

    // Jalankan Flyway migration SEBELUM Exposed connect
    // Flyway akan create/update schema otomatis sesuai versi migration
    val flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas("system", "inventory", "sales", "receivable", "purchasing")
        .defaultSchema("system")
        .createSchemas(true)
        .load()

    flyway.migrate()  // Auto-detect dan jalankan migration yang belum diapply

    // Setelah Flyway selesai, baru Exposed connect
    Database.connect(dataSource)
}
```

---

## 7. Roadmap Backend

Kerjakan fase ini secara **BERURUTAN**.
Jangan loncat ke fase berikutnya sebelum fase sekarang selesai dan lolos test Postman.

---

### FASE 0 — Foundation ✅ File sudah tersedia

- [x] `build.gradle.kts` dengan semua dependency
- [x] `Application.kt` dengan urutan plugin yang benar
- [x] `AppModule.kt` Koin DI
- [x] `Security.kt` JWT + Rate Limiting
- [x] `Monitoring.kt` CallId + Call Logging
- [x] `.env.example` template kredensial
- [ ] Migration Flyway V1–V11 (konversi dari tb_terminal_schema.sql)
- [ ] Tambah kolom photo_public_id di V12 (ganti photo_filename)

---

### FASE 1 — Infrastruktur Plugin
**Estimasi: 2–3 hari**

#### 1.1 Buat file migration Flyway (konversi dari tb_terminal_schema.sql)

```
Bagi file tb_terminal_schema.sql menjadi file migration terpisah:

V1__init_schemas_and_enums.sql   → CREATE SCHEMA + CREATE TYPE
V2__system_tables.sql             → roles, users, audit_logs, store_settings
V3__inventory_tables.sql          → categories, units, products, dll.
V4__sales_tables.sql              → cash_sessions, transactions, dll.
V5__receivable_tables.sql         → customers, receivables, dll.
V6__purchasing_tables.sql         → suppliers, purchases, dll.
V7__indexes.sql                   → semua CREATE INDEX
V8__trigger_functions.sql         → semua CREATE OR REPLACE FUNCTION
V9__triggers.sql                  → semua CREATE TRIGGER
V10__views.sql                    → semua CREATE VIEW
V11__seed_data.sql                → INSERT data awal (roles, units, categories)
V12__photo_cloudinary.sql         → ALTER TABLE inventory.products
                                    RENAME COLUMN photo_filename TO photo_public_id
```

#### 1.2 Buat file plugin yang belum ada

```
plugins/Database.kt      → HikariCP + Flyway migration runner (lihat 6.6)
plugins/Serialization.kt → ContentNegotiation + JSON config
plugins/StatusPages.kt   → centralized error handler
plugins/Cors.kt          → CORS config
plugins/Routing.kt       → register semua route + GET /health
shared/ApiResponse.kt    → format response standar
shared/RoleConstants.kt  → konstanta Role.OWNER/ADMIN/KASIR
shared/Exceptions.kt     → custom exception classes
shared/Extensions.kt     → requireRole, extractUserId, badRequest, dll.
shared/CloudinaryHelper.kt → wrapper Cloudinary SDK
```

**Checklist Fase 1:**
- [ ] `./gradlew run` berjalan tanpa error
- [ ] Flyway migration berhasil (log: "Successfully applied N migrations")
- [ ] `GET /health` return 200 + status database
- [ ] Log muncul dengan format: `[callId] METHOD /path → STATUS (Xms)`

---

### FASE 2 — Autentikasi
**Estimasi: 2–3 hari**

#### 2.1 Buat Exposed Table Object — schema system

```kotlin
// system/SystemTable.kt
object RolesTable : Table("system.roles") { ... }
object UsersTable : Table("system.users") { ... }
object AuditLogsTable : Table("system.audit_logs") { ... }
object StoreSettingsTable : Table("system.store_settings") { ... }
```

#### 2.2 SystemRepository.kt — semua fungsi suspend

```
suspend fun findUserByUsername(username: String): UserRow?
suspend fun findUserById(id: UUID): UserRow?
suspend fun updateLastLogin(id: UUID)
suspend fun findAllUsers(): List<UserRow>
suspend fun createUser(req: CreateUserRequest): UUID
suspend fun updateUser(id: UUID, req: UpdateUserRequest): Boolean
suspend fun deactivateUser(id: UUID): Boolean
suspend fun resetPin(id: UUID, newPinHash: String): Boolean
```

#### 2.3 SystemService.kt — BCrypt wajib di Dispatchers.IO

```kotlin
suspend fun login(req: LoginRequest): ApiResponse<LoginResponse> {
    val user = repo.findUserByUsername(req.username)
        ?: return ApiResponse.error("Username atau PIN salah", "UNAUTHORIZED")

    if (!user.isActive) {
        return ApiResponse.error("Akun tidak aktif", "UNAUTHORIZED")
    }

    // WAJIB: BCrypt di Dispatchers.IO — operasi berat, jangan blocking thread
    val isValid = withContext(Dispatchers.IO) {
        BCrypt.checkpw(req.pin, user.pinHash)
    }

    if (!isValid) {
        return ApiResponse.error("Username atau PIN salah", "UNAUTHORIZED")
    }

    repo.updateLastLogin(user.id)

    val token = JwtHelper.generateToken(user)
    return ApiResponse.success(
        LoginResponse(token = token, user = UserDto.from(user)),
        "Login berhasil"
    )
}
```

**Checklist Fase 2:**
- [ ] POST /api/auth/login PIN benar → 200 + token
- [ ] POST /api/auth/login PIN salah → 401
- [ ] POST /api/auth/login 6x berturut → 429 (rate limited)
- [ ] GET endpoint tanpa token → 401
- [ ] GET /api/system/users dengan token role kasir → 403
- [ ] Pastikan BCrypt tidak di main thread (test dengan concurrent request)

---

### FASE 3 — Inventory
**Estimasi: 3–4 hari**

```
Endpoint:
GET    /api/inventory/categories           → semua role
POST   /api/inventory/categories           → admin, owner
PUT    /api/inventory/categories/{id}      → admin, owner

GET    /api/inventory/units                → semua role
POST   /api/inventory/units               → admin, owner

GET    /api/inventory/products             → semua role (pagination + filter kategori)
GET    /api/inventory/products/{id}        → semua role
POST   /api/inventory/products             → admin, owner
PUT    /api/inventory/products/{id}        → admin, owner
DELETE /api/inventory/products/{id}        → owner (soft delete: is_active = false)

GET    /api/inventory/stock                → semua role (dari v_stock_detail)
GET    /api/inventory/stock/critical       → admin, owner

POST   /api/inventory/stock/adjustment     → admin, owner (opname / koreksi)

GET    /api/inventory/price-history/{productId} → owner
```

**Checklist Fase 3:**
- [ ] Produk baru terbuat → baris stock otomatis ada (dari trigger atau seed)
- [ ] GET /api/inventory/stock/critical hanya tampil produk qty <= min_stock
- [ ] Foto produk: photo_public_id tersimpan, bukan URL lengkap

---

### FASE 4 — Receivable (Pelanggan)
**Estimasi: 2–3 hari**

```
Endpoint:
GET    /api/receivable/customers               → semua role
GET    /api/receivable/customers/{id}          → semua role
POST   /api/receivable/customers               → admin, owner
PUT    /api/receivable/customers/{id}          → admin, owner
GET    /api/receivable/customers/{id}/credit   → semua role (dari v_customer_credit)

GET    /api/receivable/receivables             → admin, owner
GET    /api/receivable/receivables/{id}        → admin, owner
POST   /api/receivable/receivables/{id}/pay    → admin, owner
```

**Checklist Fase 4:**
- [ ] Cek limit kredit dari v_customer_credit sebelum transaksi hutang
- [ ] POST pay → trigger otomatis update paid_amount + status

---

### FASE 5 — Sales (POS)
**Estimasi: 4–5 hari — Fase terkompleks**

```
Endpoint:
POST   /api/sales/sessions                     → kasir, admin (buka shift)
GET    /api/sales/sessions/current             → kasir, admin
PUT    /api/sales/sessions/{id}/close          → kasir, admin (tutup shift)

POST   /api/sales/transactions                 → kasir, admin
GET    /api/sales/transactions                 → semua role
GET    /api/sales/transactions/{id}            → semua role
GET    /api/sales/transactions/today           → semua role
```

**Logika SalesService.createTransaction() — urutan wajib:**

```
1. Validasi request body (Konform)
2. Cek cash_session aktif untuk user ini
3. Untuk setiap item: cek stok cukup
4. Jika customer_id ada dan status = hutang:
   → Query v_customer_credit
   → Tolak jika total_hutang_baru > remaining_limit
5. Mulai newSuspendedTransaction(Dispatchers.IO):
   a. INSERT ke sales.transactions
   b. INSERT ke sales.transaction_items (trigger update stok otomatis)
   c. INSERT ke sales.payments
   d. Jika hutang/dp: INSERT ke receivable.receivables
6. Return detail transaksi lengkap
```

**Checklist Fase 5:**
- [ ] Transaksi tunai → stok berkurang otomatis (via trigger)
- [ ] Transaksi hutang → piutang terbuat otomatis
- [ ] Stok tidak cukup → return 422 STOCK_INSUFFICIENT
- [ ] Limit kredit terlampaui → return 422 CREDIT_LIMIT_EXCEEDED
- [ ] Tutup shift → difference = closing_cash - system_cash tersimpan
- [ ] 2 kasir transaksi bersamaan → data konsisten (test concurrent)

---

### FASE 6 — Purchasing (Supplier)
**Estimasi: 3–4 hari**

```
Endpoint:
GET    /api/purchasing/suppliers               → semua role
POST   /api/purchasing/suppliers               → admin, owner
PUT    /api/purchasing/suppliers/{id}          → admin, owner

POST   /api/purchasing/purchases               → admin, owner
GET    /api/purchasing/purchases               → admin, owner
GET    /api/purchasing/purchases/{id}          → admin, owner

GET    /api/purchasing/payables                → admin, owner
POST   /api/purchasing/payables/{id}/pay       → admin, owner
```

**Checklist Fase 6:**
- [ ] POST purchase → stok bertambah + price_buy terupdate (via trigger)
- [ ] POST pay → trigger update paid_amount + status hutang

---

### FASE 7 — Reports
**Estimasi: 2–3 hari — Semua endpoint khusus owner**

```
Endpoint:
GET    /api/reports/dashboard          → aggregasi dari semua view
GET    /api/reports/daily-sales        → dari v_daily_sales
GET    /api/reports/stock              → dari v_stock_detail
GET    /api/reports/receivables        → dari v_receivables_active
GET    /api/reports/payables           → dari v_payables_active
GET    /api/reports/cash-session       → rekap shift kasir per hari
```

**Checklist Fase 7:**
- [ ] Semua endpoint return 403 jika diakses role selain owner
- [ ] Dashboard return data real-time

---

### FASE 8 — PDF & Export
**Estimasi: 2 hari**

```
GET  /api/receivable/receivables/{id}/invoice
     → Generate PDF invoice A4 dengan iText 7
     → Return sebagai file download atau base64

GET  /api/sales/transactions/{id}/receipt
     → Return data JSON format struk (cetak thermal di Android)
     → Bukan PDF — printer ESC/POS dihandle di sisi Android
```

---

### FASE 9 — Testing & Deploy
**Estimasi: 3–5 hari**

```
Testing wajib:
- [ ] Semua endpoint di Postman collection berhasil
- [ ] Concurrent test: 2 kasir transaksi bersamaan → stok benar
- [ ] Brute-force test: login 6x → return 429 (rate limited)
- [ ] Role test: kasir akses endpoint owner → return 403
- [ ] Trigger test: purchase → stok bertambah benar
- [ ] Flyway test: hapus database, jalankan ulang → schema terbentuk sempurna

Deploy:
- [ ] ./gradlew buildFatJar
- [ ] Upload JAR ke hosting
- [ ] Set semua env variables di server
- [ ] Jalankan: nohup java -jar *.jar &
- [ ] Aktifkan HTTPS (SSL)
- [ ] Verify semua endpoint dengan URL production
- [ ] Pastikan Flyway migration berjalan di server (cek log)
```

---

## 8. Endpoint API

### Format URL

```
Base URL development : http://localhost:8080
Base URL production  : https://api.yourdomain.com
Prefix semua API     : /api
```

### Tabel Endpoint Lengkap

| Method | Endpoint | Role | Deskripsi |
|---|---|---|---|
| GET | /health | Public | Health check + status DB |
| POST | /api/auth/login | Public + RateLimit | Login username + PIN |
| GET | /api/system/users | owner | Daftar user |
| POST | /api/system/users | owner | Buat user baru |
| PUT | /api/system/users/:id/pin | owner | Reset PIN |
| PUT | /api/system/users/:id/deactivate | owner | Nonaktifkan user |
| GET | /api/system/settings | semua | Baca pengaturan toko |
| PUT | /api/system/settings | owner | Update pengaturan toko |
| GET | /api/inventory/categories | semua | Daftar kategori |
| POST | /api/inventory/categories | admin,owner | Tambah kategori |
| PUT | /api/inventory/categories/:id | admin,owner | Edit kategori |
| GET | /api/inventory/units | semua | Daftar satuan |
| POST | /api/inventory/units | admin,owner | Tambah satuan |
| GET | /api/inventory/products | semua | Daftar produk + pagination |
| GET | /api/inventory/products/:id | semua | Detail produk |
| POST | /api/inventory/products | admin,owner | Tambah produk |
| PUT | /api/inventory/products/:id | admin,owner | Edit produk |
| DELETE | /api/inventory/products/:id | owner | Soft delete produk |
| GET | /api/inventory/stock | semua | Stok semua produk |
| GET | /api/inventory/stock/critical | admin,owner | Produk stok kritis |
| POST | /api/inventory/stock/adjustment | admin,owner | Opname / koreksi |
| GET | /api/inventory/price-history/:id | owner | Histori harga produk |
| GET | /api/receivable/customers | semua | Daftar pelanggan |
| GET | /api/receivable/customers/:id | semua | Detail pelanggan |
| POST | /api/receivable/customers | admin,owner | Tambah pelanggan |
| PUT | /api/receivable/customers/:id | admin,owner | Edit pelanggan |
| GET | /api/receivable/customers/:id/credit | semua | Sisa limit kredit |
| GET | /api/receivable/receivables | admin,owner | Piutang aktif |
| GET | /api/receivable/receivables/:id | admin,owner | Detail piutang |
| POST | /api/receivable/receivables/:id/pay | admin,owner | Catat cicilan |
| GET | /api/receivable/receivables/:id/invoice | admin,owner | Download PDF invoice |
| POST | /api/sales/sessions | kasir,admin | Buka shift |
| GET | /api/sales/sessions/current | kasir,admin | Shift aktif |
| PUT | /api/sales/sessions/:id/close | kasir,admin | Tutup shift |
| POST | /api/sales/transactions | kasir,admin | Buat transaksi |
| GET | /api/sales/transactions | semua | Daftar transaksi |
| GET | /api/sales/transactions/:id | semua | Detail transaksi |
| GET | /api/sales/transactions/:id/receipt | semua | Data struk JSON |
| GET | /api/purchasing/suppliers | semua | Daftar supplier |
| POST | /api/purchasing/suppliers | admin,owner | Tambah supplier |
| PUT | /api/purchasing/suppliers/:id | admin,owner | Edit supplier |
| POST | /api/purchasing/purchases | admin,owner | Catat barang masuk |
| GET | /api/purchasing/purchases | admin,owner | Riwayat pembelian |
| GET | /api/purchasing/purchases/:id | admin,owner | Detail pembelian |
| GET | /api/purchasing/payables | admin,owner | Hutang supplier aktif |
| POST | /api/purchasing/payables/:id/pay | admin,owner | Bayar hutang supplier |
| GET | /api/reports/dashboard | owner | Dashboard ringkasan |
| GET | /api/reports/daily-sales | owner | Penjualan harian |
| GET | /api/reports/stock | owner | Laporan stok |
| GET | /api/reports/receivables | owner | Laporan piutang |
| GET | /api/reports/payables | owner | Laporan hutang supplier |
| GET | /api/reports/cash-session | owner | Rekap shift kasir |

---

## 9. Pola yang Digunakan

### 9.1 Role Check

```kotlin
// shared/Extensions.kt
suspend fun ApplicationCall.requireRole(vararg allowedRoles: String) {
    val role = this.principal<JWTPrincipal>()
        ?.payload?.getClaim("role")?.asString()
        ?: throw AuthenticationException("Token tidak valid")

    if (role !in allowedRoles) {
        throw AuthorizationException("Role '$role' tidak memiliki akses ke resource ini")
    }
}

fun ApplicationCall.extractUserId(): UUID {
    val id = this.principal<JWTPrincipal>()
        ?.payload?.getClaim("user_id")?.asString()
        ?: throw AuthenticationException("Token tidak valid")
    return UUID.fromString(id)
}

fun ApplicationCall.extractRole(): String {
    return this.principal<JWTPrincipal>()
        ?.payload?.getClaim("role")?.asString()
        ?: throw AuthenticationException("Token tidak valid")
}
```

### 9.2 Custom Exceptions

```kotlin
// shared/Exceptions.kt
class AuthenticationException(message: String = "Unauthorized") : Exception(message)
class AuthorizationException(message: String = "Forbidden") : Exception(message)
class NotFoundException(message: String = "Data tidak ditemukan") : Exception(message)
class ValidationException(message: String, val errors: List<String> = emptyList()) : Exception(message)
class BusinessException(message: String, val code: String) : Exception(message)
```

### 9.3 Set User ID untuk Trigger price_history

```kotlin
// WAJIB sebelum setiap UPDATE harga produk
suspend fun updateProductPrice(id: UUID, userId: UUID, req: UpdatePriceRequest) =
    newSuspendedTransaction(Dispatchers.IO) {
        exec("SET LOCAL app.current_user_id = '$userId'")
        ProductTable.update({ ProductTable.id eq id }) {
            it[priceRetail]     = req.priceRetail.toBigDecimal()
            it[priceContractor] = req.priceContractor.toBigDecimal()
            it[priceBuy]        = req.priceBuy.toBigDecimal()
        }
        // Trigger auto-INSERT ke price_history — tidak perlu kode tambahan
    }
```

### 9.4 Health Check yang Benar

```kotlin
// Bukan hanya return "ok" — cek konektivitas database juga
get("/health") {
    try {
        val dbOk = newSuspendedTransaction(Dispatchers.IO) {
            exec("SELECT 1") { true } ?: false
        }
        if (dbOk) {
            call.respond(mapOf("status" to "ok", "database" to "connected", "version" to "1.0.0"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "error", "database" to "disconnected"))
        }
    } catch (e: Exception) {
        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "error", "message" to "Database unreachable"))
    }
}
```

---

## 10. Yang Dilarang

```
❌ Jangan kelola schema database secara manual via file SQL mentah.
   → DILARANG KERAS. Wajib menggunakan Flyway untuk semua perubahan schema.
   → File migrasi di: src/main/resources/db/migration/
   → Format nama: V{nomor}__{deskripsi}.sql
   → File yang sudah di-apply ke production TIDAK BOLEH diedit.

❌ Jangan gunakan transaction {} biasa (blocking) di Repository.
   → DILARANG — akan memblokir Netty worker thread dan crash di concurrent request.
   → WAJIB: suspend fun + newSuspendedTransaction(Dispatchers.IO) { }
   → Tidak ada pengecualian untuk aturan ini.

❌ Jangan lakukan operasi BCrypt di main thread.
   → BCrypt.hashpw dan BCrypt.checkpw adalah operasi CPU-bound berat.
   → WAJIB bungkus dengan withContext(Dispatchers.IO) { ... }

❌ Jangan simpan URL lengkap Cloudinary di database.
   → Simpan hanya photo_public_id (contoh: "tb-terminal/products/abc123")
   → URL dibangun di client dari Public ID

❌ Jangan buat endpoint GraphQL.
   → Project ini REST API murni.

❌ Jangan gunakan @Transactional annotation.
   → Exposed memakai newSuspendedTransaction, bukan annotation gaya Spring Boot.

❌ Jangan hardcode role string di luar RoleConstants.kt.
   → Selalu pakai Role.OWNER, Role.ADMIN, Role.KASIR

❌ Jangan return pin_hash, password_hash, atau credential apapun di response.

❌ Jangan buat lebih dari satu Koin module.
   → Semua dependency masuk AppModule.kt.

❌ Jangan buat instance Repository atau Service dengan konstruktor langsung.
   → val repo = ProductRepository() ← SALAH
   → Gunakan Koin inject()

❌ Jangan gunakan Thread.sleep() atau blocking I/O di Route.
   → Ktor + Netty adalah async. Blocking call akan mematikan server.
   → Gunakan withContext(Dispatchers.IO) { } jika terpaksa.

❌ Jangan commit file .env ke Git.
❌ Jangan hardcode credential di kode atau application.yaml.
❌ Jangan UPDATE manual ke inventory.stock, receivables.paid_amount,
   atau supplier_payables.paid_amount — biarkan trigger database yang handle.
❌ Jangan INSERT lebih dari 1 baris ke system.store_settings.
❌ Jangan UPDATE atau DELETE baris di system.audit_logs.
```

---

## 11. Checklist Sebelum Commit

```
Code Quality:
- [ ] Tidak ada TODO yang belum diselesaikan
- [ ] Tidak ada println() atau System.out yang tertinggal
- [ ] Semua fungsi Repository adalah suspend function
- [ ] Semua operasi DB pakai newSuspendedTransaction(Dispatchers.IO)
- [ ] Semua BCrypt call ada di dalam withContext(Dispatchers.IO)
- [ ] Tidak ada hardcoded credential, UUID, atau URL Cloudinary

Migration:
- [ ] Perubahan schema sudah dibuatkan file migration Flyway baru
- [ ] File migration menggunakan nomor versi berurutan yang benar
- [ ] File migration sudah ditest di database lokal (flyway.migrate() berhasil)

Security:
- [ ] Semua endpoint baru ada authenticate { } kecuali /health dan /api/auth/login
- [ ] Endpoint yang butuh role sudah ada requireRole()
- [ ] Input baru sudah divalidasi dengan Konform sebelum ke database
- [ ] Tidak ada stack trace yang dikembalikan ke client

Database:
- [ ] Tidak ada UPDATE manual ke stok, paid_amount, atau status hutang
- [ ] Tidak ada edit file migration yang sudah pernah di-apply
- [ ] Set LOCAL app.current_user_id sebelum UPDATE harga produk

DI & Architecture:
- [ ] Repository/Service baru sudah didaftarkan di AppModule.kt sebagai single {}
- [ ] Tidak ada instantiasi manual Repository/Service di luar AppModule
- [ ] Response menggunakan format ApiResponse standar

Testing:
- [ ] Endpoint baru sudah ditest di Postman (sukses dan error case)
- [ ] Role restriction sudah dicoba dengan token berbeda
- [ ] Concurrent request sudah dicoba untuk endpoint transaksi
```

---

## 📝 Kritik dan Saran dari Reviewer

> Bagian ini mencatat kritik konstruktif yang diterima selama development
> untuk meningkatkan kualitas arsitektur secara berkelanjutan.

### Perbaikan yang sudah diaplikasikan di v1.0.0

| # | Kritik | Solusi yang Diterapkan |
|---|---|---|
| 1 | Schema manual via SQL mentah adalah praktik amatir | Flyway wajib untuk semua migration |
| 2 | `transaction {}` blocking akan crash server concurrent | `newSuspendedTransaction(Dispatchers.IO)` di semua repository |
| 3 | `photo_filename` = storage lokal device = risiko data loss | `photo_public_id` via Cloudinary |
| 4 | BCrypt blocking di main thread memperlambat login | `withContext(Dispatchers.IO)` wajib untuk semua BCrypt call |

### Saran tambahan yang perlu dipertimbangkan ke depan

| # | Topik | Rekomendasi |
|---|---|---|
| 1 | **Swagger / OpenAPI** | Tambahkan `ktor-server-openapi` untuk dokumentasi endpoint otomatis. Membantu saat integrasi dengan tim Android atau tester |
| 2 | **JWT Expiration** | 24 jam terlalu lama untuk sistem POS. Pertimbangkan 8 jam (satu shift) + refresh token untuk keamanan lebih baik |
| 3 | **HikariCP Pool Size** | `maximumPoolSize = 10` terlalu besar untuk VPS 1 core. Rumus ideal: `(core_count * 2) + disk_spindle`. Untuk 1 core + SSD = 3–5 cukup |
| 4 | **Soft Delete Konsisten** | Semua tabel master (products, customers, suppliers) harus konsisten pakai `is_active = false`, bukan DELETE. Sudah ada di products dan customers, pastikan suppliers juga |
| 5 | **Pagination Default** | Tetapkan default `per_page = 20, max per_page = 100` secara konsisten di semua endpoint list. Jangan biarkan tiap developer tentukan sendiri |
| 6 | **Health Check DB** | `/health` harus cek koneksi database, bukan hanya return "ok". Sudah ada contoh di Bagian 9.4 |
| 7 | **Cloudinary Folder Structure** | Gunakan folder terorganisir: `tb-terminal/{store_id}/products/` untuk memudahkan manajemen jika suatu saat multi-toko |
| 8 | **Logging Level** | Set `WARN` untuk production, `INFO` untuk development. Jangan `DEBUG` di production — log akan membanjiri disk |

---

*Dokumen ini harus diupdate setiap kali ada perubahan arsitektur signifikan.*
*Setiap perubahan harus dicatat di bagian "Kritik dan Saran" dengan nomor versi.*
