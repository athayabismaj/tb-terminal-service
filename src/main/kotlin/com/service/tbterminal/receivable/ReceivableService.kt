package com.service.tbterminal.receivable

import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.util.UUID

class ReceivableService(private val repository: ReceivableRepository) {

    // ==========================================
    // CUSTOMERS
    // ==========================================

    suspend fun getCustomers(page: Int, limit: Int, search: String?): PaginatedResponse<CustomerResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit
        return repository.getPaginatedCustomers(safePage, safeLimit, search)
    }

    suspend fun getCustomerById(id: String): CustomerResponse {
        val uuid = parseUUID(id)
        return repository.getCustomerById(uuid)
            ?: throw NotFoundException("Pelanggan tidak ditemukan atau sudah dinonaktifkan")
    }

    suspend fun createCustomer(request: CustomerRequest): CustomerResponse {
        val name = request.name.trim()
        validateCustomerInput(name, request.creditLimit, request.paymentTermDays)

        // Cek duplikasi nama
        val existing = repository.getCustomerByName(name)
        if (existing != null) {
            throw ValidationException("Pelanggan dengan nama '$name' sudah ada")
        }

        val newId = repository.createCustomer(
            name = name,
            phone = request.phone?.trim(),
            address = request.address?.trim(),
            isContractor = request.isContractor,
            creditLimit = request.creditLimit,
            paymentTermDays = request.paymentTermDays
        )
        return repository.getCustomerById(newId)!!
    }

    suspend fun updateCustomer(id: String, request: CustomerRequest): CustomerResponse {
        val uuid = parseUUID(id)
        val name = request.name.trim()
        validateCustomerInput(name, request.creditLimit, request.paymentTermDays)

        val current = repository.getCustomerById(uuid)
            ?: throw NotFoundException("Pelanggan tidak ditemukan atau sudah dinonaktifkan")

        // Cek duplikasi nama hanya jika nama berubah
        if (current.name.lowercase() != name.lowercase()) {
            val existing = repository.getCustomerByName(name)
            if (existing != null) {
                throw ValidationException("Pelanggan dengan nama '$name' sudah ada")
            }
        }

        repository.updateCustomer(
            id = uuid,
            name = name,
            phone = request.phone?.trim(),
            address = request.address?.trim(),
            isContractor = request.isContractor,
            creditLimit = request.creditLimit,
            paymentTermDays = request.paymentTermDays
        )
        return repository.getCustomerById(uuid)!!
    }

    suspend fun deleteCustomer(id: String) {
        val uuid = parseUUID(id)
        repository.getCustomerById(uuid)
            ?: throw NotFoundException("Pelanggan tidak ditemukan atau sudah dinonaktifkan")

        try {
            repository.softDeleteCustomer(uuid)
        } catch (e: ExposedSQLException) {
            // Tangkap FK violation jika pelanggan masih punya piutang aktif
            if (e.sqlState == "23503" || e.message?.contains("violates foreign key constraint") == true) {
                throw ValidationException("Pelanggan tidak dapat dihapus karena masih memiliki data terkait (piutang aktif)")
            }
            throw e
        }
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private fun validateCustomerInput(name: String, creditLimit: java.math.BigDecimal, paymentTermDays: Int) {
        if (name.isEmpty()) {
            throw ValidationException("Nama pelanggan tidak boleh kosong")
        }
        if (creditLimit < java.math.BigDecimal.ZERO) {
            throw ValidationException("Limit kredit tidak boleh kurang dari nol")
        }
        if (paymentTermDays < 0) {
            throw ValidationException("Termin bayar tidak boleh kurang dari nol hari")
        }
    }

    private fun parseUUID(id: String): UUID {
        return try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Format ID tidak valid")
        }
    }
}
