package com.service.tbterminal.sales

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Aturan murni (tanpa akses database) untuk memvalidasi snapshot checkout offline.
 *
 * Klien offline boleh menyimpan harga lokal, tetapi server tidak boleh menerima
 * angka apa pun yang konsisten secara aritmetika saja. Setiap nilai uang wajib
 * dapat dijelaskan oleh data server: daftar harga produk saat ini, riwayat harga
 * selama sesi kas berjalan, konfigurasi pajak, dan aturan pembulatan tunai.
 */
internal object OfflineCheckoutGuard {
    /** Kelipatan pembulatan tunai terbesar yang diizinkan pengaturan toko. */
    const val MAX_CASH_ROUNDING_UNIT = 1000

    /** Kelipatan pembulatan yang dapat dipilih di pengaturan toko (selain 0 = nonaktif). */
    private val ALLOWED_ROUNDING_UNITS = listOf(100, 500, 1000)

    /** Toleransi selisih jam perangkat terhadap server. */
    val CLOCK_SKEW: Duration = Duration.ofMinutes(5)

    /**
     * Mencari faktor satuan yang menjelaskan harga offline.
     *
     * @param unitFactors faktor satuan sekunder produk (jumlah satuan dasar per satu satuan sekunder).
     * @param expectedBaseUnitPrice harga satuan dasar menurut server untuk kuantitas dasar tertentu
     *   (sudah memperhitungkan harga kontraktor dan harga grosir).
     * @param historicalBaseUnitPrices harga satuan dasar lama yang masih berlaku ketika sesi kas dibuka.
     * @return faktor yang cocok (1 untuk satuan dasar) atau `null` bila harga tidak dikenali server.
     */
    fun matchUnitFactor(
        priceAtTransaction: BigDecimal,
        quantity: BigDecimal,
        unitFactors: List<BigDecimal>,
        expectedBaseUnitPrice: (baseQuantity: BigDecimal) -> BigDecimal,
        historicalBaseUnitPrices: Collection<BigDecimal>
    ): BigDecimal? {
        if (priceAtTransaction < BigDecimal.ZERO || quantity <= BigDecimal.ZERO) return null
        val price = priceAtTransaction.money()
        val factors = (listOf(BigDecimal.ONE) + unitFactors.filter { it > BigDecimal.ZERO })
            .distinctBy { it.stripTrailingZeros() }

        for (factor in factors) {
            val baseQuantity = quantity.multiply(factor)
            val candidates = buildList {
                add(expectedBaseUnitPrice(baseQuantity))
                addAll(historicalBaseUnitPrices)
            }
            val matched = candidates.any { candidate ->
                candidate >= BigDecimal.ZERO && candidate.multiply(factor).money().compareTo(price) == 0
            }
            if (matched) return factor
        }
        return null
    }

    /**
     * @param settingsChangedDuringSession true bila pengaturan pembulatan diubah setelah sesi kas dibuka,
     *   sehingga perangkat offline mungkin masih memakai aturan lama.
     * @return pesan error atau `null` bila valid.
     */
    fun validateRounding(
        roundingAmount: BigDecimal,
        totalAfterRounding: BigDecimal,
        currentRoundingUnit: Int,
        settingsChangedDuringSession: Boolean
    ): String? {
        val magnitude = roundingAmount.abs()
        if (roundingAmount.stripTrailingZeros().scale() > 2) {
            return "Nilai pembulatan maksimal memiliki 2 angka desimal"
        }
        if (magnitude >= BigDecimal(MAX_CASH_ROUNDING_UNIT)) {
            return "Nilai pembulatan offline melebihi batas pembulatan tunai yang diizinkan"
        }
        if (magnitude.signum() != 0) {
            // Total hasil pembulatan wajib kelipatan salah satu unit yang diizinkan,
            // dan besar pembulatan harus lebih kecil dari unit tersebut.
            val explainable = ALLOWED_ROUNDING_UNITS.any { unit ->
                val unitValue = BigDecimal(unit)
                magnitude < unitValue && totalAfterRounding.remainder(unitValue).signum() == 0
            }
            if (!explainable) {
                return "Nilai pembulatan offline tidak sesuai aturan pembulatan tunai"
            }
        }
        if (settingsChangedDuringSession) return null
        if (currentRoundingUnit <= 0) {
            return if (magnitude.compareTo(BigDecimal.ZERO) == 0) null
            else "Pembulatan tunai tidak aktif, tetapi transaksi offline memuat nilai pembulatan"
        }
        return if (magnitude < BigDecimal(currentRoundingUnit)) null
        else "Nilai pembulatan offline tidak sesuai kelipatan pembulatan toko"
    }

