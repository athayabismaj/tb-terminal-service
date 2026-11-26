# Perubahan Kontrak API

Untuk tim Android (`tb-terminal-app`), terbaru di atas. Perubahan dari pekerjaan keamanan ada di
`SECURITY_HARDENING.md`.

## Batas angka desimal di request JSON (Fase 4 backend)

Berlaku untuk semua field desimal (`BigDecimalSerializer`: harga, jumlah, uang, faktor, tarif). Nilai yang
melewati salah satu batas berikut kini dijawab **400 `VALIDATION_ERROR`** dengan pesan
"Nilai angka di luar batas yang diizinkan", sebelum perhitungan apa pun:

| Batas | Nilai | Contoh ditolak |
|---|---|---|
| Panjang teks | maks. 64 karakter | `"111…1"` (65 digit) |
| Digit bilangan bulat | maks. 18 | `"1E+18"`, `"1E+3000000"` |
| Digit desimal (skala) | maks. 20 | `"0.000000000000000000001"`, `"1E-3000000"` |

- Sebelumnya nilai seperti `"1E+3000000"` lolos parsing dan bisa memakan CPU sekitar 1,5 detik per nilai di
  `setScale`, lalu dijawab dengan error lain atau 500 (numeric overflow).
- Kolom terbesar di database adalah `NUMERIC(15,2)`, jadi nilai sah dari aplikasi tidak terdampak.
- Teks yang bukan angka tetap dijawab seperti sebelumnya: 400 `INVALID_REQUEST` "Format request tidak valid".
- Android tidak perlu diubah. Aplikasi sudah menampilkan pesan `VALIDATION_ERROR` apa adanya.

## Data satuan (Fase 5b backend, dipakai Fase B1c Android)

Field baru semuanya **aditif**: tidak ada field yang dihapus atau berubah arti. Perubahan perilaku hanya di
`checkout/sync` (bagian 3 dan 4). Migrasi database: `V47`.

### Urutan rilis (wajib)

- **(a)** APK B1c dirilis lebih dulu dan **harus kompatibel dengan backend v1.0.7**: fallback ke `/products` bila
  `baseUnitId` tidak ada di respons stok.
- **(b)** Semua HP diperbarui ke B1c. Antrean sync transaksi lama sebaiknya sudah kosong.
- **(c)** Baru backend 5b di-deploy.

Alasannya, v1.0.6 membaca satuan sekunder langsung dari respons stok, yang selama ini kosong
(`CheckoutViewModel.kt:2815-2826`). Begitu backend 5b aktif:

- Tombol ganti satuan muncul di keranjang v1.0.6. Checkout online mengirim `(quantity × faktor).toInt()` (baris 1916 dan
  2094): 1 dus isi 2,5 terkirim 2, jadi harga dan stok dihitung untuk 2.
- Penjualan offline v1.0.6 dikirim tanpa `unitId` dan `baseQuantity`, jadi satuannya harus ditebak dari harga (bagian 4).

`main` sesudah B1b masih ber-`versionName` 1.0.6. B1c perlu nomor versi baru supaya langkah (b) bisa diperiksa di HP.

### 1. `GET /api/inventory/stock`

Field baru per item `data.data[]`:

| Field | Tipe JSON | Arti |
|---|---|---|
| `baseUnitId` | string UUID, selalu ada | Satuan dasar produk |
| `baseUnitName` | string, selalu ada | Nama satuan dasar (sama dengan `unitName`) |
| `secondaryUnitId` | string UUID, opsional | Satuan sekunder |
| `secondaryUnitName` | string, opsional | Nama satuan sekunder |
| `secondaryUnitFactor` | string desimal, opsional | Jumlah satuan dasar per satu satuan sekunder; eksak, tanpa nol di belakang |

- Ketiga field sekunder ada semua atau tidak ada semua. Produk tanpa satuan sekunder: field **tidak dikirim** (baca
  sebagai `null`), sama seperti `secondaryUnitId` di endpoint produk.
- Hanya konversi yang langsung ke satuan dasar. Konversi lain tidak boleh dipakai menghitung stok.

Dengan satuan sekunder:

```json
{
  "productId": "<uuid produk>",
  "sku": "PAKU-5",
  "productName": "Paku 5 cm",
  "categoryName": "Paku",
  "unitName": "Pcs",
  "quantity": "100.00",
  "minStock": "10.00",
  "priceBuy": "8000.00",
  "priceRetail": "10000.00",
  "priceContractor": "9500.00",
  "isActive": true,
  "baseUnitId": "<uuid pcs>",
  "baseUnitName": "Pcs",
  "secondaryUnitId": "<uuid dus>",
  "secondaryUnitName": "Dus",
  "secondaryUnitFactor": "12"
}
```

