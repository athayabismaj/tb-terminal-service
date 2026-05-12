package com.service.tbterminal.purchasing

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

// ==========================================
// ENUMS
// ==========================================

enum class PayableStatus(val dbValue: String) {
    BELUM_LUNAS("belum_lunas"),
    SEBAGIAN("sebagian"),
    LUNAS("lunas")
}

enum class PurchasePaymentMethod(val dbValue: String) {
    TUNAI("tunai"),
    TRANSFER("transfer"),
    QRIS("qris"),
    HUTANG("hutang"),
    DP("dp")
}

// ==========================================
// EXPOSED TABLES
// ==========================================

object SuppliersTable : Table("purchasing.suppliers") {
    val id = uuid("id")
    val name = varchar("name", 150)
    val phone = varchar("phone", 20).nullable()
    val address = text("address").nullable()
    val paymentTermDays = integer("payment_term_days").default(30)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object PurchasesTable : Table("purchasing.purchases") {
    val id = uuid("id")
    val supplierId = uuid("supplier_id").references(SuppliersTable.id)
    val userId = uuid("user_id")
    val invoiceNo = varchar("invoice_no", 100).nullable()
    val total = decimal("total", 15, 2)
    val receivedAt = timestamp("received_at")
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

object PurchaseItemsTable : Table("purchasing.purchase_items") {
    val id = uuid("id")
    val purchaseId = uuid("purchase_id").references(PurchasesTable.id)
    val productId = uuid("product_id")
    val unitId = uuid("unit_id")
    val quantity = decimal("quantity", 10, 2)
    val priceAtTransaction = decimal("price_at_transaction", 15, 2)
    val cogsAtTransaction = decimal("cogs_at_transaction", 15, 2)
    val subtotal = decimal("subtotal", 15, 2)

    override val primaryKey = PrimaryKey(id)
}

object SupplierPayablesTable : Table("purchasing.supplier_payables") {
    val id = uuid("id")
    val supplierId = uuid("supplier_id").references(SuppliersTable.id)
    val purchaseId = uuid("purchase_id").references(PurchasesTable.id)
    val amount = decimal("amount", 15, 2)
    val paidAmount = decimal("paid_amount", 15, 2)
    val dueDate = date("due_date")
    val status = customEnumeration(
        "status", "system.payable_status",
        fromDb = { PayableStatus.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { it.dbValue }
    )
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ==========================================
// DTOs — Supplier
// ==========================================

@Serializable
data class SupplierRequest(
    val name: String,
    val phone: String? = null,
    val address: String? = null,
    val paymentTermDays: Int = 30
)

@Serializable
data class SupplierResponse(
    val id: String,
    val name: String,
    val phone: String?,
    val address: String?,
    val paymentTermDays: Int,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String
)

// ==========================================
// DTOs — Purchase
// ==========================================

@Serializable
data class PurchaseItemRequest(
    val productId: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val qty: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val price: java.math.BigDecimal   // Harga beli per unit dari nota supplier
)

@Serializable
data class PurchaseRequest(
    val supplierId: String,
    val invoiceNo: String? = null,
    val paymentMethod: String,          // "tunai", "transfer", "qris", "hutang", "dp"
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amountPaid: java.math.BigDecimal,
    val notes: String? = null,
    val dueDays: Int = 30,              // Termin hutang jika HUTANG/DP
    val items: List<PurchaseItemRequest>
)

@Serializable
data class PurchaseItemResponse(
    val productId: String,
    val productName: String,
    val unitId: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val quantity: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val priceAtTransaction: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val cogsAtTransaction: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val subtotal: java.math.BigDecimal
)

@Serializable
data class PurchaseResponse(
    val id: String,
    val supplierId: String,
    val supplierName: String,
    val userId: String,
    val invoiceNo: String?,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val total: java.math.BigDecimal,
    val notes: String?,
    val receivedAt: String,
    val createdAt: String,
    val items: List<PurchaseItemResponse>
)

@Serializable
data class PurchaseSummary(
    val id: String,
    val supplierId: String,
    val supplierName: String,
    val invoiceNo: String?,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val total: java.math.BigDecimal,
    val receivedAt: String,
    val createdAt: String
)

