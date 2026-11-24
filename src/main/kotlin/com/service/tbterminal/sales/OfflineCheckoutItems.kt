package com.service.tbterminal.sales

import com.service.tbterminal.inventory.ProductsTable
import com.service.tbterminal.inventory.StockTable
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.StockInsufficientException
import com.service.tbterminal.shared.ValidationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

internal fun resolveItemForOfflineSync(
    item: OfflineCheckoutSyncItemCommand,
    pricingContext: OfflinePricingContext
): ResolvedOfflineItem {
    if (item.quantity <= BigDecimal.ZERO) {
        throw ValidationException("Quantity untuk produk ${item.productId} harus lebih dari 0")
    }
    if (item.quantity.scale() > 2) {
        throw ValidationException("Quantity maksimal memiliki 2 angka desimal")
    }
    if (listOf(
            item.priceAtTransaction, item.cogsAtTransaction, item.grossLineTotal,
            item.discountValue, item.discount, item.subtotal
        ).any { it.scale() > 2 }
    ) {
        throw ValidationException("Nilai uang item checkout maksimal memiliki 2 angka desimal")
    }
    if (item.priceAtTransaction < BigDecimal.ZERO) {
        throw ValidationException("Harga produk ${item.productId} tidak boleh negatif")
    }
    if (item.cogsAtTransaction < BigDecimal.ZERO) {
        throw ValidationException("HPP produk ${item.productId} tidak boleh negatif")
    }
    if (item.discount < BigDecimal.ZERO) {
        throw ValidationException("Diskon produk ${item.productId} tidak boleh negatif")
    }
    if (item.discountType != null || item.discountValue.compareTo(BigDecimal.ZERO) != 0 ||
        item.discount.compareTo(BigDecimal.ZERO) != 0
    ) {
        throw ValidationException("Diskon offline tidak didukung")
    }

    val product = ProductsTable.select {
        (ProductsTable.id eq item.productId) and (ProductsTable.isActive eq true)
    }.forUpdate().singleOrNull()
        ?: throw NotFoundException("Produk dengan ID ${item.productId} tidak ditemukan atau tidak aktif")

    val expectedGross = item.priceAtTransaction.multiply(item.quantity).normalizeMoney()
    if (item.grossLineTotal.normalizeMoney().compareTo(expectedGross) != 0) {
        throw ValidationException("Gross line total sync tidak valid untuk produk ${item.productId}")
    }
    val expectedSubtotal = expectedGross.subtract(item.discount).normalizeMoney()
    val requestedSubtotal = item.subtotal.normalizeMoney()
    if (requestedSubtotal.compareTo(expectedSubtotal) != 0) {
        throw ValidationException("Subtotal item sync tidak valid untuk produk ${item.productId}")
    }

    // Harga offline wajib dikenali dari daftar harga server (harga eceran/kontraktor/grosir
    // saat ini, atau harga lama yang masih berlaku ketika sesi kas dibuka).
    val baseUnitId = product[ProductsTable.baseUnitId]
    val resolution = OfflineUnitResolver.resolve(
        quantity = item.quantity,
        priceAtTransaction = item.priceAtTransaction,
        clientUnit = item.unit,
        baseUnitId = baseUnitId,
        secondaryUnits = pricingContext.secondaryUnits(item.productId, baseUnitId),
        expectedBaseUnitPrice = { baseQuantity ->
            pricingContext.tierPricing.resolveUnitPrice(product, baseQuantity)
        },
        historicalBaseUnitPrices = pricingContext.historicalPricesByProduct[item.productId].orEmpty(),
        rejectOnlyHistory = pricingContext.rejectOnlyHistory(item.productId, baseUnitId)
    )
    val resolved = when (resolution) {
        is OfflineUnitResolution.Resolved -> resolution
        is OfflineUnitResolution.PriceUnknown -> throw offlineConflict(
            OfflineConflictCode.OFFLINE_PRICE_CONFLICT,
            "Pelanggaran business rule harga: harga ${product[ProductsTable.name]} " +
                "(Rp ${item.priceAtTransaction.normalizeMoney().toPlainString()}) tidak sesuai daftar harga server"
        )
        is OfflineUnitResolution.UnitConflict -> throw offlineConflict(
            OfflineConflictCode.OFFLINE_UNIT_CONFLICT,
            "Pelanggaran business rule satuan: ${product[ProductsTable.name]}: ${resolution.reason}"
        )
    }

    return ResolvedOfflineItem(
        item = ResolvedItem(
            productId = item.productId,
            unitId = baseUnitId,
            productName = product[ProductsTable.name],
            qty = resolved.baseQuantity,
            priceAtTransaction = resolved.baseUnitPrice,
            // HPP dihitung server; nilai HPP dari perangkat tidak dipercaya.
            cogsAtTransaction = product[ProductsTable.priceBuy],
            grossLineTotal = item.grossLineTotal.normalizeMoney(),
            discountType = item.discountType,
            discountValue = item.discountValue.normalizeMoney(),
            discount = item.discount.normalizeMoney(),
            subtotal = requestedSubtotal,
            note = item.note
        ),
        sold = item,
        unit = resolved
    )
}

