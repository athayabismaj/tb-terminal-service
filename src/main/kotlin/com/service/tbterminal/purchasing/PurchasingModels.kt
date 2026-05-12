package com.service.tbterminal.purchasing

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

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

// ==========================================
// DTOs
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
