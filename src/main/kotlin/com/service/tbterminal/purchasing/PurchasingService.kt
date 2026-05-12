package com.service.tbterminal.purchasing

import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.util.UUID

class PurchasingService(private val repository: PurchasingRepository) {

    // ==========================================
    // SUPPLIERS
    // ==========================================

    suspend fun getSuppliers(page: Int, limit: Int, search: String?): PaginatedResponse<SupplierResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit
        return repository.getPaginatedSuppliers(safePage, safeLimit, search)
    }

    suspend fun getSupplierById(id: String): SupplierResponse {
        val uuid = parseUUID(id)
        return repository.getSupplierById(uuid)
            ?: throw NotFoundException("Supplier tidak ditemukan atau sudah dinonaktifkan")
    }

    suspend fun createSupplier(request: SupplierRequest): SupplierResponse {
        val name = request.name.trim()
        validateSupplierInput(name, request.paymentTermDays)

        // Cek duplikasi nama
        val existing = repository.getSupplierByName(name)
        if (existing != null) {
            throw ValidationException("Supplier dengan nama '$name' sudah ada")
        }

        val newId = repository.createSupplier(
            name = name,
            phone = request.phone?.trim(),
            address = request.address?.trim(),
            paymentTermDays = request.paymentTermDays
        )
        return repository.getSupplierById(newId)!!
    }

    suspend fun updateSupplier(id: String, request: SupplierRequest): SupplierResponse {
        val uuid = parseUUID(id)
        val name = request.name.trim()
        validateSupplierInput(name, request.paymentTermDays)

        val current = repository.getSupplierById(uuid)
            ?: throw NotFoundException("Supplier tidak ditemukan atau sudah dinonaktifkan")

        // Cek duplikasi nama hanya jika nama berubah
        if (current.name.lowercase() != name.lowercase()) {
            val existing = repository.getSupplierByName(name)
            if (existing != null) {
                throw ValidationException("Supplier dengan nama '$name' sudah ada")
            }
        }

        repository.updateSupplier(
            id = uuid,
            name = name,
            phone = request.phone?.trim(),
            address = request.address?.trim(),
            paymentTermDays = request.paymentTermDays
        )
        return repository.getSupplierById(uuid)!!
    }

    suspend fun deleteSupplier(id: String) {
        val uuid = parseUUID(id)
        repository.getSupplierById(uuid)
            ?: throw NotFoundException("Supplier tidak ditemukan atau sudah dinonaktifkan")

        try {
            repository.softDeleteSupplier(uuid)
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23503" || e.message?.contains("violates foreign key constraint") == true) {
                throw ValidationException("Supplier tidak dapat dihapus karena masih memiliki data terkait (pembelian atau hutang aktif)")
            }
            throw e
        }
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private fun validateSupplierInput(name: String, paymentTermDays: Int) {
        if (name.isEmpty()) {
            throw ValidationException("Nama supplier tidak boleh kosong")
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
