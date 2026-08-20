package com.service.tbterminal.inventory

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.util.UUID
import kotlin.math.ceil

internal class InventoryProductRepository {
    suspend fun getProducts(page: Int, limit: Int, search: String?): PaginatedResponse<ProductResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val query = ProductsTable.select { ProductsTable.isActive eq true }.applySearch(search)
        val totalCount = query.count()
        val data = query.limit(limit, ((page - 1) * limit).toLong()).map(::toProductResponse)
        PaginatedResponse(data, totalCount, page, limit, totalPages(totalCount, limit))
    }

    suspend fun getProductById(id: UUID): ProductResponse? = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.select { (ProductsTable.id eq id) and (ProductsTable.isActive eq true) }
            .singleOrNull()
            ?.let(::toProductResponse)
    }

    suspend fun getProductByIdIncludingInactive(id: UUID): ProductResponse? = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.select { ProductsTable.id eq id }
            .singleOrNull()
            ?.let(::toProductResponse)
    }

    suspend fun getBySku(sku: String, includeInactive: Boolean): ProductResponse? = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.select { ProductsTable.sku eq sku }
            .apply { if (!includeInactive) andWhere { ProductsTable.isActive eq true } }
            .singleOrNull()
            ?.let(::toProductResponse)
    }

    suspend fun createProductAndInitStock(
        categoryId: UUID,
        baseUnitId: UUID,
        sku: String,
        name: String,
        priceBuy: BigDecimal,
        priceRetail: BigDecimal,
        priceContractor: BigDecimal,
        discount: BigDecimal,
        minStock: BigDecimal,
        photoFilename: String?
    ): UUID = newSuspendedTransaction(Dispatchers.IO) {
        val productId = insertProduct(categoryId, baseUnitId, sku, name, priceBuy, priceRetail, priceContractor, discount, minStock, photoFilename)
        StockTable.insert {
            it[this.productId] = productId
            it[unitId] = baseUnitId
            it[quantity] = BigDecimal.ZERO
        }
        productId
    }

    suspend fun updateProduct(
        id: UUID,
        categoryId: UUID,
        baseUnitId: UUID,
        name: String,
        priceBuy: BigDecimal,
        priceRetail: BigDecimal,
        priceContractor: BigDecimal,
        discount: BigDecimal,
        minStock: BigDecimal,
        photoFilename: String?
    ): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        updateProductFields(id, categoryId, baseUnitId, name, priceBuy, priceRetail, priceContractor, discount, minStock, photoFilename) > 0
    }

    suspend fun restoreProductAndOverwrite(
        id: UUID,
        categoryId: UUID,
        baseUnitId: UUID,
        name: String,
        priceBuy: BigDecimal,
        priceRetail: BigDecimal,
        priceContractor: BigDecimal,
        discount: BigDecimal,
        minStock: BigDecimal,
        photoFilename: String?
    ): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.update({ ProductsTable.id eq id }) { it[isActive] = true }
        updateProductFields(id, categoryId, baseUnitId, name, priceBuy, priceRetail, priceContractor, discount, minStock, photoFilename) > 0
    }

    suspend fun softDeleteProduct(id: UUID): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.update({ ProductsTable.id eq id }) { it[isActive] = false } > 0
    }

    suspend fun activateProduct(id: UUID): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.update({ ProductsTable.id eq id }) { it[isActive] = true } > 0
    }

    private fun insertProduct(
        categoryId: UUID,
        baseUnitId: UUID,
        sku: String,
        name: String,
        priceBuy: BigDecimal,
        priceRetail: BigDecimal,
        priceContractor: BigDecimal,
        discount: BigDecimal,
        minStock: BigDecimal,
        photoFilename: String?
    ): UUID {
        val productId = UUID.randomUUID()
        ProductsTable.insert {
            it[id] = productId
            it[this.categoryId] = categoryId
            it[this.baseUnitId] = baseUnitId
            it[this.sku] = sku
            it[this.name] = name
            it[this.priceBuy] = priceBuy
            it[this.priceRetail] = priceRetail
            it[this.priceContractor] = priceContractor
            it[this.discount] = discount
            it[this.minStock] = minStock
            it[this.photoFilename] = photoFilename
            it[isActive] = true
        }
        return productId
    }

    private fun updateProductFields(
        id: UUID,
        categoryId: UUID,
        baseUnitId: UUID,
        name: String,
        priceBuy: BigDecimal,
        priceRetail: BigDecimal,
        priceContractor: BigDecimal,
        discount: BigDecimal,
        minStock: BigDecimal,
        photoFilename: String?
    ): Int {
        return ProductsTable.update({ ProductsTable.id eq id }) {
            it[this.categoryId] = categoryId
            it[this.baseUnitId] = baseUnitId
            it[this.name] = name
            it[this.priceBuy] = priceBuy
            it[this.priceRetail] = priceRetail
            it[this.priceContractor] = priceContractor
            it[this.discount] = discount
            it[this.minStock] = minStock
            if (photoFilename != null) it[this.photoFilename] = photoFilename
        }
    }

    private fun org.jetbrains.exposed.sql.Query.applySearch(search: String?): org.jetbrains.exposed.sql.Query {
        if (search.isNullOrBlank()) return this
        val searchTerm = "%${search.lowercase()}%"
        return andWhere {
            (ProductsTable.name.lowerCase() like searchTerm) or (ProductsTable.sku.lowerCase() like searchTerm)
        }
    }

    private fun toProductResponse(row: ResultRow): ProductResponse {
        return ProductResponse(
            id = row[ProductsTable.id].toString(),
            categoryId = row[ProductsTable.categoryId].toString(),
            baseUnitId = row[ProductsTable.baseUnitId].toString(),
            sku = row[ProductsTable.sku],
            name = row[ProductsTable.name],
            priceBuy = row[ProductsTable.priceBuy],
            priceRetail = row[ProductsTable.priceRetail],
            priceContractor = row[ProductsTable.priceContractor],
            discount = row[ProductsTable.discount],
            minStock = row[ProductsTable.minStock],
            photoFilename = row[ProductsTable.photoFilename],
            isActive = row[ProductsTable.isActive]
        )
    }

    private fun totalPages(total: Long, limit: Int): Int {
        return ceil(total.toDouble() / limit).toInt()
    }
}

