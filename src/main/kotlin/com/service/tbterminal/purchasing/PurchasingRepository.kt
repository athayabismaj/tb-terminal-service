package com.service.tbterminal.purchasing

import com.service.tbterminal.inventory.PaginatedResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

interface PurchasingRepository {
    // Suppliers
    suspend fun getPaginatedSuppliers(page: Int, limit: Int, search: String?): PaginatedResponse<SupplierResponse>
    suspend fun getSupplierById(id: UUID): SupplierResponse?
    suspend fun getSupplierByName(name: String): SupplierResponse?
    suspend fun createSupplier(name: String, phone: String?, address: String?, paymentTermDays: Int): UUID
    suspend fun updateSupplier(id: UUID, name: String, phone: String?, address: String?, paymentTermDays: Int): Boolean
    suspend fun softDeleteSupplier(id: UUID): Boolean
}

class PurchasingRepositoryImpl : PurchasingRepository {

    // ==========================================
    // SUPPLIERS
    // ==========================================

    override suspend fun getPaginatedSuppliers(page: Int, limit: Int, search: String?): PaginatedResponse<SupplierResponse> = transaction {
        val offset = ((page - 1) * limit).toLong()

        var query = SuppliersTable.select { SuppliersTable.isActive eq true }

        if (!search.isNullOrBlank()) {
            val searchTerm = "%${search.lowercase()}%"
            query = query.andWhere {
                (SuppliersTable.name.lowerCase() like searchTerm) or
                (SuppliersTable.phone.lowerCase() like searchTerm)
            }
        }

        val totalCount = query.count()
        val totalPages = kotlin.math.ceil(totalCount.toDouble() / limit).toInt()

        val data = query
            .orderBy(SuppliersTable.name, SortOrder.ASC)
            .limit(limit, offset)
            .map { rowToSupplierResponse(it) }

        PaginatedResponse(
            data = data,
            total = totalCount,
            page = page,
            limit = limit,
            totalPages = totalPages
        )
    }

    override suspend fun getSupplierById(id: UUID): SupplierResponse? = transaction {
        SuppliersTable.select { (SuppliersTable.id eq id) and (SuppliersTable.isActive eq true) }
            .singleOrNull()?.let { rowToSupplierResponse(it) }
    }

    override suspend fun getSupplierByName(name: String): SupplierResponse? = transaction {
        SuppliersTable.select {
            (SuppliersTable.name.lowerCase() eq name.lowercase()) and (SuppliersTable.isActive eq true)
        }.singleOrNull()?.let { rowToSupplierResponse(it) }
    }

    override suspend fun createSupplier(
        name: String, phone: String?, address: String?, paymentTermDays: Int
    ): UUID = transaction {
        SuppliersTable.insert {
            it[this.name] = name
            it[this.phone] = phone
            it[this.address] = address
            it[this.paymentTermDays] = paymentTermDays
        } get SuppliersTable.id
    }

    override suspend fun updateSupplier(
        id: UUID, name: String, phone: String?, address: String?, paymentTermDays: Int
    ): Boolean = transaction {
        val updatedRows = SuppliersTable.update({ SuppliersTable.id eq id }) {
            it[this.name] = name
            it[this.phone] = phone
            it[this.address] = address
            it[this.paymentTermDays] = paymentTermDays
            it[this.updatedAt] = Instant.now()
        }
        updatedRows > 0
    }

    override suspend fun softDeleteSupplier(id: UUID): Boolean = transaction {
        val updatedRows = SuppliersTable.update({ SuppliersTable.id eq id }) {
            it[isActive] = false
            it[updatedAt] = Instant.now()
        }
        updatedRows > 0
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private fun rowToSupplierResponse(row: ResultRow): SupplierResponse {
        return SupplierResponse(
            id = row[SuppliersTable.id].toString(),
            name = row[SuppliersTable.name],
            phone = row[SuppliersTable.phone],
            address = row[SuppliersTable.address],
            paymentTermDays = row[SuppliersTable.paymentTermDays],
            isActive = row[SuppliersTable.isActive],
            createdAt = row[SuppliersTable.createdAt].toString(),
            updatedAt = row[SuppliersTable.updatedAt].toString()
        )
    }
}
