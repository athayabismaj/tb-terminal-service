package com.service.tbterminal.receivable

import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.sales.TransactionsTable
import com.service.tbterminal.sales.TrxStatus
import com.service.tbterminal.sales.PaymentsTable
import com.service.tbterminal.sales.PaymentMethod
import com.service.tbterminal.sales.CashSessionsTable
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.system.AuditAction
import com.service.tbterminal.system.AuditLogsTable
import com.service.tbterminal.system.UsersTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
import java.time.LocalDate
import java.math.BigDecimal
import java.util.UUID

interface ReceivableRepository {
    // Customers
    suspend fun getPaginatedCustomers(page: Int, limit: Int, search: String?): PaginatedResponse<CustomerResponse>
    suspend fun getCustomerById(id: UUID): CustomerResponse?
    suspend fun getCustomerByName(name: String): CustomerResponse?
    suspend fun createCustomer(
        name: String, phone: String?, address: String?,
        isContractor: Boolean, creditLimit: java.math.BigDecimal, paymentTermDays: Int
    ): UUID
    suspend fun updateCustomer(
        id: UUID, name: String, phone: String?, address: String?,
        isContractor: Boolean, creditLimit: java.math.BigDecimal, paymentTermDays: Int
    ): Boolean
    suspend fun softDeleteCustomer(id: UUID): Boolean

    // Receivables
    suspend fun getPaginatedReceivables(
        page: Int, limit: Int, customerId: UUID?, status: ReceivableStatus?,
        dueFilter: ReceivableDueFilter, dueFrom: LocalDate?, dueTo: LocalDate?
    ): PaginatedResponse<ReceivableResponse>
    suspend fun getReceivableById(id: UUID): ReceivableResponse?
    suspend fun getReceivableForUpdate(id: UUID): ReceivableForUpdateRow?
    suspend fun createStandaloneReceivable(
        userId: UUID,
        customerId: UUID,
        amount: BigDecimal,
        debtDate: LocalDate,
        dueDate: LocalDate,
        legacyInvoiceNumber: String?,
        source: ReceivableSource,
        notes: String?
    ): ReceivableResponse
    suspend fun getCustomerSummaries(
        page: Int, limit: Int, dueFilter: ReceivableDueFilter
    ): PaginatedResponse<CustomerReceivableSummaryResponse>

    // Payments
    suspend fun getPaginatedPayments(
        page: Int,
        limit: Int,
        receivableId: UUID?,
        customerId: UUID?,
        method: RecPaymentMethod?,
        userId: UUID?,
        customerSearch: String?,
        receiverSearch: String?,
        status: ReceivableStatus?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?
    ): PaginatedResponse<PaymentHistoryResponse>
    suspend fun getPaymentReceipt(paymentId: UUID): PaymentHistoryResponse?
    suspend fun insertPaymentAndUpdateReceivable(
        receivableId: UUID, userId: UUID,
        paymentAmount: java.math.BigDecimal, method: RecPaymentMethod,
        reference: String?, notes: String?, idempotencyKey: String
    ): PaymentResponse
    suspend fun reversePayment(
        paymentId: UUID,
        userId: UUID,
        idempotencyKey: String,
        reason: String
    ): PaymentResponse
}

// Data class internal untuk menyimpan data piutang yang di-lock (FOR UPDATE)
data class ReceivableForUpdateRow(
    val id: UUID,
    val customerId: UUID,
    val transactionId: UUID?,
    val source: ReceivableSource,
    val amount: java.math.BigDecimal,
    val paidAmount: java.math.BigDecimal,
    val status: ReceivableStatus
)

class ReceivableRepositoryImpl : ReceivableRepository {

    // ==========================================
    // CUSTOMERS
    // ==========================================

    override suspend fun getPaginatedCustomers(page: Int, limit: Int, search: String?): PaginatedResponse<CustomerResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val offset = ((page - 1) * limit).toLong()

        val totalCount = customerQuery(search).count()
        val totalPages = kotlin.math.ceil(totalCount.toDouble() / limit).toInt()

        val data = customerQuery(search)
            .orderBy(CustomersTable.name, SortOrder.ASC)
            .limit(limit, offset)
            .map { rowToCustomerResponse(it) }