Tanpa satuan sekunder, bagian akhirnya:

```json
{
  "isActive": true,
  "baseUnitId": "<uuid pcs>",
  "baseUnitName": "Pcs"
}
```

### 2. Item transaksi

Field baru `unitName` (string, selalu ada) di setiap `data.items[]` milik `GET /api/sales/transactions/{id}`, dan di semua
respons lain yang berbentuk sama (checkout online beserta retry-nya, bayar hutang).

```json
{
  "productId": "<uuid produk>",
  "productName": "Paku 5 cm",
  "unitId": "<uuid pcs>",
  "unitName": "Pcs",
  "quantity": "24.00",
  "priceAtTransaction": "10000.00",
  "cogsAtTransaction": "8000.00",
  "grossLineTotal": "240000.00",
  "discount": "0.00",
  "subtotal": "240000.00"
}
```

- Satuannya adalah satuan yang **tercatat di item**, yaitu satuan dasar saat transaksi, bukan satuan produk sekarang.
  Penjualan offline "2 dus" tampil 24 Pcs.
- Bila satuan itu diganti namanya, yang tampil nama barunya.
- Daftar riwayat `GET /api/sales/transactions` tidak memuat item, jadi tidak berubah. Layar riwayat mengambil `unitName`
  dari detail. Respons `POST /api/sales/checkout/sync` juga tidak memuat item.

### 3. Faktor konversi lama di `POST /api/sales/checkout/sync`

Request dan respons tidak berubah bentuk. Yang berubah: bila faktor diganti atau satuan sekunder dihapus **saat sesi kas
berjalan**, transaksi dengan faktor lama tetap diterima di dalam jendela tepercaya yang sama dengan riwayat harga.

| Kondisi | Sebelum | Sesudah |
|---|---|---|
| `unitId` + `baseQuantity` memakai faktor lama di dalam jendela | 409 `OFFLINE_UNIT_CONFLICT` | Diterima |
| Satuan sekunder sudah dihapus, di dalam jendela | 409 `OFFLINE_UNIT_CONFLICT` | Diterima |
| Klien lama, harga cocok hanya dengan faktor lama di dalam jendela | 409 `OFFLINE_PRICE_CONFLICT` | Diterima dan dikonversi |
| Klien lama, harga cocok dengan faktor lama **dan** faktor/satuan lain | Dikonversi dengan faktor saat ini | 409 `OFFLINE_UNIT_CONFLICT` (tidak pasti) |
| Klien baru, faktor lama di luar jendela atau menuju satuan dasar yang berbeda | 409 | 409 (tidak berubah) |

- Harga dan `baseQuantity` wajib memakai faktor yang sama; campuran ditolak 409 `OFFLINE_PRICE_CONFLICT`.
- `{faktor}` di pesan konflik adalah faktor saat ini (bila satuannya sudah dihapus: faktor terakhir di dalam jendela).
- `unitFactor` di audit `OFFLINE_ITEM_UNIT` adalah faktor yang benar-benar dipakai.
- Perubahan faktor sebelum V47 tidak tercatat, jadi tidak bisa dikenali.

### 4. Item tanpa `unitId` (klien lama) yang jumlahnya tidak pasti

Seluruh riwayat faktor **dan** harga, termasuk di luar jendela sesi, kini dipakai **hanya untuk menolak**. Bila harga item
bisa dijelaskan oleh lebih dari satu faktor (faktor 1 = satuan dasar ikut dihitung), jawabannya 409
`OFFLINE_UNIT_CONFLICT` dengan alasan "harga cocok dengan lebih dari satu satuan, sehingga jumlah satuan dasar tidak
pasti". Riwayat di luar jendela tidak pernah membuat harga atau faktor menjadi sah.

| Celah | Contoh | Sebelum | Sesudah |
|---|---|---|---|
| 1. Produk Rp0 yang punya **atau pernah** punya satuan sekunder | 2 dus @ 0, dus sudah dihapus | Diterima sebagai 2 pcs bila dus sudah dihapus di luar jendela | 409, di dalam maupun di luar jendela |
| 2. Harga satuan dasar sekarang = harga dus lama | Dulu 12 × 100; dus dihapus, harga pcs 1.200 | Diterima sebagai pcs | 409 |
| 3. Satuan diganti, harga dusnya sama | Dulu 12 × 100, sekarang 24 × 50 | Dikonversi dengan faktor 24 | 409 |
| 4. Satuan dasar produk diganti | Antrean "3 pcs @ 100", satuan dasar kini dus | Bisa diterima sebagai 3 dus | **Belum ditutup**, lihat `BACKLOG.md` |

