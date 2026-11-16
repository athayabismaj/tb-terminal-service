package com.service.tbterminal.sales

import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal
import java.util.UUID

object QuotationValidation {
    private const val MAX_ITEMS = 100
    private const val MAX_NOTE_LENGTH = 300
    private const val MIN_VALID_DAYS = 1
    private const val MAX_VALID_DAYS = 365

    fun parseUuid(raw: String?, fieldName: String): UUID {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty()) throw ValidationException("$fieldName wajib diisi")
        return try {
            UUID.fromString(clean)
        } catch (_: IllegalArgumentException) {
            throw ValidationException("$fieldName tidak valid")
        }
    }

    fun validateCreateRequest(request: CreateQuotationRequest) {
        if (request.items.isEmpty()) {
            throw ValidationException("Minimal harus ada 1 barang untuk membuat penawaran harga")
        }
        if (request.items.size > MAX_ITEMS) {
            throw ValidationException("Maksimal $MAX_ITEMS barang per penawaran harga")
        }
        if (request.validDays !in MIN_VALID_DAYS..MAX_VALID_DAYS) {
            throw ValidationException("Masa berlaku penawaran harus antara $MIN_VALID_DAYS hingga $MAX_VALID_DAYS hari")
        }
        request.customerId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parseUuid(it, "customerId")
        }
        request.items.forEachIndexed { idx, item ->
            parseUuid(item.productId, "productId baris ${idx + 1}")
            if (item.qty <= BigDecimal.ZERO || item.qty.scale() > 2) {
                throw ValidationException("Qty baris ${idx + 1} harus lebih dari nol (maksimal 2 desimal)")
            }
            val noteLen = item.note?.trim()?.length ?: 0
            if (noteLen > MAX_NOTE_LENGTH) {
                throw ValidationException("Catatan item baris ${idx + 1} maksimal $MAX_NOTE_LENGTH karakter")
            }
        }
    }

    fun validateStatusTransition(
        currentStatus: QuotationStatus,
        request: UpdateQuotationStatusRequest,
    ): Pair<QuotationStatus, UUID?> {
        val target = try {
            QuotationStatus.valueOf(request.status.trim().uppercase())
        } catch (_: IllegalArgumentException) {
            throw ValidationException("Status penawaran tidak valid: ${request.status}")
        }

        if (currentStatus == QuotationStatus.CONVERTED && target != QuotationStatus.CONVERTED) {
            throw ValidationException("Penawaran yang sudah menjadi transaksi tidak dapat diubah statusnya")
        }
        if (currentStatus == QuotationStatus.CANCELLED && target == QuotationStatus.CONVERTED) {
            throw ValidationException("Penawaran yang sudah dibatalkan tidak dapat dijadikan transaksi")
        }

        val convertedTxId = if (target == QuotationStatus.CONVERTED) {
            request.convertedTransactionId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                parseUuid(it, "convertedTransactionId")
            }
        } else {
            null
        }

        return target to convertedTxId
    }
}
