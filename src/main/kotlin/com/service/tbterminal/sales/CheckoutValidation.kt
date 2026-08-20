package com.service.tbterminal.sales

import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID

private val IDEMPOTENCY_KEY_PATTERN = Regex("^[A-Za-z0-9._:-]{8,100}$")

internal data class CheckoutPaymentAmounts(
    val status: TrxStatus,
    val paidAmount: BigDecimal,
    val amountTendered: BigDecimal,
    val changeAmount: BigDecimal,
    val receivableAmount: BigDecimal
)

internal fun validateCheckoutRequest(request: CheckoutRequest): String {
    if (request.items.isEmpty()) throw ValidationException("Keranjang belanja tidak boleh kosong")
    if (request.items.size > 100) throw ValidationException("Maksimal 100 item berbeda per transaksi")
    val key = request.idempotencyKey.trim()
    if (!IDEMPOTENCY_KEY_PATTERN.matches(key)) {
        throw ValidationException("idempotencyKey wajib 8-100 karakter dan hanya boleh berisi huruf, angka, titik, garis bawah, titik dua, atau tanda hubung")
    }
    val seenProducts = mutableSetOf<String>()
    request.items.forEach { item ->
        runCatching { UUID.fromString(item.productId) }.getOrElse {
            throw ValidationException("Format Product ID tidak valid: ${item.productId}")
        }
        if (!seenProducts.add(item.productId.lowercase())) {
            throw ValidationException("Produk yang sama tidak boleh muncul lebih dari sekali dalam keranjang")
        }
        if (item.qty <= BigDecimal.ZERO) throw ValidationException("Quantity harus lebih dari nol")
        if (item.qty.scale() > 2) throw ValidationException("Quantity maksimal memiliki 2 angka desimal")
        if (item.discount < BigDecimal.ZERO) throw ValidationException("Diskon tidak boleh negatif")
        if (item.discount.scale() > 2) throw ValidationException("Diskon maksimal memiliki 2 angka desimal")
    }
    if (request.amountPaid < BigDecimal.ZERO) throw ValidationException("Jumlah bayar tidak boleh negatif")
    if (request.amountPaid.scale() > 2) throw ValidationException("Jumlah bayar maksimal memiliki 2 angka desimal")
    if (request.notes != null && request.notes.length > 1000) throw ValidationException("Catatan maksimal 1000 karakter")
    return key
}

internal fun resolveCheckoutPayment(
    paymentMethod: PaymentMethod,
    requestedAmount: BigDecimal,
    total: BigDecimal,
    customerId: UUID?,
    dueDays: Int
): CheckoutPaymentAmounts {
    if (total <= BigDecimal.ZERO) throw ValidationException("Total transaksi harus lebih dari nol")
    if (requestedAmount.scale() > 2) throw ValidationException("Jumlah bayar maksimal memiliki 2 angka desimal")
    val amount = requestedAmount.normalizeCheckoutMoney()
    val normalizedTotal = total.normalizeCheckoutMoney()
    val credit = paymentMethod == PaymentMethod.HUTANG || paymentMethod == PaymentMethod.DP
    if (credit && customerId == null) throw ValidationException("Transaksi hutang/DP memerlukan pelanggan terdaftar")
    if (credit && dueDays !in 1..3650) throw ValidationException("Termin piutang harus antara 1 dan 3650 hari")

    return when (paymentMethod) {
        PaymentMethod.TUNAI -> {
            if (amount < normalizedTotal) throw ValidationException("Pembayaran tunai kurang dari total transaksi")
            CheckoutPaymentAmounts(
                TrxStatus.LUNAS,
                normalizedTotal,
                amount,
                amount.subtract(normalizedTotal).normalizeCheckoutMoney(),
                BigDecimal.ZERO
            )
        }
        PaymentMethod.TRANSFER, PaymentMethod.QRIS -> {
            if (amount.compareTo(normalizedTotal) != 0) {
                throw ValidationException("Pembayaran transfer/QRIS harus sama dengan total transaksi")
            }
            CheckoutPaymentAmounts(TrxStatus.LUNAS, normalizedTotal, normalizedTotal, BigDecimal.ZERO, BigDecimal.ZERO)
        }
        PaymentMethod.HUTANG -> {
            if (amount.compareTo(BigDecimal.ZERO) != 0) throw ValidationException("Pembayaran hutang harus bernilai nol")
            CheckoutPaymentAmounts(TrxStatus.HUTANG, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, normalizedTotal)
        }
        PaymentMethod.DP -> {
            if (amount <= BigDecimal.ZERO || amount >= normalizedTotal) {
                throw ValidationException("DP harus lebih dari nol dan kurang dari total transaksi")
            }
            CheckoutPaymentAmounts(
                TrxStatus.DP, amount, amount, BigDecimal.ZERO,
                normalizedTotal.subtract(amount).normalizeCheckoutMoney()
            )
        }
    }
}

internal fun checkoutRequestFingerprint(request: CheckoutRequest, normalizedKey: String): String {
    val canonical = buildString {
        append(normalizedKey).append('|')
        append(request.customerId?.trim().orEmpty()).append('|')
        append(request.paymentMethod.trim().lowercase()).append('|')
        append(request.amountPaid.normalizeCheckoutMoney().toPlainString()).append('|')
        append(request.dueDays).append('|').append(request.notes?.trim().orEmpty()).append('|')
        request.items.sortedBy { it.productId.lowercase() }.forEach { item ->
            append(item.productId.lowercase()).append(':')
            append(item.qty.stripTrailingZeros().toPlainString()).append(':')
            append(item.discount.normalizeCheckoutMoney().toPlainString()).append(';')
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal fun BigDecimal.normalizeCheckoutMoney(): BigDecimal = setScale(2, java.math.RoundingMode.HALF_UP)
