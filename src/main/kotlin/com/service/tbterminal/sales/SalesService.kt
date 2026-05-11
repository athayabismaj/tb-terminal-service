package com.service.tbterminal.sales

import com.service.tbterminal.inventory.InventoryRepository
import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.SessionNotFoundException
import com.service.tbterminal.shared.ValidationException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.util.UUID

class SalesService(
    private val repository: SalesRepository,
    private val inventoryRepository: InventoryRepository
) {

    // ==========================================
    // CASH SESSIONS (SHIFT KASIR)
    // ==========================================

    suspend fun getActiveSession(userId: UUID): CashSessionResponse? {
        return repository.getActiveSession(userId)
    }

    suspend fun openSession(userId: UUID, request: OpenSessionRequest): CashSessionResponse {
        val existingSession = repository.getActiveSession(userId)
        if (existingSession != null) {
            throw ValidationException(
                "Anda masih memiliki sesi kasir yang belum ditutup (ID: ${existingSession.id}). " +
                "Tutup sesi tersebut sebelum membuka yang baru."
            )
        }
        if (request.startingCash < java.math.BigDecimal.ZERO) {
            throw ValidationException("Modal awal tidak boleh kurang dari nol")
        }

        val newSessionId = repository.openSession(userId, request.startingCash)
        return repository.getSessionById(newSessionId)!!
    }

    suspend fun closeSession(userId: UUID, request: CloseSessionRequest): CashSessionResponse {
        val activeSession = repository.getActiveSession(userId)
            ?: throw SessionNotFoundException("Tidak ada sesi kasir aktif yang bisa ditutup")

        if (request.endingCashPhysical < java.math.BigDecimal.ZERO) {
            throw ValidationException("Uang fisik akhir tidak boleh kurang dari nol")
        }

        val sessionId = UUID.fromString(activeSession.id)
        val systemCash = activeSession.systemCash ?: activeSession.openingCash
        val difference = request.endingCashPhysical.subtract(systemCash)

        val success = repository.closeSession(
            sessionId = sessionId,
            closingCash = request.endingCashPhysical,
            systemCash = systemCash,
            difference = difference,
            notes = request.notes
        )

        if (!success) throw NotFoundException("Gagal menutup sesi kasir")
        return repository.getSessionById(sessionId)!!
    }

    // ==========================================
    // POS — CHECKOUT ENGINE
    // ==========================================

    suspend fun checkout(userId: UUID, request: CheckoutRequest): TransactionResponse {
        // 1. Validasi sesi kasir aktif
        val activeSession = repository.getActiveSession(userId)
            ?: throw SessionNotFoundException("Buka sesi kasir terlebih dahulu sebelum bertransaksi")

        val sessionId = UUID.fromString(activeSession.id)

        // 2. Validasi items tidak boleh kosong
        if (request.items.isEmpty()) {
            throw ValidationException("Keranjang belanja tidak boleh kosong")
        }

        // 3. Parse payment method — cocokkan dengan DB enum value
        val paymentMethod = PaymentMethod.entries.firstOrNull { it.dbValue == request.paymentMethod.lowercase() }
            ?: throw ValidationException(
                "Metode pembayaran '${request.paymentMethod}' tidak valid. " +
                "Gunakan: tunai, transfer, qris, hutang, atau dp"
            )

        // 4. Parse customerId jika ada
        val customerId: UUID? = request.customerId?.let {
            try { UUID.fromString(it) } catch (e: IllegalArgumentException) {
                throw ValidationException("Format Customer ID tidak valid")
            }
        }

        // 5. NEVER TRUST CLIENT: Resolve harga dari database
        val resolvedItems = mutableListOf<ResolvedItem>()
        var calculatedTotal = java.math.BigDecimal.ZERO

        for (item in request.items) {
            val productId = try { UUID.fromString(item.productId) } catch (e: IllegalArgumentException) {
                throw ValidationException("Format Product ID tidak valid: ${item.productId}")
            }
            if (item.qty <= java.math.BigDecimal.ZERO) {
                throw ValidationException("Quantity untuk produk ${item.productId} harus lebih dari 0")
            }
            if (item.discount < java.math.BigDecimal.ZERO) {
                throw ValidationException("Diskon tidak boleh negatif")
            }

            // Query ke inventory untuk ambil harga & unit asli
            val product = inventoryRepository.getProductById(productId)
                ?: throw NotFoundException("Produk dengan ID ${item.productId} tidak ditemukan atau tidak aktif")

            val unitId = UUID.fromString(product.baseUnitId)
            val subtotal = (product.priceRetail.subtract(item.discount)).multiply(item.qty)
            calculatedTotal = calculatedTotal.add(subtotal)

            resolvedItems.add(
                ResolvedItem(
                    productId = productId,
                    unitId = unitId,
                    productName = product.name,
                    qty = item.qty,
                    priceAtTransaction = product.priceRetail,    // Snapshot harga riil
                    cogsAtTransaction = product.priceBuy,        // Snapshot HPP riil
                    discount = item.discount,
                    subtotal = subtotal
                )
            )
        }

        // 6. Validasi amountPaid vs total
        if (request.amountPaid < java.math.BigDecimal.ZERO) {
            throw ValidationException("Jumlah bayar tidak boleh negatif")
        }

        // 7. Tentukan trx_status berdasarkan metode & jumlah bayar
        val trxStatus = when {
            paymentMethod == PaymentMethod.HUTANG -> TrxStatus.HUTANG
            paymentMethod == PaymentMethod.DP -> TrxStatus.DP
            request.amountPaid < calculatedTotal -> TrxStatus.HUTANG
            else -> TrxStatus.LUNAS
        }

        // 8. Jika hutang tapi tidak ada customer ID — tolak
        if ((trxStatus == TrxStatus.HUTANG || trxStatus == TrxStatus.DP) && customerId == null) {
            throw ValidationException("Transaksi hutang/DP memerlukan Customer ID yang valid")
        }

        // 9. Eksekusi atomik di repository (tangkap trigger error stok)
        return try {
            repository.executeCheckout(
                sessionId = sessionId,
                userId = userId,
                customerId = customerId,
                resolvedItems = resolvedItems,
                totalAmount = calculatedTotal,
                paymentMethod = paymentMethod,
                amountPaid = request.amountPaid,
                trxStatus = trxStatus,
                notes = request.notes,
                dueDays = request.dueDays
            )
        } catch (e: ExposedSQLException) {
            // Tangkap trigger fn_sync_stock yang melempar error stok tidak cukup
            val msg = e.message ?: ""
            if (msg.contains("Insufficient stock") || msg.contains("Stock record not found")) {
                throw ValidationException("Stok tidak mencukupi untuk satu atau lebih item dalam keranjang")
            }
            throw e // Re-throw jika bukan error stok
        }
    }

    suspend fun getTransactions(page: Int, limit: Int, sessionId: String?): PaginatedResponse<TransactionSummary> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit
        val sessionUuid = sessionId?.let {
            try { UUID.fromString(it) } catch (e: IllegalArgumentException) {
                throw ValidationException("Format Session ID tidak valid")
            }
        }
        return repository.getPaginatedTransactions(safePage, safeLimit, sessionUuid)
    }
}

