package com.service.tbterminal.inventory

import com.service.tbterminal.shared.NotFoundException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
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
        val rows = query.limit(limit, ((page - 1) * limit).toLong()).toList()
        val tiersByProduct = loadPriceTiersMap(rows.map { it[ProductsTable.id] })
        val data = rows.map { row -> toProductResponse(row, tiersByProduct[row[ProductsTable.id]].orEmpty()) }
        PaginatedResponse(data, totalCount, page, limit, totalPages(totalCount, limit))
    }

    suspend fun getProductById(id: UUID): ProductResponse? = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.select { (ProductsTable.id eq id) and (ProductsTable.isActive eq true) }
            .singleOrNull()
            ?.let { row -> toProductResponse(row, loadPriceTiersMap(listOf(id))[id].orEmpty()) }
    }

    suspend fun getProductByIdIncludingInactive(id: UUID): ProductResponse? = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.select { ProductsTable.id eq id }
            .singleOrNull()
            ?.let { row -> toProductResponse(row, loadPriceTiersMap(listOf(id))[id].orEmpty()) }
    }

    suspend fun getBySku(sku: String, includeInactive: Boolean): ProductResponse? = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.select { ProductsTable.sku eq sku }
            .apply { if (!includeInactive) andWhere { ProductsTable.isActive eq true } }
            .singleOrNull()
            ?.let { row ->
                val id = row[ProductsTable.id]
                toProductResponse(row, loadPriceTiersMap(listOf(id))[id].orEmpty())
            }
    }

    suspend fun getProductPriceTiers(productId: UUID): List<ProductPriceTierDto> = newSuspendedTransaction(Dispatchers.IO) {
        loadPriceTiersMap(listOf(productId))[productId].orEmpty()
    }

    suspend fun replaceProductPriceTiers(
        productId: UUID,
        tiers: List<ProductPriceTierDto>
    ): List<ProductPriceTierDto> = newSuspendedTransaction(Dispatchers.IO) {
        val sorted = TieredPricingResolver.validateAndSortTiers(tiers)
        ProductPriceTiersTable.deleteWhere { ProductPriceTiersTable.productId eq productId }
        sorted.forEach { tier ->
            ProductPriceTiersTable.insert {
                it[id] = UUID.randomUUID()
                it[this.productId] = productId
                it[minQty] = tier.minQty
                it[unitPrice] = tier.unitPrice
            }
        }
        loadPriceTiersMap(listOf(productId))[productId].orEmpty()
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
        secondaryUnitId: UUID?,
        secondaryUnitFactor: BigDecimal?
    ): UUID = newSuspendedTransaction(Dispatchers.IO) {
        val productId = insertProduct(categoryId, baseUnitId, sku, name, priceBuy, priceRetail, priceContractor, discount, minStock)
        StockTable.insert {
            it[this.productId] = productId
            it[unitId] = baseUnitId
            it[quantity] = BigDecimal.ZERO
        }
        replaceUnitConversion(productId, baseUnitId, secondaryUnitId, secondaryUnitFactor)
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
        secondaryUnitId: UUID?,
        secondaryUnitFactor: BigDecimal?
    ): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val updated = updateProductFields(id, categoryId, baseUnitId, name, priceBuy, priceRetail, priceContractor, discount, minStock) > 0
        if (updated) replaceUnitConversion(id, baseUnitId, secondaryUnitId, secondaryUnitFactor)
        updated
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
        secondaryUnitId: UUID?,
        secondaryUnitFactor: BigDecimal?
    ): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.update({ ProductsTable.id eq id }) { it[isActive] = true }
        val updated = updateProductFields(id, categoryId, baseUnitId, name, priceBuy, priceRetail, priceContractor, discount, minStock) > 0
        if (updated) replaceUnitConversion(id, baseUnitId, secondaryUnitId, secondaryUnitFactor)
        updated
    }

    suspend fun softDeleteProduct(id: UUID): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.update({ ProductsTable.id eq id }) { it[isActive] = false } > 0
    }

    suspend fun activateProduct(id: UUID): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        ProductsTable.update({ ProductsTable.id eq id }) { it[isActive] = true } > 0
    }

    suspend fun setProductPhoto(id: UUID, photoUrl: String, publicId: String): String? = newSuspendedTransaction(Dispatchers.IO) {
        val row = ProductsTable
            .select { (ProductsTable.id eq id) and (ProductsTable.isActive eq true) }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Produk tidak ditemukan atau tidak aktif")
        val oldPublicId = row[ProductsTable.photoPublicId]
        ProductsTable.update({ ProductsTable.id eq id }) {
            it[photoFilename] = photoUrl
            it[photoPublicId] = publicId
        }
        oldPublicId
    }

    suspend fun clearProductPhoto(id: UUID): String? = newSuspendedTransaction(Dispatchers.IO) {
        val row = ProductsTable
            .select { (ProductsTable.id eq id) and (ProductsTable.isActive eq true) }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Produk tidak ditemukan atau tidak aktif")
        val oldPublicId = row[ProductsTable.photoPublicId]
        ProductsTable.update({ ProductsTable.id eq id }) {
            it[photoFilename] = null
            it[photoPublicId] = null
        }
        oldPublicId
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
        minStock: BigDecimal
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
        minStock: BigDecimal
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
        }
    }

    private fun replaceUnitConversion(
        productId: UUID,
        baseUnitId: UUID,
        secondaryUnitId: UUID?,
        factor: BigDecimal?
    ) {
        // Konversi yang sama tidak ditulis ulang: setiap penghapusan tercatat sebagai perubahan faktor.
        val current = UnitConversionsTable.select { UnitConversionsTable.productId eq productId }.toList()
        val unchanged = current.singleOrNull()?.let {
            it[UnitConversionsTable.fromUnitId] == secondaryUnitId &&
                it[UnitConversionsTable.toUnitId] == baseUnitId &&
                factor?.compareTo(it[UnitConversionsTable.factor]) == 0
        } ?: false
        if (unchanged) return

        UnitConversionsTable.deleteWhere { UnitConversionsTable.productId eq productId }
        if (secondaryUnitId != null && factor != null) {
            UnitConversionsTable.insert {
                it[id] = UUID.randomUUID()
                it[this.productId] = productId
                it[fromUnitId] = secondaryUnitId
                it[toUnitId] = baseUnitId
                it[this.factor] = factor
            }
        }
    }

    private fun org.jetbrains.exposed.sql.Query.applySearch(search: String?): org.jetbrains.exposed.sql.Query {
        if (search.isNullOrBlank()) return this
        val searchTerm = "%${search.lowercase()}%"
        return andWhere {
            (ProductsTable.name.lowerCase() like searchTerm) or (ProductsTable.sku.lowerCase() like searchTerm)
        }
    }

    private fun loadPriceTiersMap(productIds: List<UUID>): Map<UUID, List<ProductPriceTierDto>> {
        if (productIds.isEmpty()) return emptyMap()
        return ProductPriceTiersTable.select { ProductPriceTiersTable.productId inList productIds.distinct() }
            .orderBy(ProductPriceTiersTable.minQty to SortOrder.ASC)
            .groupBy { it[ProductPriceTiersTable.productId] }
            .mapValues { (_, rows) ->
                rows.map { r ->
                    ProductPriceTierDto(
                        id = r[ProductPriceTiersTable.id].toString(),
                        minQty = r[ProductPriceTiersTable.minQty],
                        unitPrice = r[ProductPriceTiersTable.unitPrice],
                    )
                }
            }
    }

    private fun toProductResponse(
        row: ResultRow,
        priceTiers: List<ProductPriceTierDto> = emptyList(),
    ): ProductResponse {
        val conversion = UnitConversionsTable
            .select { UnitConversionsTable.productId eq row[ProductsTable.id] }
            .limit(1)
            .singleOrNull()
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
            photoUrl = row[ProductsTable.photoFilename],
            isActive = row[ProductsTable.isActive],
            secondaryUnitId = conversion?.get(UnitConversionsTable.fromUnitId)?.toString(),
            secondaryUnitFactor = conversion?.get(UnitConversionsTable.factor)?.stripTrailingZeros()?.toPlainString(),
            priceTiers = priceTiers,
        )
    }

    private fun totalPages(total: Long, limit: Int): Int {
        return ceil(total.toDouble() / limit).toInt()
    }
}

