package com.service.tbterminal.inventory

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

internal class InventoryUnitRepository {
    suspend fun getAllUnits(): List<UnitResponse> = transaction {
        UnitsTable.selectAll().map(::toUnitResponse)
    }

    suspend fun getUnitById(id: UUID): UnitResponse? = transaction {
        UnitsTable.select { UnitsTable.id eq id }.singleOrNull()?.let(::toUnitResponse)
    }

    suspend fun getByNameOrSymbol(name: String, symbol: String): UnitResponse? = transaction {
        UnitsTable.select { (UnitsTable.name eq name) or (UnitsTable.symbol eq symbol) }
            .firstOrNull()
            ?.let(::toUnitResponse)
    }

    suspend fun createUnit(name: String, symbol: String): UUID = transaction {
        val insertedId = UUID.randomUUID()
        UnitsTable.insert {
            it[id] = insertedId
            it[this.name] = name
            it[this.symbol] = symbol
        }
        insertedId
    }

    suspend fun updateUnit(id: UUID, name: String, symbol: String): Boolean = transaction {
        UnitsTable.update({ UnitsTable.id eq id }) {
            it[this.name] = name
            it[this.symbol] = symbol
        } > 0
    }

    suspend fun deleteUnit(id: UUID): Boolean = transaction {
        UnitsTable.deleteWhere { UnitsTable.id eq id } > 0
    }

    private fun toUnitResponse(row: ResultRow): UnitResponse {
        return UnitResponse(
            id = row[UnitsTable.id].toString(),
            name = row[UnitsTable.name],
            symbol = row[UnitsTable.symbol],
            createdAt = row[UnitsTable.createdAt].toString()
        )
    }
}
