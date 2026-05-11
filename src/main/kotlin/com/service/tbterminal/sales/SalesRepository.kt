package com.service.tbterminal.sales

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

interface SalesRepository {
    suspend fun getActiveSession(userId: UUID): CashSessionResponse?
    suspend fun getSessionById(sessionId: UUID): CashSessionResponse?
    suspend fun openSession(userId: UUID, startingCash: java.math.BigDecimal): UUID
    suspend fun closeSession(
        sessionId: UUID,
        closingCash: java.math.BigDecimal,
        systemCash: java.math.BigDecimal,
        difference: java.math.BigDecimal,
        notes: String?
    ): Boolean
}

class SalesRepositoryImpl : SalesRepository {

    override suspend fun getActiveSession(userId: UUID): CashSessionResponse? = transaction {
        CashSessionsTable.select {
            (CashSessionsTable.userId eq userId) and
            (CashSessionsTable.closedAt.isNull())
        }.singleOrNull()?.let { rowToResponse(it) }
    }

    override suspend fun getSessionById(sessionId: UUID): CashSessionResponse? = transaction {
        CashSessionsTable.select { CashSessionsTable.id eq sessionId }
            .singleOrNull()?.let { rowToResponse(it) }
    }

    override suspend fun openSession(userId: UUID, startingCash: java.math.BigDecimal): UUID = transaction {
        CashSessionsTable.insert {
            it[this.userId] = userId
            it[this.openingCash] = startingCash
            it[this.systemCash] = startingCash // Initial system_cash = starting_cash
        } get CashSessionsTable.id
    }

    override suspend fun closeSession(
        sessionId: UUID,
        closingCash: java.math.BigDecimal,
        systemCash: java.math.BigDecimal,
        difference: java.math.BigDecimal,
        notes: String?
    ): Boolean = transaction {
        val updatedRows = CashSessionsTable.update({ CashSessionsTable.id eq sessionId }) {
            it[this.closedAt] = Instant.now()
            it[this.closingCash] = closingCash
            it[this.systemCash] = systemCash
            it[this.difference] = difference
            it[this.notes] = notes
        }
        updatedRows > 0
    }

    private fun rowToResponse(row: ResultRow): CashSessionResponse {
        val isClosed = row[CashSessionsTable.closedAt] != null
        return CashSessionResponse(
            id = row[CashSessionsTable.id].toString(),
            userId = row[CashSessionsTable.userId].toString(),
            openedAt = row[CashSessionsTable.openedAt].toString(),
            closedAt = row[CashSessionsTable.closedAt]?.toString(),
            openingCash = row[CashSessionsTable.openingCash],
            closingCash = row[CashSessionsTable.closingCash],
            systemCash = row[CashSessionsTable.systemCash],
            difference = row[CashSessionsTable.difference],
            notes = row[CashSessionsTable.notes],
            status = if (isClosed) SessionStatus.CLOSED.name else SessionStatus.OPEN.name
        )
    }
}
