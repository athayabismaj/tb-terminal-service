package com.service.tbterminal.sales

import com.service.tbterminal.inventory.UnitConversionsTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
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

/** Faktor konversi lama (diisi trigger `inventory.fn_log_unit_conversion_history`). */
internal object UnitConversionHistoryTable : Table("inventory.unit_conversion_history") {
    val id = uuid("id")
    val productId = uuid("product_id")
    val fromUnitId = uuid("from_unit_id")
    val toUnitId = uuid("to_unit_id")
    val oldFactor = decimal("old_factor", 10, 4)
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
    val historicalPricesByProduct: Map<UUID, Set<BigDecimal>>,
    private val everUnitsByProduct: Map<UUID, List<ProductUnitRow>>,
    private val everPricesByProduct: Map<UUID, Set<BigDecimal>>
) {
    /**
     * Hanya konversi yang bermuara ke satuan dasar produk yang boleh menentukan stok. Satu satuan bisa
     * muncul lebih dari sekali: faktor saat ini lebih dulu, lalu faktor lama di dalam jendela tepercaya.
     */
    fun secondaryUnits(productId: UUID, baseUnitId: UUID): List<OfflineProductUnit> =
        unitsByProduct[productId].towards(baseUnitId)

    fun rejectOnlyHistory(productId: UUID, baseUnitId: UUID) = OfflineUnitHistory(
        units = everUnitsByProduct[productId].towards(baseUnitId),
        baseUnitPrices = everPricesByProduct[productId].orEmpty()
    )

    private fun List<ProductUnitRow>?.towards(baseUnitId: UUID): List<OfflineProductUnit> =
        orEmpty().filter { it.toUnitId == baseUnitId }.map(ProductUnitRow::unit)
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
        return OfflinePricingContext(tierPricing, emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }

    // Urutan baca penting: perubahan yang di-commit di antara kedua query tetap terlihat sebagai
    // faktor saat ini (query pertama) sekaligus riwayat, bukan hilang dari keduanya.
    val currentUnits = UnitConversionsTable
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
    // Riwayat dibaca seluruhnya: yang di dalam jendela tepercaya boleh menjelaskan transaksi (perangkat
    // offline mungkin belum menerima perubahan setelah sesi dimulai), sisanya hanya boleh menolak.
    val everUnits = UnitConversionHistoryTable
        .select { UnitConversionHistoryTable.productId inList distinctIds }
        .orderBy(UnitConversionHistoryTable.changedAt to SortOrder.DESC)
        .map {
            Dated(
                it[UnitConversionHistoryTable.productId], it[UnitConversionHistoryTable.changedAt],
                ProductUnitRow(
                    unit = OfflineProductUnit(
                        it[UnitConversionHistoryTable.fromUnitId], it[UnitConversionHistoryTable.oldFactor]
                    ),
                    toUnitId = it[UnitConversionHistoryTable.toUnitId]
                )
            )
        }
    val trustedUnits = everUnits.since(windowStart).byProduct()
    val everPrices = PriceHistoryTable
        .select { PriceHistoryTable.productId inList distinctIds }
        .flatMap { row ->
            val oldContractor = row[PriceHistoryTable.oldPriceContractor]
                .takeIf { tierPricing.isContractorCustomer && it > BigDecimal.ZERO }
            listOfNotNull(row[PriceHistoryTable.oldPriceRetail], oldContractor)
                .map { Dated(row[PriceHistoryTable.productId], row[PriceHistoryTable.changedAt], it) }
        }

    return OfflinePricingContext(
        tierPricing = tierPricing,
        unitsByProduct = distinctIds.associateWith { currentUnits[it].orEmpty() + trustedUnits[it].orEmpty() },
        historicalPricesByProduct = everPrices.since(windowStart).byProduct().mapValues { (_, prices) -> prices.toSet() },
        everUnitsByProduct = everUnits.byProduct(),
        everPricesByProduct = everPrices.byProduct().mapValues { (_, prices) -> prices.toSet() }
    )
}

private data class Dated<T>(val productId: UUID, val changedAt: OffsetDateTime, val value: T)

private fun <T> List<Dated<T>>.since(start: OffsetDateTime) = filter { !it.changedAt.isBefore(start) }

private fun <T> List<Dated<T>>.byProduct(): Map<UUID, List<T>> = groupBy({ it.productId }, { it.value })
