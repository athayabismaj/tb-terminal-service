package com.service.tbterminal.inventory

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
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

    suspend fun getStockAdjustments(
        page: Int,
        limit: Int,
        search: String?,
        type: String?
    ): PaginatedResponse<StockAdjustmentResponse> = transaction {
        val offset = (page - 1) * limit
        val adjType = type.toAdjTypeOrNull()
        val totalCount = stockAdjustmentQuery()
            .applyAdjustmentFilters(search, adjType)
            .count()
        val data = stockAdjustmentQuery()
            .applyAdjustmentFilters(search, adjType)
            .orderBy(StockAdjustmentsTable.createdAt, SortOrder.DESC)
            .limit(limit, offset.toLong())
            .map(::toStockAdjustmentResponse)

        PaginatedResponse(data, totalCount, page, limit, totalPages(totalCount, limit))
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

    private fun stockAdjustmentQuery(): org.jetbrains.exposed.sql.Query {
        return (StockAdjustmentsTable innerJoin ProductsTable innerJoin CategoriesTable innerJoin UnitsTable)
            .selectAll()
    }

    private fun org.jetbrains.exposed.sql.Query.applyAdjustmentFilters(
        search: String?,
        type: AdjType?
    ): org.jetbrains.exposed.sql.Query {
        if (type != null) {
            andWhere { StockAdjustmentsTable.adjType eq type }
        }

        if (search.isNullOrBlank()) return this
        val searchTerm = "%${search.lowercase()}%"
        return andWhere {
            (ProductsTable.name.lowerCase() like searchTerm) or
                (ProductsTable.sku.lowerCase() like searchTerm) or
                (StockAdjustmentsTable.reason.lowerCase() like searchTerm)
        }
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

    private fun toStockAdjustmentResponse(row: ResultRow): StockAdjustmentResponse {
        val qtyBefore = row[StockAdjustmentsTable.qtyBefore]
        val qtyAfter = row[StockAdjustmentsTable.qtyAfter]
        val adjType = row[StockAdjustmentsTable.adjType]

        return StockAdjustmentResponse(
            id = row[StockAdjustmentsTable.id].toString(),
            productId = row[StockAdjustmentsTable.productId].toString(),
            sku = row[ProductsTable.sku],
            productName = row[ProductsTable.name],
            categoryName = row[CategoriesTable.name],
            unitName = row[UnitsTable.name],
            adjustmentType = adjType.name,
            adjustmentTypeLabel = adjType.label(),
            qtyBefore = qtyBefore,
            qtyAfter = qtyAfter,
            difference = qtyAfter.subtract(qtyBefore),
            reason = row[StockAdjustmentsTable.reason],
            userId = row[StockAdjustmentsTable.userId].toString(),
            createdAt = row[StockAdjustmentsTable.createdAt].toString()
        )
    }

    private fun totalPages(total: Long, limit: Int): Int {
        return ceil(total.toDouble() / limit).toInt()
    }

    private fun String?.toAdjTypeOrNull(): AdjType? {
        val value = this?.trim()?.takeIf(String::isNotBlank) ?: return null
        return AdjType.entries.firstOrNull { type ->
            type.name.equals(value, ignoreCase = true) || type.dbValue.equals(value, ignoreCase = true)
        }
    }

    private fun AdjType.label(): String {
        return when (this) {
            AdjType.OPNAME -> "Opname"
            AdjType.CORRECTION -> "Koreksi"
            AdjType.DAMAGE -> "Rusak/Retur"
        }
    }
}
