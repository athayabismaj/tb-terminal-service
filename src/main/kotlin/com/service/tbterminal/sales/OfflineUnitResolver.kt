package com.service.tbterminal.sales

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Satuan sekunder produk: satu [unitId] berisi [factor] satuan dasar. */
internal data class OfflineProductUnit(val unitId: UUID, val factor: BigDecimal)

/**
 * Semua faktor dan harga satuan dasar yang pernah tercatat, termasuk di luar jendela sesi.
 * HANYA untuk menolak: harga klien lama yang juga bisa dijelaskan oleh faktor lain berarti jumlah
 * satuan dasarnya tidak pasti. Tidak pernah membuat harga atau faktor menjadi sah.
 */
internal data class OfflineUnitHistory(
    val units: List<OfflineProductUnit>,
    val baseUnitPrices: Collection<BigDecimal>
)

/** Satuan yang dinyatakan klien baru lewat `unitId` dan `baseQuantity`. */
data class OfflineItemUnit(val unitId: UUID, val baseQuantity: BigDecimal)

internal enum class OfflineUnitSource {
    /** Klien mengirim `unitId` dan `baseQuantity`. */
    CLIENT_UNIT,

    /** Klien lama; harganya cocok dengan tepat satu faktor selain 1 sehingga dikonversi server. */
    LEGACY_AUTO,

    /** Klien lama yang menjual dalam satuan dasar. */
    LEGACY_BASE
}

internal sealed interface OfflineUnitResolution {
    data class Resolved(
        /** `null` bila dua satuan sekunder berfaktor sama, sehingga satuan aslinya tidak diketahui. */
        val soldUnitId: UUID?,
        val factor: BigDecimal,
        val baseQuantity: BigDecimal,
        val baseUnitPrice: BigDecimal,
        val source: OfflineUnitSource
    ) : OfflineUnitResolution

    data object PriceUnknown : OfflineUnitResolution

    data class UnitConflict(val reason: String) : OfflineUnitResolution
}

/**
 * Mengubah item checkout offline ke satuan dasar (aturan murni, tanpa akses database).
 *
 * Stok hanya dikenal dalam satuan dasar, sedangkan nilai uang yang sudah dibayar pelanggan tidak
 * boleh bergeser. Karena itu konversi hanya sah bila `baseQuantity × harga dasar` sama persis
 * dengan subtotal dalam satuan asalnya.
 */
internal object OfflineUnitResolver {
    /** Presisi kolom `quantity` di transaksi dan stok. */
    private const val QUANTITY_SCALE = 2

    /**
     * @param secondaryUnits faktor saat ini dan faktor lama yang masih berlaku di dalam jendela sesi;
     *   faktor saat ini wajib lebih dulu karena dipakai di pesan konflik.
     */
    fun resolve(
        quantity: BigDecimal,
        priceAtTransaction: BigDecimal,
        clientUnit: OfflineItemUnit?,
        baseUnitId: UUID,
        secondaryUnits: List<OfflineProductUnit>,
        expectedBaseUnitPrice: (baseQuantity: BigDecimal) -> BigDecimal,
        historicalBaseUnitPrices: Collection<BigDecimal>,
        rejectOnlyHistory: OfflineUnitHistory
    ): OfflineUnitResolution {
        fun basePrices(
            factor: BigDecimal,
            knownPrices: Collection<BigDecimal> = historicalBaseUnitPrices
        ) = OfflineCheckoutGuard.matchingBaseUnitPrices(
            priceAtTransaction, quantity, factor, expectedBaseUnitPrice, knownPrices
        )

        if (clientUnit != null) {
            val factors = if (clientUnit.unitId == baseUnitId) {
                listOf(BigDecimal.ONE)
            } else {
                secondaryUnits.filter { it.unitId == clientUnit.unitId }.map(OfflineProductUnit::factor)
            }
            if (factors.isEmpty()) {
                return OfflineUnitResolution.UnitConflict("satuan yang dikirim bukan satuan produk ini")
            }
            val factor = factors.firstOrNull { quantity.multiply(it).compareTo(clientUnit.baseQuantity) == 0 }
                ?: return OfflineUnitResolution.UnitConflict(
                    "baseQuantity tidak sama dengan quantity x faktor satuan di server " +
                        "(${factors.first().stripTrailingZeros().toPlainString()})"
                )
            return toBase(quantity, priceAtTransaction, factor, basePrices(factor)) { base, price ->
                OfflineUnitResolution.Resolved(clientUnit.unitId, factor, base, price, OfflineUnitSource.CLIENT_UNIT)
            }
        }

        val matches = candidateFactors(secondaryUnits)
            .map { factor -> factor to basePrices(factor) }
            .filter { (_, prices) -> prices.isNotEmpty() }
        val everKnownPrices = historicalBaseUnitPrices + rejectOnlyHistory.baseUnitPrices
        val explanations = candidateFactors(secondaryUnits + rejectOnlyHistory.units)
            .count { factor -> basePrices(factor, everKnownPrices).isNotEmpty() }
        if (matches.isNotEmpty() && explanations > 1) {
            return OfflineUnitResolution.UnitConflict(
                "harga cocok dengan lebih dari satu satuan, sehingga jumlah satuan dasar tidak pasti"
            )
        }
        val (factor, prices) = matches.singleOrNull() ?: return OfflineUnitResolution.PriceUnknown
        return toBase(quantity, priceAtTransaction, factor, prices) { base, price ->
            if (factor.compareTo(BigDecimal.ONE) == 0) {
                OfflineUnitResolution.Resolved(baseUnitId, factor, base, price, OfflineUnitSource.LEGACY_BASE)
            } else {
                val soldUnitId = secondaryUnits.filter { it.factor.compareTo(factor) == 0 }
                    .map(OfflineProductUnit::unitId).distinct().singleOrNull()
                OfflineUnitResolution.Resolved(soldUnitId, factor, base, price, OfflineUnitSource.LEGACY_AUTO)
            }
        }
    }

    private fun candidateFactors(units: List<OfflineProductUnit>): List<BigDecimal> =
        (listOf(BigDecimal.ONE) + units.map(OfflineProductUnit::factor)).distinctBy { it.stripTrailingZeros() }

    private fun toBase(
        quantity: BigDecimal,
        priceAtTransaction: BigDecimal,
        factor: BigDecimal,
        basePrices: List<BigDecimal>,
        resolved: (baseQuantity: BigDecimal, baseUnitPrice: BigDecimal) -> OfflineUnitResolution.Resolved
    ): OfflineUnitResolution {
        if (basePrices.isEmpty()) return OfflineUnitResolution.PriceUnknown
        val baseQuantity = quantity.multiply(factor).stripTrailingZeros()
        if (baseQuantity.scale() > QUANTITY_SCALE) {
            return OfflineUnitResolution.UnitConflict("jumlah dalam satuan dasar melebihi 2 angka desimal")
        }
        val subtotal = priceAtTransaction.multiply(quantity).money()
        val exactPrice = basePrices.firstOrNull { it.multiply(baseQuantity).money().compareTo(subtotal) == 0 }
            ?: return OfflineUnitResolution.UnitConflict("subtotal tidak sama persis setelah diubah ke satuan dasar")
        return resolved(baseQuantity.setScale(QUANTITY_SCALE), exactPrice.money())
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
}
