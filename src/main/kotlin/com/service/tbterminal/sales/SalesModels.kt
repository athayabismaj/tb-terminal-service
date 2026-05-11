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
