package com.service.tbterminal.inventory

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.date
import org.postgresql.util.PGobject

// ==========================================
// EXPOSED TABLES (Mapping Database)
// ==========================================

object CategoriesTable : Table("inventory.categories") {
    val id = uuid("id").databaseGenerated()
    val name = varchar("name", 100)
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()
    
    override val primaryKey = PrimaryKey(id)
}

object UnitsTable : Table("inventory.units") {
    val id = uuid("id").databaseGenerated()
    val name = varchar("name", 50)
    val symbol = varchar("symbol", 20)
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

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
    val discount = decimal("discount", 15, 2).default(java.math.BigDecimal.ZERO)
    val minStock = decimal("min_stock", 10, 2)
    val photoFilename = varchar("photo_filename", 255).nullable()
    val isActive = bool("is_active").default(true)
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()
    val updatedAt = timestampWithTimeZone("updated_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object UnitConversionsTable : Table("inventory.unit_conversions") {
    val id = uuid("id").databaseGenerated()
    val productId = uuid("product_id").references(ProductsTable.id)
    val fromUnitId = uuid("from_unit_id").references(UnitsTable.id)
    val toUnitId = uuid("to_unit_id").references(UnitsTable.id)
    val factor = decimal("factor", 10, 4)
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object StockTable : Table("inventory.stock") {
    val id = uuid("id").databaseGenerated()
    val productId = uuid("product_id").references(ProductsTable.id)
    val unitId = uuid("unit_id").references(UnitsTable.id)
    val quantity = decimal("quantity", 10, 2)
    val updatedAt = timestampWithTimeZone("updated_at").databaseGenerated()

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
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val discount: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val minStock: java.math.BigDecimal,
    val photoFilename: String?,
    val isActive: Boolean,
    val secondaryUnitId: String? = null,
    val secondaryUnitFactor: String? = null
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
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val discount: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val minStock: java.math.BigDecimal,
    val photoFilename: String? = null,
    val secondaryUnitId: String? = null,
    val secondaryUnitFactor: String? = null
)

@Serializable
data class ProductUpdateRequest(
    val categoryId: String,
    val baseUnitId: String,
    val name: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceBuy: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceRetail: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val priceContractor: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val discount: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val minStock: java.math.BigDecimal,
    val photoFilename: String? = null,
    val secondaryUnitId: String? = null,
    val secondaryUnitFactor: String? = null
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
    val adjustmentSource = varchar("source", 30).default("manual")
    val referenceType = varchar("reference_type", 40).nullable()
    val referenceId = uuid("reference_id").nullable()
    val occurredOn = date("occurred_on").databaseGenerated()
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

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
    val discount = decimal("discount", 15, 2).default(java.math.BigDecimal.ZERO)
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
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val discount: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    val isActive: Boolean
)

@Serializable
data class StockAdjustmentResponse(
    val id: String,
    val productId: String,
    val sku: String,
    val productName: String,
    val categoryName: String,
    val unitName: String,
    val adjustmentType: String,
    val adjustmentTypeLabel: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val qtyBefore: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val qtyAfter: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val difference: java.math.BigDecimal,
    val reason: String,
    val userId: String,
    val source: String,
    val occurredOn: String,
    val createdAt: String
)

@Serializable
data class StockOpnameRequest(
    val productId: String,
    val adjustmentType: String, // "OPNAME", "CORRECTION", "DAMAGE", dll
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val actualQty: java.math.BigDecimal,
    val notes: String? = null
)

@Serializable
data class OpeningStockRequest(
    val productId: String,
    val date: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val quantity: java.math.BigDecimal,
    val note: String
)

@Serializable
data class OpeningStockResponse(
    val adjustmentId: String,
    val productId: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val quantity: java.math.BigDecimal,
    val date: String,
    val note: String,
    val userId: String
)

@Serializable
data class ProductCsvImportRequest(val csv: String)

@Serializable
data class ProductCsvRowPreview(
    val rowNumber: Int,
    val sku: String,
    val name: String,
    val category: String,
    val unit: String,
    val priceBuy: String,
    val priceRetail: String,
    val priceContractor: String,
    val minStock: String,
    val openingStock: String,
    val openingDate: String,
    val openingNote: String,
    val errors: List<String>
) {
    val valid: Boolean get() = errors.isEmpty()
}

enum class StockMovementType {
    OPENING_BALANCE, PURCHASE, SALE, OPNAME, CORRECTION, DAMAGE, VOID, LEGACY_BASELINE
}

object StockMovementsTable : Table("inventory.stock_movements") {
    val id = uuid("id").databaseGenerated()
    val sequenceNo = long("sequence_no").databaseGenerated()
    val productId = uuid("product_id").references(ProductsTable.id)
    val unitId = uuid("unit_id").references(UnitsTable.id)
    val movementType = customEnumeration(
        "movement_type", "system.stock_movement_type",
        fromDb = { StockMovementType.valueOf(it.toString()) },
        toDb = { value -> PGobject().apply { type = "system.stock_movement_type"; this.value = value.name } }
    )
    val balanceBefore = decimal("balance_before", 10, 2)
    val qtyIn = decimal("qty_in", 10, 2)
    val qtyOut = decimal("qty_out", 10, 2)
    val balanceAfter = decimal("balance_after", 10, 2)
    val referenceType = varchar("reference_type", 40)
    val referenceId = uuid("reference_id")
    val referenceNumber = varchar("reference_number", 100).nullable()
    val userId = uuid("user_id").nullable()
    val occurredAt = timestampWithTimeZone("occurred_at")
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class StockMovementResponse(
    val id: String,
    val productId: String,
    val sku: String,
    val productName: String,
    val unitName: String,
    val type: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val balanceBefore: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val qtyIn: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val qtyOut: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val balanceAfter: java.math.BigDecimal,
    val referenceType: String,
    val referenceId: String,
    val referenceNumber: String?,
    val userId: String?,
    val occurredAt: String
)

@Serializable
data class StockCardResponse(
    val data: List<StockMovementResponse>,
    val total: Long,
    val page: Int,
    val limit: Int,
    val totalPages: Int,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val currentStock: java.math.BigDecimal?,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val ledgerBalance: java.math.BigDecimal?,
    val reconciled: Boolean
)

@Serializable
data class ProductCsvPreviewResponse(
    val totalRows: Int,
    val validRows: Int,
    val invalidRows: Int,
    val rows: List<ProductCsvRowPreview>
)

@Serializable
data class ProductCsvImportResponse(
    val importedProducts: Int,
    val openingBalances: Int
)
