package com.service.tbterminal.sales

import com.service.tbterminal.inventory.ProductsTable
import com.service.tbterminal.inventory.StockTable
import com.service.tbterminal.inventory.StockAdjustmentsTable
import com.service.tbterminal.inventory.StockMovementsTable
import com.service.tbterminal.inventory.AdjType
import com.service.tbterminal.receivable.CustomersTable
import com.service.tbterminal.receivable.ReceivableStatus
import com.service.tbterminal.receivable.ReceivableSource
import com.service.tbterminal.receivable.ReceivablePaymentsTable
import com.service.tbterminal.receivable.ReceivablePaymentEntryType
import com.service.tbterminal.receivable.RecPaymentMethod
import com.service.tbterminal.receivable.ReceivablesTable
import com.service.tbterminal.receivable.ensureReceivableCreditLimit
import com.service.tbterminal.receivable.receivableToday
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.SessionNotFoundException
import com.service.tbterminal.shared.StockInsufficientException
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.system.UsersTable
import com.service.tbterminal.system.AuditAction
import com.service.tbterminal.system.AuditLogsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID

interface SalesRepository {
    suspend fun getActiveSession(userId: UUID): CashSessionResponse?
    suspend fun getSessionById(sessionId: UUID): CashSessionResponse?
    suspend fun getPaginatedSessions(
        page: Int,
        limit: Int,
        status: String?,
        startDate: String? = null,
        endDate: String? = null
    ): com.service.tbterminal.inventory.PaginatedResponse<CashSessionResponse>
    suspend fun openSession(userId: UUID, startingCash: java.math.BigDecimal): CashSessionResponse
    suspend fun findOfflineSyncedCashSession(
        deviceId: String,
        clientGeneratedId: String,
        cashierUserId: UUID? = null
    ): OfflineCashSessionOpenSyncResponse?
    suspend fun syncOpenCashSession(command: OfflineCashSessionOpenSyncCommand): OfflineCashSessionOpenSyncResponse
    suspend fun syncCloseCashSession(command: OfflineCashSessionCloseSyncCommand): OfflineCashSessionCloseSyncResponse
    suspend fun closeSession(
        sessionId: UUID,
        closingCash: java.math.BigDecimal,
        systemCash: java.math.BigDecimal,
        difference: java.math.BigDecimal,
        notes: String?
    ): Boolean

    // POS
    suspend fun executeCheckout(
        userId: UUID,
        customerId: UUID?,
        requestItems: List<CheckoutItemRequest>,
        paymentMethod: PaymentMethod,
        amountPaid: java.math.BigDecimal,
        notes: String?,
        dueDays: Int,
        idempotencyKey: String,
        requestFingerprint: String
    ): TransactionResponse
    suspend fun findCheckoutByIdempotencyKey(idempotencyKey: String): IdempotentCheckout?

    suspend fun findOfflineSyncedCheckout(
        deviceId: String,
        clientGeneratedId: String,
        cashierUserId: UUID? = null
    ): OfflineCheckoutSyncResponse?
    suspend fun executeOfflineCheckoutSync(command: OfflineCheckoutSyncCommand): OfflineCheckoutSyncResponse

    suspend fun getPaginatedTransactions(
        page: Int,
        limit: Int,
        sessionId: UUID? = null,
        search: String? = null,
        receiptNumber: String? = null,
        cashierId: UUID? = null,
        customerId: UUID? = null,
        paymentMethod: PaymentMethod? = null,
        status: TrxStatus? = null,
        startAt: OffsetDateTime? = null,
        endExclusive: OffsetDateTime? = null
    ): com.service.tbterminal.inventory.PaginatedResponse<TransactionSummary>

    suspend fun getTransactionById(id: UUID): TransactionResponse?
    suspend fun voidTransaction(
        actorUserId: UUID,
        transactionId: UUID,
        reason: String,
        idempotencyKey: String
    ): VoidTransactionResponse
    suspend fun getReceivableIdByTransactionId(transactionId: UUID): UUID?

    // KAS HARIAN (PETTY CASH)
    suspend fun addExpense(userId: UUID, request: CashExpenseRequest): CashExpenseResponse
    suspend fun findOfflineSyncedCashExpense(
        deviceId: String,
        clientGeneratedId: String,
        cashierUserId: UUID? = null
    ): OfflineCashExpenseSyncResponse?
    suspend fun syncCashExpense(command: OfflineCashExpenseSyncCommand): OfflineCashExpenseSyncResponse
    suspend fun getExpenses(sessionId: UUID): List<CashExpenseResponse>
    suspend fun getPaginatedExpenses(
        page: Int,
        limit: Int,
        sessionId: UUID? = null,
        startDate: String? = null,
        endDate: String? = null
    ): com.service.tbterminal.inventory.PaginatedResponse<CashExpenseResponse>
    
    suspend fun payTransactionDebt(userId: UUID, transactionId: UUID, request: PayDebtRequest): TransactionResponse
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

data class IdempotentCheckout(
    val requestFingerprint: String,
    val transaction: TransactionResponse
)

data class OfflineCheckoutSyncItemCommand(
    val productId: UUID,
    val productNameSnapshot: String,
    val quantity: java.math.BigDecimal,
    val priceAtTransaction: java.math.BigDecimal,
    val cogsAtTransaction: java.math.BigDecimal,
    val discount: java.math.BigDecimal,
    val subtotal: java.math.BigDecimal
)

data class OfflineCheckoutSyncCommand(
    val clientGeneratedId: String,
    val deviceId: String,
    val cashierUserId: UUID,
    val cashSessionId: UUID,
    val customerId: UUID?,
    val paymentMethod: PaymentMethod,
    val total: java.math.BigDecimal,
    val paidAmount: java.math.BigDecimal,
    val remainingAmount: java.math.BigDecimal,
    val occurredAt: OffsetDateTime,
    val note: String?,
    val dueDays: Int,
    val items: List<OfflineCheckoutSyncItemCommand>
)

data class OfflineCashSessionOpenSyncCommand(
    val clientGeneratedId: String,
    val deviceId: String,
    val cashierUserId: UUID,
    val openedAt: OffsetDateTime,
    val startingCash: java.math.BigDecimal,
    val openingNote: String?
)

data class OfflineCashSessionCloseSyncCommand(
    val deviceId: String,
    val clientGeneratedId: String,
    val serverCashSessionId: UUID,
    val cashierUserId: UUID,
    val closedAt: OffsetDateTime,
    val actualCash: java.math.BigDecimal,
    val expectedCash: java.math.BigDecimal?,
    val difference: java.math.BigDecimal?,
    val closingNote: String?
)

data class OfflineCashExpenseSyncCommand(
    val clientGeneratedId: String,
    val deviceId: String,
    val cashierUserId: UUID,
    val serverCashSessionId: UUID,
    val amount: java.math.BigDecimal,
    val category: String,
    val note: String,
    val occurredAt: OffsetDateTime
)

@kotlinx.serialization.Serializable
data class TransactionSummary(
    val id: String,
    val receiptId: String,
    val sessionId: String,
    val customerId: String?,
    val customerName: String?,
    val cashierId: String,
    val cashierName: String?,
    val paymentMethods: List<String>,
    val type: String,
    val status: String,
    @kotlinx.serialization.Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val total: java.math.BigDecimal,
    @kotlinx.serialization.Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val paidAmount: java.math.BigDecimal,
    @kotlinx.serialization.Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class)
    val remainingAmount: java.math.BigDecimal,
    val createdAt: String,
    val voidedAt: String? = null,
    val voidReason: String? = null
)


class SalesRepositoryImpl : SalesRepository {

    override suspend fun getActiveSession(userId: UUID): CashSessionResponse? = newSuspendedTransaction(Dispatchers.IO) {
        CashSessionsTable.select {
            (CashSessionsTable.userId eq userId) and
            (CashSessionsTable.closedAt.isNull())
        }.singleOrNull()?.let { rowToResponse(it) }
    }

    override suspend fun getSessionById(sessionId: UUID): CashSessionResponse? = newSuspendedTransaction(Dispatchers.IO) {
        CashSessionsTable.select { CashSessionsTable.id eq sessionId }
            .singleOrNull()?.let { row ->
                rowToResponse(row, getUserName(row[CashSessionsTable.userId]))
            }
    }

    override suspend fun getPaginatedSessions(
        page: Int,
        limit: Int,
        status: String?,
        startDate: String?,
        endDate: String?
    ): com.service.tbterminal.inventory.PaginatedResponse<CashSessionResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val offset = ((page - 1) * limit).toLong()
        var query = CashSessionsTable.selectAll()
        query = when (status?.uppercase()) {
            SessionStatus.OPEN.name -> query.andWhere { CashSessionsTable.closedAt.isNull() }
            SessionStatus.CLOSED.name -> query.andWhere { CashSessionsTable.closedAt.isNotNull() }
            else -> query
        }
        parseStartDate(startDate)?.let { date ->
            query = query.andWhere { CashSessionsTable.openedAt greaterEq date }
        }
        parseEndDate(endDate)?.let { date ->
            query = query.andWhere { CashSessionsTable.openedAt less date }
        }

        val total = query.count()
        val rows = query
            .orderBy(CashSessionsTable.openedAt, SortOrder.DESC)
            .limit(limit, offset)
            .toList()
        val userNames = getUserNames(rows.map { it[CashSessionsTable.userId] })

