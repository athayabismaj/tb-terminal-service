package com.service.tbterminal.inventory

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

internal class InventoryCategoryRepository {
    suspend fun getAllCategories(): List<CategoryResponse> = transaction {
        CategoriesTable.selectAll().map(::toCategoryResponse)
    }

    suspend fun getCategoryById(id: UUID): CategoryResponse? = transaction {
        CategoriesTable.select { CategoriesTable.id eq id }.singleOrNull()?.let(::toCategoryResponse)
    }

    suspend fun getCategoryByName(name: String): CategoryResponse? = transaction {
        CategoriesTable.select { CategoriesTable.name eq name }.singleOrNull()?.let(::toCategoryResponse)
    }

    suspend fun createCategory(name: String): UUID = transaction {
        val insertedId = UUID.randomUUID()
        CategoriesTable.insert {
            it[id] = insertedId
            it[this.name] = name
        }
        insertedId
    }

    suspend fun updateCategory(id: UUID, name: String): Boolean = transaction {
        CategoriesTable.update({ CategoriesTable.id eq id }) {
            it[this.name] = name
        } > 0
    }

    suspend fun deleteCategory(id: UUID): Boolean = transaction {
        CategoriesTable.deleteWhere { CategoriesTable.id eq id } > 0
    }

    private fun toCategoryResponse(row: ResultRow): CategoryResponse {
        return CategoryResponse(
            id = row[CategoriesTable.id].toString(),
            name = row[CategoriesTable.name],
            createdAt = row[CategoriesTable.createdAt].toString()
        )
    }
}
