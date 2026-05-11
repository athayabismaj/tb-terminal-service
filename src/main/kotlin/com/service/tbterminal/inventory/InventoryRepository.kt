package com.service.tbterminal.inventory

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

interface InventoryRepository {
    // Categories
    suspend fun getAllCategories(): List<CategoryResponse>
    suspend fun getCategoryById(id: UUID): CategoryResponse?
    suspend fun getCategoryByName(name: String): CategoryResponse?
    suspend fun createCategory(name: String): UUID
    suspend fun updateCategory(id: UUID, name: String): Boolean
    suspend fun deleteCategory(id: UUID): Boolean

    // Units
    suspend fun getAllUnits(): List<UnitResponse>
    suspend fun getUnitById(id: UUID): UnitResponse?
    suspend fun getUnitByNameOrSymbol(name: String, symbol: String): UnitResponse?
    suspend fun createUnit(name: String, symbol: String): UUID
    suspend fun updateUnit(id: UUID, name: String, symbol: String): Boolean
    suspend fun deleteUnit(id: UUID): Boolean

    // Products
    suspend fun getProducts(page: Int, limit: Int, search: String?): PaginatedResponse<ProductResponse>
    suspend fun getProductById(id: UUID): ProductResponse?
    suspend fun getProductBySku(sku: String, includeInactive: Boolean = false): ProductResponse?
    suspend fun createProductAndInitStock(
        categoryId: UUID, baseUnitId: UUID, sku: String, name: String,
        priceBuy: java.math.BigDecimal, priceRetail: java.math.BigDecimal, priceContractor: java.math.BigDecimal,
        minStock: java.math.BigDecimal, photoFilename: String?
    ): UUID
    suspend fun updateProduct(
        id: UUID, categoryId: UUID, baseUnitId: UUID, name: String,
        priceBuy: java.math.BigDecimal, priceRetail: java.math.BigDecimal, priceContractor: java.math.BigDecimal,
        minStock: java.math.BigDecimal, photoFilename: String?
    ): Boolean
    suspend fun restoreProductAndOverwrite(
        id: UUID, categoryId: UUID, baseUnitId: UUID, name: String,
        priceBuy: java.math.BigDecimal, priceRetail: java.math.BigDecimal, priceContractor: java.math.BigDecimal,
        minStock: java.math.BigDecimal, photoFilename: String?
    ): Boolean
    suspend fun softDeleteProduct(id: UUID): Boolean

    // Stock
    suspend fun getPaginatedStockDetail(limit: Int, offset: Int, search: String?): PaginatedResponse<StockDetailResponse>
    suspend fun getCurrentStockForUpdate(productId: UUID): java.math.BigDecimal?
    suspend fun executeOpname(productId: UUID, oldQty: java.math.BigDecimal, newQty: java.math.BigDecimal, userId: UUID, adjType: AdjType, notes: String?): Boolean
}

class InventoryRepositoryImpl : InventoryRepository {

    // ==========================================
    // CATEGORIES
    // ==========================================
    // ... (rest of implementation)



    // ==========================================
    // CATEGORIES
    // ==========================================

    override suspend fun getAllCategories(): List<CategoryResponse> = transaction {
        CategoriesTable.selectAll().map {
            CategoryResponse(
                id = it[CategoriesTable.id].toString(),
                name = it[CategoriesTable.name],
                createdAt = it[CategoriesTable.createdAt].toString()
            )
        }
    }

    override suspend fun getCategoryById(id: UUID): CategoryResponse? = transaction {
        CategoriesTable.select { CategoriesTable.id eq id }.singleOrNull()?.let {
            CategoryResponse(
                id = it[CategoriesTable.id].toString(),
                name = it[CategoriesTable.name],
                createdAt = it[CategoriesTable.createdAt].toString()
            )
        }
    }

    override suspend fun getCategoryByName(name: String): CategoryResponse? = transaction {
        CategoriesTable.select { CategoriesTable.name eq name }.singleOrNull()?.let {
            CategoryResponse(
                id = it[CategoriesTable.id].toString(),
                name = it[CategoriesTable.name],
                createdAt = it[CategoriesTable.createdAt].toString()
            )
        }
    }

    override suspend fun createCategory(name: String): UUID = transaction {
        val insertedId = CategoriesTable.insert {
            it[this.name] = name
        } get CategoriesTable.id
        insertedId
    }

    override suspend fun updateCategory(id: UUID, name: String): Boolean = transaction {
        val updatedRows = CategoriesTable.update({ CategoriesTable.id eq id }) {
            it[this.name] = name
        }
        updatedRows > 0
    }

    override suspend fun deleteCategory(id: UUID): Boolean = transaction {
        val deletedRows = CategoriesTable.deleteWhere { CategoriesTable.id eq id }
        deletedRows > 0
    }

