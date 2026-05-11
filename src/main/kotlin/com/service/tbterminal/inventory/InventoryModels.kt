package com.service.tbterminal.inventory

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

// ==========================================
// EXPOSED TABLES (Mapping Database)
// ==========================================

object CategoriesTable : Table("inventory.categories") {
    val id = uuid("id")
    val name = varchar("name", 100)
    val createdAt = timestamp("created_at")
    
    override val primaryKey = PrimaryKey(id)
}

object UnitsTable : Table("inventory.units") {
    val id = uuid("id")
    val name = varchar("name", 50)
    val symbol = varchar("symbol", 20)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ==========================================
// DATA CLASSES (DTOs)
// ==========================================

@Serializable
data class CategoryResponse(
    val id: String,
    val name: String,
    val createdAt: String
)

@Serializable
data class CategoryRequest(
    val name: String
)

@Serializable
data class UnitResponse(
    val id: String,
    val name: String,
    val symbol: String,
    val createdAt: String
)

@Serializable
data class UnitRequest(
    val name: String,
    val symbol: String
)
