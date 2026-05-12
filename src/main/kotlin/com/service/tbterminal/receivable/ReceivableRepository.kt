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
}

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
    // HELPER
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
