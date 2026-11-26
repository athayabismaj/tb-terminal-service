# Backlog Temuan di Luar Scope

Format: [prioritas] temuan — lokasi — dampak — usulan fase.

## Ditemukan saat `fix/offline-secondary-unit`

- ~~[SEDANG] Angka JSON dengan eksponen besar tidak dibatasi~~ — **selesai di Fase 4** (`shared/DecimalInputLimits.kt`, lihat `API_CHANGES.md`)
- [RENDAH] Harga grosir lama tidak masuk jendela harga riwayat (hanya eceran/kontraktor) — `sales/OfflinePricingSupport.kt` — perubahan harga grosir selama sesi offline menghasilkan `OFFLINE_PRICE_CONFLICT` — setelah Fase 2. Dikaji di Fase 5b, **tidak** dikerjakan karena tidak sederhana: harga riwayat saat ini tidak melihat kuantitas, jadi memasukkan `unit_price` lama apa adanya membuat harga grosir sah untuk 1 pcs. Juga ada kasus sebaliknya: tier yang **baru ditambahkan** membuat harga eceran perangkat (kuantitas sudah memenuhi tier) ditolak. Usulan: tabel `inventory.price_tier_history` berisi snapshot seluruh daftar tier sebelum diganti (termasuk daftar kosong) + `changed_at`, ditulis `replaceProductPriceTiers` hanya bila berubah dan setelah mengunci baris produk; `OfflineUnitResolver`/`OfflineCheckoutGuard` menerima harga riwayat sebagai fungsi kuantitas dasar: untuk tiap snapshot di jendela, hitung `TieredPricingResolver.resolveUnitPrice(eceran saat ini atau lama, snapshot, qty)`
- [RENDAH] `normalizeMoney`/`money` disalin di dua file baru (`OfflineCheckoutItems.kt`, `OfflineUnitResolver.kt`) — digabung di Fase 1

## Ditemukan saat `feat/unit-data-contract` (Fase 5b)

- [SEDANG] `main` Android sesudah B1b (bukan v1.0.6) berhenti mengambil `/api/inventory/products` begitu endpoint stok mengirim `baseUnitId`, lalu memakai satuan sekunder dari cache bila null — repo Android `InventoryRepository.kt:806-816, 821-823, 851-856`, `ProductLocalDataSource.kt:52-56` — satuan sekunder yang **dihapus** di server tertinggal di cache — wajib di Fase B1c, sebelum rilis
- [SEDANG] Checkout online Android memotong kuantitas satuan sekunder dengan `(quantity × faktor).toInt()` — v1.0.6 `CheckoutViewModel.kt:1916, 2094`; `main` `:1920, 2098` — faktor pecahan (mis. 2,5) membuat harga dan stok dihitung untuk 2; di v1.0.6 baru terpicu setelah backend 5b, di `main` sudah bisa terjadi — wajib di Fase B1c
- [SEDANG] Celah 4 sync offline klien lama: bila satuan **dasar** produk diganti, item tanpa `unitId` ditafsirkan dalam satuan dasar baru (antrean "3 pcs @ 100" diterima sebagai 3 dus bila 100 masih harga sah di jendela sesi) — `sales/OfflineUnitResolver.kt` — stok salah tanpa peringatan; riwayat satuan dasar tidak disimpan — wajibkan `unitId` (flag konfigurasi, default off) setelah semua HP memakai B1c dan antrean sync lama habis. Celah 1–3 (Rp0, harga dasar = harga dus lama, satuan pengganti berharga sama) sudah ditutup di Fase 5b; lihat `API_CHANGES.md`
- [RENDAH] Klien baru: bila satuan dasar diganti sementara satuan sekunder, faktor, dan harganya tetap sama, `baseQuantity` diterima dalam satuan dasar baru — `sales/OfflineUnitResolver.kt` — sangat jarang; klien tidak mengirim satuan dasar — kirim `baseUnitId` per item (aditif) bila diperlukan
- [RENDAH] `main` Android sesudah B1b masih `versionName` 1.0.6 / `versionCode` 6, sama dengan tag v1.0.6 — repo Android `app/build.gradle.kts:97-98` — versi di HP tidak bisa dibedakan — naikkan di B1c
- [SEDANG] `discount` di `GET /api/inventory/stock` selalu 0: kolomnya ada di view, tetapi `toStockDetailResponse` tidak membacanya — `inventory/InventoryStockRepository.kt` — Android menimpa diskon lokal dengan 0 dari endpoint stok — perbaikan satu baris, tetapi mengubah nilai yang diterima Android; perlu persetujuan
- [RENDAH] `GET /api/inventory/stock` dan `GET /api/inventory/products` dipaginasi tanpa `ORDER BY`, dan `limit` tidak dibatasi — `inventory/InventoryStockRepository.kt`, `InventoryProductRepository.kt`, `InventoryService.kt` — baris bisa terlewat atau ganda antarhalaman — urutkan per nama + id
- [RENDAH] `toProductResponse` mengambil konversi per produk (N+1) dan tidak memfilter `to_unit` = satuan dasar — `inventory/InventoryProductRepository.kt` — lambat di daftar produk; bisa berbeda dengan endpoint stok bila ada konversi tidak langsung — pakai `loadProductUnits`
- [RENDAH] Mengubah satuan dasar produk tidak mengubah `inventory.stock.unit_id` maupun jumlah stok — `InventoryProductRepository.updateProduct` — jumlah stok lama ditafsirkan dalam satuan baru — perlu keputusan bisnis (larang bila sudah ada mutasi)
- [RENDAH] `createProduct`/`updateProduct` menulis produk dan harga grosir dalam dua transaksi terpisah, dan mengganti harga grosir tidak mengunci baris produk — `inventory/InventoryService.kt` — kegagalan di tengah menyimpan sebagian — gabungkan dalam satu transaksi
- ~~[RENDAH] `IntegrationTest.test_authenticated_user_can_read_and_update_only_safe_profile_fields` mengasumsikan DB baru~~ — **selesai di Fase 4** (selisih hitungan audit)