        PaginatedResponse(
            data = data,
            total = totalCount,
            page = page,
            limit = limit,
            totalPages = totalPages
        )
    }

    override suspend fun getCustomerById(id: UUID): CustomerResponse? = newSuspendedTransaction(Dispatchers.IO) {
        CustomersTable.select { (CustomersTable.id eq id) and (CustomersTable.isActive eq true) }
            .singleOrNull()?.let { rowToCustomerResponse(it) }
    }

    override suspend fun getCustomerByName(name: String): CustomerResponse? = newSuspendedTransaction(Dispatchers.IO) {
        CustomersTable.select {
            (CustomersTable.name.lowerCase() eq name.lowercase()) and (CustomersTable.isActive eq true)
        }.singleOrNull()?.let { rowToCustomerResponse(it) }
    }

    override suspend fun createCustomer(
        name: String, phone: String?, address: String?,
        isContractor: Boolean, creditLimit: java.math.BigDecimal, paymentTermDays: Int
    ): UUID = newSuspendedTransaction(Dispatchers.IO) {
        val customerId = UUID.randomUUID()
        CustomersTable.insert {
            it[this.id] = customerId
            it[this.name] = name
            it[this.phone] = phone
            it[this.address] = address
            it[this.isContractor] = isContractor
            it[this.creditLimit] = creditLimit
            it[this.paymentTermDays] = paymentTermDays
        }
        customerId
    }

    override suspend fun updateCustomer(
        id: UUID, name: String, phone: String?, address: String?,
        isContractor: Boolean, creditLimit: java.math.BigDecimal, paymentTermDays: Int
    ): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val updatedRows = CustomersTable.update({ CustomersTable.id eq id }) {
            it[this.name] = name
            it[this.phone] = phone
            it[this.address] = address
            it[this.isContractor] = isContractor
            it[this.creditLimit] = creditLimit
            it[this.paymentTermDays] = paymentTermDays
            it[this.updatedAt] = OffsetDateTime.now()
        }
        updatedRows > 0
    }

    override suspend fun softDeleteCustomer(id: UUID): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val updatedRows = CustomersTable.update({ CustomersTable.id eq id }) {
            it[isActive] = false
            it[updatedAt] = OffsetDateTime.now()
        }
        updatedRows > 0
    }

    // ==========================================
    // RECEIVABLES
    // ==========================================

    override suspend fun getPaginatedReceivables(
        page: Int, limit: Int, customerId: UUID?, status: ReceivableStatus?,
        dueFilter: ReceivableDueFilter, dueFrom: LocalDate?, dueTo: LocalDate?
    ): PaginatedResponse<ReceivableResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val offset = ((page - 1) * limit).toLong()

        // JOIN receivables ← customers untuk ambil nama pelanggan
        val totalCount = receivableQuery(customerId, status, dueFilter, dueFrom, dueTo).count()
        val totalPages = kotlin.math.ceil(totalCount.toDouble() / limit).toInt()

        val data = receivableQuery(customerId, status, dueFilter, dueFrom, dueTo)
            .orderBy(ReceivablesTable.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .map(::rowToReceivableResponse)

        PaginatedResponse(
            data = data,
            total = totalCount,
            page = page,
            limit = limit,
            totalPages = totalPages
        )
    }

    override suspend fun getReceivableById(id: UUID): ReceivableResponse? = newSuspendedTransaction(Dispatchers.IO) {
        ReceivablesTable.innerJoin(CustomersTable)
            .select { ReceivablesTable.id eq id }
            .singleOrNull()?.let(::rowToReceivableResponse)
    }

    override suspend fun getReceivableForUpdate(id: UUID): ReceivableForUpdateRow? = newSuspendedTransaction(Dispatchers.IO) {
        ReceivablesTable.select { ReceivablesTable.id eq id }
            .forUpdate()
            .singleOrNull()?.let { row ->
                ReceivableForUpdateRow(
                    id = row[ReceivablesTable.id],
                    customerId = row[ReceivablesTable.customerId],
                    transactionId = row[ReceivablesTable.transactionId],
                    source = row[ReceivablesTable.receivableSource],
                    amount = row[ReceivablesTable.amount],
                    paidAmount = row[ReceivablesTable.paidAmount],
                    status = row[ReceivablesTable.status]
                )
            }
    }

    override suspend fun createStandaloneReceivable(
        userId: UUID,
        customerId: UUID,
        amount: BigDecimal,
        debtDate: LocalDate,
        dueDate: LocalDate,
        legacyInvoiceNumber: String?,
        source: ReceivableSource,
        notes: String?
    ): ReceivableResponse = newSuspendedTransaction(Dispatchers.IO) {
        val customer = CustomersTable.select { CustomersTable.id eq customerId }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Pelanggan tidak ditemukan")
        if (!customer[CustomersTable.isActive]) {
            throw ValidationException("Saldo awal hanya dapat dibuat untuk pelanggan aktif")
        }

        val outstanding = ReceivablesTable.select {
            (ReceivablesTable.customerId eq customerId) and
                (ReceivablesTable.isActive eq true)
        }.sumOf { row -> row[ReceivablesTable.amount].subtract(row[ReceivablesTable.paidAmount]) }
        val creditLimit = customer[CustomersTable.creditLimit]
        ensureReceivableCreditLimit(outstanding, amount, creditLimit)

        val receivableId = UUID.randomUUID()
        ReceivablesTable.insert {
            it[id] = receivableId
            it[this.customerId] = customerId
            it[this.transactionId] = null
            it[this.receivableSource] = source
            it[this.amount] = amount
            it[this.paidAmount] = BigDecimal.ZERO
            it[this.debtDate] = debtDate
            it[this.dueDate] = dueDate
            it[this.legacyInvoiceNumber] = legacyInvoiceNumber
            it[this.notes] = notes
            it[this.createdBy] = userId
            it[this.status] = ReceivableStatus.UNPAID
        }
        insertAudit(userId, AuditAction.INSERT, "receivables", receivableId)

        ReceivablesTable.innerJoin(CustomersTable)
            .select { ReceivablesTable.id eq receivableId }
            .single()
            .let(::rowToReceivableResponse)
    }

    override suspend fun getCustomerSummaries(
        page: Int,
        limit: Int,
        dueFilter: ReceivableDueFilter
    ): PaginatedResponse<CustomerReceivableSummaryResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val today = receivableToday()
        val grouped = receivableQuery(null, null, dueFilter, null, null)
            .orderBy(CustomersTable.name, SortOrder.ASC)
            .toList()
            .groupBy { row -> row[ReceivablesTable.customerId] }
            .map { (customerId, rows) ->
                val totalAmount = rows.fold(BigDecimal.ZERO) { total, row -> total.add(row[ReceivablesTable.amount]) }
                val totalPaid = rows.fold(BigDecimal.ZERO) { total, row -> total.add(row[ReceivablesTable.paidAmount]) }
                val openRows = rows.filter { it[ReceivablesTable.status] != ReceivableStatus.PAID }
                CustomerReceivableSummaryResponse(
                    customerId = customerId.toString(),
                    customerName = rows.first()[CustomersTable.name],
                    totalAmount = totalAmount,
                    totalPaid = totalPaid,
                    totalRemaining = totalAmount.subtract(totalPaid),
                    unpaidCount = openRows.size.toLong(),
                    overdueCount = openRows.count { it[ReceivablesTable.dueDate] < today }.toLong(),
                    nearestDueDate = openRows.minOfOrNull { it[ReceivablesTable.dueDate] }?.toString()
                )
            }
        val offset = ((page - 1) * limit).coerceAtLeast(0)
        val pageData = grouped.drop(offset).take(limit)
        PaginatedResponse(
            data = pageData,
            total = grouped.size.toLong(),
            page = page,
            limit = limit,
            totalPages = kotlin.math.ceil(grouped.size.toDouble() / limit).toInt()
        )
    }

    // ==========================================
    // PAYMENTS
    // ==========================================

    override suspend fun getPaginatedPayments(
        page: Int,
        limit: Int,
        receivableId: UUID?,
        customerId: UUID?,
        method: RecPaymentMethod?,
        userId: UUID?,
        customerSearch: String?,
        receiverSearch: String?,
        status: ReceivableStatus?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?
    ): PaginatedResponse<PaymentHistoryResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val offset = ((page - 1) * limit).toLong()
        val totalCount = paymentHistoryQuery(
            receivableId, customerId, method, userId, customerSearch, receiverSearch, status, dateFrom, dateTo
        ).count()
        val totalPages = kotlin.math.ceil(totalCount.toDouble() / limit).toInt()
        val data = paymentHistoryQuery(
            receivableId, customerId, method, userId, customerSearch, receiverSearch, status, dateFrom, dateTo
        )
            .orderBy(ReceivablePaymentsTable.paidAt, SortOrder.DESC)
            .limit(limit, offset)
            .map(::rowToPaymentHistoryResponse)

        PaginatedResponse(data, totalCount, page, limit, totalPages)
    }

    override suspend fun getPaymentReceipt(paymentId: UUID): PaymentHistoryResponse? =
        newSuspendedTransaction(Dispatchers.IO) {
            paymentHistoryQuery(paymentId = paymentId)
                .singleOrNull()
                ?.let(::rowToPaymentHistoryResponse)
        }

    override suspend fun insertPaymentAndUpdateReceivable(
        receivableId: UUID, userId: UUID,
        paymentAmount: java.math.BigDecimal, method: RecPaymentMethod,
        reference: String?, notes: String?, idempotencyKey: String
    ): PaymentResponse = newSuspendedTransaction(Dispatchers.IO) {
        lockIdempotencyKey(idempotencyKey)
        paymentHistoryQuery(idempotencyKey = idempotencyKey).singleOrNull()?.let { existing ->
            if (
                existing[ReceivablePaymentsTable.entryType] != ReceivablePaymentEntryType.PAYMENT ||
                existing[ReceivablePaymentsTable.receivableId] != receivableId ||
                existing[ReceivablePaymentsTable.amount].compareTo(paymentAmount) != 0 ||
                existing[ReceivablePaymentsTable.method] != method ||
                existing[ReceivablePaymentsTable.reference] != reference ||
                existing[ReceivablePaymentsTable.notes] != notes
            ) {
                throw ValidationException("Idempotency key sudah digunakan untuk request pembayaran yang berbeda")
            }
            return@newSuspendedTransaction rowToPaymentResponse(existing, idempotentReplay = true)
        }

        val lockedReceivable = ReceivablesTable.select { ReceivablesTable.id eq receivableId }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Piutang tidak ditemukan")
        if (!lockedReceivable[ReceivablesTable.isActive]) {
            throw ValidationException("Piutang sudah dinonaktifkan")
        }
        if (lockedReceivable[ReceivablesTable.status] == ReceivableStatus.PAID) {
            throw ValidationException("Piutang ini sudah lunas, tidak dapat menerima pembayaran lagi")
        }
        val receivableAmount = lockedReceivable[ReceivablesTable.amount]
        val remainingAmount = receivableAmount.subtract(lockedReceivable[ReceivablesTable.paidAmount])
        if (paymentAmount > remainingAmount) {
            throw ValidationException("Pembayaran melebihi sisa piutang ${remainingAmount.toPlainString()}")
        }
        // Trigger database mengubah paid_amount dan status dalam transaksi yang sama.
        val paymentId = UUID.randomUUID()
        ReceivablePaymentsTable.insert {
            it[this.id] = paymentId
            it[this.receivableId] = receivableId
            it[this.userId] = userId
            it[this.amount] = paymentAmount
            it[this.method] = method
            it[this.reference] = reference
            it[this.notes] = notes
            it[this.entryType] = ReceivablePaymentEntryType.PAYMENT
            it[this.idempotencyKey] = idempotencyKey
            it[this.reversedPaymentId] = null
        }

        val receivableRow = ReceivablesTable.select { ReceivablesTable.id eq receivableId }.single()
        val newPaidAmount = receivableRow[ReceivablesTable.paidAmount]
        val newStatus = receivableRow[ReceivablesTable.status]
        if (newStatus != deriveReceivableStatus(receivableAmount, newPaidAmount)) {
            error("Status piutang tidak konsisten setelah pembayaran")
        }
        val transactionId = receivableRow[ReceivablesTable.transactionId]
        if (receivableRow[ReceivablesTable.receivableSource] == ReceivableSource.SALE && transactionId != null) {
            val transactionRow = TransactionsTable.select { TransactionsTable.id eq transactionId }
                .forUpdate().singleOrNull()
                ?: throw NotFoundException("Transaksi sumber piutang tidak ditemukan")
            val newTransactionPaid = transactionRow[TransactionsTable.paidAmount]
                .add(paymentAmount)
                .min(transactionRow[TransactionsTable.total])
            val newTransactionStatus = if (newStatus == ReceivableStatus.PAID) TrxStatus.LUNAS else TrxStatus.DP
            TransactionsTable.update({ TransactionsTable.id eq transactionId }) {
                it[this.paidAmount] = newTransactionPaid
                it[this.status] = newTransactionStatus
            }
            val salesPaymentId = UUID.randomUUID()
            PaymentsTable.insert {
                it[id] = salesPaymentId
                it[this.transactionId] = transactionId
                it[this.method] = method.toSalesPaymentMethod()
                it[this.amount] = paymentAmount
            }
            insertAudit(userId, AuditAction.INSERT, "payments", salesPaymentId, schema = "sales")
            insertAudit(userId, AuditAction.UPDATE, "transactions", transactionId, schema = "sales")
        }
        if (method == RecPaymentMethod.TUNAI) {
            adjustCashSessionForPayment(userId, OffsetDateTime.now(), paymentAmount, add = true, auditUserId = userId)
        }

        insertAudit(userId, AuditAction.INSERT, "receivable_payments", paymentId)
        insertAudit(userId, AuditAction.UPDATE, "receivables", receivableId)

        val paymentRow = paymentHistoryQuery(paymentId = paymentId).single()
        rowToPaymentResponse(paymentRow, idempotentReplay = false)
    }

    override suspend fun reversePayment(
        paymentId: UUID,
        userId: UUID,
        idempotencyKey: String,
        reason: String
    ): PaymentResponse = newSuspendedTransaction(Dispatchers.IO) {
        lockIdempotencyKey(idempotencyKey)
        paymentHistoryQuery(idempotencyKey = idempotencyKey).singleOrNull()?.let { existing ->
            if (
                existing[ReceivablePaymentsTable.entryType] != ReceivablePaymentEntryType.REVERSAL ||
                existing[ReceivablePaymentsTable.reversedPaymentId] != paymentId ||
                existing[ReceivablePaymentsTable.notes] != reason
            ) {
                throw ValidationException("Idempotency key sudah digunakan untuk request reversal yang berbeda")
            }
            return@newSuspendedTransaction rowToPaymentResponse(existing, idempotentReplay = true)
        }

        val originalPreview = ReceivablePaymentsTable
            .select { ReceivablePaymentsTable.id eq paymentId }
            .singleOrNull()
            ?: throw NotFoundException("Pembayaran tidak ditemukan")
        val receivableId = originalPreview[ReceivablePaymentsTable.receivableId]
        val lockedReceivable = ReceivablesTable.select { ReceivablesTable.id eq receivableId }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Piutang tidak ditemukan")
        val original = ReceivablePaymentsTable.select { ReceivablePaymentsTable.id eq paymentId }
            .forUpdate()
            .single()

        if (original[ReceivablePaymentsTable.entryType] != ReceivablePaymentEntryType.PAYMENT) {
            throw ValidationException("Hanya entri pembayaran asli yang dapat direversal")
        }
        if (original[ReceivablePaymentsTable.method] !in supportedReceivablePaymentMethods) {
            throw ValidationException("Metode pembayaran lama ini tidak mendukung reversal")
        }
        if (ReceivablePaymentsTable.select {
                ReceivablePaymentsTable.reversedPaymentId eq paymentId
            }.count() > 0L
        ) {
            throw ValidationException("Pembayaran sudah pernah direversal")
        }
        if (original[ReceivablePaymentsTable.amount] > lockedReceivable[ReceivablesTable.paidAmount]) {
            throw ValidationException("Pembayaran tidak dapat direversal karena saldo terbayar sudah berubah")
        }

        val reversalId = UUID.randomUUID()
        ReceivablePaymentsTable.insert {
            it[id] = reversalId
            it[this.receivableId] = receivableId
            it[this.userId] = userId
            it[amount] = original[ReceivablePaymentsTable.amount]
            it[method] = original[ReceivablePaymentsTable.method]
            it[reference] = "REVERSAL:${original[ReceivablePaymentsTable.paymentNumber]}"
            it[notes] = reason
            it[entryType] = ReceivablePaymentEntryType.REVERSAL
            it[this.idempotencyKey] = idempotencyKey
            it[reversedPaymentId] = paymentId
        }

        val source = lockedReceivable[ReceivablesTable.receivableSource]
        val transactionId = lockedReceivable[ReceivablesTable.transactionId]
        if (source == ReceivableSource.SALE && transactionId != null) {
            val transaction = TransactionsTable.select { TransactionsTable.id eq transactionId }
                .forUpdate()
                .singleOrNull()
                ?: throw NotFoundException("Transaksi sumber piutang tidak ditemukan")
            val newTransactionPaid = transaction[TransactionsTable.paidAmount]
                .subtract(original[ReceivablePaymentsTable.amount])
                .coerceAtLeast(BigDecimal.ZERO)
            TransactionsTable.update({ TransactionsTable.id eq transactionId }) {
                it[paidAmount] = newTransactionPaid
                it[status] = if (newTransactionPaid == BigDecimal.ZERO) TrxStatus.HUTANG else TrxStatus.DP
            }
            val salesReversalId = UUID.randomUUID()
            PaymentsTable.insert {
                it[id] = salesReversalId
                it[this.transactionId] = transactionId
                it[method] = original[ReceivablePaymentsTable.method].toSalesPaymentMethod()
                it[amount] = original[ReceivablePaymentsTable.amount].negate()
                it[reference] = "REVERSAL:${original[ReceivablePaymentsTable.paymentNumber]}"
            }
            insertAudit(userId, AuditAction.INSERT, "payments", salesReversalId, schema = "sales")
            insertAudit(userId, AuditAction.UPDATE, "transactions", transactionId, schema = "sales")
        }
        if (original[ReceivablePaymentsTable.method] == RecPaymentMethod.TUNAI) {
            adjustCashSessionForPayment(
                original[ReceivablePaymentsTable.userId],
                original[ReceivablePaymentsTable.paidAt],
                original[ReceivablePaymentsTable.amount],
                add = false,
                auditUserId = userId
            )
        }

        insertAudit(userId, AuditAction.INSERT, "receivable_payments", reversalId)
        insertAudit(userId, AuditAction.UPDATE, "receivables", receivableId)
        rowToPaymentResponse(paymentHistoryQuery(paymentId = reversalId).single(), idempotentReplay = false)
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private fun adjustCashSessionForPayment(
        receiverId: UUID,
        paidAt: OffsetDateTime,
        amount: BigDecimal,
        add: Boolean,
        auditUserId: UUID
    ) {
        val session = CashSessionsTable.select {
            (CashSessionsTable.userId eq receiverId) and (CashSessionsTable.openedAt lessEq paidAt)
        }.orderBy(CashSessionsTable.openedAt to SortOrder.DESC).forUpdate().firstOrNull()
            ?: throw ValidationException("Pembayaran tunai memerlukan sesi kas penerima yang sesuai")
        val closedAt = session[CashSessionsTable.closedAt]
        if (closedAt != null && paidAt > closedAt) {
            throw ValidationException("Pembayaran tunai tidak cocok dengan periode sesi kas")
        }
        val current = session[CashSessionsTable.systemCash] ?: session[CashSessionsTable.openingCash]
        val updated = if (add) current.add(amount) else current.subtract(amount)
        val sessionId = session[CashSessionsTable.id]
        CashSessionsTable.update({ CashSessionsTable.id eq sessionId }) {
            it[systemCash] = updated
            session[CashSessionsTable.closingCash]?.let { closing -> it[difference] = closing.subtract(updated) }
        }
        insertAudit(auditUserId, AuditAction.UPDATE, "cash_sessions", sessionId, schema = "sales")
    }

    private fun customerQuery(search: String?): Query {
        var query = CustomersTable.select { CustomersTable.isActive eq true }

        if (!search.isNullOrBlank()) {
            val searchTerm = "%${search.lowercase()}%"
            query = query.andWhere {
                (CustomersTable.name.lowerCase() like searchTerm) or
                    (CustomersTable.phone.lowerCase() like searchTerm)
            }
        }

        return query
    }

    private fun receivableQuery(
        customerId: UUID?,
        status: ReceivableStatus?,
        dueFilter: ReceivableDueFilter,
        dueFrom: LocalDate?,
        dueTo: LocalDate?
    ): Query {
        var query = ReceivablesTable.innerJoin(CustomersTable)
            .select { ReceivablesTable.isActive eq true }

        if (customerId != null) {
            query = query.andWhere { ReceivablesTable.customerId eq customerId }
        }
        if (status != null) {
            query = query.andWhere { ReceivablesTable.status eq status }
        }
        val today = receivableToday()
        when (dueFilter) {
            ReceivableDueFilter.ALL -> Unit
            ReceivableDueFilter.OVERDUE -> query = query.andWhere {
                (ReceivablesTable.dueDate less today) and (ReceivablesTable.status neq ReceivableStatus.PAID)
            }
            ReceivableDueFilter.DUE_TODAY -> query = query.andWhere {
                (ReceivablesTable.dueDate eq today) and (ReceivablesTable.status neq ReceivableStatus.PAID)
            }
            ReceivableDueFilter.UPCOMING -> query = query.andWhere {
                (ReceivablesTable.dueDate greater today) and (ReceivablesTable.status neq ReceivableStatus.PAID)
            }
        }
        dueFrom?.let { value -> query = query.andWhere { ReceivablesTable.dueDate greaterEq value } }
        dueTo?.let { value -> query = query.andWhere { ReceivablesTable.dueDate lessEq value } }

        return query
    }

    private fun paymentHistoryQuery(
        receivableId: UUID? = null,
        customerId: UUID? = null,
        method: RecPaymentMethod? = null,
        userId: UUID? = null,
        customerSearch: String? = null,
        receiverSearch: String? = null,
        status: ReceivableStatus? = null,
        dateFrom: LocalDate? = null,
        dateTo: LocalDate? = null,
        paymentId: UUID? = null,
        idempotencyKey: String? = null
    ): Query {
        var query = ReceivablePaymentsTable
            .innerJoin(ReceivablesTable)
            .innerJoin(CustomersTable)
            .join(UsersTable, JoinType.INNER, ReceivablePaymentsTable.userId, UsersTable.id)
            .selectAll()
        receivableId?.let { value -> query = query.andWhere { ReceivablePaymentsTable.receivableId eq value } }
        if (customerId != null) {
            query = query.andWhere { ReceivablesTable.customerId eq customerId }
        }
        method?.let { value -> query = query.andWhere { ReceivablePaymentsTable.method eq value } }
        userId?.let { value -> query = query.andWhere { ReceivablePaymentsTable.userId eq value } }
        customerSearch?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            query = query.andWhere { CustomersTable.name.lowerCase() like "%${value.lowercase()}%" }
        }
        receiverSearch?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            query = query.andWhere { UsersTable.name.lowerCase() like "%${value.lowercase()}%" }
        }
        status?.let { value -> query = query.andWhere { ReceivablePaymentsTable.statusAfter eq value } }
        dateFrom?.let { value -> query = query.andWhere { ReceivablePaymentsTable.paymentDate greaterEq value } }
        dateTo?.let { value -> query = query.andWhere { ReceivablePaymentsTable.paymentDate lessEq value } }
        paymentId?.let { value -> query = query.andWhere { ReceivablePaymentsTable.id eq value } }
        idempotencyKey?.let { value -> query = query.andWhere { ReceivablePaymentsTable.idempotencyKey eq value } }
        return query
    }

    private fun rowToPaymentHistoryResponse(row: ResultRow): PaymentHistoryResponse {
        val receivableAmount = row[ReceivablesTable.amount]
        return PaymentHistoryResponse(
            id = row[ReceivablePaymentsTable.id].toString(),
            paymentNumber = row[ReceivablePaymentsTable.paymentNumber],
            receivableId = row[ReceivablePaymentsTable.receivableId].toString(),
            customerId = row[ReceivablesTable.customerId].toString(),
            customerName = row[CustomersTable.name],
            transactionId = row[ReceivablesTable.transactionId]?.toString(),
            source = row[ReceivablesTable.receivableSource].name,
            amount = row[ReceivablePaymentsTable.amount],
            method = row[ReceivablePaymentsTable.method].dbValue,
            reference = row[ReceivablePaymentsTable.reference],
            notes = row[ReceivablePaymentsTable.notes],
            paidAt = row[ReceivablePaymentsTable.paidAt].toString(),
            paymentDate = row[ReceivablePaymentsTable.paymentDate].toString(),
            entryType = row[ReceivablePaymentsTable.entryType].name,
            reversedPaymentId = row[ReceivablePaymentsTable.reversedPaymentId]?.toString(),
            receivedBy = row[ReceivablePaymentsTable.userId].toString(),
            receivedByName = row[UsersTable.name],
            balanceBefore = row[ReceivablePaymentsTable.balanceBefore],
            balanceAfter = row[ReceivablePaymentsTable.balanceAfter],
            isReversed = ReceivablePaymentsTable.select {
                ReceivablePaymentsTable.reversedPaymentId eq row[ReceivablePaymentsTable.id]
            }.count() > 0L,
            receivableStatus = row[ReceivablePaymentsTable.statusAfter].name,
            receivableRemainingAmount = row[ReceivablePaymentsTable.balanceAfter]
        )
    }

    private fun rowToPaymentResponse(row: ResultRow, idempotentReplay: Boolean): PaymentResponse {
        val amount = row[ReceivablesTable.amount]
        val balanceAfter = row[ReceivablePaymentsTable.balanceAfter]
        return PaymentResponse(
            id = row[ReceivablePaymentsTable.id].toString(),
            paymentNumber = row[ReceivablePaymentsTable.paymentNumber],
            receivableId = row[ReceivablePaymentsTable.receivableId].toString(),
            customerId = row[ReceivablesTable.customerId].toString(),
            customerName = row[CustomersTable.name],
            amount = row[ReceivablePaymentsTable.amount],
            method = row[ReceivablePaymentsTable.method].dbValue,
            reference = row[ReceivablePaymentsTable.reference],
            notes = row[ReceivablePaymentsTable.notes],
            paidAt = row[ReceivablePaymentsTable.paidAt].toString(),
            paymentDate = row[ReceivablePaymentsTable.paymentDate].toString(),
            entryType = row[ReceivablePaymentsTable.entryType].name,
            reversedPaymentId = row[ReceivablePaymentsTable.reversedPaymentId]?.toString(),
            receivedBy = row[ReceivablePaymentsTable.userId].toString(),
            receivedByName = row[UsersTable.name],
            balanceBefore = row[ReceivablePaymentsTable.balanceBefore],
            balanceAfter = balanceAfter,
            receivableStatus = row[ReceivablePaymentsTable.statusAfter].name,
            receivableRemainingAmount = balanceAfter,
            idempotentReplay = idempotentReplay
        )
    }

    private fun org.jetbrains.exposed.sql.Transaction.lockIdempotencyKey(idempotencyKey: String) {
        exec("SELECT pg_advisory_xact_lock(hashtextextended('$idempotencyKey', 0))")
    }

    private fun rowToReceivableResponse(row: ResultRow): ReceivableResponse {
        val amount = row[ReceivablesTable.amount]
        val paidAmount = row[ReceivablesTable.paidAmount]
        return ReceivableResponse(
            id = row[ReceivablesTable.id].toString(),
            customerId = row[ReceivablesTable.customerId].toString(),
            customerName = row[CustomersTable.name],
            transactionId = row[ReceivablesTable.transactionId]?.toString(),
            source = row[ReceivablesTable.receivableSource].name,
            amount = amount,
            paidAmount = paidAmount,
            remainingAmount = amount.subtract(paidAmount),
            debtDate = row[ReceivablesTable.debtDate].toString(),
            dueDate = row[ReceivablesTable.dueDate].toString(),
            status = row[ReceivablesTable.status].name,
            legacyInvoiceNumber = row[ReceivablesTable.legacyInvoiceNumber],
            notes = row[ReceivablesTable.notes],
            createdBy = row[ReceivablesTable.createdBy].toString(),
            isActive = row[ReceivablesTable.isActive],
            createdAt = row[ReceivablesTable.createdAt].toString()
        )
    }

    private fun insertAudit(
        userId: UUID,
        action: AuditAction,
        table: String,
        recordId: UUID,
        schema: String = "receivable"
    ) {
        AuditLogsTable.insert {
            it[id] = UUID.randomUUID()
            it[this.userId] = userId
            it[this.action] = action
            it[targetSchemaName] = schema
            it[targetTableName] = table
            it[this.recordId] = recordId
        }
    }

    private fun rowToCustomerResponse(row: ResultRow): CustomerResponse {
        return CustomerResponse(
            id = row[CustomersTable.id].toString(),
            name = row[CustomersTable.name],
            phone = row[CustomersTable.phone],
            address = row[CustomersTable.address],
            isContractor = row[CustomersTable.isContractor],
            creditLimit = row[CustomersTable.creditLimit],
            paymentTermDays = row[CustomersTable.paymentTermDays],
            isActive = row[CustomersTable.isActive],
            createdAt = row[CustomersTable.createdAt].toString(),
            updatedAt = row[CustomersTable.updatedAt].toString()
        )
    }
}

private val supportedReceivablePaymentMethods = setOf(
    RecPaymentMethod.TUNAI,
    RecPaymentMethod.TRANSFER,
    RecPaymentMethod.QRIS
)

private fun RecPaymentMethod.toSalesPaymentMethod(): PaymentMethod = when (this) {
    RecPaymentMethod.TUNAI -> PaymentMethod.TUNAI
    RecPaymentMethod.TRANSFER -> PaymentMethod.TRANSFER
    RecPaymentMethod.QRIS -> PaymentMethod.QRIS
    RecPaymentMethod.HUTANG -> PaymentMethod.HUTANG
    RecPaymentMethod.DP -> PaymentMethod.DP
}
