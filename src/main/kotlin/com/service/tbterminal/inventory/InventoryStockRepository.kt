package com.service.tbterminal.inventory

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.util.UUID
import kotlin.math.ceil

internal class InventoryStockRepository {
    suspend fun getPaginatedStockDetail(
        limit: Int,
        offset: Int,
        search: String?
    ): PaginatedResponse<StockDetailResponse> = transaction {
        val query = VStockDetailView.selectAll().applySearch(search)
        val totalCount = query.count()
        val data = query.limit(limit, offset.toLong()).map(::toStockDetailResponse)
        PaginatedResponse(data, totalCount, (offset / limit) + 1, limit, totalPages(totalCount, limit))
    }

    suspend fun getCurrentStockForUpdate(productId: UUID): BigDecimal? = transaction {
        StockTable.select { StockTable.productId eq productId }
            .forUpdate()
            .singleOrNull()
            ?.get(StockTable.quantity)
    }

    suspend fun executeOpname(
        productId: UUID,
        oldQty: BigDecimal,
        newQty: BigDecimal,
        userId: UUID,
        adjType: AdjType,
        notes: String?
    ): Boolean = transaction {
        StockAdjustmentsTable.insert {
            it[this.productId] = productId
            it[this.userId] = userId
            it[this.adjType] = adjType
            it[qtyBefore] = oldQty
            it[qtyAfter] = newQty
            it[reason] = notes.orEmpty()
        }
        StockTable.update({ StockTable.productId eq productId }) { it[quantity] = newQty } > 0
    }

    private fun org.jetbrains.exposed.sql.Query.applySearch(search: String?): org.jetbrains.exposed.sql.Query {
        if (search.isNullOrBlank()) return this
        val searchTerm = "%${search.lowercase()}%"
        return andWhere {
            (VStockDetailView.productName.lowerCase() like searchTerm) or (VStockDetailView.sku.lowerCase() like searchTerm)
        }
    }

    private fun toStockDetailResponse(row: ResultRow): StockDetailResponse {
        return StockDetailResponse(
            productId = row[VStockDetailView.productId].toString(),
            sku = row[VStockDetailView.sku],
            productName = row[VStockDetailView.productName],
            categoryName = row[VStockDetailView.categoryName],
            unitName = row[VStockDetailView.unitName],
            quantity = row[VStockDetailView.quantity],
            minStock = row[VStockDetailView.minStock],
            priceBuy = row[VStockDetailView.priceBuy],
            priceRetail = row[VStockDetailView.priceRetail],
            priceContractor = row[VStockDetailView.priceContractor],
            isActive = row[VStockDetailView.isActive]
        )
    }

    private fun totalPages(total: Long, limit: Int): Int {
        return ceil(total.toDouble() / limit).toInt()
    }
}