- Hanya item **tanpa** `unitId` dan `baseQuantity`. Klien yang mengirim keduanya tidak terpengaruh.
- Harga Rp0 pada produk yang **tidak pernah** punya satuan sekunder tetap diterima, karena hanya ada satu tafsir.
- Konsekuensi: pada produk seperti celah 2 dan 3, penjualan sah dari klien lama juga ditolak sampai APK diperbarui.
- Keterbatasan: perubahan faktor sebelum V47 dan perubahan harga grosir tidak tercatat, jadi tidak bisa ikut menolak.

### Daftar kerja Android (B1c)

1. `StockDetailResponseDto` sudah punya field satuan; tambah `baseUnitName: String? = null`.
2. **Wajib kompatibel dengan backend v1.0.7** (urutan rilis langkah a): bila `baseUnitId` **tidak** ada di respons stok,
   tetap ambil `/products` dan `/units` seperti sekarang.
3. Bila `baseUnitId` ada, anggap data satuannya lengkap: `secondaryUnitId` kosong berarti produk **tidak punya** satuan
   sekunder, jadi hapus dari Room dan jangan mengambil `/products` untuk data satuan. `main` sesudah B1b mempertahankan
   nilai lama bila null (`InventoryRepository.kt:806-856`, `ProductLocalDataSource.kt:52-56`) dan tidak lagi mengambil
   `/products` begitu `baseUnitId` dikirim, sehingga satuan yang dihapus di server tertinggal di cache. Logika ini tidak
   ada di v1.0.6.
4. `TransactionItemDto` (dan model di bawahnya): tambah `unitName: String? = null`, pakai di struk dan invoice. Hapus
   cadangan "pcs" untuk item transaksi dari server.
5. Simpan faktor satuan **saat checkout dibuat**; jangan menghitung ulang `baseQuantity` saat sync (lihat B1b poin 4).
6. Hapus `(quantity × faktor).toInt()` di preview dan checkout online (`CheckoutViewModel.kt:1920, 2098` di `main`); kirim
   `BigDecimal` eksak, atau tolak bila hasilnya lebih dari 2 desimal. Tanpa ini, risiko di bawah tetap ada di B1c.

## Satuan sekunder di sync checkout offline (Fase 5 backend, dipakai Fase B1b Android)

Endpoint: `POST /api/sales/checkout/sync`. Aditif: klien lama tetap bisa sync. Tidak ada migrasi database.

### Field baru per item

| Field | Tipe JSON | Arti |
|---|---|---|
| `unitId` | string UUID, opsional | Satuan yang dijual: satuan dasar **atau** satuan sekunder produk itu |
| `baseQuantity` | string desimal, opsional | `quantity × faktor satuan`, **tanpa pembulatan** |

- Keduanya dikirim bersama atau tidak sama sekali.
- Arti `quantity`, `priceAtTransaction`, dan `subtotal` **tidak berubah**: tetap dalam satuan yang dijual.
  Total transaksi, pajak, dan pembulatan dihitung seperti biasa.

### Contoh

2 dus (isi 12, harga dasar Rp10.000) dan 3 pcs, dalam satu transaksi:

```json
{
  "clientGeneratedId": "3f0f1a52-6a0e-4f0a-9d55-0d3f5b0a7c11",
  "deviceId": "kasir-1",
  "localTransactionCode": "LOCAL-0001",
  "cashierUserId": "<uuid kasir>",
  "cashSessionId": "<uuid sesi kas server>",
  "paymentMethod": "tunai",
  "subtotal": "270000.00",
  "discount": "0.00",
  "total": "270000.00",
  "paidAmount": "270000.00",
  "remainingAmount": "0.00",
  "occurredAt": "2026-11-24T10:15:00+07:00",
  "items": [
    {
      "productId": "<uuid produk>",
      "productNameSnapshot": "Paku 5 cm",
      "quantity": "2.00",
      "priceAtTransaction": "120000.00",
      "cogsAtTransaction": "0.00",
      "discount": "0.00",
      "subtotal": "240000.00",
      "unitId": "<uuid dus = secondaryUnitId>",
      "baseQuantity": "24"
    },
    {
      "productId": "<uuid produk>",
      "productNameSnapshot": "Paku 5 cm",
      "quantity": "3.00",
      "priceAtTransaction": "10000.00",
      "cogsAtTransaction": "0.00",
      "discount": "0.00",
      "subtotal": "30000.00",
      "unitId": "<uuid pcs = baseUnitId>",
      "baseQuantity": "3"
    }
  ]
}
```

Hasil: stok berkurang 27 pcs. Transaksi menyimpan 24 pcs × 10.000 dan 3 pcs × 10.000; subtotal dan total
tetap persis. Respons sync tidak berubah.

### Aturan jawaban

