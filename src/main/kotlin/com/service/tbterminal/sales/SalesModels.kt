package com.service.tbterminal.sales

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.postgresql.util.PGobject

// ==========================================
// ENUM
// ==========================================

enum class SessionStatus { OPEN, CLOSED }

// ==========================================
// EXPOSED TABLES
// ==========================================

object CashSessionsTable : Table("sales.cash_sessions") {
    val id = uuid("id").databaseGenerated()
    val userId = uuid("user_id")
    val openedAt = timestampWithTimeZone("opened_at").databaseGenerated()
    val closedAt = timestampWithTimeZone("closed_at").nullable()
    val openingCash = decimal("opening_cash", 15, 2)
    val closingCash = decimal("closing_cash", 15, 2).nullable()
    val systemCash = decimal("system_cash", 15, 2).nullable()
    val difference = decimal("difference", 15, 2).nullable()
    val notes = text("notes").nullable()
    val clientGeneratedId = varchar("client_generated_id", 100).nullable()
    val deviceId = varchar("device_id", 100).nullable()
    val offlineSyncedAt = timestampWithTimeZone("offline_synced_at").nullable()
    val closeClientGeneratedId = varchar("close_client_generated_id", 100).nullable()
    val closeDeviceId = varchar("close_device_id", 100).nullable()
    val closeOfflineSyncedAt = timestampWithTimeZone("close_offline_synced_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object CashExpensesTable : Table("sales.cash_expenses") {
    val id = uuid("id").databaseGenerated()
    val sessionId = uuid("session_id").references(CashSessionsTable.id)
    val userId = uuid("user_id")
    val amount = decimal("amount", 15, 2)
    val description = text("description")
    val category = varchar("category", 100).nullable()
    val clientGeneratedId = varchar("client_generated_id", 100).nullable()
    val deviceId = varchar("device_id", 100).nullable()
    val occurredAt = timestampWithTimeZone("occurred_at").nullable()
    val offlineSyncedAt = timestampWithTimeZone("offline_synced_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

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
data class CashExpenseRequest(
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amount: java.math.BigDecimal,
    val description: String
)

@Serializable
data class PayDebtRequest(
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amount: java.math.BigDecimal,
    val method: String // "tunai", "transfer", "qris"
)

@Serializable
data class CashExpenseResponse(
    val id: String,
    val sessionId: String,
    val userId: String,
    val userName: String? = null,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amount: java.math.BigDecimal,
    val description: String,
    val createdAt: String
)

@Serializable
data class OfflineCashExpenseSyncRequest(
    val clientGeneratedId: String,
    val deviceId: String,
    val cashierUserId: String,
    val serverCashSessionId: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amount: java.math.BigDecimal,
    val category: String,
    val note: String,
    val occurredAt: String
)

@Serializable
data class OfflineCashExpenseSyncResponse(
    val syncStatus: String,
    val serverExpenseId: String,
    val syncedAt: String
)

@Serializable
data class CashSessionResponse(
    val id: String,
    val userId: String,
    val userName: String? = null,
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
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val totalExpenses: java.math.BigDecimal,
    val notes: String?,
    val status: String   // "OPEN" | "CLOSED"
)

@Serializable
data class OfflineCashSessionOpenSyncRequest(
    val clientGeneratedId: String,
    val deviceId: String,
    val cashierUserId: String,
    val openedAt: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val startingCash: java.math.BigDecimal,
    val openingNote: String? = null
)

@Serializable
data class OfflineCashSessionOpenSyncResponse(
    val syncStatus: String,
    val serverCashSessionId: String,
    val openedAt: String,
    val syncedAt: String
)

@Serializable
data class OfflineCashSessionCloseSyncRequest(
    val deviceId: String,
    val clientGeneratedId: String,
    val serverCashSessionId: String,
    val cashierUserId: String,
    val closedAt: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val actualCash: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val expectedCash: java.math.BigDecimal? = null,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val difference: java.math.BigDecimal? = null,
    val closingNote: String? = null
)

@Serializable
data class OfflineCashSessionCloseSyncResponse(
    val syncStatus: String,
    val serverCashSessionId: String,
    val closedAt: String,
    val syncedAt: String
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
    HUTANG("hutang"),
    VOIDED("voided")
}

enum class TrxType(val dbValue: String) {
    PENJUALAN("penjualan"),
    RETUR_MASUK("retur_masuk")
}

// ==========================================
// EXPOSED TABLES — POS
// ==========================================

object TransactionsTable : Table("sales.transactions") {
    val id = uuid("id").databaseGenerated()
    val sessionId = uuid("session_id").references(CashSessionsTable.id)
    val customerId = uuid("customer_id").nullable()
    val userId = uuid("user_id")
    val type = customEnumeration(
        "type", "system.trx_type",
        fromDb = { TrxType.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { value -> postgresEnum("system.trx_type", value.dbValue) }
    )
    val status = customEnumeration(
        "status", "system.trx_status",
        fromDb = { TrxStatus.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { value -> postgresEnum("system.trx_status", value.dbValue) }
    )
    val total = decimal("total", 15, 2)
    val dpAmount = decimal("dp_amount", 15, 2)
    val paidAmount = decimal("paid_amount", 15, 2)
    val receiptNumber = varchar("receipt_number", 50).databaseGenerated()
    val idempotencyKey = varchar("idempotency_key", 100).nullable()
    val requestFingerprint = char("request_fingerprint", 64).nullable()
    val amountTendered = decimal("amount_tendered", 15, 2).default(java.math.BigDecimal.ZERO)
    val changeAmount = decimal("change_amount", 15, 2).default(java.math.BigDecimal.ZERO)
    val notes = text("notes").nullable()
    val clientGeneratedId = varchar("client_generated_id", 100).nullable()
    val deviceId = varchar("device_id", 100).nullable()
    val occurredAt = timestampWithTimeZone("occurred_at").nullable()
    val syncedAt = timestampWithTimeZone("synced_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object TransactionItemsTable : Table("sales.transaction_items") {
    val id = uuid("id").databaseGenerated()
    val transactionId = uuid("transaction_id").references(TransactionsTable.id)
    val productId = uuid("product_id")
    val productName = varchar("product_name", 255)
    val unitId = uuid("unit_id")
    val quantity = decimal("quantity", 10, 2)
    val priceAtTransaction = decimal("price_at_transaction", 15, 2)
    val cogsAtTransaction = decimal("cogs_at_transaction", 15, 2)
    val discount = decimal("discount", 15, 2)
    val subtotal = decimal("subtotal", 15, 2)

    override val primaryKey = PrimaryKey(id)
}

object PaymentsTable : Table("sales.payments") {
    val id = uuid("id").databaseGenerated()
    val transactionId = uuid("transaction_id").references(TransactionsTable.id)
    val method = customEnumeration(
        "method", "system.payment_method",
        fromDb = { PaymentMethod.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { value -> postgresEnum("system.payment_method", value.dbValue) }
    )
    val amount = decimal("amount", 15, 2)
    val reference = varchar("reference", 100).nullable()
    val transactionVoidId = uuid("transaction_void_id").nullable()
    val paidAt = timestampWithTimeZone("paid_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object TransactionVoidsTable : Table("sales.transaction_voids") {
    val id = uuid("id").databaseGenerated()
    val transactionId = uuid("transaction_id").references(TransactionsTable.id)
    val voidedBy = uuid("voided_by")
    val reason = text("reason")
    val idempotencyKey = varchar("idempotency_key", 100)
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

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
    val idempotencyKey: String,
    val customerId: String? = null,       // null = pelanggan umum
    val items: List<CheckoutItemRequest>,
    val paymentMethod: String,             // "tunai", "transfer", "qris", "hutang", "dp"
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amountPaid: java.math.BigDecimal,
    val notes: String? = null,
    val dueDays: Int = 30                 // Termin piutang jika TEMPO/HUTANG (hari)
)

@Serializable
data class VoidTransactionRequest(
    val idempotencyKey: String,
    val reason: String
)

@Serializable
data class VoidTransactionResponse(
    val voidId: String,
    val transactionId: String,
    val receiptId: String,
    val status: String,
    val reason: String,
    val voidedBy: String,
    val voidedByName: String?,
    val voidedAt: String,
    val idempotentReplay: Boolean = false
)

@Serializable
data class OfflineCheckoutItemRequest(
    val productId: String,
    val productNameSnapshot: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val quantity: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val priceAtTransaction: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val cogsAtTransaction: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val discount: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val subtotal: java.math.BigDecimal
)

@Serializable
data class OfflineCheckoutSyncRequest(
    val clientGeneratedId: String,
    val deviceId: String,
    val localTransactionCode: String,
    val cashierUserId: String,
    val cashSessionId: String,
    val customerId: String? = null,
    val paymentMethod: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val subtotal: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val discount: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val total: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val paidAmount: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val remainingAmount: java.math.BigDecimal,
    val occurredAt: String,
    val note: String? = null,
    val dueDays: Int = 30,
    val items: List<OfflineCheckoutItemRequest>
)

@Serializable
data class OfflineCheckoutSyncResponse(
    val syncStatus: String,
    val serverTransactionId: String,
    val receiptId: String,
    val serverPaymentIds: List<String>,
    val serverReceivableId: String?,
    val syncedAt: String
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
    val receiptId: String,
    val idempotentReplay: Boolean = false,
    val sessionId: String,
    val customerId: String?,
    val customerName: String?,
    val userId: String,
    val cashierName: String? = null,
    val paymentMethods: List<String> = emptyList(),
    val type: String,
    val status: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val total: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val dpAmount: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val paidAmount: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val amountTendered: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val changeAmount: java.math.BigDecimal,
    val notes: String?,
    val createdAt: String,
    val voidedAt: String? = null,
    val voidedBy: String? = null,
    val voidedByName: String? = null,
    val voidReason: String? = null,
    val items: List<TransactionItemResponse>
)

private fun postgresEnum(type: String, value: String): PGobject {
    return PGobject().apply {
        this.type = type
        this.value = value
    }
}
