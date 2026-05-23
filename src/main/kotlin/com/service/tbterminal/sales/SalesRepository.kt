package com.service.tbterminal.sales

import com.service.tbterminal.inventory.ProductsTable
import com.service.tbterminal.receivable.CustomersTable
import com.service.tbterminal.receivable.ReceivableStatus
import com.service.tbterminal.receivable.ReceivablesTable
import com.service.tbterminal.shared.CreditLimitExceededException
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.SessionNotFoundException
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.system.UsersTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

interface SalesRepository {
    suspend fun getActiveSession(userId: UUID): CashSessionResponse?
    suspend fun getSessionById(sessionId: UUID): CashSessionResponse?
    suspend fun openSession(userId: UUID, startingCash: java.math.BigDecimal): CashSessionResponse
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

    override suspend fun openSession(userId: UUID, startingCash: java.math.BigDecimal): CashSessionResponse = transaction {
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

        CashSessionsTable.select { CashSessionsTable.id eq sessionId }
            .single()
            .let(::rowToResponse)
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
        userId: UUID,
        customerId: UUID?,
        requestItems: List<CheckoutItemRequest>,
        paymentMethod: PaymentMethod,
        amountPaid: java.math.BigDecimal,
        notes: String?,
        dueDays: Int
    ): TransactionResponse = transaction {
        val session = CashSessionsTable.select {
            (CashSessionsTable.userId eq userId) and CashSessionsTable.closedAt.isNull()
        }.forUpdate().singleOrNull()
            ?: throw SessionNotFoundException("Buka sesi kasir terlebih dahulu sebelum bertransaksi")

        val sessionId = session[CashSessionsTable.id]
        val resolvedItems = requestItems.map(::resolveItemForCheckout)
        val totalAmount = resolvedItems.fold(BigDecimal.ZERO) { total, item -> total.add(item.subtotal) }

        if (amountPaid > totalAmount) {
            throw ValidationException("Jumlah bayar tidak boleh melebihi total transaksi")
        }

        val trxStatus = when {
            paymentMethod == PaymentMethod.HUTANG -> TrxStatus.HUTANG
            paymentMethod == PaymentMethod.DP -> TrxStatus.DP
            amountPaid < totalAmount -> TrxStatus.HUTANG
            else -> TrxStatus.LUNAS
        }
        val receivableAmount = totalAmount.subtract(amountPaid)
        val dueDate = if (trxStatus == TrxStatus.HUTANG || trxStatus == TrxStatus.DP) {
            lockCustomerAndValidateCredit(customerId, receivableAmount, dueDays)
        } else {
            null
        }

        val dpAmount = if (trxStatus == TrxStatus.DP) amountPaid else java.math.BigDecimal.ZERO
        val paidAmount = if (trxStatus == TrxStatus.LUNAS) totalAmount else amountPaid

        // 1. Insert transaksi utama
        val trxId = UUID.randomUUID()
        TransactionsTable.insert {
            it[this.id] = trxId
            it[this.sessionId] = sessionId
            it[this.userId] = userId
            it[this.customerId] = customerId
            it[this.type] = TrxType.PENJUALAN
            it[this.status] = trxStatus
            it[this.total] = totalAmount
            it[this.dpAmount] = dpAmount
            it[this.paidAmount] = paidAmount
            it[this.notes] = notes
        }

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
        if (dueDate != null && customerId != null) {
            ReceivablesTable.insert {
                it[this.customerId] = customerId
                it[this.transactionId] = trxId
                it[this.amount] = receivableAmount
                it[this.paidAmount] = java.math.BigDecimal.ZERO
                it[this.dueDate] = dueDate
                it[this.status] = ReceivableStatus.BELUM_LUNAS
            }
        }

        if (paymentMethod == PaymentMethod.TUNAI && amountPaid > BigDecimal.ZERO) {
            val currentSystemCash = session[CashSessionsTable.systemCash] ?: session[CashSessionsTable.openingCash]
            CashSessionsTable.update({ CashSessionsTable.id eq sessionId }) {
                it[this.systemCash] = currentSystemCash.add(amountPaid)
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
        dueDays: Int
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

        val existingOutstanding = ReceivablesTable.select { ReceivablesTable.customerId eq lockedCustomerId }
            .sumOf { row -> row[ReceivablesTable.amount].subtract(row[ReceivablesTable.paidAmount]) }
        val projectedOutstanding = existingOutstanding.add(receivableAmount)
        val creditLimit = customer[CustomersTable.creditLimit]

        if (creditLimit > BigDecimal.ZERO && projectedOutstanding > creditLimit) {
            throw CreditLimitExceededException(
                "Limit kredit pelanggan terlampaui. Outstanding setelah transaksi: ${projectedOutstanding.toPlainString()}"
            )
        }

        return java.time.LocalDate.now().plusDays(dueDays.toLong())
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
