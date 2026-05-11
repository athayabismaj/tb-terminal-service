package com.service.tbterminal.inventory

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

interface InventoryRepository {
    // Categories
    suspend fun getAllCategories(): List<CategoryResponse>
    suspend fun getCategoryById(id: UUID): CategoryResponse?
    suspend fun getCategoryByName(name: String): CategoryResponse?
    suspend fun createCategory(name: String): UUID
    suspend fun updateCategory(id: UUID, name: String): Boolean
    suspend fun deleteCategory(id: UUID): Boolean

    // Units
    suspend fun getAllUnits(): List<UnitResponse>
    suspend fun getUnitById(id: UUID): UnitResponse?
    suspend fun getUnitByNameOrSymbol(name: String, symbol: String): UnitResponse?
    suspend fun createUnit(name: String, symbol: String): UUID
    suspend fun updateUnit(id: UUID, name: String, symbol: String): Boolean
    suspend fun deleteUnit(id: UUID): Boolean
}

class InventoryRepositoryImpl : InventoryRepository {

    // ==========================================
    // CATEGORIES
    // ==========================================

    override suspend fun getAllCategories(): List<CategoryResponse> = transaction {
        CategoriesTable.selectAll().map {
            CategoryResponse(
                id = it[CategoriesTable.id].toString(),
                name = it[CategoriesTable.name],
                createdAt = it[CategoriesTable.createdAt].toString()
            )
        }
    }

    override suspend fun getCategoryById(id: UUID): CategoryResponse? = transaction {
        CategoriesTable.select { CategoriesTable.id eq id }.singleOrNull()?.let {
            CategoryResponse(
                id = it[CategoriesTable.id].toString(),
                name = it[CategoriesTable.name],
                createdAt = it[CategoriesTable.createdAt].toString()
            )
        }
    }

    override suspend fun getCategoryByName(name: String): CategoryResponse? = transaction {
        CategoriesTable.select { CategoriesTable.name eq name }.singleOrNull()?.let {
            CategoryResponse(
                id = it[CategoriesTable.id].toString(),
                name = it[CategoriesTable.name],
                createdAt = it[CategoriesTable.createdAt].toString()
            )
        }
    }

    override suspend fun createCategory(name: String): UUID = transaction {
        val insertedId = CategoriesTable.insert {
            it[this.name] = name
        } get CategoriesTable.id
        insertedId
    }

    override suspend fun updateCategory(id: UUID, name: String): Boolean = transaction {
        val updatedRows = CategoriesTable.update({ CategoriesTable.id eq id }) {
            it[this.name] = name
        }
        updatedRows > 0
    }

    override suspend fun deleteCategory(id: UUID): Boolean = transaction {
        val deletedRows = CategoriesTable.deleteWhere { CategoriesTable.id eq id }
        deletedRows > 0
    }

    // ==========================================
    // UNITS
    // ==========================================

    override suspend fun getAllUnits(): List<UnitResponse> = transaction {
        UnitsTable.selectAll().map {
            UnitResponse(
                id = it[UnitsTable.id].toString(),
                name = it[UnitsTable.name],
                symbol = it[UnitsTable.symbol],
                createdAt = it[UnitsTable.createdAt].toString()
            )
        }
    }

    override suspend fun getUnitById(id: UUID): UnitResponse? = transaction {
        UnitsTable.select { UnitsTable.id eq id }.singleOrNull()?.let {
            UnitResponse(
                id = it[UnitsTable.id].toString(),
                name = it[UnitsTable.name],
                symbol = it[UnitsTable.symbol],
                createdAt = it[UnitsTable.createdAt].toString()
            )
        }
    }

    override suspend fun getUnitByNameOrSymbol(name: String, symbol: String): UnitResponse? = transaction {
        UnitsTable.select { (UnitsTable.name eq name) or (UnitsTable.symbol eq symbol) }.firstOrNull()?.let {
            UnitResponse(
                id = it[UnitsTable.id].toString(),
                name = it[UnitsTable.name],
                symbol = it[UnitsTable.symbol],
                createdAt = it[UnitsTable.createdAt].toString()
            )
        }
    }

    override suspend fun createUnit(name: String, symbol: String): UUID = transaction {
        val insertedId = UnitsTable.insert {
            it[this.name] = name
            it[this.symbol] = symbol
        } get UnitsTable.id
        insertedId
    }

    override suspend fun updateUnit(id: UUID, name: String, symbol: String): Boolean = transaction {
        val updatedRows = UnitsTable.update({ UnitsTable.id eq id }) {
            it[this.name] = name
            it[this.symbol] = symbol
        }
        updatedRows > 0
    }

    override suspend fun deleteUnit(id: UUID): Boolean = transaction {
        val deletedRows = UnitsTable.deleteWhere { UnitsTable.id eq id }
        deletedRows > 0
    }
}
