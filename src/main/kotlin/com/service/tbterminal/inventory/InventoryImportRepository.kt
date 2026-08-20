package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

data class ResolvedProductImportRow(
    val sku: String,
    val name: String,
    val categoryId: UUID,
    val unitId: UUID,
    val priceBuy: BigDecimal,
    val priceRetail: BigDecimal,
    val priceContractor: BigDecimal,
    val minStock: BigDecimal,
    val openingStock: BigDecimal,
    val openingDate: LocalDate?,
    val openingNote: String
)

internal class InventoryImportRepository {
    suspend fun getAllSkus(): Set<String> = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.slice(ProductsTable.sku).selectAll().map { normalizeSku(it[ProductsTable.sku]) }.toSet()
    }

    suspend fun createOpeningBalance(
        productId: UUID,
        quantity: BigDecimal,
        occurredOn: LocalDate,
        note: String,
        userId: UUID
    ): UUID = newSuspendedTransaction(Dispatchers.IO) {
        val current = StockTable.select { StockTable.productId eq productId }
            .forUpdate().singleOrNull()?.get(StockTable.quantity)
            ?: throw ValidationException("Data stok produk tidak ditemukan")
        if (current.compareTo(BigDecimal.ZERO) != 0) {
            throw ValidationException("Saldo awal hanya dapat dicatat saat stok masih nol")
        }
        val alreadyExists = StockAdjustmentsTable.select {
            (StockAdjustmentsTable.productId eq productId) and
                (StockAdjustmentsTable.adjustmentSource.lowerCase() eq "opening_balance")
        }.any() || StockAdjustmentsTable.select {
            (StockAdjustmentsTable.productId eq productId) and
                (StockAdjustmentsTable.adjustmentSource.lowerCase() eq "csv_import")
        }.any()
        if (alreadyExists) throw ValidationException("Saldo awal produk sudah pernah dicatat")

        insertAdjustment(productId, userId, quantity, occurredOn, note, "opening_balance")
    }

    suspend fun importProducts(rows: List<ResolvedProductImportRow>, userId: UUID): ProductCsvImportResponse =
        newSuspendedTransaction(Dispatchers.IO) {
            var openingBalances = 0
            rows.forEach { row ->
                val productId = UUID.randomUUID()
                ProductsTable.insert {
                    it[id] = productId
                    it[categoryId] = row.categoryId
                    it[baseUnitId] = row.unitId
                    it[sku] = row.sku
                    it[name] = row.name
                    it[priceBuy] = row.priceBuy
                    it[priceRetail] = row.priceRetail
                    it[priceContractor] = row.priceContractor
                    it[discount] = BigDecimal.ZERO
                    it[minStock] = row.minStock
                    it[isActive] = true
                }
                StockTable.insert {
                    it[this.productId] = productId
                    it[unitId] = row.unitId
                    it[quantity] = BigDecimal.ZERO
                }
                if (row.openingStock > BigDecimal.ZERO) {
                    insertAdjustment(
                        productId, userId, row.openingStock,
                        requireNotNull(row.openingDate), row.openingNote, "csv_import"
                    )
                    openingBalances++
                }
            }
            ProductCsvImportResponse(rows.size, openingBalances)
        }

    private fun insertAdjustment(
        productId: UUID,
        userId: UUID,
        quantity: BigDecimal,
        occurredOn: LocalDate,
        note: String,
        source: String
    ): UUID {
        val adjustmentId = UUID.randomUUID()
        StockAdjustmentsTable.insert {
            it[id] = adjustmentId
            it[this.productId] = productId
            it[this.userId] = userId
            it[adjType] = AdjType.CORRECTION
            it[qtyBefore] = BigDecimal.ZERO
            it[qtyAfter] = quantity
            it[reason] = note
            it[adjustmentSource] = source
            it[this.occurredOn] = occurredOn
        }
        return adjustmentId
    }
}
