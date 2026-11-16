package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal

data class PriceTierRule(
    val minQty: BigDecimal,
    val unitPrice: BigDecimal,
)

object TieredPricingResolver {
    private const val MAX_TIERS_PER_PRODUCT = 10

    fun validateAndSortTiers(tiers: List<ProductPriceTierDto>): List<ProductPriceTierDto> {
        if (tiers.size > MAX_TIERS_PER_PRODUCT) {
            throw ValidationException("Maksimal $MAX_TIERS_PER_PRODUCT tingkat harga grosir per produk")
        }
        val seenMinQty = mutableListOf<BigDecimal>()
        tiers.forEachIndexed { idx, tier ->
            if (tier.minQty <= BigDecimal.ZERO || tier.minQty.scale() > 2) {
                throw ValidationException("Minimal qty tingkat ${idx + 1} harus > 0 (maksimal 2 desimal)")
            }
            if (tier.unitPrice < BigDecimal.ZERO || tier.unitPrice.scale() > 2) {
                throw ValidationException("Harga satuan tingkat ${idx + 1} tidak boleh negatif (maksimal 2 desimal)")
            }
            if (seenMinQty.any { it.compareTo(tier.minQty) == 0 }) {
                throw ValidationException("Minimal qty ${tier.minQty.stripTrailingZeros().toPlainString()} duplikat pada daftar harga grosir")
            }
            seenMinQty += tier.minQty
        }
        return tiers.sortedBy { it.minQty }
    }

    fun resolveUnitPrice(
        baseRetailPrice: BigDecimal,
        contractorPrice: BigDecimal?,
        isContractorCustomer: Boolean,
        qty: BigDecimal,
        tiers: List<PriceTierRule>,
    ): BigDecimal {
        if (isContractorCustomer && contractorPrice != null && contractorPrice > BigDecimal.ZERO) {
            return contractorPrice
        }
        val matchedTier = tiers
            .filter { qty.compareTo(it.minQty) >= 0 }
            .maxByOrNull { it.minQty }
        return matchedTier?.unitPrice ?: baseRetailPrice
    }
}
