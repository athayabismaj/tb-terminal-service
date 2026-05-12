package com.service.tbterminal.sales

import com.service.tbterminal.receivable.ReceivableStatus
import com.service.tbterminal.receivable.ReceivablesTable
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

    // POS
    suspend fun executeCheckout(
        sessionId: UUID,
        userId: UUID,
        customerId: UUID?,
        resolvedItems: List<ResolvedItem>,
        totalAmount: java.math.BigDecimal,
        paymentMethod: PaymentMethod,
        amountPaid: java.math.BigDecimal,
        trxStatus: TrxStatus,
        notes: String?,
        dueDays: Int
    ): TransactionResponse

    suspend fun getPaginatedTransactions(
        page: Int,
        limit: Int,
        sessionId: UUID? = null
    ): com.service.tbterminal.inventory.PaginatedResponse<TransactionSummary>

    suspend fun getTransactionById(id: UUID): TransactionResponse?
}

// Data class internal untuk membawa data produk yang sudah di-resolve dari DB
data class ResolvedItem(
    val productId: UUID,
    val unitId: UUID,
    val productName: String,
    val qty: java.math.BigDecimal,
    val priceAtTransaction: java.math.BigDecimal,
    val cogsAtTransaction: java.math.BigDecimal,
    val discount: java.math.BigDecimal,
    val subtotal: java.math.BigDecimal
)

