package com.service.tbterminal.shared

import java.math.BigDecimal

/**
 * Batas angka desimal yang diterima dari klien (JSON).
 *
 * `BigDecimal("1E+3000000")` murah saat di-parse, tetapi `setScale`/aritmetika sesudahnya
 * membangun jutaan digit dan memakan CPU. Nilai di luar batas ditolak sebelum dipakai.
 * Kolom uang/kuantitas terbesar di database adalah NUMERIC(15,2), jadi batas ini jauh di atas
 * kebutuhan bisnis.
 */
object DecimalInputLimits {
    const val MAX_LENGTH = 64
    const val MAX_INTEGER_DIGITS = 18
    const val MAX_SCALE = 20
    const val MESSAGE = "Nilai angka di luar batas yang diizinkan"

    /** Melempar [ValidationException] bila di luar batas; format tidak valid tetap [NumberFormatException]. */
    fun parse(raw: String): BigDecimal {
        if (raw.length > MAX_LENGTH) throw ValidationException(MESSAGE)
        val value = BigDecimal(raw)
        // Panjang dibatasi, jadi exponent raksasa hanya mungkin lewat notasi E: scale menjadi sangat besar/kecil.
        if (value.scale() > MAX_SCALE || value.precision().toLong() - value.scale() > MAX_INTEGER_DIGITS) {
            throw ValidationException(MESSAGE)
        }
        return value
    }
}
