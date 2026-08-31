package com.service.tbterminal.sales

import com.service.tbterminal.system.ManagerApprovalsTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

enum class RefundStatus {
    COMPLETED
}

enum class RefundDisposition {
    RETURN_TO_STOCK,
    NOT_RETURNED,
    DAMAGED
}

object TransactionRefundsTable : Table("sales.transaction_refunds") {
    val id = uuid("id").databaseGenerated()
    val transactionId = uuid("transaction_id").references(TransactionsTable.id)
    val refundNumber = varchar("refund_number", 50).databaseGenerated()
    val reason = text("reason")
    val transactionAmount = decimal("transaction_amount", 15, 2)
    val refundedAmount = decimal("refunded_amount", 15, 2)
    val status = enumerationByName<RefundStatus>("status", 20)
    val disposition = enumerationByName<RefundDisposition>("return_disposition", 30)
    val requestedByUserId = uuid("requested_by_user_id")
    val approvedByUserId = uuid("approved_by_user_id").nullable()
    val managerApprovalId = uuid("manager_approval_id").references(ManagerApprovalsTable.id).nullable()
    val idempotencyKey = varchar("idempotency_key", 100)
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class RefundTransactionRequest(
    val idempotencyKey: String,
    val reason: String,
    val returnDisposition: String = RefundDisposition.RETURN_TO_STOCK.name,
    val managerApprovalId: String? = null
)

@Serializable
data class RefundTransactionResponse(
    val refundId: String,
    val refundNumber: String,
    val transactionId: String,
    val status: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val transactionAmount: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val refundedAmount: java.math.BigDecimal,
    val returnDisposition: String,
    val reason: String,
    val requestedByUserId: String,
    val createdAt: String,
    val managerApprovalId: String? = null,
    val idempotentReplay: Boolean = false
)
