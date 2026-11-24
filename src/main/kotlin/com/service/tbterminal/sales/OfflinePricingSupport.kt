package com.service.tbterminal.sales

import com.service.tbterminal.inventory.UnitConversionsTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.select
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** Riwayat perubahan harga produk (diisi trigger `inventory.fn_log_price_history`). */
internal object PriceHistoryTable : Table("inventory.price_history") {
    val id = uuid("id")
    val productId = uuid("product_id")
    val oldPriceRetail = decimal("old_price_retail", 15, 2)
    val oldPriceContractor = decimal("old_price_contractor", 15, 2)
    val changedAt = timestampWithTimeZone("changed_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Konteks harga yang dipakai server untuk memvalidasi harga checkout offline.
 * Wajib dimuat di dalam transaksi database yang sedang berjalan.
 */
internal data class OfflinePricingContext(
    val tierPricing: SalesTierPricingContext,
    private val unitsByProduct: Map<UUID, List<ProductUnitRow>>,
    val historicalPricesByProduct: Map<UUID, Set<BigDecimal>>
) {
    /** Hanya konversi yang bermuara ke satuan dasar produk yang boleh menentukan stok. */
    fun secondaryUnits(productId: UUID, baseUnitId: UUID): List<OfflineProductUnit> =
        unitsByProduct[productId].orEmpty()
            .filter { it.toUnitId == baseUnitId }
            .map(ProductUnitRow::unit)
}

internal data class ProductUnitRow(val unit: OfflineProductUnit, val toUnitId: UUID)

internal fun loadOfflinePricingContext(
    customerId: UUID?,
    productIds: List<UUID>,
    windowStart: OffsetDateTime
): OfflinePricingContext {
    val distinctIds = productIds.distinct()
    val tierPricing = loadSalesTierPricingContext(customerId, distinctIds)
    if (distinctIds.isEmpty()) {
        return OfflinePricingContext(tierPricing, emptyMap(), emptyMap())
    }

    val units = UnitConversionsTable
        .select { UnitConversionsTable.productId inList distinctIds }
        .groupBy(
            keySelector = { it[UnitConversionsTable.productId] },
            valueTransform = {
                ProductUnitRow(
                    unit = OfflineProductUnit(it[UnitConversionsTable.fromUnitId], it[UnitConversionsTable.factor]),
                    toUnitId = it[UnitConversionsTable.toUnitId]
                )
            }
        )

    // Harga lama yang masih berlaku di awal jendela tepercaya: perangkat offline mungkin
    // belum menerima perubahan harga yang terjadi setelah sesi dimulai.
    val historicalPrices = PriceHistoryTable
        .select {
            (PriceHistoryTable.productId inList distinctIds) and
                (PriceHistoryTable.changedAt greaterEq windowStart)
        }
        .groupBy(keySelector = { it[PriceHistoryTable.productId] })
        .mapValues { (_, rows) ->
            buildSet {
                rows.forEach { row ->
                    add(row[PriceHistoryTable.oldPriceRetail])
                    val oldContractor = row[PriceHistoryTable.oldPriceContractor]
                    if (tierPricing.isContractorCustomer && oldContractor > BigDecimal.ZERO) {
                        add(oldContractor)
                    }
                }
            }
        }

    return OfflinePricingContext(
        tierPricing = tierPricing,
        unitsByProduct = units,
        historicalPricesByProduct = historicalPrices
    )
}
