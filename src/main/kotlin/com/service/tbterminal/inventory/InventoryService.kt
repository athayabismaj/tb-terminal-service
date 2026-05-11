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
    // PRODUCTS
    // ==========================================

    suspend fun getProducts(page: Int, limit: Int, search: String?): PaginatedResponse<ProductResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit
        return repository.getProducts(safePage, safeLimit, search)
    }

    suspend fun getProductById(id: String): ProductResponse {
        val uuid = parseUUID(id)
        return repository.getProductById(uuid) ?: throw NotFoundException("Produk tidak ditemukan atau tidak aktif")
    }

    suspend fun createProduct(request: ProductCreateRequest): ProductResponse {
        val sku = request.sku.trim()
        val name = request.name.trim()

        if (sku.isEmpty()) throw ValidationException("SKU tidak boleh kosong")
        if (name.isEmpty()) throw ValidationException("Nama produk tidak boleh kosong")

        val categoryId = parseUUID(request.categoryId)
        val unitId = parseUUID(request.baseUnitId)

        // Verifikasi eksistensi Kategori dan Satuan
        repository.getCategoryById(categoryId) ?: throw ValidationException("Kategori tidak valid atau tidak ditemukan")
        repository.getUnitById(unitId) ?: throw ValidationException("Satuan tidak valid atau tidak ditemukan")

        // Cek duplikasi SKU (mencakup produk yang tidak aktif)
        val existingSku = repository.getProductBySku(sku, includeInactive = true)

        if (existingSku != null) {
            if (existingSku.isActive) {
                throw ValidationException("Produk dengan SKU '$sku' sudah ada dan aktif")
            } else {
                // Restore & Overwrite jika SKU ada tapi tidak aktif
                val existingUuid = UUID.fromString(existingSku.id)
                repository.restoreProductAndOverwrite(
                    id = existingUuid,
                    categoryId = categoryId,
                    baseUnitId = unitId,
                    name = name,
                    priceBuy = request.priceBuy,
                    priceRetail = request.priceRetail,
                    priceContractor = request.priceContractor,
                    minStock = request.minStock,
                    photoFilename = request.photoFilename
                )
                return repository.getProductById(existingUuid)!!
            }
        }

        // Jika benar-benar baru, buat dan inisialisasi stok
        val newId = repository.createProductAndInitStock(
            categoryId = categoryId,
            baseUnitId = unitId,
            sku = sku,
            name = name,
            priceBuy = request.priceBuy,
            priceRetail = request.priceRetail,
            priceContractor = request.priceContractor,
            minStock = request.minStock,
            photoFilename = request.photoFilename
        )

        return repository.getProductById(newId)!!
    }

    suspend fun updateProduct(id: String, request: ProductUpdateRequest): ProductResponse {
        val uuid = parseUUID(id)
        val name = request.name.trim()

        if (name.isEmpty()) throw ValidationException("Nama produk tidak boleh kosong")

        val categoryId = parseUUID(request.categoryId)
        val unitId = parseUUID(request.baseUnitId)

        // Verifikasi Produk, Kategori dan Satuan
        repository.getProductById(uuid) ?: throw NotFoundException("Produk tidak ditemukan atau tidak aktif")
        repository.getCategoryById(categoryId) ?: throw ValidationException("Kategori tidak valid atau tidak ditemukan")
        repository.getUnitById(unitId) ?: throw ValidationException("Satuan tidak valid atau tidak ditemukan")

        repository.updateProduct(
            id = uuid,
            categoryId = categoryId,
            baseUnitId = unitId,
            name = name,
            priceBuy = request.priceBuy,
            priceRetail = request.priceRetail,
            priceContractor = request.priceContractor,
            minStock = request.minStock,
            photoFilename = request.photoFilename
        )

        return repository.getProductById(uuid)!!
    }

    suspend fun deleteProduct(id: String) {
        val uuid = parseUUID(id)
        // Pastikan produk ada dan aktif
        repository.getProductById(uuid) ?: throw NotFoundException("Produk tidak ditemukan atau sudah dihapus")
        
        // Soft delete
        repository.softDeleteProduct(uuid)
    }

    // ==========================================
    // STOCK MANAGEMENT
    // ==========================================

    suspend fun getStockDetails(page: Int, limit: Int, search: String?): PaginatedResponse<StockDetailResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit
        return repository.getStockDetails(safePage, safeLimit, search)
    }

    suspend fun adjustStock(userId: String, request: StockAdjustmentRequest) {
        val productId = parseUUID(request.productId)
        val userUuid = parseUUID(userId)
        
        // Verifikasi Produk
        repository.getProductById(productId) ?: throw NotFoundException("Produk tidak ditemukan atau tidak aktif")

        // Parse AdjType
        val adjType = try {
            AdjType.valueOf(request.adjType.uppercase())
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Tipe penyesuaian (adjType) tidak valid. Gunakan OPNAME, CORRECTION, atau DAMAGE")
        }

        // Validasi qty actual tidak boleh negatif
        if (request.qtyActual < java.math.BigDecimal.ZERO) {
            throw ValidationException("Quantity aktual tidak boleh kurang dari nol")
        }

        val success = repository.adjustStock(
            productId = productId,
            userId = userUuid,
            adjType = adjType,
            qtyActual = request.qtyActual,
            notes = request.notes
        )

        if (!success) {
            throw NotFoundException("Data stok untuk produk ini tidak ditemukan")
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