## Ditemukan saat `refactor/phase-4-sql-binding`

- ~~[SEDANG] Pool koneksi tidak dikosongkan setelah restore~~ — **dibuktikan di Fase 4**: soft evict sudah ada di
  `BackupService.executeRestore` (`DatabaseHealth.evictConnectionsAfterRestore`), tetapi test lama tetap hijau tanpa evict.
  Test restore kini meminjam semua koneksi pool, membuat prepared statement di server untuk query yang mengembalikan enum
  `system.user_role`, lalu memastikan setelah restore tidak ada koneksi lama yang tersisa. Tanpa evict ada dua gejala:
  (1) `0A000 cached plan must not change result type`: Hikari menutup koneksi itu dan retry Exposed menyembunyikannya;
  (2) `XX000 cache lookup failed for type <oid>` pada INSERT `system.audit_logs` (parameter enum): Hikari **tidak** menganggap
  koneksi rusak, ketiga retry gagal, dan audit restore hilang. Gejala (2) bergantung pada koneksi mana yang sudah memakai
  INSERT itu ≥ 5 kali, jadi tidak dipakai sebagai bukti utama.
- [RENDAH] Soft evict hanya langsung menutup koneksi yang idle. Koneksi yang sedang dipakai request lain saat restore baru ditutup
  setelah dikembalikan, sehingga request itu masih bisa terkena gejala (1)/(2) di atas — `backup/BackupService.kt` — tidak ada
  penjaga yang menolak request lain selama restore `RUNNING` — tambah mode maintenance (tolak request selain health/status
  restore selama restore berjalan), atau dokumentasikan bahwa aplikasi wajib di-restart setelah restore

## Ditemukan saat `docs/release-runbook-1.0.8`

- [SEDANG] deploy/docker-compose.production.yml: APP_VERSION dan tag image ditulis mati (1.0.0) — ganti ke ${APP_VERSION:-1.0.0} dan tag image per versi agar rollback binary mungkin tanpa override; pertimbangkan LOG_LEVEL INFO untuk Flyway.
- [RENDAH] deploy/docker-compose.production.yml tanpa `name:` — nama project (dan nama volume `<project>_postgres-data`) bergantung pada folder/`-p` saat perintah dijalankan; salah folder membuat stack + volume kosong baru — runbook v1.0.8 A1 kini memeriksanya. Jangan sekadar menambah `name:` tanpa menyamakannya dengan project yang sedang jalan: nama berbeda = volume database baru yang kosong.
