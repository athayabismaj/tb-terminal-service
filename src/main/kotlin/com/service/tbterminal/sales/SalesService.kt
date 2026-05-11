package com.service.tbterminal.sales

import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.shared.SessionNotFoundException
import java.util.UUID

class SalesService(private val repository: SalesRepository) {

    // ==========================================
    // CASH SESSIONS (SHIFT KASIR)
    // ==========================================

    suspend fun getActiveSession(userId: UUID): CashSessionResponse? {
        return repository.getActiveSession(userId)
    }

    suspend fun openSession(userId: UUID, request: OpenSessionRequest): CashSessionResponse {
        // Validasi: Satu user hanya boleh punya 1 sesi aktif
        val existingSession = repository.getActiveSession(userId)
        if (existingSession != null) {
            throw ValidationException(
                "Anda masih memiliki sesi kasir yang belum ditutup (ID: ${existingSession.id}). " +
                "Tutup sesi tersebut sebelum membuka yang baru."
            )
        }

        // Validasi: starting cash tidak boleh negatif
        if (request.startingCash < java.math.BigDecimal.ZERO) {
            throw ValidationException("Modal awal tidak boleh kurang dari nol")
        }

        val newSessionId = repository.openSession(userId, request.startingCash)
        return repository.getSessionById(newSessionId)!!
    }

    suspend fun closeSession(userId: UUID, request: CloseSessionRequest): CashSessionResponse {
        // Ambil sesi aktif milik user ini
        val activeSession = repository.getActiveSession(userId)
            ?: throw SessionNotFoundException("Tidak ada sesi kasir aktif yang bisa ditutup")

        // Validasi: ending cash tidak boleh negatif
        if (request.endingCashPhysical < java.math.BigDecimal.ZERO) {
            throw ValidationException("Uang fisik akhir tidak boleh kurang dari nol")
        }

        val sessionId = UUID.fromString(activeSession.id)
        val systemCash = activeSession.systemCash ?: activeSession.openingCash

        // Hitung selisih di level Service (Kotlin), bukan database
        val difference = request.endingCashPhysical.subtract(systemCash)

        val success = repository.closeSession(
            sessionId = sessionId,
            closingCash = request.endingCashPhysical,
            systemCash = systemCash,
            difference = difference,
            notes = request.notes
        )

        if (!success) {
            throw NotFoundException("Gagal menutup sesi kasir")
        }

        return repository.getSessionById(sessionId)!!
    }
}