@kotlinx.serialization.Serializable
data class TransactionSummary(
    val id: String,
    val sessionId: String,
    val customerId: String?,
    val type: String,
    val status: String,
    @kotlinx.serialization.Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val total: java.math.BigDecimal,
    @kotlinx.serialization.Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val paidAmount: java.math.BigDecimal,
    val createdAt: String
)


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

    // ==========================================
    // POS — CHECKOUT ENGINE
    // ==========================================

    override suspend fun executeCheckout(
        sessionId: UUID,
        userId: UUID,
        customerId: UUID?,
        resolvedItems: List<ResolvedItem>,
        totalAmount: java.math.BigDecimal,
        paymentMethod: PaymentMethod,
        amountPaid: java.math.BigDecimal,
        trxStatus: TrxStatus,
        notes: String?,
        dueDays: Int
    ): TransactionResponse = transaction {
        // Tentukan dpAmount
        val dpAmount = if (trxStatus == TrxStatus.DP) amountPaid else java.math.BigDecimal.ZERO
        val paidAmount = if (trxStatus == TrxStatus.LUNAS) totalAmount else amountPaid

        // 1. Insert transaksi utama
        val trxId = TransactionsTable.insert {
            it[this.sessionId] = sessionId
            it[this.userId] = userId
            it[this.customerId] = customerId
            it[this.type] = TrxType.PENJUALAN
            it[this.status] = trxStatus
            it[this.total] = totalAmount
            it[this.dpAmount] = dpAmount
            it[this.paidAmount] = paidAmount
            it[this.notes] = notes
        } get TransactionsTable.id

        // 2. Loop insert transaction_items (trigger fn_sync_stock akan berjalan otomatis)
        resolvedItems.forEach { item ->
            TransactionItemsTable.insert {
                it[this.transactionId] = trxId
                it[this.productId] = item.productId
                it[this.unitId] = item.unitId
                it[this.quantity] = item.qty
                it[this.priceAtTransaction] = item.priceAtTransaction
                it[this.cogsAtTransaction] = item.cogsAtTransaction
                it[this.discount] = item.discount
                it[this.subtotal] = item.subtotal
            }
        }

        // 3. Insert payment record
        PaymentsTable.insert {
            it[this.transactionId] = trxId
            it[this.method] = paymentMethod
            it[this.amount] = amountPaid
        }

        // 4. Jika HUTANG/DP — insert ke receivable.receivables
        if ((trxStatus == TrxStatus.HUTANG || trxStatus == TrxStatus.DP) && customerId != null) {
            val sisaHutang = totalAmount.subtract(amountPaid)
            ReceivablesTable.insert {
                it[this.customerId] = customerId
                it[this.transactionId] = trxId
                it[this.amount] = sisaHutang
                it[this.paidAmount] = java.math.BigDecimal.ZERO
                it[this.dueDate] = java.time.LocalDate.now().plusDays(dueDays.toLong())
                it[this.status] = ReceivableStatus.BELUM_LUNAS
            }
        }

        // Ambil data transaksi yang baru dibuat (inline, tidak memanggil suspend function)
        val trxRow = TransactionsTable.select { TransactionsTable.id eq trxId }.single()
        val items = TransactionItemsTable.select { TransactionItemsTable.transactionId eq trxId }
            .map { row ->
                TransactionItemResponse(
                    productId = row[TransactionItemsTable.productId].toString(),
                    productName = resolvedItems.firstOrNull { it.productId.toString() == row[TransactionItemsTable.productId].toString() }?.productName ?: "",
                    unitId = row[TransactionItemsTable.unitId].toString(),
                    quantity = row[TransactionItemsTable.quantity],
                    priceAtTransaction = row[TransactionItemsTable.priceAtTransaction],
                    cogsAtTransaction = row[TransactionItemsTable.cogsAtTransaction],
                    discount = row[TransactionItemsTable.discount],
                    subtotal = row[TransactionItemsTable.subtotal]
                )
            }

        TransactionResponse(
            id = trxRow[TransactionsTable.id].toString(),
            sessionId = trxRow[TransactionsTable.sessionId].toString(),
            customerId = trxRow[TransactionsTable.customerId]?.toString(),
            userId = trxRow[TransactionsTable.userId].toString(),
            type = trxRow[TransactionsTable.type].dbValue,
            status = trxRow[TransactionsTable.status].dbValue,
            total = trxRow[TransactionsTable.total],
            dpAmount = trxRow[TransactionsTable.dpAmount],
            paidAmount = trxRow[TransactionsTable.paidAmount],
            notes = trxRow[TransactionsTable.notes],
            createdAt = trxRow[TransactionsTable.createdAt].toString(),
            items = items
        )
    }

    override suspend fun getTransactionById(id: UUID): TransactionResponse? = transaction {
        val trxRow = TransactionsTable.select { TransactionsTable.id eq id }.singleOrNull()
            ?: return@transaction null

        val items = TransactionItemsTable.select { TransactionItemsTable.transactionId eq id }
            .map { row ->
                TransactionItemResponse(
                    productId = row[TransactionItemsTable.productId].toString(),
                    productName = "", // join ke inventory tidak dilakukan (snapshot sudah ada)
                    unitId = row[TransactionItemsTable.unitId].toString(),
                    quantity = row[TransactionItemsTable.quantity],
                    priceAtTransaction = row[TransactionItemsTable.priceAtTransaction],
                    cogsAtTransaction = row[TransactionItemsTable.cogsAtTransaction],
                    discount = row[TransactionItemsTable.discount],
                    subtotal = row[TransactionItemsTable.subtotal]
                )
            }

        TransactionResponse(
            id = trxRow[TransactionsTable.id].toString(),
            sessionId = trxRow[TransactionsTable.sessionId].toString(),
            customerId = trxRow[TransactionsTable.customerId]?.toString(),
            userId = trxRow[TransactionsTable.userId].toString(),
            type = trxRow[TransactionsTable.type].dbValue,
            status = trxRow[TransactionsTable.status].dbValue,
            total = trxRow[TransactionsTable.total],
            dpAmount = trxRow[TransactionsTable.dpAmount],
            paidAmount = trxRow[TransactionsTable.paidAmount],
            notes = trxRow[TransactionsTable.notes],
            createdAt = trxRow[TransactionsTable.createdAt].toString(),
            items = items
        )
    }

    override suspend fun getPaginatedTransactions(
        page: Int,
        limit: Int,
        sessionId: UUID?
    ): com.service.tbterminal.inventory.PaginatedResponse<TransactionSummary> = transaction {
        val offset = ((page - 1) * limit).toLong()

        var query = TransactionsTable.selectAll()
        if (sessionId != null) {
            query = query.andWhere { TransactionsTable.sessionId eq sessionId }
        }

        val total = query.count()
        val totalPages = Math.ceil(total.toDouble() / limit).toInt()

        val data = query.orderBy(TransactionsTable.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .map { row ->
                TransactionSummary(
                    id = row[TransactionsTable.id].toString(),
                    sessionId = row[TransactionsTable.sessionId].toString(),
                    customerId = row[TransactionsTable.customerId]?.toString(),
                    type = row[TransactionsTable.type].dbValue,
                    status = row[TransactionsTable.status].dbValue,
                    total = row[TransactionsTable.total],
                    paidAmount = row[TransactionsTable.paidAmount],
                    createdAt = row[TransactionsTable.createdAt].toString()
                )
            }

        com.service.tbterminal.inventory.PaginatedResponse(
            data = data,
            total = total,
            page = page,
            limit = limit,
            totalPages = totalPages
        )
    }
}

