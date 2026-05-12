package com.service.tbterminal.receivable

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

// ==========================================
// EXPOSED TABLES
// ==========================================

object CustomersTable : Table("receivable.customers") {
    val id = uuid("id")
    val name = varchar("name", 150)
    val phone = varchar("phone", 20).nullable()
    val address = text("address").nullable()
    val isContractor = bool("is_contractor").default(false)
    val creditLimit = decimal("credit_limit", 15, 2)
    val paymentTermDays = integer("payment_term_days").default(0)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ==========================================
// DTOs
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