/** Item siap simpan (satuan dasar) beserta bentuk aslinya dari perangkat untuk jejak audit. */
internal data class ResolvedOfflineItem(
    val item: ResolvedItem,
    val sold: OfflineCheckoutSyncItemCommand,
    val unit: OfflineUnitResolution.Resolved
) {
    /** Transaksi hanya menyimpan satuan dasar, jadi "2 dus" hanya bisa ditelusuri dari sini. */
    fun auditMetadata(): String = buildJsonObject {
        put("event", "OFFLINE_ITEM_UNIT")
        put("source", unit.source.name)
        put("productId", item.productId.toString())
        put("soldUnitId", unit.soldUnitId?.toString())
        put("soldQuantity", sold.quantity.toPlainString())
        put("soldUnitPrice", sold.priceAtTransaction.normalizeMoney().toPlainString())
        put("unitFactor", unit.factor.stripTrailingZeros().toPlainString())
        put("baseUnitId", item.unitId.toString())
        put("baseQuantity", item.qty.toPlainString())
        put("baseUnitPrice", item.priceAtTransaction.toPlainString())
        put("subtotal", item.subtotal.toPlainString())
    }.toString()
}

internal data class LockedStock(val id: UUID, val quantity: BigDecimal)

/** Dipanggil sebelum produk dikunci: urutan lock checkout adalah stok lalu produk. */
internal fun lockOfflineStocks(productIds: List<UUID>): Map<UUID, LockedStock> {
    val sortedIds = productIds.distinct().sortedBy(UUID::toString)
    val lockedStocks = StockTable.select { StockTable.productId inList sortedIds }
        .orderBy(StockTable.productId to SortOrder.ASC)
        .forUpdate()
        .associate { row ->
            row[StockTable.productId] to LockedStock(row[StockTable.id], row[StockTable.quantity])
        }
    sortedIds.forEach { productId ->
        if (productId !in lockedStocks) throw NotFoundException("Data stok untuk produk $productId tidak ditemukan")
    }
    return lockedStocks
}

/** Kecukupan stok hanya bermakna setelah semua item diubah ke satuan dasar. */
internal fun requireSufficientOfflineStocks(
    lockedStocks: Map<UUID, LockedStock>,
    items: List<ResolvedOfflineItem>
) {
    val requested = items.groupBy { it.item.productId }
        .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { total, row -> total.add(row.item.qty) } }
    requested.keys.sortedBy(UUID::toString).forEach { productId ->
        val available = requireNotNull(lockedStocks[productId]).quantity
        val quantity = requireNotNull(requested[productId])
        if (quantity > available) {
            throw StockInsufficientException(
                "Stok produk $productId tidak cukup. Tersedia ${available.toPlainString()}, diminta ${quantity.toPlainString()}"
            )
        }
    }
}

/**
 * Membaca `unitId` dan `baseQuantity`. Keduanya opsional demi klien lama, tetapi wajib berpasangan:
 * salah satunya saja tidak cukup untuk memastikan jumlah satuan dasar.
 */
internal fun parseOfflineItemUnit(item: OfflineCheckoutItemRequest): OfflineItemUnit? {
    if (item.unitId == null && item.baseQuantity == null) return null
    if (item.unitId == null || item.baseQuantity == null) {
        throw ValidationException("unitId dan baseQuantity wajib dikirim bersamaan")
    }
    val unitId = runCatching { UUID.fromString(item.unitId.trim()) }.getOrNull()
        ?: throw ValidationException("Format Unit ID tidak valid")
    return OfflineItemUnit(unitId, item.baseQuantity)
}

private fun BigDecimal.normalizeMoney(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
