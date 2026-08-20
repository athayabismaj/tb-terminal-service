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

    suspend fun getCategories(page: Int, limit: Int, search: String?): PaginatedResponse<CategoryResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 10 else limit.coerceAtMost(50)
        return repository.getCategories(safePage, safeLimit, search)
    }

    suspend fun getCategoryById(id: String): CategoryResponse {
        val uuid = parseInventoryUUID(id)
        return repository.getCategoryById(uuid) ?: throw NotFoundException("Kategori tidak ditemukan")
    }

    suspend fun createCategory(request: CategoryRequest): CategoryResponse {
        val name = request.name.trim()
        if (name.isEmpty()) throw ValidationException("Nama kategori tidak boleh kosong")
        if (name.length > 100) throw ValidationException("Nama kategori maksimal 100 karakter")

        val existing = repository.getCategoryByName(name)
        if (existing != null) throw ValidationException("Kategori dengan nama '$name' sudah ada")

        val newId = repository.createCategory(name)
        return repository.getCategoryById(newId)!!
    }

    suspend fun updateCategory(id: String, request: CategoryRequest): CategoryResponse {
        val uuid = parseInventoryUUID(id)
        val name = request.name.trim()
        if (name.isEmpty()) throw ValidationException("Nama kategori tidak boleh kosong")
        if (name.length > 100) throw ValidationException("Nama kategori maksimal 100 karakter")

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

    suspend fun getUnits(page: Int, limit: Int, search: String?): PaginatedResponse<UnitResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 10 else limit.coerceAtMost(50)
        return repository.getUnits(safePage, safeLimit, search)
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
        if (name.length > 50) throw ValidationException("Nama satuan maksimal 50 karakter")
        if (symbol.length > 20) throw ValidationException("Simbol satuan maksimal 20 karakter")

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
        if (name.length > 50) throw ValidationException("Nama satuan maksimal 50 karakter")
        if (symbol.length > 20) throw ValidationException("Simbol satuan maksimal 20 karakter")

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
        return repository.getProductByIdIncludingInactive(uuid) ?: throw NotFoundException("Produk tidak ditemukan")
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

        requireValidProductValues(
            name, request.priceBuy, request.priceRetail, request.priceContractor,
            request.discount, request.minStock
        )

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
            discount = request.discount,
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

    suspend fun activateProduct(id: String): ProductResponse {
        val uuid = parseInventoryUUID(id)
        val product = repository.getProductByIdIncludingInactive(uuid)
            ?: throw NotFoundException("Produk tidak ditemukan")

        if (!product.isActive) {
            repository.activateProduct(uuid)
        }

        return repository.getProductByIdIncludingInactive(uuid)!!
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

    suspend fun getStockAdjustments(
        page: Int,
        limit: Int,
        search: String?,
        type: String?
    ): PaginatedResponse<StockAdjustmentResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit.coerceAtMost(200)
        return repository.getStockAdjustments(safePage, safeLimit, search, type)
    }

    suspend fun getStockCard(
        page: Int,
        limit: Int,
        productId: String?,
        search: String?,
        type: String?,
        startDate: String?,
        endDate: String?
    ): StockCardResponse {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 200)
        val productUuid = productId?.trim()?.takeIf(String::isNotBlank)?.let(::parseInventoryUUID)
        val movementType = type?.trim()?.takeIf { it.isNotBlank() && !it.equals("ALL", true) }?.let { raw ->
            StockMovementType.entries.firstOrNull { it.name.equals(raw, true) }
                ?: throw ValidationException("Jenis mutasi stok tidak valid")
        }
        val zone = java.time.ZoneId.of("Asia/Jakarta")
        fun parseDate(value: String?, label: String, plusDay: Boolean): java.time.OffsetDateTime? {
            val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
            val date = runCatching { java.time.LocalDate.parse(raw) }.getOrNull()
                ?: throw ValidationException("Format $label harus YYYY-MM-DD")
            return date.plusDays(if (plusDay) 1 else 0).atStartOfDay(zone).toOffsetDateTime()
        }
        val startAt = parseDate(startDate, "startDate", false)
        val endExclusive = parseDate(endDate, "endDate", true)
        if (startAt != null && endExclusive != null && !startAt.isBefore(endExclusive)) {
            throw ValidationException("startDate tidak boleh setelah endDate")
        }
        return repository.getStockCard(safePage, safeLimit, productUuid, search, movementType, startAt, endExclusive)
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
        val success = repository.executeOpname(
            productId = productId,
            newQty = request.actualQty,
            userId = userUuid,
            adjType = adjType,
            notes = request.notes?.trim()?.takeIf(String::isNotBlank)
        )
        if (!success) {
            throw NotFoundException("Gagal melakukan opname pada stok ini")
        }
    }

    suspend fun createOpeningStock(userId: String, request: OpeningStockRequest): OpeningStockResponse {
        val productId = parseInventoryUUID(request.productId)
        val userUuid = parseInventoryUUID(userId)
        repository.getProductById(productId) ?: throw NotFoundException("Produk tidak ditemukan atau tidak aktif")
        if (request.quantity <= java.math.BigDecimal.ZERO) {
            throw ValidationException("Jumlah saldo awal harus lebih dari nol")
        }
        if (request.quantity.scale() > 2 || request.quantity > java.math.BigDecimal("99999999.99")) {
            throw ValidationException("Jumlah saldo awal tidak valid")
        }
        val date = runCatching { java.time.LocalDate.parse(request.date.trim()) }.getOrNull()
            ?: throw ValidationException("Tanggal saldo awal wajib berformat YYYY-MM-DD")
        if (date > inventoryToday()) throw ValidationException("Tanggal saldo awal tidak boleh di masa depan")
        val note = request.note.trim()
        if (note.isBlank()) throw ValidationException("Catatan saldo awal wajib diisi")
        if (note.length > 500) throw ValidationException("Catatan saldo awal maksimal 500 karakter")
        val adjustmentId = repository.createOpeningBalance(productId, request.quantity, date, note, userUuid)
        return OpeningStockResponse(adjustmentId.toString(), productId.toString(), request.quantity, date.toString(), note, userUuid.toString())
    }

    suspend fun previewProductImport(request: ProductCsvImportRequest): ProductCsvPreviewResponse {
        if (request.csv.isBlank()) throw ValidationException("File CSV kosong")
        if (request.csv.length > 2_000_000) throw ValidationException("Ukuran CSV maksimal 2 MB")
        val categories = repository.getAllCategories().associate { it.name.trim().lowercase() to it.id }
        val units = repository.getAllUnits().flatMap { unit ->
            listOf(unit.name.trim().lowercase() to unit.id, unit.symbol.trim().lowercase() to unit.id)
        }.toMap()
        return previewProductCsv(request.csv, categories, units, repository.getAllSkus())
    }

    suspend fun importProducts(userId: String, request: ProductCsvImportRequest): ProductCsvImportResponse {
        val preview = previewProductImport(request)
        if (preview.totalRows == 0) throw ValidationException("CSV tidak memiliki baris produk")
        if (preview.invalidRows > 0) {
            throw ValidationException("Impor dibatalkan: ${preview.invalidRows} baris tidak valid. Jalankan preview kembali")
        }
        val categories = repository.getAllCategories().associate { it.name.trim().lowercase() to java.util.UUID.fromString(it.id) }
        val units = repository.getAllUnits().flatMap { unit ->
            listOf(
                unit.name.trim().lowercase() to java.util.UUID.fromString(unit.id),
                unit.symbol.trim().lowercase() to java.util.UUID.fromString(unit.id)
            )
        }.toMap()
        val rows = preview.rows.map { row ->
            val opening = row.openingStock.ifBlank { "0" }.replace(',', '.').toBigDecimal()
            ResolvedProductImportRow(
                sku = row.sku,
                name = row.name,
                categoryId = requireNotNull(categories[row.category.trim().lowercase()]),
                unitId = requireNotNull(units[row.unit.trim().lowercase()]),
                priceBuy = row.priceBuy.replace(',', '.').toBigDecimal(),
                priceRetail = row.priceRetail.replace(',', '.').toBigDecimal(),
                priceContractor = row.priceContractor.replace(',', '.').toBigDecimal(),
                minStock = row.minStock.replace(',', '.').toBigDecimal(),
                openingStock = opening,
                openingDate = if (opening > java.math.BigDecimal.ZERO) java.time.LocalDate.parse(row.openingDate) else null,
                openingNote = row.openingNote
            )
        }
        return repository.importProducts(rows, parseInventoryUUID(userId))
    }
}
