package com.service.tbterminal.inventory

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal
import java.util.UUID
import java.time.OffsetDateTime
import kotlin.math.ceil

internal class InventoryStockRepository {
    suspend fun getStockCard(
        page: Int,
        limit: Int,
        productId: UUID?,
        search: String?,
        type: StockMovementType?,
        startAt: OffsetDateTime?,
        endExclusive: OffsetDateTime?
    ): StockCardResponse = newSuspendedTransaction(Dispatchers.IO) {
        fun filteredQuery(): org.jetbrains.exposed.sql.Query {
            val movementLedger = StockMovementsTable
                .join(
                    ProductsTable,
                    JoinType.INNER,
                    additionalConstraint = { StockMovementsTable.productId eq ProductsTable.id }
                )
                .join(
                    UnitsTable,
                    JoinType.INNER,
                    additionalConstraint = { StockMovementsTable.unitId eq UnitsTable.id }
                )
            var query = movementLedger.selectAll()
            productId?.let { id -> query = query.andWhere { StockMovementsTable.productId eq id } }
            type?.let { movementType -> query = query.andWhere { StockMovementsTable.movementType eq movementType } }
            startAt?.let { value -> query = query.andWhere { StockMovementsTable.occurredAt greaterEq value } }
            endExclusive?.let { value -> query = query.andWhere { StockMovementsTable.occurredAt less value } }
            search?.trim()?.takeIf(String::isNotBlank)?.let { value ->
                val term = "%${value.lowercase()}%"
                query = query.andWhere {
                    (ProductsTable.sku.lowerCase() like term) or
                        (ProductsTable.name.lowerCase() like term) or
                        (StockMovementsTable.referenceNumber.lowerCase() like term)
                }
            }
            return query
        }

        val total = filteredQuery().count()
        val rows = filteredQuery()
            .orderBy(StockMovementsTable.sequenceNo to SortOrder.DESC)
            .limit(limit, ((page - 1) * limit).toLong())
            .toList()
        val currentStock = productId?.let { id ->
            StockTable.select { StockTable.productId eq id }.singleOrNull()?.get(StockTable.quantity)
        }
        val ledgerBalance = productId?.let { id ->
            StockMovementsTable.select { StockMovementsTable.productId eq id }
                .orderBy(StockMovementsTable.sequenceNo to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(StockMovementsTable.balanceAfter)
                ?: BigDecimal.ZERO
        }

        StockCardResponse(
            data = rows.map { row ->
                StockMovementResponse(
                    id = row[StockMovementsTable.id].toString(),
                    productId = row[StockMovementsTable.productId].toString(),
                    sku = row[ProductsTable.sku],
                    productName = row[ProductsTable.name],
                    unitName = row[UnitsTable.name],
                    type = row[StockMovementsTable.movementType].name,
                    balanceBefore = row[StockMovementsTable.balanceBefore],
                    qtyIn = row[StockMovementsTable.qtyIn],
                    qtyOut = row[StockMovementsTable.qtyOut],
                    balanceAfter = row[StockMovementsTable.balanceAfter],
                    referenceType = row[StockMovementsTable.referenceType],
                    referenceId = row[StockMovementsTable.referenceId].toString(),
                    referenceNumber = row[StockMovementsTable.referenceNumber],
                    userId = row[StockMovementsTable.userId]?.toString(),
                    occurredAt = row[StockMovementsTable.occurredAt].toString()
                )
            },
            total = total,
            page = page,
            limit = limit,
            totalPages = totalPages(total, limit),
            currentStock = currentStock,
            ledgerBalance = ledgerBalance,
            reconciled = productId == null || currentStock?.compareTo(ledgerBalance) == 0
        )
    }

    suspend fun getPaginatedStockDetail(
        limit: Int,
        offset: Int,
        search: String?
    ): PaginatedResponse<StockDetailResponse> = newSuspendedTransaction(Dispatchers.IO) {
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
    ): PaginatedResponse<StockAdjustmentResponse> = newSuspendedTransaction(Dispatchers.IO) {
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

    suspend fun getCurrentStockForUpdate(productId: UUID): BigDecimal? = newSuspendedTransaction(Dispatchers.IO) {
        StockTable.select { StockTable.productId eq productId }
            .forUpdate()
            .singleOrNull()
            ?.get(StockTable.quantity)
    }

    suspend fun executeOpname(
        productId: UUID,
        newQty: BigDecimal,
        userId: UUID,
        adjType: AdjType,
        notes: String?
    ): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val oldQty = StockTable.select { StockTable.productId eq productId }
            .forUpdate()
            .singleOrNull()
            ?.get(StockTable.quantity)
            ?: throw NotFoundException("Data stok untuk produk ini tidak ditemukan")
        if (oldQty.compareTo(newQty) == 0) {
            throw ValidationException("Stok fisik sama dengan sistem, tidak ada penyesuaian")
        }
        StockAdjustmentsTable.insert {
            it[this.productId] = productId
            it[this.userId] = userId
            it[this.adjType] = adjType
            it[qtyBefore] = oldQty
            it[qtyAfter] = newQty
            it[reason] = notes.orEmpty()
        }
        true
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
        val source = row[StockAdjustmentsTable.adjustmentSource]

        return StockAdjustmentResponse(
            id = row[StockAdjustmentsTable.id].toString(),
            productId = row[StockAdjustmentsTable.productId].toString(),
            sku = row[ProductsTable.sku],
            productName = row[ProductsTable.name],
            categoryName = row[CategoriesTable.name],
            unitName = row[UnitsTable.name],
            adjustmentType = if (source == "opening_balance" || source == "csv_import") "OPENING_BALANCE" else adjType.name,
            adjustmentTypeLabel = if (source == "opening_balance" || source == "csv_import") "Saldo Awal" else adjType.label(),
            qtyBefore = qtyBefore,
            qtyAfter = qtyAfter,
            difference = qtyAfter.subtract(qtyBefore),
            reason = row[StockAdjustmentsTable.reason],
            userId = row[StockAdjustmentsTable.userId].toString(),
            source = source,
            occurredOn = row[StockAdjustmentsTable.occurredOn].toString(),
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