        com.service.tbterminal.inventory.PaginatedResponse(
            data = rows.map { row ->
                rowToResponse(row, userNames[row[CashSessionsTable.userId]])
            },
            total = total,
            page = page,
            limit = limit,
            totalPages = Math.ceil(total.toDouble() / limit).toInt()
        )
    }

    override suspend fun openSession(userId: UUID, startingCash: java.math.BigDecimal): CashSessionResponse = newSuspendedTransaction(Dispatchers.IO) {
        val user = UsersTable.select { UsersTable.id eq userId }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("User pembuka sesi tidak ditemukan")

        if (!user[UsersTable.isActive]) {
            throw ValidationException("User pembuka sesi tidak aktif")
        }

        val activeSession = CashSessionsTable.select {
            (CashSessionsTable.userId eq userId) and CashSessionsTable.closedAt.isNull()
        }.forUpdate().singleOrNull()

        if (activeSession != null) {
            throw ValidationException(
                "Anda masih memiliki sesi kasir yang belum ditutup (ID: ${activeSession[CashSessionsTable.id]}). " +
                    "Tutup sesi tersebut sebelum membuka yang baru."
            )
        }

        val sessionId = UUID.randomUUID()
        CashSessionsTable.insert {
            it[this.id] = sessionId
            it[this.userId] = userId
            it[this.openingCash] = startingCash
            it[this.systemCash] = startingCash
        }

        insertCheckoutAudit(userId, "sales", "cash_sessions", sessionId, AuditAction.INSERT)

        CashSessionsTable.select { CashSessionsTable.id eq sessionId }
            .single()
            .let(::rowToResponse)
    }

    override suspend fun findOfflineSyncedCashSession(
        deviceId: String,
        clientGeneratedId: String,
        cashierUserId: UUID?
    ): OfflineCashSessionOpenSyncResponse? = newSuspendedTransaction(Dispatchers.IO) {
        findOfflineCashSessionSyncResponse(
            deviceId = deviceId,
            clientGeneratedId = clientGeneratedId,
            cashierUserId = cashierUserId,
            syncStatus = "DUPLICATE"
        )
    }

    override suspend fun syncOpenCashSession(
        command: OfflineCashSessionOpenSyncCommand
    ): OfflineCashSessionOpenSyncResponse = newSuspendedTransaction(Dispatchers.IO) {
        findOfflineCashSessionSyncResponse(
            deviceId = command.deviceId,
            clientGeneratedId = command.clientGeneratedId,
            cashierUserId = command.cashierUserId,
            syncStatus = "DUPLICATE"
        )?.let { return@newSuspendedTransaction it }

        val user = UsersTable.select { UsersTable.id eq command.cashierUserId }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Kasir sync sesi kas offline tidak ditemukan")

        if (!user[UsersTable.isActive]) {
            throw ValidationException("Kasir sync sesi kas offline tidak aktif")
        }

        val activeSession = CashSessionsTable.select {
            (CashSessionsTable.userId eq command.cashierUserId) and CashSessionsTable.closedAt.isNull()
        }.forUpdate().singleOrNull()
        if (activeSession != null) {
            throw ValidationException(
                "Anda masih memiliki sesi kasir yang belum ditutup (ID: ${activeSession[CashSessionsTable.id]}). " +
                    "Tutup sesi tersebut sebelum sinkronisasi sesi lokal."
            )
        }

        val sessionId = UUID.randomUUID()
        val syncedAt = OffsetDateTime.now()
        CashSessionsTable.insert {
            it[this.id] = sessionId
            it[this.userId] = command.cashierUserId
            it[this.openedAt] = command.openedAt
            it[this.openingCash] = command.startingCash
            it[this.systemCash] = command.startingCash
            it[this.notes] = command.openingNote
            it[this.clientGeneratedId] = command.clientGeneratedId
            it[this.deviceId] = command.deviceId
            it[this.offlineSyncedAt] = syncedAt
        }

        insertCheckoutAudit(
            command.cashierUserId,
            "sales",
            "cash_sessions",
            sessionId,
            AuditAction.INSERT
        )

        OfflineCashSessionOpenSyncResponse(
            syncStatus = "CREATED",
            serverCashSessionId = sessionId.toString(),
            openedAt = command.openedAt.toString(),
            syncedAt = syncedAt.toString()
        )
    }

    override suspend fun syncCloseCashSession(
        command: OfflineCashSessionCloseSyncCommand
    ): OfflineCashSessionCloseSyncResponse = newSuspendedTransaction(Dispatchers.IO) {
        val session = CashSessionsTable.select { CashSessionsTable.id eq command.serverCashSessionId }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Sesi kas sync close offline tidak ditemukan")

        if (session[CashSessionsTable.userId] != command.cashierUserId) {
            throw ValidationException("Sesi kas tidak sesuai dengan cashierUserId sync close offline")
        }

        session[CashSessionsTable.closedAt]?.let { existingClosedAt ->
            val closeSyncStatus = if (
                session[CashSessionsTable.closeDeviceId] == command.deviceId &&
                session[CashSessionsTable.closeClientGeneratedId] == command.clientGeneratedId
            ) {
                "DUPLICATE"
            } else {
                "ALREADY_CLOSED"
            }
            return@newSuspendedTransaction OfflineCashSessionCloseSyncResponse(
                syncStatus = closeSyncStatus,
                serverCashSessionId = command.serverCashSessionId.toString(),
                closedAt = existingClosedAt.toString(),
                syncedAt = (session[CashSessionsTable.closeOfflineSyncedAt] ?: existingClosedAt).toString()
            )
        }

        val syncedAt = OffsetDateTime.now()
        val systemCash = session[CashSessionsTable.systemCash]
            ?: session[CashSessionsTable.openingCash]
        val difference = command.actualCash.subtract(systemCash).normalizeMoney()

        CashSessionsTable.update({ CashSessionsTable.id eq command.serverCashSessionId }) {
            it[this.closedAt] = command.closedAt
            it[this.closingCash] = command.actualCash
            it[this.systemCash] = systemCash
            it[this.difference] = difference
            it[this.notes] = command.closingNote ?: session[CashSessionsTable.notes]
            it[this.closeClientGeneratedId] = command.clientGeneratedId
            it[this.closeDeviceId] = command.deviceId
            it[this.closeOfflineSyncedAt] = syncedAt
        }

        insertCheckoutAudit(
            command.cashierUserId,
            "sales",
            "cash_sessions",
            command.serverCashSessionId,
            AuditAction.UPDATE
        )

        OfflineCashSessionCloseSyncResponse(
            syncStatus = "UPDATED",
            serverCashSessionId = command.serverCashSessionId.toString(),
            closedAt = command.closedAt.toString(),
            syncedAt = syncedAt.toString()
        )
    }

    override suspend fun closeSession(
        sessionId: UUID,
        closingCash: java.math.BigDecimal,
        systemCash: java.math.BigDecimal,
        difference: java.math.BigDecimal,
        notes: String?
    ): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val session = CashSessionsTable.select { CashSessionsTable.id eq sessionId }
            .forUpdate()
            .singleOrNull()
            ?: return@newSuspendedTransaction false
        if (session[CashSessionsTable.closedAt] != null) {
            return@newSuspendedTransaction false
        }

        val updatedRows = CashSessionsTable.update({
            (CashSessionsTable.id eq sessionId) and CashSessionsTable.closedAt.isNull()
        }) {
            it[this.closedAt] = OffsetDateTime.now()
            it[this.closingCash] = closingCash
            it[this.systemCash] = systemCash
            it[this.difference] = difference
            it[this.notes] = notes
        }
        if (updatedRows > 0) {
            insertCheckoutAudit(
                session[CashSessionsTable.userId],
                "sales",
                "cash_sessions",
                sessionId,
                AuditAction.UPDATE
            )
            true
        } else {
            false
        }
    }

    private fun rowToResponse(row: ResultRow, userName: String? = null): CashSessionResponse {
        val sessionId = row[CashSessionsTable.id]
        val totalExpenses = CashExpensesTable.select { CashExpensesTable.sessionId eq sessionId }
            .map { it[CashExpensesTable.amount] }
            .fold(BigDecimal.ZERO, BigDecimal::add)

        val isClosed = row[CashSessionsTable.closedAt] != null
        return CashSessionResponse(
            id = sessionId.toString(),
            userId = row[CashSessionsTable.userId].toString(),
            userName = userName,
            openedAt = row[CashSessionsTable.openedAt].toString(),
            closedAt = row[CashSessionsTable.closedAt]?.toString(),
            openingCash = row[CashSessionsTable.openingCash],
            closingCash = row[CashSessionsTable.closingCash],
            systemCash = row[CashSessionsTable.systemCash],
            difference = row[CashSessionsTable.difference],
            totalExpenses = totalExpenses,
            notes = row[CashSessionsTable.notes],
            status = if (isClosed) SessionStatus.CLOSED.name else SessionStatus.OPEN.name
        )
    }

    private fun getUserName(userId: UUID): String? {
        return UsersTable.select { UsersTable.id eq userId }
            .singleOrNull()
            ?.get(UsersTable.name)
    }

    private fun getUserNames(userIds: List<UUID>): Map<UUID, String> {
        if (userIds.isEmpty()) return emptyMap()
        return UsersTable.select { UsersTable.id inList userIds.distinct() }
            .associate { row -> row[UsersTable.id] to row[UsersTable.name] }
    }

    private fun findOfflineCashSessionSyncResponse(
        deviceId: String,
        clientGeneratedId: String,
        cashierUserId: UUID?,
        syncStatus: String
    ): OfflineCashSessionOpenSyncResponse? {
        var query = CashSessionsTable.select {
            (CashSessionsTable.deviceId eq deviceId) and
                (CashSessionsTable.clientGeneratedId eq clientGeneratedId)
        }
        if (cashierUserId != null) {
            query = query.andWhere { CashSessionsTable.userId eq cashierUserId }
        }
        val row = query.singleOrNull() ?: return null

        return OfflineCashSessionOpenSyncResponse(
            syncStatus = syncStatus,
            serverCashSessionId = row[CashSessionsTable.id].toString(),
            openedAt = row[CashSessionsTable.openedAt].toString(),
            syncedAt = (row[CashSessionsTable.offlineSyncedAt] ?: row[CashSessionsTable.createdAt]).toString()
        )
    }

    // ==========================================
    // PETTY CASH (PENGELUARAN KASIR)
    // ==========================================

    override suspend fun addExpense(userId: UUID, request: CashExpenseRequest): CashExpenseResponse = newSuspendedTransaction(Dispatchers.IO) {
        val session = CashSessionsTable.select {
            (CashSessionsTable.userId eq userId) and CashSessionsTable.closedAt.isNull()
        }.forUpdate().singleOrNull()
            ?: throw SessionNotFoundException("Buka sesi kasir terlebih dahulu sebelum mencatat pengeluaran")

        if (request.amount <= BigDecimal.ZERO) {
            throw ValidationException("Nominal pengeluaran harus lebih dari 0")
        }

        val sessionId = session[CashSessionsTable.id]
        val expenseId = UUID.randomUUID()
        CashExpensesTable.insert {
            it[this.id] = expenseId
            it[this.sessionId] = sessionId
            it[this.userId] = userId
            it[this.amount] = request.amount
            it[this.description] = request.description
        }

        // Update kas sistem
        val currentSystemCash = session[CashSessionsTable.systemCash] ?: session[CashSessionsTable.openingCash]
        CashSessionsTable.update({ CashSessionsTable.id eq sessionId }) {
            it[this.systemCash] = currentSystemCash.subtract(request.amount)
        }

        val row = CashExpensesTable.select { CashExpensesTable.id eq expenseId }.single()
        rowToExpenseResponse(row, getUserName(row[CashExpensesTable.userId]))
    }

    override suspend fun findOfflineSyncedCashExpense(
        deviceId: String,
        clientGeneratedId: String,
        cashierUserId: UUID?
    ): OfflineCashExpenseSyncResponse? = newSuspendedTransaction(Dispatchers.IO) {
        findOfflineCashExpenseSyncResponse(
            deviceId = deviceId,
            clientGeneratedId = clientGeneratedId,
            cashierUserId = cashierUserId,
            syncStatus = "DUPLICATE"
        )
    }

    override suspend fun syncCashExpense(command: OfflineCashExpenseSyncCommand): OfflineCashExpenseSyncResponse = newSuspendedTransaction(Dispatchers.IO) {
        findOfflineCashExpenseSyncResponse(
            deviceId = command.deviceId,
            clientGeneratedId = command.clientGeneratedId,
            cashierUserId = command.cashierUserId,
            syncStatus = "DUPLICATE"
        )?.let { return@newSuspendedTransaction it }

        val session = CashSessionsTable.select { CashSessionsTable.id eq command.serverCashSessionId }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Sesi kas untuk sync pengeluaran offline tidak ditemukan")

        if (session[CashSessionsTable.userId] != command.cashierUserId) {
            throw ValidationException("Sesi kas tidak sesuai dengan cashierUserId sync pengeluaran offline")
        }
        if (session[CashSessionsTable.closedAt] != null) {
            throw ValidationException("Sesi kas server sudah ditutup. Sync pengeluaran offline tidak dapat diproses.")
        }

        val expenseId = UUID.randomUUID()
        val syncedAt = OffsetDateTime.now()
        val description = command.note.ifBlank { command.category }
        CashExpensesTable.insert {
            it[this.id] = expenseId
            it[this.sessionId] = command.serverCashSessionId
            it[this.userId] = command.cashierUserId
            it[this.amount] = command.amount
            it[this.description] = description
            it[this.category] = command.category
            it[this.clientGeneratedId] = command.clientGeneratedId
            it[this.deviceId] = command.deviceId
            it[this.occurredAt] = command.occurredAt
            it[this.offlineSyncedAt] = syncedAt
        }

        val currentSystemCash = session[CashSessionsTable.systemCash] ?: session[CashSessionsTable.openingCash]
        CashSessionsTable.update({ CashSessionsTable.id eq command.serverCashSessionId }) {
            it[this.systemCash] = currentSystemCash.subtract(command.amount)
        }

        OfflineCashExpenseSyncResponse(
            syncStatus = "CREATED",
            serverExpenseId = expenseId.toString(),
            syncedAt = syncedAt.toString()
        )
    }

    private fun findOfflineCashExpenseSyncResponse(
        deviceId: String,
        clientGeneratedId: String,
        cashierUserId: UUID?,
        syncStatus: String
    ): OfflineCashExpenseSyncResponse? {
        var query = CashExpensesTable.select {
            (CashExpensesTable.deviceId eq deviceId) and
                (CashExpensesTable.clientGeneratedId eq clientGeneratedId)
        }
        if (cashierUserId != null) {
            query = query.andWhere { CashExpensesTable.userId eq cashierUserId }
        }
        val row = query.singleOrNull() ?: return null

        return OfflineCashExpenseSyncResponse(
            syncStatus = syncStatus,
            serverExpenseId = row[CashExpensesTable.id].toString(),
            syncedAt = (row[CashExpensesTable.offlineSyncedAt] ?: row[CashExpensesTable.createdAt]).toString()
        )
    }

    // ==========================================
    // POS — CHECKOUT ENGINE
    // ==========================================

    override suspend fun executeCheckout(
        userId: UUID,
        customerId: UUID?,
        requestItems: List<CheckoutItemRequest>,
        paymentMethod: PaymentMethod,
        amountPaid: java.math.BigDecimal,
        notes: String?,
        dueDays: Int,
        idempotencyKey: String,
        requestFingerprint: String
    ): TransactionResponse = newSuspendedTransaction(Dispatchers.IO) {
        val session = CashSessionsTable.select {
            (CashSessionsTable.userId eq userId) and CashSessionsTable.closedAt.isNull()
        }.forUpdate().singleOrNull()
            ?: throw SessionNotFoundException("Buka sesi kasir terlebih dahulu sebelum bertransaksi")

        val sessionId = session[CashSessionsTable.id]

        findCheckoutByIdempotencyKeyInTransaction(idempotencyKey)?.let { existing ->
            if (existing.transaction.userId != userId.toString()) {
                throw ValidationException("idempotencyKey sudah digunakan oleh transaksi lain")
            }
            if (existing.requestFingerprint != requestFingerprint) {
                throw ValidationException("idempotencyKey sudah digunakan untuk payload checkout yang berbeda")
            }
            return@newSuspendedTransaction existing.transaction.copy(idempotentReplay = true)
        }

        val lockedStockIds = lockAndValidateStocks(requestItems)
        val resolvedItems = requestItems.map(::resolveItemForCheckout)
        val totalAmount = resolvedItems.fold(BigDecimal.ZERO) { total, item -> total.add(item.subtotal) }.normalizeCheckoutMoney()
        val payment = resolveCheckoutPayment(paymentMethod, amountPaid, totalAmount, customerId, dueDays)
        val dueDate = if (payment.status == TrxStatus.HUTANG || payment.status == TrxStatus.DP) {
            lockCustomerAndValidateCredit(customerId, payment.receivableAmount, dueDays)
        } else {
            null
        }

        // 1. Insert transaksi utama
        val trxId = UUID.randomUUID()
        TransactionsTable.insert {
            it[this.id] = trxId
            it[this.sessionId] = sessionId
            it[this.userId] = userId
            it[this.customerId] = customerId
            it[this.type] = TrxType.PENJUALAN
            it[this.status] = payment.status
            it[this.total] = totalAmount
            it[this.dpAmount] = if (payment.status == TrxStatus.DP) payment.paidAmount else BigDecimal.ZERO
            it[this.paidAmount] = payment.paidAmount
            it[this.amountTendered] = payment.amountTendered
            it[this.changeAmount] = payment.changeAmount
            it[this.idempotencyKey] = idempotencyKey
            it[this.requestFingerprint] = requestFingerprint
            it[this.notes] = notes?.trim()?.takeIf(String::isNotBlank)
        }

        // 2. Loop insert transaction_items (trigger fn_sync_stock akan berjalan otomatis)
        val transactionItemIds = resolvedItems.map { item ->
            val transactionItemId = UUID.randomUUID()
            TransactionItemsTable.insert {
                it[id] = transactionItemId
                it[this.transactionId] = trxId
                it[this.productId] = item.productId
                it[this.productName] = item.productName
                it[this.unitId] = item.unitId
                it[this.quantity] = item.qty
                it[this.priceAtTransaction] = item.priceAtTransaction
                it[this.cogsAtTransaction] = item.cogsAtTransaction
                it[this.discount] = item.discount
                it[this.subtotal] = item.subtotal
            }
            transactionItemId
        }

        // 3. Insert payment record
        val paymentId = if (payment.paidAmount > BigDecimal.ZERO) UUID.randomUUID() else null
        if (paymentId != null) {
            PaymentsTable.insert {
                it[id] = paymentId
                it[this.transactionId] = trxId
                it[this.method] = paymentMethod
                it[this.amount] = payment.paidAmount
            }
        }

        // 4. Jika HUTANG/DP — insert ke receivable.receivables
        val receivableId = if (dueDate != null && customerId != null) UUID.randomUUID() else null
        if (dueDate != null && customerId != null && receivableId != null) {
            ReceivablesTable.insert {
                it[id] = receivableId
                it[this.customerId] = customerId
                it[this.transactionId] = trxId
                it[this.receivableSource] = ReceivableSource.SALE
                it[this.amount] = payment.receivableAmount
                it[this.paidAmount] = java.math.BigDecimal.ZERO
                it[this.debtDate] = receivableToday()
                it[this.dueDate] = dueDate
                it[this.createdBy] = userId
                it[this.status] = ReceivableStatus.UNPAID
            }
        }

        if (paymentMethod == PaymentMethod.TUNAI && payment.paidAmount > BigDecimal.ZERO) {
            val currentSystemCash = session[CashSessionsTable.systemCash] ?: session[CashSessionsTable.openingCash]
            CashSessionsTable.update({ CashSessionsTable.id eq sessionId }) {
                it[this.systemCash] = currentSystemCash.add(payment.paidAmount)
            }
        }

        insertCheckoutAudit(userId, "sales", "transactions", trxId)
        transactionItemIds.forEach { insertCheckoutAudit(userId, "sales", "transaction_items", it) }
        paymentId?.let { insertCheckoutAudit(userId, "sales", "payments", it) }
        receivableId?.let { insertCheckoutAudit(userId, "receivable", "receivables", it) }
        lockedStockIds.values.forEach {
            insertCheckoutAudit(userId, "inventory", "stock", it, AuditAction.UPDATE)
        }
        if (paymentMethod == PaymentMethod.TUNAI && payment.paidAmount > BigDecimal.ZERO) {
            insertCheckoutAudit(userId, "sales", "cash_sessions", sessionId, AuditAction.UPDATE)
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

        val customerName = trxRow[TransactionsTable.customerId]?.let { cid ->
            CustomersTable.select { CustomersTable.id eq cid }.singleOrNull()?.get(CustomersTable.name)
        }

        TransactionResponse(
            id = trxRow[TransactionsTable.id].toString(),
            receiptId = trxRow[TransactionsTable.receiptNumber],
            sessionId = trxRow[TransactionsTable.sessionId].toString(),
            customerId = trxRow[TransactionsTable.customerId]?.toString(),
            customerName = customerName,
            userId = trxRow[TransactionsTable.userId].toString(),
            type = trxRow[TransactionsTable.type].dbValue,
            status = trxRow[TransactionsTable.status].dbValue,
            total = trxRow[TransactionsTable.total],
            dpAmount = trxRow[TransactionsTable.dpAmount],
            paidAmount = trxRow[TransactionsTable.paidAmount],
            amountTendered = trxRow[TransactionsTable.amountTendered],
            changeAmount = trxRow[TransactionsTable.changeAmount],
            notes = trxRow[TransactionsTable.notes],
            createdAt = trxRow[TransactionsTable.createdAt].toString(),
            items = items
        )
    }

    override suspend fun findCheckoutByIdempotencyKey(idempotencyKey: String): IdempotentCheckout? =
        newSuspendedTransaction(Dispatchers.IO) {
            findCheckoutByIdempotencyKeyInTransaction(idempotencyKey)
        }

    override suspend fun findOfflineSyncedCheckout(
        deviceId: String,
        clientGeneratedId: String,
        cashierUserId: UUID?
    ): OfflineCheckoutSyncResponse? = newSuspendedTransaction(Dispatchers.IO) {
        findOfflineSyncResponse(deviceId, clientGeneratedId, cashierUserId, syncStatus = "DUPLICATE")
    }

    override suspend fun executeOfflineCheckoutSync(
        command: OfflineCheckoutSyncCommand
    ): OfflineCheckoutSyncResponse = newSuspendedTransaction(Dispatchers.IO) {
        findOfflineSyncResponse(
            deviceId = command.deviceId,
            clientGeneratedId = command.clientGeneratedId,
            cashierUserId = command.cashierUserId,
            syncStatus = "DUPLICATE"
        )?.let { return@newSuspendedTransaction it }

        val cashier = UsersTable.select { UsersTable.id eq command.cashierUserId }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Kasir sync offline tidak ditemukan")

        if (!cashier[UsersTable.isActive]) {
            throw ValidationException("Kasir sync offline tidak aktif")
        }

        val session = CashSessionsTable.select { CashSessionsTable.id eq command.cashSessionId }
            .forUpdate()
            .singleOrNull()
            ?: throw SessionNotFoundException("Sesi kasir sync offline tidak ditemukan")

        if (session[CashSessionsTable.closedAt] != null) {
            throw SessionNotFoundException("Sesi kasir sync offline sudah ditutup")
        }
        if (session[CashSessionsTable.userId] != command.cashierUserId) {
            throw ValidationException("Sesi kasir tidak sesuai dengan cashierUserId sync offline")
        }

        val lockedStockIds = lockAndValidateOfflineStocks(command.items)
        val resolvedItems = command.items.map(::resolveItemForOfflineSync)
        val itemTotal = resolvedItems.fold(BigDecimal.ZERO) { total, item -> total.add(item.subtotal) }
            .normalizeMoney()
        val totalAmount = command.total.normalizeMoney()

        if (itemTotal.compareTo(totalAmount) != 0) {
            throw ValidationException("Total transaksi sync tidak sesuai dengan total item")
        }
        val payment = resolveCheckoutPayment(
            command.paymentMethod, command.paidAmount, totalAmount, command.customerId, command.dueDays
        )
        val dueDate = if (payment.status == TrxStatus.HUTANG || payment.status == TrxStatus.DP) {
            lockCustomerAndValidateCredit(
                customerId = command.customerId,
                receivableAmount = payment.receivableAmount,
                dueDays = command.dueDays,
                baseDate = command.occurredAt.toLocalDate()
            )
        } else {
            null
        }

        val trxId = UUID.randomUUID()
        val paymentId = if (payment.paidAmount > BigDecimal.ZERO) UUID.randomUUID() else null
        val receivableId = if (dueDate != null && command.customerId != null) UUID.randomUUID() else null
        val syncedAt = OffsetDateTime.now()

        TransactionsTable.insert {
            it[this.id] = trxId
            it[this.sessionId] = command.cashSessionId
            it[this.userId] = command.cashierUserId
            it[this.customerId] = command.customerId
            it[this.type] = TrxType.PENJUALAN
            it[this.status] = payment.status
            it[this.total] = totalAmount
            it[this.dpAmount] = if (payment.status == TrxStatus.DP) payment.paidAmount else BigDecimal.ZERO
            it[this.paidAmount] = payment.paidAmount
            it[this.amountTendered] = payment.amountTendered
            it[this.changeAmount] = payment.changeAmount
            it[this.notes] = command.note
            it[this.clientGeneratedId] = command.clientGeneratedId
            it[this.deviceId] = command.deviceId
            it[this.occurredAt] = command.occurredAt
            it[this.syncedAt] = syncedAt
        }

        val transactionItemIds = resolvedItems.map { item ->
            val transactionItemId = UUID.randomUUID()
            TransactionItemsTable.insert {
                it[id] = transactionItemId
                it[this.transactionId] = trxId
                it[this.productId] = item.productId
                it[this.productName] = item.productName
                it[this.unitId] = item.unitId
                it[this.quantity] = item.qty
                it[this.priceAtTransaction] = item.priceAtTransaction
                it[this.cogsAtTransaction] = item.cogsAtTransaction
                it[this.discount] = item.discount
                it[this.subtotal] = item.subtotal
            }
            transactionItemId
        }

        if (paymentId != null) {
            PaymentsTable.insert {
                it[id] = paymentId
                it[this.transactionId] = trxId
                it[this.method] = command.paymentMethod
                it[this.amount] = payment.paidAmount
            }
        }

        if (dueDate != null && command.customerId != null && receivableId != null) {
            ReceivablesTable.insert {
                it[this.id] = receivableId
                it[this.customerId] = command.customerId
                it[this.transactionId] = trxId
                it[this.receivableSource] = ReceivableSource.SALE
                it[this.amount] = payment.receivableAmount
                it[this.paidAmount] = BigDecimal.ZERO
                it[this.debtDate] = command.occurredAt.toLocalDate()
                it[this.dueDate] = dueDate
                it[this.createdBy] = command.cashierUserId
                it[this.status] = ReceivableStatus.UNPAID
            }
        }

        if (command.paymentMethod == PaymentMethod.TUNAI && payment.paidAmount > BigDecimal.ZERO) {
            val currentSystemCash = session[CashSessionsTable.systemCash] ?: session[CashSessionsTable.openingCash]
            CashSessionsTable.update({ CashSessionsTable.id eq command.cashSessionId }) {
                it[this.systemCash] = currentSystemCash.add(payment.paidAmount)
            }
        }

        insertCheckoutAudit(command.cashierUserId, "sales", "transactions", trxId)
        transactionItemIds.forEach {
            insertCheckoutAudit(command.cashierUserId, "sales", "transaction_items", it)
        }
        paymentId?.let { insertCheckoutAudit(command.cashierUserId, "sales", "payments", it) }
        receivableId?.let { insertCheckoutAudit(command.cashierUserId, "receivable", "receivables", it) }
        lockedStockIds.values.forEach {
            insertCheckoutAudit(command.cashierUserId, "inventory", "stock", it, AuditAction.UPDATE)
        }
        if (command.paymentMethod == PaymentMethod.TUNAI && payment.paidAmount > BigDecimal.ZERO) {
            insertCheckoutAudit(
                command.cashierUserId, "sales", "cash_sessions", command.cashSessionId, AuditAction.UPDATE
            )
        }

        OfflineCheckoutSyncResponse(
            syncStatus = "CREATED",
            serverTransactionId = trxId.toString(),
            receiptId = TransactionsTable.select { TransactionsTable.id eq trxId }
                .single()[TransactionsTable.receiptNumber],
            serverPaymentIds = paymentId?.let { listOf(it.toString()) }.orEmpty(),
            serverReceivableId = receivableId?.toString(),
            syncedAt = syncedAt.toString()
        )
    }

    private fun resolveItemForOfflineSync(item: OfflineCheckoutSyncItemCommand): ResolvedItem {
        if (item.quantity <= BigDecimal.ZERO) {
            throw ValidationException("Quantity untuk produk ${item.productId} harus lebih dari 0")
        }
        if (item.quantity.scale() > 2) {
            throw ValidationException("Quantity maksimal memiliki 2 angka desimal")
        }
        if (listOf(item.priceAtTransaction, item.cogsAtTransaction, item.discount, item.subtotal).any { it.scale() > 2 }) {
            throw ValidationException("Nilai uang item checkout maksimal memiliki 2 angka desimal")
        }
        if (item.priceAtTransaction < BigDecimal.ZERO) {
            throw ValidationException("Harga produk ${item.productId} tidak boleh negatif")
        }
        if (item.cogsAtTransaction < BigDecimal.ZERO) {
            throw ValidationException("HPP produk ${item.productId} tidak boleh negatif")
        }
        if (item.discount < BigDecimal.ZERO) {
            throw ValidationException("Diskon produk ${item.productId} tidak boleh negatif")
        }
        if (item.discount > item.priceAtTransaction) {
            throw ValidationException("Diskon tidak boleh melebihi harga produk ${item.productId}")
        }

        val product = ProductsTable.select {
            (ProductsTable.id eq item.productId) and (ProductsTable.isActive eq true)
        }.forUpdate().singleOrNull()
            ?: throw NotFoundException("Produk dengan ID ${item.productId} tidak ditemukan atau tidak aktif")

        val expectedSubtotal = item.priceAtTransaction
            .subtract(item.discount)
            .multiply(item.quantity)
            .normalizeMoney()
        val requestedSubtotal = item.subtotal.normalizeMoney()
        if (requestedSubtotal.compareTo(expectedSubtotal) != 0) {
            throw ValidationException("Subtotal item sync tidak valid untuk produk ${item.productId}")
        }

        return ResolvedItem(
            productId = item.productId,
            unitId = product[ProductsTable.baseUnitId],
            productName = item.productNameSnapshot.trim(),
            qty = item.quantity,
            priceAtTransaction = item.priceAtTransaction.normalizeMoney(),
            cogsAtTransaction = item.cogsAtTransaction.normalizeMoney(),
            discount = item.discount.normalizeMoney(),
            subtotal = requestedSubtotal
        )
    }

    private fun resolveItemForCheckout(item: CheckoutItemRequest): ResolvedItem {
        val productId = try {
            UUID.fromString(item.productId)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Format Product ID tidak valid: ${item.productId}")
        }

        if (item.qty <= BigDecimal.ZERO) {
            throw ValidationException("Quantity untuk produk ${item.productId} harus lebih dari 0")
        }
        if (item.discount < BigDecimal.ZERO) {
            throw ValidationException("Diskon tidak boleh negatif")
        }

        val product = ProductsTable.select {
            (ProductsTable.id eq productId) and (ProductsTable.isActive eq true)
        }.forUpdate().singleOrNull()
            ?: throw NotFoundException("Produk dengan ID ${item.productId} tidak ditemukan atau tidak aktif")

        val price = product[ProductsTable.priceRetail]
        if (item.discount > price) {
            throw ValidationException("Diskon tidak boleh melebihi harga produk ${item.productId}")
        }

        val subtotal = price.subtract(item.discount)
            .multiply(item.qty)
            .setScale(2, RoundingMode.HALF_UP)

        return ResolvedItem(
            productId = productId,
            unitId = product[ProductsTable.baseUnitId],
            productName = product[ProductsTable.name],
            qty = item.qty,
            priceAtTransaction = price,
            cogsAtTransaction = product[ProductsTable.priceBuy],
            discount = item.discount,
            subtotal = subtotal
        )
    }

    private fun lockCustomerAndValidateCredit(
        customerId: UUID?,
        receivableAmount: BigDecimal,
        dueDays: Int,
        baseDate: java.time.LocalDate = java.time.LocalDate.now()
    ): java.time.LocalDate {
        if (receivableAmount <= BigDecimal.ZERO) {
            throw ValidationException("Transaksi hutang/DP harus memiliki sisa tagihan")
        }

        val lockedCustomerId = customerId
            ?: throw ValidationException("Transaksi hutang/DP memerlukan Customer ID yang valid")

        val customer = CustomersTable.select { CustomersTable.id eq lockedCustomerId }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Pelanggan untuk transaksi kredit tidak ditemukan")

        if (!customer[CustomersTable.isActive]) {
            throw ValidationException("Pelanggan untuk transaksi kredit tidak aktif")
        }

        val existingOutstanding = ReceivablesTable.select {
            (ReceivablesTable.customerId eq lockedCustomerId) and (ReceivablesTable.isActive eq true)
        }
            .sumOf { row -> row[ReceivablesTable.amount].subtract(row[ReceivablesTable.paidAmount]) }
        val creditLimit = customer[CustomersTable.creditLimit]
        ensureReceivableCreditLimit(existingOutstanding, receivableAmount, creditLimit)

        return baseDate.plusDays(dueDays.toLong())
    }

    private fun lockAndValidateStocks(items: List<CheckoutItemRequest>): Map<UUID, UUID> {
        val requested = items.associate { item ->
            val productId = runCatching { UUID.fromString(item.productId) }.getOrElse {
                throw ValidationException("Format Product ID tidak valid: ${item.productId}")
            }
            productId to item.qty
        }
        val productIds = requested.keys.sortedBy(UUID::toString)
        val lockedStocks = StockTable.select { StockTable.productId inList productIds }
            .orderBy(StockTable.productId to SortOrder.ASC)
            .forUpdate()
            .associate { row ->
                row[StockTable.productId] to (row[StockTable.id] to row[StockTable.quantity])
            }

        productIds.forEach { productId ->
            val available = lockedStocks[productId]?.second
                ?: throw NotFoundException("Data stok untuk produk $productId tidak ditemukan")
            val quantity = requireNotNull(requested[productId])
            if (quantity > available) {
                throw StockInsufficientException(
                    "Stok produk $productId tidak cukup. Tersedia ${available.toPlainString()}, diminta ${quantity.toPlainString()}"
                )
            }
        }
        return lockedStocks.mapValues { (_, stock) -> stock.first }
    }

    private fun lockAndValidateOfflineStocks(items: List<OfflineCheckoutSyncItemCommand>): Map<UUID, UUID> {
        val requested = items.groupBy(OfflineCheckoutSyncItemCommand::productId)
            .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { total, row -> total.add(row.quantity) } }
        val productIds = requested.keys.sortedBy(UUID::toString)
        val lockedStocks = StockTable.select { StockTable.productId inList productIds }
            .orderBy(StockTable.productId to SortOrder.ASC)
            .forUpdate()
            .associate { row ->
                row[StockTable.productId] to (row[StockTable.id] to row[StockTable.quantity])
            }
        productIds.forEach { productId ->
            val available = lockedStocks[productId]?.second
                ?: throw NotFoundException("Data stok untuk produk $productId tidak ditemukan")
            val quantity = requireNotNull(requested[productId])
            if (quantity > available) {
                throw StockInsufficientException(
                    "Stok produk $productId tidak cukup. Tersedia ${available.toPlainString()}, diminta ${quantity.toPlainString()}"
                )
            }
        }
        return lockedStocks.mapValues { (_, stock) -> stock.first }
    }

    private fun insertCheckoutAudit(
        userId: UUID,
        schema: String,
        table: String,
        recordId: UUID,
        auditAction: AuditAction = AuditAction.INSERT
    ) {
        AuditLogsTable.insert {
            it[id] = UUID.randomUUID()
            it[this.userId] = userId
            it[action] = auditAction
            it[targetSchemaName] = schema
            it[targetTableName] = table
            it[this.recordId] = recordId
        }
    }

    override suspend fun getTransactionById(id: UUID): TransactionResponse? = newSuspendedTransaction(Dispatchers.IO) {
        getTransactionByIdInTransaction(id)
    }

    private fun getTransactionByIdInTransaction(id: UUID): TransactionResponse? {
        val trxRow = TransactionsTable.leftJoin(CustomersTable, { TransactionsTable.customerId }, { CustomersTable.id })
            .select { TransactionsTable.id eq id }
            .singleOrNull()
            ?: return null

        val items = TransactionItemsTable.select { TransactionItemsTable.transactionId eq id }
            .map { row ->
                TransactionItemResponse(
                    productId = row[TransactionItemsTable.productId].toString(),
                    productName = row[TransactionItemsTable.productName],
                    unitId = row[TransactionItemsTable.unitId].toString(),
                    quantity = row[TransactionItemsTable.quantity],
                    priceAtTransaction = row[TransactionItemsTable.priceAtTransaction],
                    cogsAtTransaction = row[TransactionItemsTable.cogsAtTransaction],
                    discount = row[TransactionItemsTable.discount],
                    subtotal = row[TransactionItemsTable.subtotal]
                )
            }

        val customerName = trxRow.getOrNull(CustomersTable.name)
        val userId = trxRow[TransactionsTable.userId]
        val cashierName = getUserName(userId)
        val paymentMethods = PaymentsTable.select { PaymentsTable.transactionId eq id }
            .map { it[PaymentsTable.method].dbValue }.distinct()
        val voidRow = TransactionVoidsTable.select { TransactionVoidsTable.transactionId eq id }.singleOrNull()
        val voidedBy = voidRow?.get(TransactionVoidsTable.voidedBy)

        return TransactionResponse(
            id = trxRow[TransactionsTable.id].toString(),
            receiptId = trxRow[TransactionsTable.receiptNumber],
            sessionId = trxRow[TransactionsTable.sessionId].toString(),
            customerId = trxRow[TransactionsTable.customerId]?.toString(),
            customerName = customerName,
            userId = userId.toString(),
            cashierName = cashierName,
            paymentMethods = paymentMethods,
            type = trxRow[TransactionsTable.type].dbValue,
            status = trxRow[TransactionsTable.status].dbValue,
            total = trxRow[TransactionsTable.total],
            dpAmount = trxRow[TransactionsTable.dpAmount],
            paidAmount = trxRow[TransactionsTable.paidAmount],
            amountTendered = trxRow[TransactionsTable.amountTendered],
            changeAmount = trxRow[TransactionsTable.changeAmount],
            notes = trxRow[TransactionsTable.notes],
            createdAt = trxRow[TransactionsTable.createdAt].toString(),
            voidedAt = voidRow?.get(TransactionVoidsTable.createdAt)?.toString(),
            voidedBy = voidedBy?.toString(),
            voidedByName = voidedBy?.let(::getUserName),
            voidReason = voidRow?.get(TransactionVoidsTable.reason),
            items = items
        )
    }

    private fun findCheckoutByIdempotencyKeyInTransaction(idempotencyKey: String): IdempotentCheckout? {
        val row = TransactionsTable.select { TransactionsTable.idempotencyKey eq idempotencyKey }
            .singleOrNull() ?: return null
        val fingerprint = row[TransactionsTable.requestFingerprint] ?: return null
        val transaction = getTransactionByIdInTransaction(row[TransactionsTable.id]) ?: return null
        return IdempotentCheckout(fingerprint, transaction)
    }

    override suspend fun getReceivableIdByTransactionId(transactionId: UUID): UUID? = newSuspendedTransaction(Dispatchers.IO) {
        ReceivablesTable
            .select { ReceivablesTable.transactionId eq transactionId }
            .singleOrNull()
            ?.get(ReceivablesTable.id)
    }

    override suspend fun voidTransaction(
        actorUserId: UUID,
        transactionId: UUID,
        reason: String,
        idempotencyKey: String
    ): VoidTransactionResponse = newSuspendedTransaction(Dispatchers.IO) {
        val existingByKey = TransactionVoidsTable.select {
            TransactionVoidsTable.idempotencyKey eq idempotencyKey
        }.forUpdate().singleOrNull()
        if (existingByKey != null) {
            if (existingByKey[TransactionVoidsTable.transactionId] != transactionId ||
                existingByKey[TransactionVoidsTable.reason] != reason
            ) throw ValidationException("idempotencyKey sudah digunakan untuk permintaan void yang berbeda")
            return@newSuspendedTransaction voidResponse(existingByKey, true)
        }

        // Urutan lock sama dengan pembayaran piutang: receivable dahulu, lalu transaksi.
        val receivableRow = ReceivablesTable.select { ReceivablesTable.transactionId eq transactionId }
            .forUpdate().singleOrNull()
        val transactionRow = TransactionsTable.select { TransactionsTable.id eq transactionId }
            .forUpdate().singleOrNull()
            ?: throw NotFoundException("Transaksi tidak ditemukan")
        if (transactionRow[TransactionsTable.status] == TrxStatus.VOIDED) {
            TransactionVoidsTable.select { TransactionVoidsTable.transactionId eq transactionId }
                .singleOrNull()
                ?.takeIf {
                    it[TransactionVoidsTable.idempotencyKey] == idempotencyKey &&
                        it[TransactionVoidsTable.reason] == reason
                }
                ?.let { return@newSuspendedTransaction voidResponse(it, true) }
            throw ValidationException("Transaksi sudah pernah dibatalkan")
        }

        val transactionItems = TransactionItemsTable.select {
            TransactionItemsTable.transactionId eq transactionId
        }.toList()
        if (transactionItems.isEmpty()) throw ValidationException("Transaksi tidak memiliki detail item")
        val quantitiesByProduct = transactionItems.groupBy { it[TransactionItemsTable.productId] }
            .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { acc, row -> acc + row[TransactionItemsTable.quantity] } }
        val stockRows = StockTable.select { StockTable.productId inList quantitiesByProduct.keys.sortedBy(UUID::toString) }
            .orderBy(StockTable.productId to SortOrder.ASC).forUpdate()
            .associateBy { it[StockTable.productId] }
        if (stockRows.size != quantitiesByProduct.size) throw ValidationException("Data stok transaksi tidak lengkap")

        val voidId = UUID.randomUUID()
        TransactionVoidsTable.insert {
            it[id] = voidId
            it[this.transactionId] = transactionId
            it[voidedBy] = actorUserId
            it[this.reason] = reason
            it[this.idempotencyKey] = idempotencyKey
        }

        quantitiesByProduct.toSortedMap(compareBy(UUID::toString)).forEach { (productId, quantity) ->
            val before = requireNotNull(stockRows[productId])[StockTable.quantity]
            val adjustmentId = UUID.randomUUID()
            StockAdjustmentsTable.insert {
                it[id] = adjustmentId
                it[this.productId] = productId
                it[userId] = actorUserId
                it[adjType] = AdjType.CORRECTION
                it[qtyBefore] = before
                it[qtyAfter] = before.add(quantity)
                it[this.reason] = "VOID ${transactionRow[TransactionsTable.receiptNumber]}: $reason"
                it[adjustmentSource] = "transaction_void"
                it[referenceType] = "TRANSACTION_VOID"
                it[referenceId] = voidId
                it[occurredOn] = receivableToday()
            }
            insertCheckoutAudit(actorUserId, "inventory", "stock_adjustments", adjustmentId)
            StockMovementsTable.select {
                (StockMovementsTable.referenceType eq "TRANSACTION_VOID") and
                    (StockMovementsTable.referenceId eq adjustmentId)
            }.singleOrNull()?.let { movement ->
                insertCheckoutAudit(actorUserId, "inventory", "stock_movements", movement[StockMovementsTable.id])
            }
            insertCheckoutAudit(actorUserId, "inventory", "stock", requireNotNull(stockRows[productId])[StockTable.id], AuditAction.UPDATE)
        }

        val receivableId = receivableRow?.get(ReceivablesTable.id)
        if (receivableRow != null && receivableId != null) {
            val ledgerRows = ReceivablePaymentsTable.select {
                ReceivablePaymentsTable.receivableId eq receivableId
            }.orderBy(ReceivablePaymentsTable.paidAt to SortOrder.ASC).toList()
            val reversedIds = ledgerRows.mapNotNull { it[ReceivablePaymentsTable.reversedPaymentId] }.toSet()
            ledgerRows.filter {
                it[ReceivablePaymentsTable.entryType] == ReceivablePaymentEntryType.PAYMENT &&
                    it[ReceivablePaymentsTable.id] !in reversedIds
            }.forEach { payment ->
                val reversalId = UUID.randomUUID()
                ReceivablePaymentsTable.insert {
                    it[id] = reversalId
                    it[this.receivableId] = receivableId
                    it[userId] = actorUserId
                    it[amount] = payment[ReceivablePaymentsTable.amount]
                    it[entryType] = ReceivablePaymentEntryType.REVERSAL
                    it[this.idempotencyKey] = "void-$voidId-${payment[ReceivablePaymentsTable.id]}"
                    it[reversedPaymentId] = payment[ReceivablePaymentsTable.id]
                    it[method] = payment[ReceivablePaymentsTable.method]
                    it[reference] = payment[ReceivablePaymentsTable.paymentNumber]
                    it[notes] = "Reversal otomatis karena void ${transactionRow[TransactionsTable.receiptNumber]}"
                }
                val salesPaymentId = UUID.randomUUID()
                PaymentsTable.insert {
                    it[id] = salesPaymentId
                    it[this.transactionId] = transactionId
                    it[method] = PaymentMethod.entries.first { method ->
                        method.dbValue == payment[ReceivablePaymentsTable.method].dbValue
                    }
                    it[amount] = payment[ReceivablePaymentsTable.amount].negate()
                    it[reference] = "VOID:$voidId"
                    it[transactionVoidId] = voidId
                }
                if (payment[ReceivablePaymentsTable.method] == RecPaymentMethod.TUNAI) {
                    reverseCashForPayment(
                        payment[ReceivablePaymentsTable.userId],
                        payment[ReceivablePaymentsTable.paidAt],
                        payment[ReceivablePaymentsTable.amount],
                        actorUserId
                    )
                }
                insertCheckoutAudit(actorUserId, "receivable", "receivable_payments", reversalId)
                insertCheckoutAudit(actorUserId, "sales", "payments", salesPaymentId)
            }
            ReceivablesTable.update({ ReceivablesTable.id eq receivableId }) { it[isActive] = false }
            insertCheckoutAudit(actorUserId, "receivable", "receivables", receivableId, AuditAction.UPDATE)
        }

        val initialPaid = if (receivableRow != null) {
            transactionRow[TransactionsTable.total].subtract(receivableRow[ReceivablesTable.amount]).max(BigDecimal.ZERO)
        } else transactionRow[TransactionsTable.paidAmount]
        if (initialPaid > BigDecimal.ZERO) {
            val initialPayment = PaymentsTable.select {
                (PaymentsTable.transactionId eq transactionId) and (PaymentsTable.amount greater BigDecimal.ZERO)
            }.orderBy(PaymentsTable.paidAt to SortOrder.ASC).limit(1).singleOrNull()
            if (initialPayment != null) {
                val compensationId = UUID.randomUUID()
                PaymentsTable.insert {
                    it[id] = compensationId
                    it[this.transactionId] = transactionId
                    it[method] = initialPayment[PaymentsTable.method]
                    it[amount] = initialPaid.negate()
                    it[reference] = "VOID:$voidId"
                    it[transactionVoidId] = voidId
                }
                if (initialPayment[PaymentsTable.method] == PaymentMethod.TUNAI) {
                    reverseCashSession(transactionRow[TransactionsTable.sessionId], initialPaid, actorUserId)
                }
                insertCheckoutAudit(actorUserId, "sales", "payments", compensationId)
            }
        }

        TransactionsTable.update({ TransactionsTable.id eq transactionId }) {
            it[status] = TrxStatus.VOIDED
            it[paidAmount] = BigDecimal.ZERO.setScale(2)
            it[dpAmount] = BigDecimal.ZERO.setScale(2)
        }
        insertCheckoutAudit(actorUserId, "sales", "transaction_voids", voidId)
        insertCheckoutAudit(actorUserId, "sales", "transactions", transactionId, AuditAction.UPDATE)
        voidResponse(
            TransactionVoidsTable.select { TransactionVoidsTable.id eq voidId }.single(),
            false
        )
    }

    private fun voidResponse(row: ResultRow, replay: Boolean): VoidTransactionResponse {
        val transaction = TransactionsTable.select {
            TransactionsTable.id eq row[TransactionVoidsTable.transactionId]
        }.single()
        val actor = row[TransactionVoidsTable.voidedBy]
        return VoidTransactionResponse(
            voidId = row[TransactionVoidsTable.id].toString(),
            transactionId = row[TransactionVoidsTable.transactionId].toString(),
            receiptId = transaction[TransactionsTable.receiptNumber],
            status = TrxStatus.VOIDED.dbValue,
            reason = row[TransactionVoidsTable.reason],
            voidedBy = actor.toString(),
            voidedByName = getUserName(actor),
            voidedAt = row[TransactionVoidsTable.createdAt].toString(),
            idempotentReplay = replay
        )
    }

    private fun reverseCashForPayment(
        receiverId: UUID,
        paidAt: OffsetDateTime,
        amount: BigDecimal,
        actorUserId: UUID
    ) {
        val session = CashSessionsTable.select {
            (CashSessionsTable.userId eq receiverId) and (CashSessionsTable.openedAt lessEq paidAt)
        }.orderBy(CashSessionsTable.openedAt to SortOrder.DESC).forUpdate().firstOrNull()
            ?: throw ValidationException("Sesi kas penerima pembayaran tidak ditemukan untuk void")
        val closedAt = session[CashSessionsTable.closedAt]
        if (closedAt != null && paidAt > closedAt) throw ValidationException("Pembayaran tunai tidak cocok dengan sesi kas")
        reverseCashSession(session[CashSessionsTable.id], amount, actorUserId)
    }

    private fun reverseCashSession(sessionId: UUID, amount: BigDecimal, actorUserId: UUID) {
        val session = CashSessionsTable.select { CashSessionsTable.id eq sessionId }.forUpdate().single()
        val current = session[CashSessionsTable.systemCash] ?: session[CashSessionsTable.openingCash]
        val updated = current.subtract(amount)
        CashSessionsTable.update({ CashSessionsTable.id eq sessionId }) {
            it[systemCash] = updated
            session[CashSessionsTable.closingCash]?.let { closing -> it[difference] = closing.subtract(updated) }
        }
        insertCheckoutAudit(actorUserId, "sales", "cash_sessions", sessionId, AuditAction.UPDATE)
    }

    private fun findOfflineSyncResponse(
        deviceId: String,
        clientGeneratedId: String,
        cashierUserId: UUID?,
        syncStatus: String
    ): OfflineCheckoutSyncResponse? {
        var query = TransactionsTable.select {
            (TransactionsTable.deviceId eq deviceId) and
                (TransactionsTable.clientGeneratedId eq clientGeneratedId)
        }
        if (cashierUserId != null) {
            query = query.andWhere { TransactionsTable.userId eq cashierUserId }
        }
        val trxRow = query.singleOrNull() ?: return null

        val transactionId = trxRow[TransactionsTable.id]
        val paymentIds = PaymentsTable
            .select { PaymentsTable.transactionId eq transactionId }
            .orderBy(PaymentsTable.paidAt to SortOrder.ASC)
            .map { row -> row[PaymentsTable.id].toString() }
        val receivableId = ReceivablesTable
            .select { ReceivablesTable.transactionId eq transactionId }
            .singleOrNull()
            ?.get(ReceivablesTable.id)
            ?.toString()

        return OfflineCheckoutSyncResponse(
            syncStatus = syncStatus,
            serverTransactionId = transactionId.toString(),
            receiptId = trxRow[TransactionsTable.receiptNumber],
            serverPaymentIds = paymentIds,
            serverReceivableId = receivableId,
            syncedAt = (trxRow[TransactionsTable.syncedAt] ?: trxRow[TransactionsTable.createdAt]).toString()
        )
    }

    override suspend fun getPaginatedTransactions(
        page: Int,
        limit: Int,
        sessionId: UUID?,
        search: String?,
        receiptNumber: String?,
        cashierId: UUID?,
        customerId: UUID?,
        paymentMethod: PaymentMethod?,
        status: TrxStatus?,
        startAt: OffsetDateTime?,
        endExclusive: OffsetDateTime?
    ): com.service.tbterminal.inventory.PaginatedResponse<TransactionSummary> = newSuspendedTransaction(Dispatchers.IO) {
        val offset = ((page - 1) * limit).toLong()
        var query = TransactionsTable
            .leftJoin(CustomersTable, { TransactionsTable.customerId }, { CustomersTable.id })
            .leftJoin(ReceivablesTable, { TransactionsTable.id }, { ReceivablesTable.transactionId })
            .selectAll()
        sessionId?.let { value -> query = query.andWhere { TransactionsTable.sessionId eq value } }
        cashierId?.let { value -> query = query.andWhere { TransactionsTable.userId eq value } }
        customerId?.let { value -> query = query.andWhere { TransactionsTable.customerId eq value } }
        status?.let { value -> query = query.andWhere { TransactionsTable.status eq value } }
        startAt?.let { value -> query = query.andWhere { TransactionsTable.createdAt greaterEq value } }
        endExclusive?.let { value -> query = query.andWhere { TransactionsTable.createdAt less value } }
        receiptNumber?.let { value ->
            query = query.andWhere { TransactionsTable.receiptNumber.lowerCase() like "%${value.lowercase()}%" }
        }
        paymentMethod?.let { method ->
            val ids = PaymentsTable.select { PaymentsTable.method eq method }
                .map { it[PaymentsTable.transactionId] }.distinct()
            if (ids.isEmpty()) {
                return@newSuspendedTransaction com.service.tbterminal.inventory.PaginatedResponse(
                    emptyList(), 0, page, limit, 0
                )
            }
            query = query.andWhere { TransactionsTable.id inList ids }
        }
        search?.let { value ->
            val term = "%${value.lowercase()}%"
            val matchingCashiers = UsersTable.select { UsersTable.name.lowerCase() like term }
                .map { it[UsersTable.id] }
            query = query.andWhere {
                (TransactionsTable.receiptNumber.lowerCase() like term) or
                    (CustomersTable.name.lowerCase() like term) or
                    (TransactionsTable.userId inList matchingCashiers)
            }
        }

        val total = query.count()
        val totalPages = Math.ceil(total.toDouble() / limit).toInt()
        val rows = query.orderBy(TransactionsTable.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .toList()
        val transactionIds = rows.map { it[TransactionsTable.id] }
        val paymentMethods = if (transactionIds.isEmpty()) emptyMap() else PaymentsTable
            .select { PaymentsTable.transactionId inList transactionIds }
            .groupBy { it[PaymentsTable.transactionId] }
            .mapValues { (_, payments) -> payments.map { it[PaymentsTable.method].dbValue }.distinct() }
        val cashierNames = getUserNames(rows.map { it[TransactionsTable.userId] })
        val voids = if (transactionIds.isEmpty()) emptyMap() else TransactionVoidsTable
            .select { TransactionVoidsTable.transactionId inList transactionIds }
            .associateBy { it[TransactionVoidsTable.transactionId] }
        val data = rows.map { row ->
                val transactionTotal = row[TransactionsTable.total]
                val transactionPaid = row[TransactionsTable.paidAmount]
                val storedStatus = row[TransactionsTable.status]
                val receivableAmount = row.getOrNull(ReceivablesTable.amount)
                val receivablePaid = row.getOrNull(ReceivablesTable.paidAmount) ?: BigDecimal.ZERO
                val remainingAmount = if (storedStatus == TrxStatus.VOIDED) BigDecimal.ZERO else receivableAmount
                    ?.subtract(receivablePaid)
                    ?.max(BigDecimal.ZERO)
                    ?: transactionTotal.subtract(transactionPaid).max(BigDecimal.ZERO)
                val effectivePaidAmount = if (storedStatus == TrxStatus.VOIDED) BigDecimal.ZERO else transactionTotal
                    .subtract(remainingAmount)
                    .max(BigDecimal.ZERO)
                    .min(transactionTotal)
                val effectiveStatus = when {
                    storedStatus == TrxStatus.VOIDED -> TrxStatus.VOIDED
                    remainingAmount <= BigDecimal.ZERO -> TrxStatus.LUNAS
                    effectivePaidAmount > BigDecimal.ZERO -> TrxStatus.DP
                    else -> storedStatus
                }
                val transactionId = row[TransactionsTable.id]
                val voidRow = voids[transactionId]

                TransactionSummary(
                    id = transactionId.toString(),
                    receiptId = row[TransactionsTable.receiptNumber],
                    sessionId = row[TransactionsTable.sessionId].toString(),
                    customerId = row[TransactionsTable.customerId]?.toString(),
                    customerName = row[CustomersTable.name],
                    cashierId = row[TransactionsTable.userId].toString(),
                    cashierName = cashierNames[row[TransactionsTable.userId]],
                    paymentMethods = paymentMethods[transactionId].orEmpty(),
                    type = row[TransactionsTable.type].dbValue,
                    status = effectiveStatus.dbValue,
                    total = transactionTotal,
                    paidAmount = effectivePaidAmount,
                    remainingAmount = remainingAmount,
                    createdAt = row[TransactionsTable.createdAt].toString(),
                    voidedAt = voidRow?.get(TransactionVoidsTable.createdAt)?.toString(),
                    voidReason = voidRow?.get(TransactionVoidsTable.reason)
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

    // ==========================================
    // PELUNASAN PIUTANG (DEBT SETTLEMENT)
    // ==========================================

    override suspend fun getExpenses(sessionId: UUID): List<CashExpenseResponse> = newSuspendedTransaction(Dispatchers.IO) {
        CashExpensesTable.select { CashExpensesTable.sessionId eq sessionId }
            .orderBy(CashExpensesTable.createdAt to SortOrder.DESC)
            .map { row -> rowToExpenseResponse(row) }
    }

    override suspend fun getPaginatedExpenses(
        page: Int,
        limit: Int,
        sessionId: UUID?,
        startDate: String?,
        endDate: String?
    ): com.service.tbterminal.inventory.PaginatedResponse<CashExpenseResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val offset = ((page - 1) * limit).toLong()
        var query = CashExpensesTable.selectAll()
        if (sessionId != null) {
            query = query.andWhere { CashExpensesTable.sessionId eq sessionId }
        }
        parseStartDate(startDate)?.let { date ->
            query = query.andWhere { CashExpensesTable.createdAt greaterEq date }
        }
        parseEndDate(endDate)?.let { date ->
            query = query.andWhere { CashExpensesTable.createdAt less date }
        }
        val total = query.count()
        val rows = query.orderBy(CashExpensesTable.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .toList()
        val userNames = getUserNames(rows.map { it[CashExpensesTable.userId] })
        com.service.tbterminal.inventory.PaginatedResponse(
            data = rows.map { row ->
                rowToExpenseResponse(row, userNames[row[CashExpensesTable.userId]])
            },
            total = total,
            page = page,
            limit = limit,
            totalPages = Math.ceil(total.toDouble() / limit).toInt()
        )
    }

    private fun rowToExpenseResponse(row: ResultRow, userName: String? = null): CashExpenseResponse {
        return CashExpenseResponse(
            id = row[CashExpensesTable.id].toString(),
            sessionId = row[CashExpensesTable.sessionId].toString(),
            userId = row[CashExpensesTable.userId].toString(),
            userName = userName,
            amount = row[CashExpensesTable.amount],
            description = row[CashExpensesTable.description],
            createdAt = (row[CashExpensesTable.occurredAt] ?: row[CashExpensesTable.createdAt]).toString()
        )
    }

    override suspend fun payTransactionDebt(
        userId: UUID,
        transactionId: UUID,
        request: PayDebtRequest
    ): TransactionResponse = newSuspendedTransaction(Dispatchers.IO) {
        if (request.amount <= BigDecimal.ZERO) throw ValidationException("Nominal pelunasan harus lebih dari 0")
        if (request.amount.scale() > 2) throw ValidationException("Nominal pelunasan maksimal dua angka desimal")
        val methodEnum = PaymentMethod.entries.firstOrNull {
            it.dbValue.equals(request.method, ignoreCase = true)
        }?.takeIf { it in setOf(PaymentMethod.TUNAI, PaymentMethod.TRANSFER, PaymentMethod.QRIS) }
            ?: throw ValidationException("Metode pembayaran harus tunai, transfer, atau qris")

        // Urutan lock sama dengan endpoint pembayaran piutang: receivable lalu transaction.
        val receivableRow = ReceivablesTable.select {
            (ReceivablesTable.transactionId eq transactionId) and (ReceivablesTable.isActive eq true)
        }.forUpdate().singleOrNull()
            ?: throw NotFoundException("Piutang transaksi tidak ditemukan")
        val trxRow = TransactionsTable.select { TransactionsTable.id eq transactionId }
            .forUpdate().singleOrNull()
            ?: throw NotFoundException("Transaksi tidak ditemukan")

        val currentPaid = trxRow[TransactionsTable.paidAmount]
        val total = trxRow[TransactionsTable.total]
        if (trxRow[TransactionsTable.status] == TrxStatus.LUNAS || currentPaid >= total) {
            throw ValidationException("Transaksi ini sudah lunas")
        }
        val receivableRemaining = receivableRow[ReceivablesTable.amount]
            .subtract(receivableRow[ReceivablesTable.paidAmount])
        if (request.amount > receivableRemaining) {
            throw ValidationException("Nominal bayar melebihi sisa hutang (Sisa: $receivableRemaining)")
        }

        val salesPaymentId = UUID.randomUUID()
        PaymentsTable.insert {
            it[id] = salesPaymentId
            it[this.transactionId] = transactionId
            it[this.method] = methodEnum
            it[this.amount] = request.amount
        }
        val receivablePaymentId = UUID.randomUUID()
        ReceivablePaymentsTable.insert {
            it[id] = receivablePaymentId
            it[receivableId] = receivableRow[ReceivablesTable.id]
            it[this.userId] = userId
            it[amount] = request.amount
            it[method] = when (methodEnum) {
                PaymentMethod.TUNAI -> RecPaymentMethod.TUNAI
                PaymentMethod.TRANSFER -> RecPaymentMethod.TRANSFER
                PaymentMethod.QRIS -> RecPaymentMethod.QRIS
                else -> error("Metode pembayaran piutang tidak valid")
            }
        }

        val newPaidAmount = currentPaid.add(request.amount).min(total)
        TransactionsTable.update({ TransactionsTable.id eq transactionId }) {
            it[paidAmount] = newPaidAmount
            it[status] = if (newPaidAmount >= total) TrxStatus.LUNAS else TrxStatus.DP
        }

        var updatedSessionId: UUID? = null
        if (methodEnum == PaymentMethod.TUNAI) {
            val session = CashSessionsTable.select {
                (CashSessionsTable.userId eq userId) and CashSessionsTable.closedAt.isNull()
            }.forUpdate().singleOrNull()
            if (session != null) {
                updatedSessionId = session[CashSessionsTable.id]
                val currentSystemCash = session[CashSessionsTable.systemCash] ?: session[CashSessionsTable.openingCash]
                CashSessionsTable.update({ CashSessionsTable.id eq updatedSessionId }) {
                    it[systemCash] = currentSystemCash.add(request.amount)
                }
            }
        }

        insertCheckoutAudit(userId, "sales", "payments", salesPaymentId)
        insertCheckoutAudit(userId, "receivable", "receivable_payments", receivablePaymentId)
        insertCheckoutAudit(
            userId, "receivable", "receivables", receivableRow[ReceivablesTable.id], AuditAction.UPDATE
        )
        insertCheckoutAudit(userId, "sales", "transactions", transactionId, AuditAction.UPDATE)
        updatedSessionId?.let { insertCheckoutAudit(userId, "sales", "cash_sessions", it, AuditAction.UPDATE) }

        getTransactionByIdInTransaction(transactionId)
            ?: throw NotFoundException("Transaksi tidak ditemukan setelah pembayaran")
    }
}

private fun parseStartDate(value: String?): OffsetDateTime? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        runCatching { OffsetDateTime.parse(value) }.getOrElse {
            java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneId.of("Asia/Jakarta")).toOffsetDateTime()
        }
    }.getOrNull()
}

private fun parseEndDate(value: String?): OffsetDateTime? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        runCatching { OffsetDateTime.parse(value) }.getOrElse {
            java.time.LocalDate.parse(value).plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Jakarta")).toOffsetDateTime()
        }
    }.getOrNull()
}

private fun BigDecimal.normalizeMoney(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
