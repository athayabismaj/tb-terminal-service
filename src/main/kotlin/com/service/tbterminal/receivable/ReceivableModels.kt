package com.service.tbterminal.receivable

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

// ==========================================
// ENUMS — Sesuai ENUM PostgreSQL di V5 (lowercase DB values)
// ==========================================

enum class ReceivableStatus(val dbValue: String) {
    BELUM_LUNAS("belum_lunas"),
    SEBAGIAN("sebagian"),
    LUNAS("lunas")
}

enum class RecPaymentMethod(val dbValue: String) {
    TUNAI("tunai"),
    TRANSFER("transfer"),
    QRIS("qris"),
    HUTANG("hutang"),
    DP("dp")
}

// ==========================================
// EXPOSED TABLES
// ==========================================

object CustomersTable : Table("receivable.customers") {
    val id = uuid("id").databaseGenerated()
    val name = varchar("name", 150)
    val phone = varchar("phone", 20).nullable()
    val address = text("address").nullable()
    val isContractor = bool("is_contractor").default(false)
    val creditLimit = decimal("credit_limit", 15, 2)
    val paymentTermDays = integer("payment_term_days").default(0)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at").databaseGenerated()
    val updatedAt = timestamp("updated_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

// Dipindahkan dari SalesModels.kt (SalesReceivablesTable) dan diperluas
object ReceivablesTable : Table("receivable.receivables") {
    val id = uuid("id").databaseGenerated()
    val customerId = uuid("customer_id").references(CustomersTable.id)
    val transactionId = uuid("transaction_id") // FK ke sales.transactions (cross-schema, diatur di V11)
    val amount = decimal("amount", 15, 2)
    val paidAmount = decimal("paid_amount", 15, 2)
    val dueDate = date("due_date")
    val status = customEnumeration(
        "status", "system.receivable_status",
        fromDb = { ReceivableStatus.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { it.dbValue }
    )
    val createdAt = timestamp("created_at").databaseGenerated()
    val updatedAt = timestamp("updated_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object ReceivablePaymentsTable : Table("receivable.receivable_payments") {
    val id = uuid("id").databaseGenerated()
    val receivableId = uuid("receivable_id").references(ReceivablesTable.id)
    val userId = uuid("user_id") // FK ke system.users (cross-schema, diatur di V11)
    val amount = decimal("amount", 15, 2)
    val method = customEnumeration(
        "method", "system.payment_method",
        fromDb = { RecPaymentMethod.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { it.dbValue }
    )
    val reference = varchar("reference", 100).nullable()
    val notes = text("notes").nullable()
    val paidAt = timestamp("paid_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

// ==========================================
// DTOs — Customer
// ==========================================

@Serializable
data class CustomerRequest(
    val name: String,
    val phone: String? = null,
    val address: String? = null,
    val isContractor: Boolean = false,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val creditLimit: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    val paymentTermDays: Int = 0
)

@Serializable
data class CustomerResponse(
    val id: String,
    val name: String,
    val phone: String?,
    val address: String?,
    val isContractor: Boolean,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val creditLimit: java.math.BigDecimal,
    val paymentTermDays: Int,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String
)

// ==========================================
// DTOs — Receivable & Payment
// ==========================================

@Serializable
data class ReceivableResponse(
    val id: String,
    val customerId: String,
    val customerName: String,
    val transactionId: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amount: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val paidAmount: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val remainingAmount: java.math.BigDecimal,  // Kalkulasi: amount - paidAmount
    val dueDate: String,
    val status: String,
    val createdAt: String
)

@Serializable
data class PaymentRequest(
    val receivableId: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amount: java.math.BigDecimal,
    val method: String,         // "tunai", "transfer", "qris"
    val reference: String? = null,
    val notes: String? = null
)

@Serializable
data class PaymentResponse(
    val id: String,
    val receivableId: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amount: java.math.BigDecimal,
    val method: String,
    val reference: String?,
    val notes: String?,
    val paidAt: String,
    // Status piutang setelah pembayaran
    val receivableStatus: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val receivableRemainingAmount: java.math.BigDecimal
)
