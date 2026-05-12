package com.service.tbterminal.purchasing

import com.service.tbterminal.inventory.InventoryRepository
import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.util.UUID

class PurchasingService(
    private val repository: PurchasingRepository,
    private val inventoryRepository: InventoryRepository
) {

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
    // PURCHASE ENGINE
    // ==========================================

    suspend fun purchase(userId: UUID, request: PurchaseRequest): PurchaseResponse {
        // 1. Validasi items tidak boleh kosong
        if (request.items.isEmpty()) {
            throw ValidationException("Daftar item pembelian tidak boleh kosong")
        }

        // 2. Parse supplierId
        val supplierId = parseUUID(request.supplierId)

        // 3. Validasi supplier ada
        repository.getSupplierById(supplierId)
            ?: throw NotFoundException("Supplier tidak ditemukan atau sudah dinonaktifkan")

        // 4. Parse paymentMethod
        val paymentMethod = PurchasePaymentMethod.entries.firstOrNull {
            it.dbValue == request.paymentMethod.lowercase()
        } ?: throw ValidationException(
            "Metode pembayaran '${request.paymentMethod}' tidak valid. " +
            "Gunakan: tunai, transfer, qris, hutang, atau dp"
        )

        // 5. Resolve setiap item: ambil data produk dari DB untuk snapshot HPP
        val resolvedItems = mutableListOf<ResolvedPurchaseItem>()
        var calculatedTotal = java.math.BigDecimal.ZERO

        for (item in request.items) {
            val productId = parseUUID(item.productId)

            // Validasi qty & price > 0
            if (item.qty <= java.math.BigDecimal.ZERO) {
                throw ValidationException("Kuantitas produk harus lebih dari nol")
            }
            if (item.price <= java.math.BigDecimal.ZERO) {
                throw ValidationException("Harga beli produk harus lebih dari nol")
            }

            // Ambil data produk dari DB
            val product = inventoryRepository.getProductById(productId)
                ?: throw NotFoundException("Produk dengan ID '${item.productId}' tidak ditemukan")

            val subtotal = item.qty.multiply(item.price)
            calculatedTotal = calculatedTotal.add(subtotal)

            resolvedItems.add(
                ResolvedPurchaseItem(
                    productId = productId,
                    unitId = UUID.fromString(product.baseUnitId),
                    productName = product.name,
                    qty = item.qty,
                    priceAtTransaction = item.price,        // Harga beli baru dari nota
                    cogsAtTransaction = product.priceBuy,   // Snapshot HPP lama sebelum update
                    subtotal = subtotal
                )
            )
        }

        // 6. Validasi amountPaid
        if (request.amountPaid < java.math.BigDecimal.ZERO) {
            throw ValidationException("Jumlah pembayaran tidak boleh negatif")
        }

        // 7. Eksekusi purchase atomik di repository
        return repository.executePurchase(
            userId = userId,
            supplierId = supplierId,
            invoiceNo = request.invoiceNo?.trim(),
            resolvedItems = resolvedItems,
            calculatedTotal = calculatedTotal,
            paymentMethod = paymentMethod,
            amountPaid = request.amountPaid,
            notes = request.notes?.trim(),
            dueDays = request.dueDays
        )
    }

    suspend fun getPurchases(page: Int, limit: Int, supplierId: String?): PaginatedResponse<PurchaseSummary> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit
        val supplierUuid = supplierId?.let { parseUUID(it) }
        return repository.getPaginatedPurchases(safePage, safeLimit, supplierUuid)
    }

    suspend fun getPurchaseById(id: String): PurchaseResponse {
        val uuid = parseUUID(id)
        return repository.getPurchaseById(uuid)
            ?: throw NotFoundException("Data pembelian tidak ditemukan")
    }

    // ==========================================
    // PAYABLES
    // ==========================================

    suspend fun getPayables(
        page: Int, limit: Int, supplierId: String?, status: String?
    ): PaginatedResponse<PayableResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit

        val supplierUuid = supplierId?.let { parseUUID(it) }
        val payableStatus = status?.let {
            PayableStatus.entries.firstOrNull { e -> e.dbValue == it.lowercase() }
                ?: throw ValidationException(
                    "Status '$it' tidak valid. Gunakan: belum_lunas, sebagian, atau lunas"
                )
        }

        return repository.getPaginatedPayables(safePage, safeLimit, supplierUuid, payableStatus)
    }

    suspend fun getPayableById(id: String): PayableResponse {
        val uuid = parseUUID(id)
        return repository.getPayableById(uuid)
            ?: throw NotFoundException("Data hutang tidak ditemukan")
    }

    // ==========================================
    // SUPPLIER PAYMENT ENGINE
    // ==========================================

    suspend fun paySupplier(userId: UUID, request: SupplierPaymentRequest): SupplierPaymentResponse {
        // 1. Validasi amount > 0
        if (request.amount <= java.math.BigDecimal.ZERO) {
            throw ValidationException("Jumlah pembayaran harus lebih dari nol")
        }

        // 2. Parse payableId
        val payableId = parseUUID(request.payableId)

        // 3. Parse method (hanya tunai/transfer/qris untuk pembayaran hutang)
        val method = PurchasePaymentMethod.entries.firstOrNull {
            it.dbValue == request.method.lowercase()
        } ?: throw ValidationException(
            "Metode pembayaran '${request.method}' tidak valid. " +
            "Gunakan: tunai, transfer, atau qris"
        )

        // 4-10. Eksekusi dalam satu transaksi atomik dengan FOR UPDATE lock
        return org.jetbrains.exposed.sql.transactions.transaction {
            // 4. Lock row hutang (FOR UPDATE) — mencegah race condition
            val payable = kotlinx.coroutines.runBlocking { repository.getPayableForUpdate(payableId) }
                ?: throw NotFoundException("Hutang dengan ID '${request.payableId}' tidak ditemukan")

            // 5. LUNAS Guard — Cek apakah sudah lunas
            if (payable.status == PayableStatus.LUNAS) {
                throw ValidationException("Hutang ini sudah lunas, tidak dapat menerima pembayaran lagi")
            }

            // 6. Hitung sisa hutang
            val remainingAmount = payable.amount.subtract(payable.paidAmount)

            // 7. Overpayment Guard
            if (request.amount > remainingAmount) {
                throw ValidationException(
                    "Pembayaran melebihi sisa hutang. " +
                    "Sisa hutang: ${remainingAmount.toPlainString()}, " +
                    "jumlah bayar: ${request.amount.toPlainString()}"
                )
            }

            // 8. Hitung newPaidAmount
            val newPaidAmount = payable.paidAmount.add(request.amount)

            // 9. Tentukan status baru
            val newStatus = if (newPaidAmount >= payable.amount) {
                PayableStatus.LUNAS
            } else {
                PayableStatus.SEBAGIAN
            }

            // 10. Insert payment + update payable (atomik)
            kotlinx.coroutines.runBlocking {
                repository.insertPaymentAndUpdatePayable(
                    payableId = payableId,
                    userId = userId,
                    paymentAmount = request.amount,
                    method = method,
                    reference = request.reference?.trim(),
                    notes = request.notes?.trim(),
                    newPaidAmount = newPaidAmount,
                    newStatus = newStatus
                )
            }
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

