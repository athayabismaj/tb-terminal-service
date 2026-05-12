package com.service.tbterminal.sales

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

// ==========================================
// ENUM
// ==========================================

enum class SessionStatus { OPEN, CLOSED }

// ==========================================
// EXPOSED TABLES
// ==========================================

object CashSessionsTable : Table("sales.cash_sessions") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val openedAt = timestamp("opened_at")
    val closedAt = timestamp("closed_at").nullable()
    val openingCash = decimal("opening_cash", 15, 2)
    val closingCash = decimal("closing_cash", 15, 2).nullable()
    val systemCash = decimal("system_cash", 15, 2).nullable()
    val difference = decimal("difference", 15, 2).nullable()
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ==========================================
// DTOs
// ==========================================

@Serializable
data class OpenSessionRequest(
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val startingCash: java.math.BigDecimal
)

@Serializable
data class CloseSessionRequest(
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val endingCashPhysical: java.math.BigDecimal,
    val notes: String? = null
)

@Serializable
data class CashSessionResponse(
    val id: String,
    val userId: String,
    val openedAt: String,
    val closedAt: String?,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val openingCash: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val closingCash: java.math.BigDecimal?,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val systemCash: java.math.BigDecimal?,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val difference: java.math.BigDecimal?,
    val notes: String?,
    val status: String   // "OPEN" | "CLOSED"
)

// ==========================================
// ENUM — Sesuai ENUM PostgreSQL di V5 (lowercase DB values)
// ==========================================

enum class PaymentMethod(val dbValue: String) {
    TUNAI("tunai"),
    TRANSFER("transfer"),
    QRIS("qris"),
    HUTANG("hutang"),
    DP("dp")
}

enum class TrxStatus(val dbValue: String) {
    LUNAS("lunas"),
    DP("dp"),
    HUTANG("hutang")
}

enum class TrxType(val dbValue: String) {
    PENJUALAN("penjualan"),
    RETUR_MASUK("retur_masuk")
}

// ==========================================
// EXPOSED TABLES — POS
// ==========================================

object TransactionsTable : Table("sales.transactions") {
    val id = uuid("id")
    val sessionId = uuid("session_id").references(CashSessionsTable.id)
    val customerId = uuid("customer_id").nullable()
    val userId = uuid("user_id")
    val type = customEnumeration(
        "type", "system.trx_type",
        fromDb = { TrxType.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { it.dbValue }
    )
    val status = customEnumeration(
        "status", "system.trx_status",
        fromDb = { TrxStatus.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { it.dbValue }
    )
    val total = decimal("total", 15, 2)
    val dpAmount = decimal("dp_amount", 15, 2)
    val paidAmount = decimal("paid_amount", 15, 2)
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

object TransactionItemsTable : Table("sales.transaction_items") {
    val id = uuid("id")
    val transactionId = uuid("transaction_id").references(TransactionsTable.id)
    val productId = uuid("product_id")
    val unitId = uuid("unit_id")
    val quantity = decimal("quantity", 10, 2)
    val priceAtTransaction = decimal("price_at_transaction", 15, 2)
    val cogsAtTransaction = decimal("cogs_at_transaction", 15, 2)
    val discount = decimal("discount", 15, 2)
    val subtotal = decimal("subtotal", 15, 2)

    override val primaryKey = PrimaryKey(id)
}

object PaymentsTable : Table("sales.payments") {
    val id = uuid("id")
    val transactionId = uuid("transaction_id").references(TransactionsTable.id)
    val method = customEnumeration(
        "method", "system.payment_method",
        fromDb = { PaymentMethod.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { it.dbValue }
    )
    val amount = decimal("amount", 15, 2)
    val reference = varchar("reference", 100).nullable()
    val paidAt = timestamp("paid_at")

    override val primaryKey = PrimaryKey(id)
}

// ==========================================
// DTOs — POS
// ==========================================

@Serializable
data class CheckoutItemRequest(
    val productId: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val qty: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val discount: java.math.BigDecimal = java.math.BigDecimal.ZERO
)

@Serializable
data class CheckoutRequest(
    val customerId: String? = null,       // null = pelanggan umum
    val items: List<CheckoutItemRequest>,
    val paymentMethod: String,             // "tunai", "transfer", "qris", "hutang", "dp"
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amountPaid: java.math.BigDecimal,
    val notes: String? = null,
    val dueDays: Int = 30                 // Termin piutang jika TEMPO/HUTANG (hari)
)

@Serializable
data class TransactionItemResponse(
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
    val discount: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val subtotal: java.math.BigDecimal
)

@Serializable
data class TransactionResponse(
    val id: String,
    val sessionId: String,
    val customerId: String?,
    val userId: String,
    val type: String,
    val status: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val total: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val dpAmount: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val paidAmount: java.math.BigDecimal,
    val notes: String?,
    val createdAt: String,
    val items: List<TransactionItemResponse>
)

