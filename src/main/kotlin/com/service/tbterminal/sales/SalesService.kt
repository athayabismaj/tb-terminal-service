package com.service.tbterminal.sales

import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.SessionNotFoundException
import com.service.tbterminal.shared.ValidationException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.util.UUID

class SalesService(private val repository: SalesRepository) {

    // ==========================================
    // CASH SESSIONS (SHIFT KASIR)
    // ==========================================

    suspend fun getActiveSession(userId: UUID): CashSessionResponse? {
        return repository.getActiveSession(userId)
    }

    suspend fun openSession(userId: UUID, request: OpenSessionRequest): CashSessionResponse {
        if (request.startingCash < java.math.BigDecimal.ZERO) {
            throw ValidationException("Modal awal tidak boleh kurang dari nol")
        }

        return repository.openSession(userId, request.startingCash)
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
        if (request.items.isEmpty()) {
            throw ValidationException("Keranjang belanja tidak boleh kosong")
        }

        val paymentMethod = PaymentMethod.entries.firstOrNull { it.dbValue == request.paymentMethod.lowercase() }
            ?: throw ValidationException(
                "Metode pembayaran '${request.paymentMethod}' tidak valid. " +
                "Gunakan: tunai, transfer, qris, hutang, atau dp"
            )

        val customerId: UUID? = request.customerId?.let {
            try { UUID.fromString(it) } catch (e: IllegalArgumentException) {
                throw ValidationException("Format Customer ID tidak valid")
            }
        }

        if (request.amountPaid < java.math.BigDecimal.ZERO) {
            throw ValidationException("Jumlah bayar tidak boleh negatif")
        }
        if (request.dueDays < 0) {
            throw ValidationException("Termin piutang tidak boleh kurang dari nol hari")
        }

        return try {
            repository.executeCheckout(
                userId = userId,
                customerId = customerId,
                requestItems = request.items,
                paymentMethod = paymentMethod,
                amountPaid = request.amountPaid,
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
