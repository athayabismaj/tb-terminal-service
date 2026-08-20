package com.service.tbterminal.receivable

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.postgresql.util.PGobject

// ==========================================
// ENUMS — Sesuai ENUM PostgreSQL di V5 (lowercase DB values)
// ==========================================

enum class ReceivableStatus(val dbValue: String) {
    UNPAID("belum_lunas"),
    PARTIAL("sebagian"),
    PAID("lunas")
}

enum class ReceivableSource {
    SALE,
    OPENING_BALANCE,
    ADJUSTMENT
}

enum class ReceivableDueFilter {
    ALL,
    OVERDUE,
    DUE_TODAY,
    UPCOMING
}

enum class RecPaymentMethod(val dbValue: String) {
    TUNAI("tunai"),
    TRANSFER("transfer"),
    QRIS("qris"),
    HUTANG("hutang"),
    DP("dp")
}

enum class ReceivablePaymentEntryType {
    PAYMENT,
    REVERSAL
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
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()
    val updatedAt = timestampWithTimeZone("updated_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

// Dipindahkan dari SalesModels.kt (SalesReceivablesTable) dan diperluas
object ReceivablesTable : Table("receivable.receivables") {
    val id = uuid("id").databaseGenerated()
    val customerId = uuid("customer_id").references(CustomersTable.id)
    val transactionId = uuid("transaction_id").nullable() // Hanya wajib untuk source SALE
    val receivableSource = customEnumeration(
        "source", "system.receivable_source",
        fromDb = { ReceivableSource.valueOf(it.toString()) },
        toDb = { value -> postgresEnum("system.receivable_source", value.name) }
    )
    val amount = decimal("amount", 15, 2)
    val paidAmount = decimal("paid_amount", 15, 2)
    val debtDate = date("debt_date")
    val dueDate = date("due_date")
    val legacyInvoiceNumber = varchar("legacy_invoice_number", 100).nullable()
    val notes = text("notes").nullable()
    val createdBy = uuid("created_by")
    val isActive = bool("is_active").default(true)
    val status = customEnumeration(
        "status", "system.receivable_status",
        fromDb = { ReceivableStatus.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { value -> postgresEnum("system.receivable_status", value.dbValue) }
    )
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()
    val updatedAt = timestampWithTimeZone("updated_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object ReceivablePaymentsTable : Table("receivable.receivable_payments") {
    val id = uuid("id").databaseGenerated()
    val receivableId = uuid("receivable_id").references(ReceivablesTable.id)
    val userId = uuid("user_id") // FK ke system.users (cross-schema, diatur di V11)
    val amount = decimal("amount", 15, 2)
    val paymentNumber = varchar("payment_number", 48).databaseGenerated()
    val paymentDate = date("payment_date").databaseGenerated()
    val entryType = customEnumeration(
        "entry_type", "system.receivable_payment_entry_type",
        fromDb = { ReceivablePaymentEntryType.valueOf(it.toString()) },
        toDb = { value -> postgresEnum("system.receivable_payment_entry_type", value.name) }
    )
    val idempotencyKey = varchar("idempotency_key", 100)
    val reversedPaymentId = uuid("reversed_payment_id").nullable()
    val balanceBefore = decimal("balance_before", 15, 2).databaseGenerated()
    val balanceAfter = decimal("balance_after", 15, 2).databaseGenerated()
    val statusAfter = customEnumeration(
        "status_after", "system.receivable_status",
        fromDb = { ReceivableStatus.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { value -> postgresEnum("system.receivable_status", value.dbValue) }
    ).databaseGenerated()
    val method = customEnumeration(
        "method", "system.payment_method",
        fromDb = { RecPaymentMethod.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { value -> postgresEnum("system.payment_method", value.dbValue) }
    )
    val reference = varchar("reference", 100).nullable()
    val notes = text("notes").nullable()
    val paidAt = timestampWithTimeZone("paid_at").databaseGenerated()

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
    val transactionId: String?,
    val source: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amount: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val paidAmount: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val remainingAmount: java.math.BigDecimal,  // Kalkulasi: amount - paidAmount
    val debtDate: String,
    val dueDate: String,
    val status: String,
    val legacyInvoiceNumber: String?,
    val notes: String?,
    val createdBy: String,
    val isActive: Boolean,
    val createdAt: String
)

@Serializable
data class CreateStandaloneReceivableRequest(
    val customerId: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amount: java.math.BigDecimal,
    val debtDate: String,
    val dueDate: String,
    val legacyInvoiceNumber: String? = null,
    val source: String = "OPENING_BALANCE",
    val notes: String? = null
)

@Serializable
data class CustomerReceivableSummaryResponse(
    val customerId: String,
    val customerName: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val totalAmount: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val totalPaid: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val totalRemaining: java.math.BigDecimal,
    val unpaidCount: Long,
    val overdueCount: Long,
    val nearestDueDate: String?
)

@Serializable
data class PaymentRequest(
    val receivableId: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amount: java.math.BigDecimal,
    val method: String,         // "tunai", "transfer", "qris"
    val reference: String? = null,
    val notes: String? = null,
    val idempotencyKey: String
)

@Serializable
data class ReversePaymentRequest(
    val idempotencyKey: String,
    val reason: String
)

@Serializable
data class PaymentResponse(
    val id: String,
    val paymentNumber: String,
    val receivableId: String,
    val customerId: String,
    val customerName: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amount: java.math.BigDecimal,
    val method: String,
    val reference: String?,
    val notes: String?,
    val paidAt: String,
    val paymentDate: String,
    val entryType: String,
    val reversedPaymentId: String?,
    val receivedBy: String,
    val receivedByName: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val balanceBefore: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val balanceAfter: java.math.BigDecimal,
    // Status piutang setelah pembayaran
    val receivableStatus: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val receivableRemainingAmount: java.math.BigDecimal,
    val idempotentReplay: Boolean
)

@Serializable
data class PaymentHistoryResponse(
    val id: String,
    val paymentNumber: String,
    val receivableId: String,
    val customerId: String,
    val customerName: String,
    val transactionId: String?,
    val source: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amount: java.math.BigDecimal,
    val method: String,
    val reference: String?,
    val notes: String?,
    val paidAt: String,
    val paymentDate: String,
    val entryType: String,
    val reversedPaymentId: String?,
    val receivedBy: String,
    val receivedByName: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val balanceBefore: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val balanceAfter: java.math.BigDecimal,
    val isReversed: Boolean,
    val receivableStatus: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val receivableRemainingAmount: java.math.BigDecimal
)

private fun postgresEnum(type: String, value: String): PGobject {
    return PGobject().apply {
        this.type = type
        this.value = value
    }
}
