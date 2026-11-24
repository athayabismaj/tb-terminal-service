package com.service.tbterminal.sales

import com.service.tbterminal.inventory.ProductsTable
import com.service.tbterminal.inventory.StockTable
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.StockInsufficientException
import com.service.tbterminal.shared.ValidationException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

internal fun resolveItemForOfflineSync(
    item: OfflineCheckoutSyncItemCommand,
    pricingContext: OfflinePricingContext
): ResolvedItem {
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
    val matchedUnitFactor = OfflineCheckoutGuard.matchUnitFactor(
        priceAtTransaction = item.priceAtTransaction,
        quantity = item.quantity,
        unitFactors = pricingContext.unitFactorsByProduct[item.productId].orEmpty(),
        expectedBaseUnitPrice = { baseQuantity ->
            pricingContext.tierPricing.resolveUnitPrice(product, baseQuantity)
        },
        historicalBaseUnitPrices = pricingContext.historicalPricesByProduct[item.productId].orEmpty()
    ) ?: throw offlineConflict(
        OfflineConflictCode.OFFLINE_PRICE_CONFLICT,
        "Pelanggaran business rule harga: harga ${product[ProductsTable.name]} " +
            "(Rp ${item.priceAtTransaction.normalizeMoney().toPlainString()}) tidak sesuai daftar harga server"
    )

    return ResolvedItem(
        productId = item.productId,
        unitId = product[ProductsTable.baseUnitId],
        productName = product[ProductsTable.name],
        qty = item.quantity,
        priceAtTransaction = item.priceAtTransaction.normalizeMoney(),
        // HPP dihitung server; nilai HPP dari perangkat tidak dipercaya.
        cogsAtTransaction = product[ProductsTable.priceBuy].multiply(matchedUnitFactor).normalizeMoney(),
        grossLineTotal = item.grossLineTotal.normalizeMoney(),
        discountType = item.discountType,
        discountValue = item.discountValue.normalizeMoney(),
        discount = item.discount.normalizeMoney(),
        subtotal = requestedSubtotal,
        note = item.note
    )
}

internal fun lockAndValidateOfflineStocks(items: List<OfflineCheckoutSyncItemCommand>): Map<UUID, UUID> {
    val requested = items.groupBy(OfflineCheckoutSyncItemCommand::productId)
        .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { total, row -> total.add(row.quantity) } }
    val productIds = requested.keys.sortedBy(UUID::toString)
    val lockedStocks = StockTable.select { StockTable.productId inList productIds }
        .orderBy(StockTable.productId to SortOrder.ASC)
        .forUpdate()
        .associate { row ->
            row[StockTable.productId] to (row[StockTable.id] to row[StockTable.quantity])
        }
    productIds.forEach { productId ->
        val available = lockedStocks[productId]?.second
            ?: throw NotFoundException("Data stok untuk produk $productId tidak ditemukan")
        val quantity = requireNotNull(requested[productId])
        if (quantity > available) {
            throw StockInsufficientException(
                "Stok produk $productId tidak cukup. Tersedia ${available.toPlainString()}, diminta ${quantity.toPlainString()}"
            )
        }
    }
    return lockedStocks.mapValues { (_, stock) -> stock.first }
}

private fun BigDecimal.normalizeMoney(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
