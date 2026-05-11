package com.service.tbterminal.shared

/**
 * Custom exception classes untuk TB Terminal.
 * Digunakan oleh StatusPages plugin untuk menghasilkan response error yang konsisten.
 * Jangan return stack trace ke client — return pesan error yang aman.
 */

/** Data tidak ditemukan di database */
class NotFoundException(message: String = "Data tidak ditemukan") : RuntimeException(message)

/** User tidak memiliki akses ke resource ini */
class ForbiddenException(message: String = "Akses ditolak") : RuntimeException(message)

/** User belum terautentikasi (Token invalid/tidak ada) */
class UnauthorizedException(message: String = "Sesi tidak valid atau telah berakhir") : RuntimeException(message)

/** Input dari request body tidak valid */
class ValidationException(message: String = "Validasi gagal") : RuntimeException(message)

/** Stok produk tidak mencukupi untuk transaksi */
class StockInsufficientException(message: String = "Stok tidak mencukupi") : RuntimeException(message)

/** Limit kredit pelanggan terlampaui */
class CreditLimitExceededException(message: String = "Limit kredit terlampaui") : RuntimeException(message)

/** Username sudah dipakai oleh user lain */
class UsernameTakenException(message: String = "Username sudah digunakan") : RuntimeException(message)

/** SKU produk sudah dipakai oleh produk lain */
class SkuDuplicateException(message: String = "SKU sudah digunakan") : RuntimeException(message)

/** Cash session / shift kasir tidak ditemukan atau tidak aktif */
class SessionNotFoundException(message: String = "Sesi kasir tidak aktif") : RuntimeException(message)