| Kondisi item | Jawaban |
|---|---|
| Hanya salah satu field dikirim | 400 `VALIDATION_ERROR`: unitId dan baseQuantity wajib dikirim bersamaan |
| `unitId` bukan UUID | 400 `VALIDATION_ERROR`: Format Unit ID tidak valid |
| `baseQuantity` bukan angka | 400 `INVALID_REQUEST`: Format request tidak valid |
| `unitId` bukan satuan produk itu | 409 `OFFLINE_UNIT_CONFLICT` |
| `baseQuantity` ≠ `quantity` × faktor di server (saat ini atau, sejak V47, faktor lama di dalam jendela sesi) | 409 `OFFLINE_UNIT_CONFLICT` |
| Jumlah satuan dasar lebih dari 2 desimal | 409 `OFFLINE_UNIT_CONFLICT` |
| Subtotal bergeser setelah diubah ke satuan dasar | 409 `OFFLINE_UNIT_CONFLICT` |
| Harga tidak sah **untuk satuan yang dikirim** | 409 `OFFLINE_PRICE_CONFLICT` (tidak berubah) |

Klien lama (tanpa kedua field):

| Harga cocok dengan | Jawaban |
|---|---|
| Satuan dasar saja | Diterima, perilaku tidak berubah |
| Tepat satu faktor selain 1, hasil eksak | Diterima dan dikonversi otomatis |
| Lebih dari satu faktor (termasuk satuan dasar; mis. produk berharga 0 yang punya satuan sekunder). Sejak Fase 5b riwayat faktor dan harga ikut dihitung | 409 `OFFLINE_UNIT_CONFLICT` |
| Satu faktor selain 1, hasil tidak eksak | 409 `OFFLINE_UNIT_CONFLICT` |

Teks 409: `Pelanggaran business rule satuan: {produk}: {alasan}`. Alasan yang mungkin:

- satuan yang dikirim bukan satuan produk ini
- baseQuantity tidak sama dengan quantity x faktor satuan di server ({faktor})
- harga cocok dengan lebih dari satu satuan, sehingga jumlah satuan dasar tidak pasti
- jumlah dalam satuan dasar melebihi 2 angka desimal
- subtotal tidak sama persis setelah diubah ke satuan dasar

Jawaban `DUPLICATE` diperiksa sebelum semua aturan di atas, jadi transaksi yang sudah tersimpan tetap aman diulang.

### Yang berubah di sisi baca

- `GET` transaksi, struk server, riwayat, CSV, dan laporan menampilkan **satuan dasar** (24 pcs × 10.000),
  sama seperti checkout online. Bentuk respons tidak berubah.
- HPP dicatat per satuan dasar (`price_buy`).
- Void dan refund mengembalikan jumlah satuan dasar.
- Bentuk asli ("2 dus") ada di `system.audit_logs` (`transaction_items`, `new_data.event = OFFLINE_ITEM_UNIT`)
  untuk setiap item sync offline, ditulis dalam transaksi database yang sama dengan checkout dan tidak bisa diubah:
  `source` (`CLIENT_UNIT`/`LEGACY_AUTO`/`LEGACY_BASE`), `productId`, `soldUnitId` (null bila tidak pasti),
  `soldQuantity`, `soldUnitPrice`, `unitFactor`, `baseUnitId`, `baseQuantity`, `baseUnitPrice`, `subtotal`.

### Daftar kerja Android (B1b)

1. `OfflineCheckoutSyncItemRequestDto`: tambah `unitId: String? = null` dan `baseQuantity` (nullable).
2. `LocalTransactionItemEntity`: simpan `unitServerId` dan `baseQuantity` saat checkout dibuat (migrasi Room).
   Satuan dasar: `unitServerId` produk lokal (= `baseUnitId`); satuan sekunder: `secondaryUnitId`.
3. **Jangan** memakai `toMoney()` untuk `baseQuantity`; pembulatan membuatnya tidak cocok. Hitung dengan `BigDecimal`
   dan kirim `toPlainString()`.
4. Baris Room lama yang belum punya nilai ini: kirim **tanpa** kedua field. Jangan menghitung dari faktor sekarang,
   karena faktornya mungkin sudah berubah.
5. Tambahkan `OFFLINE_UNIT_CONFLICT` ke `OFFLINE_CONFLICT_CODES` dan `CONFLICT_GUIDANCE_BY_CODE`. Saran teks:
   "Satuan atau isi per satuan berbeda dengan server. Segarkan data produk lalu tinjau transaksi ini."
   Tanpa perubahan ini pun 409 sudah ditandai CONFLICT.
6. Cegah penjualan satuan sekunder offline bila `quantity × faktor` lebih dari 2 desimal; server pasti menolaknya.
