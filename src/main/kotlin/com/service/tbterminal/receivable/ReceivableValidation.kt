package com.service.tbterminal.receivable

import com.service.tbterminal.shared.CreditLimitExceededException
import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

internal data class ValidatedStandaloneReceivable(
    val amount: BigDecimal,
    val debtDate: LocalDate,
    val dueDate: LocalDate,
    val legacyInvoiceNumber: String?,
    val source: ReceivableSource,
    val notes: String?
)

internal data class ValidatedReceivablePayment(
    val receivableId: java.util.UUID,
    val amount: BigDecimal,
    val method: RecPaymentMethod,
    val reference: String?,
    val notes: String?,
    val idempotencyKey: String
)

internal data class ValidatedPaymentReversal(
    val idempotencyKey: String,
    val reason: String
)

internal fun validateStandaloneReceivableRequest(
    request: CreateStandaloneReceivableRequest,
    today: LocalDate = receivableToday()
): ValidatedStandaloneReceivable {
    if (request.amount <= BigDecimal.ZERO) throw ValidationException("Nominal piutang harus lebih dari nol")
    if (request.amount.scale() > 2) throw ValidationException("Nominal piutang maksimal dua angka desimal")
    val debtDate = parseReceivableDate(request.debtDate, "tanggal piutang")
    val dueDate = parseReceivableDate(request.dueDate, "jatuh tempo")
    if (debtDate > today) throw ValidationException("Tanggal piutang tidak boleh di masa depan")
    if (dueDate < debtDate) throw ValidationException("Jatuh tempo tidak boleh sebelum tanggal piutang")
    val source = ReceivableSource.entries.firstOrNull { it.name.equals(request.source, ignoreCase = true) }
        ?: throw ValidationException("Sumber piutang tidak valid")
    if (source == ReceivableSource.SALE) {
        throw ValidationException("Sumber SALE hanya dapat dibuat melalui transaksi penjualan")
    }
    val legacyInvoice = request.legacyInvoiceNumber?.trim()?.takeIf(String::isNotBlank)
    if (legacyInvoice != null && legacyInvoice.length > 100) {
        throw ValidationException("Nomor nota lama maksimal 100 karakter")
    }
    val notes = request.notes?.trim()?.takeIf(String::isNotBlank)
    if (notes != null && notes.length > 1000) throw ValidationException("Catatan maksimal 1000 karakter")
    if (source == ReceivableSource.ADJUSTMENT) {
        if (legacyInvoice == null) throw ValidationException("Referensi adjustment wajib diisi")
        if (notes == null) throw ValidationException("Alasan adjustment wajib diisi")
    }
    return ValidatedStandaloneReceivable(
        request.amount, debtDate, dueDate, legacyInvoice, source, notes
    )
}

internal fun ensureReceivableCreditLimit(
    currentOutstanding: BigDecimal,
    addedAmount: BigDecimal,
    creditLimit: BigDecimal
) {
    val projected = currentOutstanding.add(addedAmount)
    if (creditLimit > BigDecimal.ZERO && projected > creditLimit) {
        throw CreditLimitExceededException(
            "Limit kredit pelanggan terlampaui. Outstanding setelah pencatatan: ${projected.toPlainString()}"
        )
    }
}

internal fun deriveReceivableStatus(amount: BigDecimal, paidAmount: BigDecimal): ReceivableStatus = when {
    paidAmount <= BigDecimal.ZERO -> ReceivableStatus.UNPAID
    paidAmount < amount -> ReceivableStatus.PARTIAL
    else -> ReceivableStatus.PAID
}

internal fun validateReceivablePaymentRequest(request: PaymentRequest): ValidatedReceivablePayment {
    if (request.amount <= BigDecimal.ZERO) throw ValidationException("Jumlah pembayaran harus lebih dari nol")
    if (request.amount.scale() > 2) throw ValidationException("Jumlah pembayaran maksimal dua angka desimal")
    val method = RecPaymentMethod.entries.firstOrNull {
        it.dbValue.equals(request.method.trim(), ignoreCase = true)
    } ?: throw ValidationException("Metode pembayaran harus tunai, transfer, atau qris")
    if (method !in setOf(RecPaymentMethod.TUNAI, RecPaymentMethod.TRANSFER, RecPaymentMethod.QRIS)) {
        throw ValidationException("Metode pembayaran harus tunai, transfer, atau qris")
    }
    val reference = request.reference?.trim()?.takeIf(String::isNotBlank)
    if (reference != null && reference.length > 100) throw ValidationException("Referensi maksimal 100 karakter")
    val notes = request.notes?.trim()?.takeIf(String::isNotBlank)
    if (notes != null && notes.length > 1000) throw ValidationException("Catatan maksimal 1000 karakter")
    return ValidatedReceivablePayment(
        receivableId = parseReceivableUuid(request.receivableId),
        amount = request.amount,
        method = method,
        reference = reference,
        notes = notes,
        idempotencyKey = validatePaymentIdempotencyKey(request.idempotencyKey)
    )
}

internal fun validatePaymentReversalRequest(request: ReversePaymentRequest): ValidatedPaymentReversal {
    val reason = request.reason.trim()
    if (reason.length !in 5..1000) {
        throw ValidationException("Alasan reversal wajib diisi 5 sampai 1000 karakter")
    }
    return ValidatedPaymentReversal(
        idempotencyKey = validatePaymentIdempotencyKey(request.idempotencyKey),
        reason = reason
    )
}

internal fun validatePaymentIdempotencyKey(value: String): String {
    val normalized = value.trim()
    if (normalized.length !in 8..100 || !normalized.matches(Regex("[A-Za-z0-9._:-]+"))) {
        throw ValidationException("Idempotency key harus 8-100 karakter dan hanya berisi huruf, angka, titik, garis, titik dua, atau underscore")
    }
    return normalized
}

private fun parseReceivableUuid(value: String): java.util.UUID = try {
    java.util.UUID.fromString(value)
} catch (_: IllegalArgumentException) {
    throw ValidationException("Format ID piutang tidak valid")
}

private fun parseReceivableDate(value: String, field: String): LocalDate = try {
    LocalDate.parse(value)
} catch (_: DateTimeParseException) {
    throw ValidationException("Format $field harus yyyy-MM-dd")
}

internal fun receivableToday(): LocalDate = LocalDate.now(ZoneId.of("Asia/Jakarta"))
