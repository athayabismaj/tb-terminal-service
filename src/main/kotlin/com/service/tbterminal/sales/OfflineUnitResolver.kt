package com.service.tbterminal.sales

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Satuan sekunder produk: satu [unitId] berisi [factor] satuan dasar. */
internal data class OfflineProductUnit(val unitId: UUID, val factor: BigDecimal)

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

    fun resolve(
        quantity: BigDecimal,
        priceAtTransaction: BigDecimal,
        clientUnit: OfflineItemUnit?,
        baseUnitId: UUID,
        secondaryUnits: List<OfflineProductUnit>,
        expectedBaseUnitPrice: (baseQuantity: BigDecimal) -> BigDecimal,
        historicalBaseUnitPrices: Collection<BigDecimal>
    ): OfflineUnitResolution {
        fun basePrices(factor: BigDecimal) = OfflineCheckoutGuard.matchingBaseUnitPrices(
            priceAtTransaction, quantity, factor, expectedBaseUnitPrice, historicalBaseUnitPrices
        )

        if (clientUnit != null) {
            val unit = (listOf(OfflineProductUnit(baseUnitId, BigDecimal.ONE)) + secondaryUnits)
                .firstOrNull { it.unitId == clientUnit.unitId }
                ?: return OfflineUnitResolution.UnitConflict("satuan yang dikirim bukan satuan produk ini")
            if (quantity.multiply(unit.factor).compareTo(clientUnit.baseQuantity) != 0) {
                return OfflineUnitResolution.UnitConflict(
                    "baseQuantity tidak sama dengan quantity x faktor satuan di server " +
                        "(${unit.factor.stripTrailingZeros().toPlainString()})"
                )
            }
            return toBase(quantity, priceAtTransaction, unit.factor, basePrices(unit.factor)) { base, price ->
                OfflineUnitResolution.Resolved(unit.unitId, unit.factor, base, price, OfflineUnitSource.CLIENT_UNIT)
            }
        }

        val matches = (listOf(BigDecimal.ONE) + secondaryUnits.map(OfflineProductUnit::factor))
            .distinctBy { it.stripTrailingZeros() }
            .map { factor -> factor to basePrices(factor) }
            .filter { (_, prices) -> prices.isNotEmpty() }
        if (matches.size > 1) {
            return OfflineUnitResolution.UnitConflict(
                "harga cocok dengan lebih dari satu satuan, sehingga jumlah satuan dasar tidak pasti"
            )
        }
        val (factor, prices) = matches.singleOrNull() ?: return OfflineUnitResolution.PriceUnknown
        return toBase(quantity, priceAtTransaction, factor, prices) { base, price ->
            if (factor.compareTo(BigDecimal.ONE) == 0) {
                OfflineUnitResolution.Resolved(baseUnitId, factor, base, price, OfflineUnitSource.LEGACY_BASE)
            } else {
                val soldUnitId = secondaryUnits.singleOrNull { it.factor.compareTo(factor) == 0 }?.unitId
                OfflineUnitResolution.Resolved(soldUnitId, factor, base, price, OfflineUnitSource.LEGACY_AUTO)
            }
        }
    }

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
