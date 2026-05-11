package com.service.tbterminal.inventory

import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.util.UUID

class InventoryService(private val repository: InventoryRepository) {

    // ==========================================
    // CATEGORIES
    // ==========================================

    suspend fun getAllCategories(): List<CategoryResponse> {
        return repository.getAllCategories()
    }

    suspend fun getCategoryById(id: String): CategoryResponse {
        val uuid = parseUUID(id)
        return repository.getCategoryById(uuid) ?: throw NotFoundException("Kategori tidak ditemukan")
    }

    suspend fun createCategory(request: CategoryRequest): CategoryResponse {
        val name = request.name.trim()
        if (name.isEmpty()) throw ValidationException("Nama kategori tidak boleh kosong")

        val existing = repository.getCategoryByName(name)
        if (existing != null) throw ValidationException("Kategori dengan nama '$name' sudah ada")

        val newId = repository.createCategory(name)
        return repository.getCategoryById(newId)!!
    }

    suspend fun updateCategory(id: String, request: CategoryRequest): CategoryResponse {
        val uuid = parseUUID(id)
        val name = request.name.trim()
        if (name.isEmpty()) throw ValidationException("Nama kategori tidak boleh kosong")

        val current = repository.getCategoryById(uuid) ?: throw NotFoundException("Kategori tidak ditemukan")
        
        // Cek duplikasi jika nama berubah
        if (current.name.lowercase() != name.lowercase()) {
            val existing = repository.getCategoryByName(name)
            if (existing != null) throw ValidationException("Kategori dengan nama '$name' sudah ada")
        }

        repository.updateCategory(uuid, name)
        return repository.getCategoryById(uuid)!!
    }

    suspend fun deleteCategory(id: String) {
        val uuid = parseUUID(id)
        val current = repository.getCategoryById(uuid) ?: throw NotFoundException("Kategori tidak ditemukan")
        
        try {
            repository.deleteCategory(uuid)
        } catch (e: Exception) {
            handleDeleteConstraintViolation(e)
            throw e // rethrow jika bukan violation constraint
        }
    }

    // ==========================================
    // UNITS
    // ==========================================

    suspend fun getAllUnits(): List<UnitResponse> {
        return repository.getAllUnits()
    }

    suspend fun getUnitById(id: String): UnitResponse {
        val uuid = parseUUID(id)
        return repository.getUnitById(uuid) ?: throw NotFoundException("Satuan tidak ditemukan")
    }

    suspend fun createUnit(request: UnitRequest): UnitResponse {
        val name = request.name.trim()
        val symbol = request.symbol.trim()
        
        if (name.isEmpty()) throw ValidationException("Nama satuan tidak boleh kosong")
        if (symbol.isEmpty()) throw ValidationException("Simbol satuan tidak boleh kosong")

        val existing = repository.getUnitByNameOrSymbol(name, symbol)
        if (existing != null) {
            if (existing.name.equals(name, ignoreCase = true)) throw ValidationException("Satuan dengan nama '$name' sudah ada")
            if (existing.symbol.equals(symbol, ignoreCase = true)) throw ValidationException("Satuan dengan simbol '$symbol' sudah ada")
        }

        val newId = repository.createUnit(name, symbol)
        return repository.getUnitById(newId)!!
    }

    suspend fun updateUnit(id: String, request: UnitRequest): UnitResponse {
        val uuid = parseUUID(id)
        val name = request.name.trim()
        val symbol = request.symbol.trim()
        
        if (name.isEmpty()) throw ValidationException("Nama satuan tidak boleh kosong")
        if (symbol.isEmpty()) throw ValidationException("Simbol satuan tidak boleh kosong")

        val current = repository.getUnitById(uuid) ?: throw NotFoundException("Satuan tidak ditemukan")
        
        // Cek duplikasi jika nama/simbol berubah
        if (current.name.lowercase() != name.lowercase() || current.symbol.lowercase() != symbol.lowercase()) {
            val existing = repository.getUnitByNameOrSymbol(name, symbol)
            if (existing != null && existing.id != id) {
                if (existing.name.equals(name, ignoreCase = true)) throw ValidationException("Satuan dengan nama '$name' sudah ada")
                if (existing.symbol.equals(symbol, ignoreCase = true)) throw ValidationException("Satuan dengan simbol '$symbol' sudah ada")
            }
        }

        repository.updateUnit(uuid, name, symbol)
        return repository.getUnitById(uuid)!!
    }

    suspend fun deleteUnit(id: String) {
        val uuid = parseUUID(id)
        val current = repository.getUnitById(uuid) ?: throw NotFoundException("Satuan tidak ditemukan")
        
        try {
            repository.deleteUnit(uuid)
        } catch (e: Exception) {
            handleDeleteConstraintViolation(e)
            throw e
        }
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private fun parseUUID(id: String): UUID {
        return try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Format ID tidak valid")
        }
    }

    private fun handleDeleteConstraintViolation(e: Exception) {
        val message = e.message ?: ""
        // Check for PostgreSQL foreign key violation (23503)
        if (e is ExposedSQLException && e.sqlState == "23503" || message.contains("foreign key constraint") || message.contains("violates foreign key constraint")) {
            throw ValidationException("Data tidak dapat dihapus karena sedang digunakan")
        }
    }
}