    /** @return pesan error atau `null` bila snapshot pajak sesuai konfigurasi toko. */
    fun validateTaxSnapshot(
        requestTaxEnabled: Boolean,
        requestTaxMode: String?,
        requestTaxRate: BigDecimal,
        storeTaxEnabled: Boolean,
        storeTaxMode: String,
        storeTaxRate: BigDecimal,
        settingsChangedDuringSession: Boolean
    ): String? {
        if (settingsChangedDuringSession) return null
        if (requestTaxEnabled != storeTaxEnabled) {
            return "Pelanggaran business rule pajak: status pajak transaksi offline berbeda dari pengaturan toko"
        }
        if (!storeTaxEnabled) return null
        if (!requestTaxMode.orEmpty().trim().equals(storeTaxMode, ignoreCase = true)) {
            return "Pelanggaran business rule pajak: mode pajak transaksi offline berbeda dari pengaturan toko"
        }
        if (requestTaxRate.compareTo(storeTaxRate) != 0) {
            return "Pelanggaran business rule pajak: tarif pajak transaksi offline berbeda dari pengaturan toko"
        }
        return null
    }

    /** Waktu buka sesi berasal dari jam perangkat, jadi wajib dekat dengan waktu server. */
    fun validateOpenedAt(openedAt: OffsetDateTime, now: OffsetDateTime, maxSessionAge: Duration): String? {
        if (openedAt.isAfter(now.plus(CLOCK_SKEW))) {
            return "Waktu buka sesi kas offline berada di masa depan"
        }
        if (openedAt.isBefore(now.minus(maxSessionAge))) {
            return "Waktu buka sesi kas offline lebih dari ${maxSessionAge.toHours()} jam yang lalu"
        }
        return null
    }

    /**
     * Awal jendela riwayat harga dan perubahan pengaturan yang boleh menjelaskan snapshot offline.
     *
     * [serverRecordedAt] (jam server) membatasi jendela ini sehingga `openedAt` yang dimundurkan
     * tidak bisa menghidupkan harga lama atau melonggarkan validasi pajak dan pembulatan.
     */
    fun trustedWindowStart(
        openedAt: OffsetDateTime,
        serverRecordedAt: OffsetDateTime,
        maxSessionAge: Duration
    ): OffsetDateTime {
        val earliestTrusted = serverRecordedAt.minus(maxSessionAge)
        return if (openedAt.isAfter(earliestTrusted)) openedAt else earliestTrusted
    }

    /** Waktu kejadian offline wajib berada di antara pembukaan sesi kas dan waktu server. */
    fun validateSessionTime(
        event: OfflineSessionEvent,
        occurredAt: OffsetDateTime,
        sessionOpenedAt: OffsetDateTime,
        now: OffsetDateTime
    ): String? {
        if (occurredAt.isBefore(sessionOpenedAt.minus(CLOCK_SKEW))) {
            return "Waktu ${event.label} offline lebih awal dari pembukaan sesi kas (session invalid)"
        }
        if (occurredAt.isAfter(now.plus(CLOCK_SKEW))) {
            return "Waktu ${event.label} offline berada di masa depan"
        }
        return null
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
}
