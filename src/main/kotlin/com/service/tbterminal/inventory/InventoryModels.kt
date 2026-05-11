package com.service.tbterminal.inventory

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

// ==========================================
// EXPOSED TABLES (Mapping Database)
// ==========================================

object CategoriesTable : Table("inventory.categories") {
    val id = uuid("id")
    val name = varchar("name", 100)
    val createdAt = timestamp("created_at")
    
    override val primaryKey = PrimaryKey(id)
}

object UnitsTable : Table("inventory.units") {
    val id = uuid("id")
    val name = varchar("name", 50)
    val symbol = varchar("symbol", 20)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ==========================================
// DATA CLASSES (DTOs)
// ==========================================

@Serializable
data class CategoryResponse(
    val id: String,
    val name: String,
    val createdAt: String
)

@Serializable
data class CategoryRequest(
    val name: String
)

@Serializable
data class UnitResponse(
    val id: String,
    val name: String,
    val symbol: String,
    val createdAt: String
)

@Serializable
data class UnitRequest(
    val name: String,
    val symbol: String
)

object ProductsTable : Table("inventory.products") {
    val id = uuid("id")
    val categoryId = uuid("category_id").references(CategoriesTable.id)
    val baseUnitId = uuid("base_unit_id").references(UnitsTable.id)
    val sku = varchar("sku", 50)
    val name = varchar("name", 200)
    val priceBuy = decimal("price_buy", 15, 2)
    val priceRetail = decimal("price_retail", 15, 2)
    val priceContractor = decimal("price_contractor", 15, 2)
    val minStock = decimal("min_stock", 10, 2)
    val photoFilename = varchar("photo_filename", 255).nullable()
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object StockTable : Table("inventory.stock") {
    val id = uuid("id")
    val productId = uuid("product_id").references(ProductsTable.id)
    val unitId = uuid("unit_id").references(UnitsTable.id)
    val quantity = decimal("quantity", 10, 2)
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class ProductResponse(
    val id: String,
    val categoryId: String,
    val baseUnitId: String,
    val sku: String,
    val name: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceBuy: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceRetail: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceContractor: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val minStock: java.math.BigDecimal,
    val photoFilename: String?,
    val isActive: Boolean
)

@Serializable
data class ProductCreateRequest(
    val categoryId: String,
    val baseUnitId: String,
    val sku: String,
    val name: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceBuy: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceRetail: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceContractor: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val minStock: java.math.BigDecimal,
    val photoFilename: String? = null
)

@Serializable
data class ProductUpdateRequest(
    val categoryId: String,
    val baseUnitId: String,
    val name: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceBuy: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceRetail: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceContractor: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val minStock: java.math.BigDecimal,
    val photoFilename: String? = null
)

@Serializable
data class PaginatedResponse<T>(
    val data: List<T>,
    val total: Long,
    val page: Int,
    val limit: Int,
    val totalPages: Int
)
