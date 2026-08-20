package com.service.tbterminal.inventory

import java.math.BigDecimal
import java.util.UUID
import java.time.OffsetDateTime

interface InventoryRepository {
    suspend fun getAllCategories(): List<CategoryResponse>
    suspend fun getCategories(page: Int, limit: Int, search: String?): PaginatedResponse<CategoryResponse>
    suspend fun getCategoryById(id: UUID): CategoryResponse?
    suspend fun getCategoryByName(name: String): CategoryResponse?
    suspend fun createCategory(name: String): UUID
    suspend fun updateCategory(id: UUID, name: String): Boolean
    suspend fun deleteCategory(id: UUID): Boolean

    suspend fun getAllUnits(): List<UnitResponse>
    suspend fun getUnits(page: Int, limit: Int, search: String?): PaginatedResponse<UnitResponse>
    suspend fun getUnitById(id: UUID): UnitResponse?
    suspend fun getUnitByNameOrSymbol(name: String, symbol: String): UnitResponse?
    suspend fun createUnit(name: String, symbol: String): UUID
    suspend fun updateUnit(id: UUID, name: String, symbol: String): Boolean
    suspend fun deleteUnit(id: UUID): Boolean

    suspend fun getProducts(page: Int, limit: Int, search: String?): PaginatedResponse<ProductResponse>
    suspend fun getProductById(id: UUID): ProductResponse?
    suspend fun getProductByIdIncludingInactive(id: UUID): ProductResponse?
    suspend fun getProductBySku(sku: String, includeInactive: Boolean = false): ProductResponse?
    suspend fun getAllSkus(): Set<String>
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
    ): UUID

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
    ): Boolean

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
    ): Boolean

    suspend fun softDeleteProduct(id: UUID): Boolean
    suspend fun activateProduct(id: UUID): Boolean
    suspend fun getPaginatedStockDetail(
        limit: Int,
        offset: Int,
        search: String?
    ): PaginatedResponse<StockDetailResponse>

    suspend fun getStockAdjustments(
        page: Int,
        limit: Int,
        search: String?,
        type: String?
    ): PaginatedResponse<StockAdjustmentResponse>
    suspend fun getStockCard(
        page: Int,
        limit: Int,
        productId: UUID?,
        search: String?,
        type: StockMovementType?,
        startAt: OffsetDateTime?,
        endExclusive: OffsetDateTime?
    ): StockCardResponse

    suspend fun getCurrentStockForUpdate(productId: UUID): BigDecimal?
    suspend fun executeOpname(
        productId: UUID,
        newQty: BigDecimal,
        userId: UUID,
        adjType: AdjType,
        notes: String?
    ): Boolean
    suspend fun createOpeningBalance(
        productId: UUID,
        quantity: BigDecimal,
        occurredOn: java.time.LocalDate,
        note: String,
        userId: UUID
    ): UUID
    suspend fun importProducts(rows: List<ResolvedProductImportRow>, userId: UUID): ProductCsvImportResponse
}

class InventoryRepositoryImpl : InventoryRepository {
    private val categories = InventoryCategoryRepository()
    private val products = InventoryProductRepository()
    private val stock = InventoryStockRepository()
    private val units = InventoryUnitRepository()
    private val imports = InventoryImportRepository()

    override suspend fun getAllCategories() = categories.getAllCategories()
    override suspend fun getCategories(page: Int, limit: Int, search: String?) =
        categories.getCategories(limit = limit, offset = (page - 1) * limit, search = search)

    override suspend fun getCategoryById(id: UUID) = categories.getCategoryById(id)
    override suspend fun getCategoryByName(name: String) = categories.getCategoryByName(name)
    override suspend fun createCategory(name: String) = categories.createCategory(name)
    override suspend fun updateCategory(id: UUID, name: String) = categories.updateCategory(id, name)
    override suspend fun deleteCategory(id: UUID) = categories.deleteCategory(id)

    override suspend fun getAllUnits() = units.getAllUnits()
    override suspend fun getUnits(page: Int, limit: Int, search: String?) =
        units.getUnits(limit = limit, offset = (page - 1) * limit, search = search)

    override suspend fun getUnitById(id: UUID) = units.getUnitById(id)
    override suspend fun getUnitByNameOrSymbol(name: String, symbol: String) = units.getByNameOrSymbol(name, symbol)
    override suspend fun createUnit(name: String, symbol: String) = units.createUnit(name, symbol)
    override suspend fun updateUnit(id: UUID, name: String, symbol: String) = units.updateUnit(id, name, symbol)
    override suspend fun deleteUnit(id: UUID) = units.deleteUnit(id)

    override suspend fun getProducts(page: Int, limit: Int, search: String?) = products.getProducts(page, limit, search)
    override suspend fun getProductById(id: UUID) = products.getProductById(id)
    override suspend fun getProductByIdIncludingInactive(id: UUID) = products.getProductByIdIncludingInactive(id)
    override suspend fun getProductBySku(sku: String, includeInactive: Boolean) = products.getBySku(sku, includeInactive)
    override suspend fun getAllSkus() = imports.getAllSkus()

    override suspend fun createProductAndInitStock(
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
    ) = products.createProductAndInitStock(categoryId, baseUnitId, sku, name, priceBuy, priceRetail, priceContractor, discount, minStock, photoFilename)

    override suspend fun updateProduct(
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
    ) = products.updateProduct(id, categoryId, baseUnitId, name, priceBuy, priceRetail, priceContractor, discount, minStock, photoFilename)

    override suspend fun restoreProductAndOverwrite(
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
    ) = products.restoreProductAndOverwrite(id, categoryId, baseUnitId, name, priceBuy, priceRetail, priceContractor, discount, minStock, photoFilename)

    override suspend fun softDeleteProduct(id: UUID) = products.softDeleteProduct(id)
    override suspend fun activateProduct(id: UUID) = products.activateProduct(id)
    override suspend fun getPaginatedStockDetail(limit: Int, offset: Int, search: String?) =
        stock.getPaginatedStockDetail(limit, offset, search)

    override suspend fun getStockAdjustments(page: Int, limit: Int, search: String?, type: String?) =
        stock.getStockAdjustments(page = page, limit = limit, search = search, type = type)

    override suspend fun getStockCard(
        page: Int,
        limit: Int,
        productId: UUID?,
        search: String?,
        type: StockMovementType?,
        startAt: OffsetDateTime?,
        endExclusive: OffsetDateTime?
    ) = stock.getStockCard(page, limit, productId, search, type, startAt, endExclusive)

    override suspend fun getCurrentStockForUpdate(productId: UUID) = stock.getCurrentStockForUpdate(productId)
    override suspend fun executeOpname(
        productId: UUID,
        newQty: BigDecimal,
        userId: UUID,
        adjType: AdjType,
        notes: String?
    ) = stock.executeOpname(productId, newQty, userId, adjType, notes)

    override suspend fun createOpeningBalance(
        productId: UUID,
        quantity: BigDecimal,
        occurredOn: java.time.LocalDate,
        note: String,
        userId: UUID
    ) = imports.createOpeningBalance(productId, quantity, occurredOn, note, userId)

    override suspend fun importProducts(rows: List<ResolvedProductImportRow>, userId: UUID) =
        imports.importProducts(rows, userId)
}

