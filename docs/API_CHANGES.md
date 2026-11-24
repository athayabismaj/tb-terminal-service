# Perubahan Kontrak API

Untuk tim Android (`tb-terminal-app`), terbaru di atas. Perubahan dari pekerjaan keamanan ada di
`SECURITY_HARDENING.md`.

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
| `baseQuantity` ≠ `quantity` × faktor di server | 409 `OFFLINE_UNIT_CONFLICT` |
| Jumlah satuan dasar lebih dari 2 desimal | 409 `OFFLINE_UNIT_CONFLICT` |
| Subtotal bergeser setelah diubah ke satuan dasar | 409 `OFFLINE_UNIT_CONFLICT` |
| Harga tidak sah **untuk satuan yang dikirim** | 409 `OFFLINE_PRICE_CONFLICT` (tidak berubah) |

Klien lama (tanpa kedua field):

| Harga cocok dengan | Jawaban |
|---|---|
| Satuan dasar saja | Diterima, perilaku tidak berubah |
| Tepat satu faktor selain 1, hasil eksak | Diterima dan dikonversi otomatis |
| Lebih dari satu faktor (termasuk satuan dasar; mis. produk berharga 0 yang punya satuan sekunder) | 409 `OFFLINE_UNIT_CONFLICT` |
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
