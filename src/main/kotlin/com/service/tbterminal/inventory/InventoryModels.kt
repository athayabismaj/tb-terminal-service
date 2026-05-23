package com.service.tbterminal.inventory

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.postgresql.util.PGobject

// ==========================================
// EXPOSED TABLES (Mapping Database)
// ==========================================

object CategoriesTable : Table("inventory.categories") {
    val id = uuid("id").databaseGenerated()
    val name = varchar("name", 100)
    val createdAt = timestamp("created_at").databaseGenerated()
    
    override val primaryKey = PrimaryKey(id)
}

object UnitsTable : Table("inventory.units") {
    val id = uuid("id").databaseGenerated()
    val name = varchar("name", 50)
    val symbol = varchar("symbol", 20)
    val createdAt = timestamp("created_at").databaseGenerated()

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
    val id = uuid("id").databaseGenerated()
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
    val createdAt = timestamp("created_at").databaseGenerated()
    val updatedAt = timestamp("updated_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object StockTable : Table("inventory.stock") {
    val id = uuid("id").databaseGenerated()
    val productId = uuid("product_id").references(ProductsTable.id)
    val unitId = uuid("unit_id").references(UnitsTable.id)
    val quantity = decimal("quantity", 10, 2)
    val updatedAt = timestamp("updated_at").databaseGenerated()

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

enum class AdjType(val dbValue: String) {
    OPNAME("opname"),
    CORRECTION("koreksi"),
    DAMAGE("retur_supplier");

    companion object {
        fun fromDb(value: String): AdjType {
            return entries.first { it.dbValue == value }
        }
    }
}

object StockAdjustmentsTable : Table("inventory.stock_adjustments") {
    val id = uuid("id").databaseGenerated()
    val productId = uuid("product_id").references(ProductsTable.id)
    val userId = uuid("user_id") // References system.users (we don't have the table mapped here, so just UUID)
    val adjType = customEnumeration(
        name = "type",
        sql = "system.adj_type",
        fromDb = { value -> AdjType.fromDb(value.toString()) },
        toDb = { value ->
            PGobject().apply {
                type = "system.adj_type"
                this.value = value.dbValue
            }
        }
    )
    val qtyBefore = decimal("qty_before", 10, 2)
    val qtyAfter = decimal("qty_after", 10, 2)
    val reason = text("reason")
    val createdAt = timestamp("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object VStockDetailView : Table("inventory.v_stock_detail") {
    val productId = uuid("product_id")
    val sku = varchar("sku", 50)
    val productName = varchar("product_name", 200)
    val categoryName = varchar("category_name", 100)
    val unitName = varchar("unit_name", 50)
    val quantity = decimal("quantity", 10, 2)
    val minStock = decimal("min_stock", 10, 2)
    val priceBuy = decimal("price_buy", 15, 2)
    val priceRetail = decimal("price_retail", 15, 2)
    val priceContractor = decimal("price_contractor", 15, 2)
    val isActive = bool("is_active")
}

@Serializable
data class StockDetailResponse(
    val productId: String,
    val sku: String,
    val productName: String,
    val categoryName: String,
    val unitName: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val quantity: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val minStock: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceBuy: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceRetail: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceContractor: java.math.BigDecimal,
    val isActive: Boolean
)

@Serializable
data class StockOpnameRequest(
    val productId: String,
    val adjustmentType: String, // "OPNAME", "CORRECTION", "DAMAGE", dll
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val actualQty: java.math.BigDecimal,
    val notes: String? = null
)
