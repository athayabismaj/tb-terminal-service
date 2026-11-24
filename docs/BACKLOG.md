# Backlog Temuan di Luar Scope

Format: [prioritas] temuan — lokasi — dampak — usulan fase.

## Ditemukan saat `fix/offline-secondary-unit`

- [SEDANG] Angka JSON dengan eksponen besar tidak dibatasi — `shared/Extensions.kt` (`BigDecimalSerializer`) dan pemakai `setScale` (mis. `SalesService.syncOfflineCheckout`) — `"quantity":"1E+3000000"` memakan ± 1,5 detik CPU per nilai (diukur terpisah di JVM, bukan lewat endpoint); user login bisa membebani server — batasi presisi/eksponen di serializer
- [SEDANG] `StockDetailResponse` tidak memuat `secondaryUnitId/Name/Factor`, padahal DTO Android (`StockDetailResponseDto`) membacanya — `inventory/InventoryModels.kt` — data satuan sekunder dari endpoint stok selalu kosong; perlu dicek apakah POS hanya mengandalkan endpoint produk — aditif, setelah Fase B1b
- [SEDANG] Riwayat faktor konversi tidak disimpan (`replaceUnitConversion` menghapus lalu menyisipkan) — `inventory/InventoryProductRepository.kt` — transaksi offline yang antre saat faktor diubah ditolak 409, dan transaksi lama tidak bisa dipastikan oleh skrip deteksi — pertimbangkan tabel riwayat
- [RENDAH] Harga grosir lama tidak masuk jendela harga riwayat (hanya eceran/kontraktor) — `sales/OfflinePricingSupport.kt` — perubahan harga grosir selama sesi offline menghasilkan `OFFLINE_PRICE_CONFLICT` — setelah Fase 2
- [RENDAH] `normalizeMoney`/`money` disalin di dua file baru (`OfflineCheckoutItems.kt`, `OfflineUnitResolver.kt`) — digabung di Fase 1
- [RENDAH] Invoice Android menulis "pcs" bila `unitName` kosong, dan respons transaksi memang tidak memuat nama satuan — repo Android `AdminReceiptDetailScreen.kt` — satuan salah untuk produk non-pcs — tambah `unitName` di respons (aditif)
