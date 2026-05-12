package com.service.tbterminal.receivable

import com.service.tbterminal.inventory.PaginatedResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
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
        page: Int, limit: Int, customerId: UUID?, status: ReceivableStatus?
    ): PaginatedResponse<ReceivableResponse>
    suspend fun getReceivableById(id: UUID): ReceivableResponse?
    suspend fun getReceivableForUpdate(id: UUID): ReceivableForUpdateRow?

    // Payments
    suspend fun insertPaymentAndUpdateReceivable(
        receivableId: UUID, userId: UUID,
        paymentAmount: java.math.BigDecimal, method: RecPaymentMethod,
        reference: String?, notes: String?,
        newPaidAmount: java.math.BigDecimal, newStatus: ReceivableStatus
    ): PaymentResponse
}

// Data class internal untuk menyimpan data piutang yang di-lock (FOR UPDATE)
data class ReceivableForUpdateRow(
    val id: UUID,
    val customerId: UUID,
    val transactionId: UUID,
    val amount: java.math.BigDecimal,
    val paidAmount: java.math.BigDecimal,
    val status: ReceivableStatus
)

class ReceivableRepositoryImpl : ReceivableRepository {

    // ==========================================
    // CUSTOMERS
    // ==========================================

    override suspend fun getPaginatedCustomers(page: Int, limit: Int, search: String?): PaginatedResponse<CustomerResponse> = transaction {
        val offset = ((page - 1) * limit).toLong()

        var query = CustomersTable.select { CustomersTable.isActive eq true }

        if (!search.isNullOrBlank()) {
            val searchTerm = "%${search.lowercase()}%"
            query = query.andWhere {
                (CustomersTable.name.lowerCase() like searchTerm) or
                (CustomersTable.phone.lowerCase() like searchTerm)
            }
        }

        val totalCount = query.count()
        val totalPages = kotlin.math.ceil(totalCount.toDouble() / limit).toInt()

        val data = query
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

    override suspend fun getCustomerById(id: UUID): CustomerResponse? = transaction {
        CustomersTable.select { (CustomersTable.id eq id) and (CustomersTable.isActive eq true) }
            .singleOrNull()?.let { rowToCustomerResponse(it) }
    }

    override suspend fun getCustomerByName(name: String): CustomerResponse? = transaction {
        CustomersTable.select {
            (CustomersTable.name.lowerCase() eq name.lowercase()) and (CustomersTable.isActive eq true)
        }.singleOrNull()?.let { rowToCustomerResponse(it) }
    }

    override suspend fun createCustomer(
        name: String, phone: String?, address: String?,
        isContractor: Boolean, creditLimit: java.math.BigDecimal, paymentTermDays: Int
    ): UUID = transaction {
        CustomersTable.insert {
            it[this.name] = name
            it[this.phone] = phone
            it[this.address] = address
            it[this.isContractor] = isContractor
            it[this.creditLimit] = creditLimit
            it[this.paymentTermDays] = paymentTermDays
        } get CustomersTable.id
    }

    override suspend fun updateCustomer(
        id: UUID, name: String, phone: String?, address: String?,
        isContractor: Boolean, creditLimit: java.math.BigDecimal, paymentTermDays: Int
    ): Boolean = transaction {
        val updatedRows = CustomersTable.update({ CustomersTable.id eq id }) {
            it[this.name] = name
            it[this.phone] = phone
            it[this.address] = address
            it[this.isContractor] = isContractor
            it[this.creditLimit] = creditLimit
            it[this.paymentTermDays] = paymentTermDays
            it[this.updatedAt] = Instant.now()
        }
        updatedRows > 0
    }

    override suspend fun softDeleteCustomer(id: UUID): Boolean = transaction {
        val updatedRows = CustomersTable.update({ CustomersTable.id eq id }) {
            it[isActive] = false
            it[updatedAt] = Instant.now()
        }
        updatedRows > 0
    }

    // ==========================================
    // RECEIVABLES
    // ==========================================

    override suspend fun getPaginatedReceivables(
        page: Int, limit: Int, customerId: UUID?, status: ReceivableStatus?
    ): PaginatedResponse<ReceivableResponse> = transaction {
        val offset = ((page - 1) * limit).toLong()

        // JOIN receivables ← customers untuk ambil nama pelanggan
        var query = ReceivablesTable.innerJoin(CustomersTable).selectAll()

        if (customerId != null) {
            query = query.andWhere { ReceivablesTable.customerId eq customerId }
        }
        if (status != null) {
            query = query.andWhere { ReceivablesTable.status eq status }
        }

        val totalCount = query.count()
        val totalPages = kotlin.math.ceil(totalCount.toDouble() / limit).toInt()

        val data = query
            .orderBy(ReceivablesTable.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .map { row ->
                val amount = row[ReceivablesTable.amount]
                val paidAmount = row[ReceivablesTable.paidAmount]
                ReceivableResponse(
                    id = row[ReceivablesTable.id].toString(),
                    customerId = row[ReceivablesTable.customerId].toString(),
                    customerName = row[CustomersTable.name],
                    transactionId = row[ReceivablesTable.transactionId].toString(),
                    amount = amount,
                    paidAmount = paidAmount,
                    remainingAmount = amount.subtract(paidAmount),
                    dueDate = row[ReceivablesTable.dueDate].toString(),
                    status = row[ReceivablesTable.status].dbValue,
                    createdAt = row[ReceivablesTable.createdAt].toString()
                )
            }

        PaginatedResponse(
            data = data,
            total = totalCount,
            page = page,
            limit = limit,
            totalPages = totalPages
        )
    }

    override suspend fun getReceivableById(id: UUID): ReceivableResponse? = transaction {
        ReceivablesTable.innerJoin(CustomersTable)
            .select { ReceivablesTable.id eq id }
            .singleOrNull()?.let { row ->
                val amount = row[ReceivablesTable.amount]
                val paidAmount = row[ReceivablesTable.paidAmount]
                ReceivableResponse(
                    id = row[ReceivablesTable.id].toString(),
                    customerId = row[ReceivablesTable.customerId].toString(),
                    customerName = row[CustomersTable.name],
                    transactionId = row[ReceivablesTable.transactionId].toString(),
                    amount = amount,
                    paidAmount = paidAmount,
                    remainingAmount = amount.subtract(paidAmount),
                    dueDate = row[ReceivablesTable.dueDate].toString(),
                    status = row[ReceivablesTable.status].dbValue,
                    createdAt = row[ReceivablesTable.createdAt].toString()
                )
            }
    }

    override suspend fun getReceivableForUpdate(id: UUID): ReceivableForUpdateRow? = transaction {
        ReceivablesTable.select { ReceivablesTable.id eq id }
            .forUpdate()
            .singleOrNull()?.let { row ->
                ReceivableForUpdateRow(
                    id = row[ReceivablesTable.id],
                    customerId = row[ReceivablesTable.customerId],
                    transactionId = row[ReceivablesTable.transactionId],
                    amount = row[ReceivablesTable.amount],
                    paidAmount = row[ReceivablesTable.paidAmount],
                    status = row[ReceivablesTable.status]
                )
            }
    }

    // ==========================================
    // PAYMENTS
    // ==========================================

    override suspend fun insertPaymentAndUpdateReceivable(
        receivableId: UUID, userId: UUID,
        paymentAmount: java.math.BigDecimal, method: RecPaymentMethod,
        reference: String?, notes: String?,
        newPaidAmount: java.math.BigDecimal, newStatus: ReceivableStatus
    ): PaymentResponse = transaction {
        // 1. INSERT payment
        val paymentId = ReceivablePaymentsTable.insert {
            it[this.receivableId] = receivableId
            it[this.userId] = userId
            it[this.amount] = paymentAmount
            it[this.method] = method
            it[this.reference] = reference
            it[this.notes] = notes
        } get ReceivablePaymentsTable.id

        // 2. UPDATE receivable paid_amount dan status
        ReceivablesTable.update({ ReceivablesTable.id eq receivableId }) {
            it[this.paidAmount] = newPaidAmount
            it[this.status] = newStatus
            it[this.updatedAt] = Instant.now()
        }

        // 3. Baca payment yang baru dibuat untuk response
        val paymentRow = ReceivablePaymentsTable.select { ReceivablePaymentsTable.id eq paymentId }.single()
        val receivableAmount = ReceivablesTable.select { ReceivablesTable.id eq receivableId }.single()
            .let { it[ReceivablesTable.amount] }

        PaymentResponse(
            id = paymentRow[ReceivablePaymentsTable.id].toString(),
            receivableId = receivableId.toString(),
            amount = paymentRow[ReceivablePaymentsTable.amount],
            method = paymentRow[ReceivablePaymentsTable.method].dbValue,
            reference = paymentRow[ReceivablePaymentsTable.reference],
            notes = paymentRow[ReceivablePaymentsTable.notes],
            paidAt = paymentRow[ReceivablePaymentsTable.paidAt].toString(),
            receivableStatus = newStatus.dbValue,
            receivableRemainingAmount = receivableAmount.subtract(newPaidAmount)
        )
    }

    // ==========================================
    // HELPERS
    // ==========================================

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

