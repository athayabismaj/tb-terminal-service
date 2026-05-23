package com.service.tbterminal.inventory

import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException

class InventoryService(private val repository: InventoryRepository) {

    // ==========================================
    // CATEGORIES
    // ==========================================

    suspend fun getAllCategories(): List<CategoryResponse> {
        return repository.getAllCategories()
    }

    suspend fun getCategoryById(id: String): CategoryResponse {
        val uuid = parseInventoryUUID(id)
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
        val uuid = parseInventoryUUID(id)
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
        val uuid = parseInventoryUUID(id)
        val current = repository.getCategoryById(uuid) ?: throw NotFoundException("Kategori tidak ditemukan")
        
        try {
            repository.deleteCategory(uuid)
        } catch (e: Exception) {
            throwIfDeleteConstraintViolation(e)
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
        val uuid = parseInventoryUUID(id)
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
        val uuid = parseInventoryUUID(id)
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
        val uuid = parseInventoryUUID(id)
        val current = repository.getUnitById(uuid) ?: throw NotFoundException("Satuan tidak ditemukan")
        
        try {
            repository.deleteUnit(uuid)
        } catch (e: Exception) {
            throwIfDeleteConstraintViolation(e)
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
        val uuid = parseInventoryUUID(id)
        return repository.getProductById(uuid) ?: throw NotFoundException("Produk tidak ditemukan atau tidak aktif")
    }

    suspend fun createProduct(request: ProductCreateRequest): ProductResponse {
        val draft = validateProductDraft(repository, request)
        val existingSku = repository.getProductBySku(draft.sku, includeInactive = true)

        if (existingSku?.isActive == true) {
            throw ValidationException("Produk dengan SKU '${draft.sku}' sudah ada dan aktif")
        }

        if (existingSku != null) {
            return restoreInactiveProduct(repository, existingSku.id, draft, request)
        }

        return createNewProduct(repository, draft, request)
    }

    suspend fun updateProduct(id: String, request: ProductUpdateRequest): ProductResponse {
        val uuid = parseInventoryUUID(id)
        val name = request.name.trim()

        if (name.isEmpty()) throw ValidationException("Nama produk tidak boleh kosong")

        val categoryId = parseInventoryUUID(request.categoryId)
        val unitId = parseInventoryUUID(request.baseUnitId)

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
        val uuid = parseInventoryUUID(id)
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
        val offset = (safePage - 1) * safeLimit
        return repository.getPaginatedStockDetail(safeLimit, offset, search)
    }

    suspend fun executeOpname(userId: String, request: StockOpnameRequest) {
        val productId = parseInventoryUUID(request.productId)
        val userUuid = parseInventoryUUID(userId)
        
        // Verifikasi Produk
        repository.getProductById(productId) ?: throw NotFoundException("Produk tidak ditemukan atau tidak aktif")

        // Parse AdjType
        val adjType = try {
            AdjType.valueOf(request.adjustmentType.uppercase())
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Tipe penyesuaian (adjustmentType) tidak valid. Gunakan OPNAME, CORRECTION, atau DAMAGE")
        }

        // Validasi qty actual tidak boleh negatif
        if (request.actualQty < java.math.BigDecimal.ZERO) {
            throw ValidationException("Quantity aktual tidak boleh kurang dari nol")
        }

        // Jalankan seluruh proses validasi dan operasi database di dalam satu transaksi
        org.jetbrains.exposed.sql.transactions.transaction {
            // Lock dan baca stok saat ini
            val currentSystemQty = kotlinx.coroutines.runBlocking { repository.getCurrentStockForUpdate(productId) }
                ?: throw NotFoundException("Data stok untuk produk ini tidak ditemukan")

            // Validasi jika tidak ada perubahan
            if (currentSystemQty.compareTo(request.actualQty) == 0) {
                throw ValidationException("Stok fisik sama dengan sistem, tidak ada penyesuaian.")
            }

            // Eksekusi pembaruan dan pencatatan audit trail
            val success = kotlinx.coroutines.runBlocking {
                repository.executeOpname(
                    productId = productId,
                    oldQty = currentSystemQty,
                    newQty = request.actualQty,
                    userId = userUuid,
                    adjType = adjType,
                    notes = request.notes
                )
            }

            if (!success) {
                throw NotFoundException("Gagal melakukan opname pada stok ini")
            }
        }
    }
}