    // ==========================================
    // UNITS
    // ==========================================

    override suspend fun getAllUnits(): List<UnitResponse> = transaction {
        UnitsTable.selectAll().map {
            UnitResponse(
                id = it[UnitsTable.id].toString(),
                name = it[UnitsTable.name],
                symbol = it[UnitsTable.symbol],
                createdAt = it[UnitsTable.createdAt].toString()
            )
        }
    }

    override suspend fun getUnitById(id: UUID): UnitResponse? = transaction {
        UnitsTable.select { UnitsTable.id eq id }.singleOrNull()?.let {
            UnitResponse(
                id = it[UnitsTable.id].toString(),
                name = it[UnitsTable.name],
                symbol = it[UnitsTable.symbol],
                createdAt = it[UnitsTable.createdAt].toString()
            )
        }
    }

    override suspend fun getUnitByNameOrSymbol(name: String, symbol: String): UnitResponse? = transaction {
        UnitsTable.select { (UnitsTable.name eq name) or (UnitsTable.symbol eq symbol) }.firstOrNull()?.let {
            UnitResponse(
                id = it[UnitsTable.id].toString(),
                name = it[UnitsTable.name],
                symbol = it[UnitsTable.symbol],
                createdAt = it[UnitsTable.createdAt].toString()
            )
        }
    }

    override suspend fun createUnit(name: String, symbol: String): UUID = transaction {
        val insertedId = UnitsTable.insert {
            it[this.name] = name
            it[this.symbol] = symbol
        } get UnitsTable.id
        insertedId
    }

    override suspend fun updateUnit(id: UUID, name: String, symbol: String): Boolean = transaction {
        val updatedRows = UnitsTable.update({ UnitsTable.id eq id }) {
            it[this.name] = name
            it[this.symbol] = symbol
        }
        updatedRows > 0
    }

    override suspend fun deleteUnit(id: UUID): Boolean = transaction {
        val deletedRows = UnitsTable.deleteWhere { UnitsTable.id eq id }
        deletedRows > 0
    }

    // ==========================================
    // PRODUCTS
    // ==========================================

    override suspend fun getProducts(page: Int, limit: Int, search: String?): PaginatedResponse<ProductResponse> = transaction {
        val offset = ((page - 1) * limit).toLong()
        
        var query = ProductsTable.select { ProductsTable.isActive eq true }
        
        if (!search.isNullOrBlank()) {
            val searchTerm = "%${search.lowercase()}%"
            query = query.andWhere {
                (ProductsTable.name.lowerCase() like searchTerm) or 
                (ProductsTable.sku.lowerCase() like searchTerm)
            }
        }

        val totalCount = query.count()
        val totalPages = Math.ceil(totalCount.toDouble() / limit).toInt()

        val data = query.limit(limit, offset).map { rowToProductResponse(it) }

        PaginatedResponse(
            data = data,
            total = totalCount,
            page = page,
            limit = limit,
            totalPages = totalPages
        )
    }

    override suspend fun getProductById(id: UUID): ProductResponse? = transaction {
        ProductsTable.select { (ProductsTable.id eq id) and (ProductsTable.isActive eq true) }
            .singleOrNull()?.let { rowToProductResponse(it) }
    }

    override suspend fun getProductBySku(sku: String, includeInactive: Boolean): ProductResponse? = transaction {
        var query = ProductsTable.select { ProductsTable.sku eq sku }
        if (!includeInactive) {
            query = query.andWhere { ProductsTable.isActive eq true }
        }
        query.singleOrNull()?.let { rowToProductResponse(it) }
    }

    override suspend fun createProductAndInitStock(
        categoryId: UUID, baseUnitId: UUID, sku: String, name: String,
        priceBuy: java.math.BigDecimal, priceRetail: java.math.BigDecimal, priceContractor: java.math.BigDecimal,
        minStock: java.math.BigDecimal, photoFilename: String?
    ): UUID = transaction {
        val productId = ProductsTable.insert {
            it[this.categoryId] = categoryId
            it[this.baseUnitId] = baseUnitId
            it[this.sku] = sku
            it[this.name] = name
            it[this.priceBuy] = priceBuy
            it[this.priceRetail] = priceRetail
            it[this.priceContractor] = priceContractor
            it[this.minStock] = minStock
            it[this.photoFilename] = photoFilename
            it[this.isActive] = true
        } get ProductsTable.id

        // Init stock with 0
        StockTable.insert {
            it[this.productId] = productId
            it[this.unitId] = baseUnitId
            it[this.quantity] = java.math.BigDecimal.ZERO
        }

        productId
    }

