package com.service.tbterminal.sales

import com.service.tbterminal.system.UsersTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

object CheckoutDiscountAttemptsTable : Table("sales.checkout_discount_attempts") {
    val id = uuid("id")
    val requestedByUserId = uuid("requested_by_user_id").references(UsersTable.id)
    val discountFingerprint = char("discount_fingerprint", 64)
    val grossSubtotal = decimal("gross_subtotal", 15, 2)
    val totalDiscountAmount = decimal("total_discount_amount", 15, 2)
    val effectiveDiscountPercent = decimal("effective_discount_percent", 7, 4)
    val cashierLimitPercent = decimal("cashier_limit_percent", 5, 2)
    val approvalRequired = bool("approval_required")
    val expiresAt = timestampWithTimeZone("expires_at")
    val consumedAt = timestampWithTimeZone("consumed_at").nullable()
    val transactionId = uuid("transaction_id").nullable()
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

data class DiscountAttemptValidation(
    val attemptId: UUID,
    val actorUserId: UUID,
    val discountFingerprint: String,
    val approvalRequired: Boolean,
    val expiresAt: OffsetDateTime,
    val consumedAt: OffsetDateTime?,
    val transactionId: UUID?
)

internal val DEFAULT_CASHIER_DISCOUNT_LIMIT_PERCENT: BigDecimal = BigDecimal("10.00")