    override suspend fun updateProduct(
        id: UUID, categoryId: UUID, baseUnitId: UUID, name: String,
        priceBuy: java.math.BigDecimal, priceRetail: java.math.BigDecimal, priceContractor: java.math.BigDecimal,
        minStock: java.math.BigDecimal, photoFilename: String?
    ): Boolean = transaction {
        val updatedRows = ProductsTable.update({ ProductsTable.id eq id }) {
            it[this.categoryId] = categoryId
            it[this.baseUnitId] = baseUnitId
            it[this.name] = name
            it[this.priceBuy] = priceBuy
            it[this.priceRetail] = priceRetail
            it[this.priceContractor] = priceContractor
            it[this.minStock] = minStock
            if (photoFilename != null) {
                it[this.photoFilename] = photoFilename
            }
        }
        updatedRows > 0
    }

    override suspend fun restoreProductAndOverwrite(
        id: UUID, categoryId: UUID, baseUnitId: UUID, name: String,
        priceBuy: java.math.BigDecimal, priceRetail: java.math.BigDecimal, priceContractor: java.math.BigDecimal,
        minStock: java.math.BigDecimal, photoFilename: String?
    ): Boolean = transaction {
        val updatedRows = ProductsTable.update({ ProductsTable.id eq id }) {
            it[this.isActive] = true
            it[this.categoryId] = categoryId
            it[this.baseUnitId] = baseUnitId
            it[this.name] = name
            it[this.priceBuy] = priceBuy
            it[this.priceRetail] = priceRetail
            it[this.priceContractor] = priceContractor
            it[this.minStock] = minStock
            if (photoFilename != null) {
                it[this.photoFilename] = photoFilename
            }
        }
        updatedRows > 0
    }

    override suspend fun softDeleteProduct(id: UUID): Boolean = transaction {
        val updatedRows = ProductsTable.update({ ProductsTable.id eq id }) {
            it[isActive] = false
        }
        updatedRows > 0
    }

    private fun rowToProductResponse(row: ResultRow): ProductResponse {
        return ProductResponse(
            id = row[ProductsTable.id].toString(),
            categoryId = row[ProductsTable.categoryId].toString(),
            baseUnitId = row[ProductsTable.baseUnitId].toString(),
            sku = row[ProductsTable.sku],
            name = row[ProductsTable.name],
            priceBuy = row[ProductsTable.priceBuy],
            priceRetail = row[ProductsTable.priceRetail],
            priceContractor = row[ProductsTable.priceContractor],
            minStock = row[ProductsTable.minStock],
            photoFilename = row[ProductsTable.photoFilename],
            isActive = row[ProductsTable.isActive]
        )
    }

    // ==========================================
    // STOCK MANAGEMENT
    // ==========================================

    override suspend fun getPaginatedStockDetail(limit: Int, offset: Int, search: String?): PaginatedResponse<StockDetailResponse> = transaction {
        var query = VStockDetailView.selectAll()
        
        if (!search.isNullOrBlank()) {
            val searchTerm = "%${search.lowercase()}%"
            query = query.andWhere {
                (VStockDetailView.productName.lowerCase() like searchTerm) or 
                (VStockDetailView.sku.lowerCase() like searchTerm)
            }
        }

        val totalCount = query.count()
        val totalPages = Math.ceil(totalCount.toDouble() / limit).toInt()

        val data = query.limit(limit, offset.toLong()).map { row ->
            StockDetailResponse(
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

        // Hitung page dari limit/offset
        val page = (offset / limit) + 1

        PaginatedResponse(
            data = data,
            total = totalCount,
            page = page,
            limit = limit,
            totalPages = totalPages
        )
    }

    override suspend fun getCurrentStockForUpdate(productId: UUID): java.math.BigDecimal? = transaction {
        StockTable.select { StockTable.productId eq productId }
            .forUpdate()
            .singleOrNull()?.get(StockTable.quantity)
    }

    override suspend fun executeOpname(
        productId: UUID, oldQty: java.math.BigDecimal, newQty: java.math.BigDecimal,
        userId: UUID, adjType: AdjType, notes: String?
    ): Boolean = transaction {
        val qtyDiff = newQty.subtract(oldQty)
        
        // Insert history
        StockAdjustmentsTable.insert {
            it[this.productId] = productId
            it[this.userId] = userId
            it[this.adjType] = adjType
            it[this.qtySystem] = oldQty
            it[this.qtyActual] = newQty
            it[this.qtyDiff] = qtyDiff
            it[this.notes] = notes
        }
        
        // Update stock
        val updatedRows = StockTable.update({ StockTable.productId eq productId }) {
            it[this.quantity] = newQty
        }
        
        updatedRows > 0
    }
}
