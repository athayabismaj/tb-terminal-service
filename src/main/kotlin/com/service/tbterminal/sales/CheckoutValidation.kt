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

internal data class AppliedCheckoutPaymentLine(
    val method: PaymentMethod,
    val amount: BigDecimal,
    val reference: String? = null
)

internal data class CheckoutPaymentResolution(
    val status: TrxStatus,
    val paidAmount: BigDecimal,
    val amountTendered: BigDecimal,
    val changeAmount: BigDecimal,
    val receivableAmount: BigDecimal,
    val appliedLines: List<AppliedCheckoutPaymentLine>,
    val cashAppliedTotal: BigDecimal
)

internal fun validateCheckoutRequest(request: CheckoutRequest): String {
    validateCheckoutItems(request.items)
    val key = request.idempotencyKey.trim()
    if (!IDEMPOTENCY_KEY_PATTERN.matches(key)) {
        throw ValidationException("idempotencyKey wajib 8-100 karakter dan hanya boleh berisi huruf, angka, titik, garis bawah, titik dua, atau tanda hubung")
    }
    if (request.payments != null) {
        validateSplitPaymentLines(request.payments)
    } else {
        if (request.paymentMethod.isBlank()) {
            throw ValidationException("Metode pembayaran wajib diisi")
        }
        if (request.amountPaid < BigDecimal.ZERO) throw ValidationException("Jumlah bayar tidak boleh negatif")
        if (request.amountPaid.scale() > 2) throw ValidationException("Jumlah bayar maksimal memiliki 2 angka desimal")
    }
    if (request.notes != null && request.notes.length > 1000) throw ValidationException("Catatan maksimal 1000 karakter")
    request.checkoutAttemptId?.let { validateUuid(it, "checkoutAttemptId") }
    request.managerApprovalId?.let { validateUuid(it, "managerApprovalId") }
    return key
}

internal fun validateSplitPaymentLines(lines: List<CheckoutPaymentLineRequest>) {
    if (lines.isEmpty()) throw ValidationException("Rincian split payment tidak boleh kosong")
    if (lines.size > 5) throw ValidationException("Maksimal 5 metode pembayaran dalam satu transaksi")
    var cashCount = 0
    lines.forEach { line ->
        val normalizedMethod = line.method.trim().lowercase()
        val parsedMethod = PaymentMethod.entries.firstOrNull { it.dbValue == normalizedMethod }
            ?: throw ValidationException("Metode split payment '${line.method}' tidak valid")
        if (parsedMethod == PaymentMethod.HUTANG || parsedMethod == PaymentMethod.DP) {
            throw ValidationException("Metode pada rincian split payment hanya boleh tunai, transfer, atau qris")
        }
        if (parsedMethod == PaymentMethod.TUNAI) {
            cashCount += 1
        }
        if (line.amount < BigDecimal.ZERO) throw ValidationException("Nominal split payment tidak boleh negatif")
        if (line.amount.scale() > 2) throw ValidationException("Nominal split payment maksimal memiliki 2 angka desimal")
        if (line.reference != null && line.reference.trim().length > 100) {
            throw ValidationException("Referensi pembayaran maksimal 100 karakter")
        }
    }
    if (cashCount > 1) {
        throw ValidationException("Maksimal satu baris pembayaran tunai dalam split payment")
    }
}

internal fun validateCheckoutItems(items: List<CheckoutItemRequest>) {
    if (items.isEmpty()) throw ValidationException("Keranjang belanja tidak boleh kosong")
    if (items.size > 100) throw ValidationException("Maksimal 100 item berbeda per transaksi")
    val seenProducts = mutableSetOf<String>()
    items.forEach { item ->
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
        item.discountRequest?.value?.let { value ->
            if (value < BigDecimal.ZERO) throw ValidationException("Nilai diskon tidak boleh negatif")
        }
        item.note?.trim()?.let { note ->
            if (note.length > 300) throw ValidationException("Catatan item maksimal 300 karakter")
        }
    }
}

private fun validateUuid(raw: String, field: String) {
    if (runCatching { UUID.fromString(raw.trim()) }.isFailure) {
        throw ValidationException("Format $field tidak valid")
    }
}

internal fun resolveCheckoutPayment(
    paymentMethod: PaymentMethod,
    requestedAmount: BigDecimal,
    total: BigDecimal,
    customerId: UUID?,
    dueDays: Int
): CheckoutPaymentAmounts {
    if (total < BigDecimal.ZERO) throw ValidationException("Total transaksi tidak boleh negatif")
    if (requestedAmount.scale() > 2) throw ValidationException("Jumlah bayar maksimal memiliki 2 angka desimal")
    val amount = requestedAmount.normalizeCheckoutMoney()
    val normalizedTotal = total.normalizeCheckoutMoney()
    val credit = paymentMethod == PaymentMethod.HUTANG || paymentMethod == PaymentMethod.DP
    if (normalizedTotal.compareTo(BigDecimal.ZERO) == 0) {
        if (credit) throw ValidationException("Transaksi bernilai nol tidak dapat menggunakan HUTANG atau DP")
        if (amount.compareTo(BigDecimal.ZERO) != 0) throw ValidationException("Pembayaran transaksi nol harus bernilai nol")
        return CheckoutPaymentAmounts(
            TrxStatus.LUNAS, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
            BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2)
        )
    }
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

internal fun resolveCheckoutPayments(
    paymentMethod: PaymentMethod?,
    requestedAmount: BigDecimal,
    paymentLines: List<CheckoutPaymentLineRequest>?,
    total: BigDecimal,
    customerId: UUID?,
    dueDays: Int
): CheckoutPaymentResolution {
    if (paymentLines == null) {
        val method = paymentMethod ?: throw ValidationException("Metode pembayaran wajib diisi")
        val legacy = resolveCheckoutPayment(method, requestedAmount, total, customerId, dueDays)
        val lines = if (legacy.paidAmount > BigDecimal.ZERO) {
            listOf(AppliedCheckoutPaymentLine(method = method, amount = legacy.paidAmount))
        } else {
            emptyList()
        }
        val cashApplied = if (method == PaymentMethod.TUNAI) legacy.paidAmount else BigDecimal.ZERO.setScale(2)
        return CheckoutPaymentResolution(
            status = legacy.status,
            paidAmount = legacy.paidAmount,
            amountTendered = legacy.amountTendered,
            changeAmount = legacy.changeAmount,
            receivableAmount = legacy.receivableAmount,
            appliedLines = lines,
            cashAppliedTotal = cashApplied
        )
    }

    validateSplitPaymentLines(paymentLines)
    if (total < BigDecimal.ZERO) throw ValidationException("Total transaksi tidak boleh negatif")
    val normalizedTotal = total.normalizeCheckoutMoney()
    if (paymentMethod == PaymentMethod.HUTANG) {
        throw ValidationException("Pembayaran hutang murni tidak menggunakan rincian split payment")
    }
    val isDp = paymentMethod == PaymentMethod.DP

    val parsedLines = paymentLines.map { line ->
        val method = PaymentMethod.entries.first { it.dbValue == line.method.trim().lowercase() }
        AppliedCheckoutPaymentLine(
            method = method,
            amount = line.amount.normalizeCheckoutMoney(),
            reference = line.reference?.trim()?.takeIf(String::isNotBlank)
        )
    }

    if (normalizedTotal.compareTo(BigDecimal.ZERO) == 0) {
        if (isDp) throw ValidationException("Transaksi bernilai nol tidak dapat menggunakan HUTANG atau DP")
        if (parsedLines.any { it.amount.compareTo(BigDecimal.ZERO) != 0 }) {
            throw ValidationException("Pembayaran transaksi nol harus bernilai nol")
        }
        return CheckoutPaymentResolution(
            status = TrxStatus.LUNAS,
            paidAmount = BigDecimal.ZERO.setScale(2),
            amountTendered = BigDecimal.ZERO.setScale(2),
            changeAmount = BigDecimal.ZERO.setScale(2),
            receivableAmount = BigDecimal.ZERO.setScale(2),
            appliedLines = emptyList(),
            cashAppliedTotal = BigDecimal.ZERO.setScale(2)
        )
    }

    if (parsedLines.any { it.amount <= BigDecimal.ZERO }) {
        throw ValidationException("Setiap nominal metode pembayaran harus lebih dari nol")
    }

    val nonCashPaid = parsedLines.filter { it.method != PaymentMethod.TUNAI }
        .fold(BigDecimal.ZERO) { acc, line -> acc.add(line.amount) }
        .normalizeCheckoutMoney()
    val cashLine = parsedLines.firstOrNull { it.method == PaymentMethod.TUNAI }
    val cashTendered = cashLine?.amount ?: BigDecimal.ZERO.setScale(2)
    val totalTendered = nonCashPaid.add(cashTendered).normalizeCheckoutMoney()

    if (nonCashPaid > normalizedTotal) {
        throw ValidationException("Total pembayaran non-tunai tidak boleh melebihi total transaksi")
    }

    if (isDp) {
        if (customerId == null) throw ValidationException("Transaksi hutang/DP memerlukan pelanggan terdaftar")
        if (dueDays !in 1..3650) throw ValidationException("Termin piutang harus antara 1 dan 3650 hari")
        if (totalTendered <= BigDecimal.ZERO || totalTendered >= normalizedTotal) {
            throw ValidationException("Total DP harus lebih dari nol dan kurang dari total transaksi")
        }
        return CheckoutPaymentResolution(
            status = TrxStatus.DP,
            paidAmount = totalTendered,
            amountTendered = totalTendered,
            changeAmount = BigDecimal.ZERO.setScale(2),
            receivableAmount = normalizedTotal.subtract(totalTendered).normalizeCheckoutMoney(),
            appliedLines = parsedLines,
            cashAppliedTotal = cashTendered
        )
    }

    if (totalTendered < normalizedTotal) {
        throw ValidationException("Total pembayaran split kurang dari total transaksi")
    }
    val remainingCashNeeded = normalizedTotal.subtract(nonCashPaid).normalizeCheckoutMoney()
    if (cashLine != null && remainingCashNeeded.compareTo(BigDecimal.ZERO) == 0) {
        throw ValidationException("Pembayaran non-tunai sudah menutup seluruh tagihan; hapus baris tunai")
    }
    val appliedLines = parsedLines.map { line ->
        if (line.method == PaymentMethod.TUNAI) line.copy(amount = remainingCashNeeded) else line
    }
    val change = totalTendered.subtract(normalizedTotal).normalizeCheckoutMoney()
    return CheckoutPaymentResolution(
        status = TrxStatus.LUNAS,
        paidAmount = normalizedTotal,
        amountTendered = totalTendered,
        changeAmount = change,
        receivableAmount = BigDecimal.ZERO.setScale(2),
        appliedLines = appliedLines,
        cashAppliedTotal = remainingCashNeeded
    )
}

internal fun checkoutRequestFingerprint(request: CheckoutRequest, normalizedKey: String): String {
    val canonical = buildString {
        append(normalizedKey).append('|')
        append(request.customerId?.trim().orEmpty()).append('|')
        append(request.paymentMethod.trim().lowercase()).append('|')
        append(request.amountPaid.normalizeCheckoutMoney().toPlainString()).append('|')
        request.payments.orEmpty().forEach { line ->
            append(line.method.trim().lowercase()).append(':')
            append(line.amount.normalizeCheckoutMoney().toPlainString()).append(':')
            append(line.reference?.trim().orEmpty()).append(',')
        }
        append('|')
        append(request.dueDays).append('|').append(request.notes?.trim().orEmpty()).append('|')
        append(request.checkoutAttemptId?.trim().orEmpty()).append('|')
        append(request.managerApprovalId?.trim().orEmpty()).append('|')
        appendDiscount(request.transactionDiscount)
        append('|')
        request.items.sortedBy { it.productId.lowercase() }.forEach { item ->
            append(item.productId.lowercase()).append(':')
            append(item.qty.stripTrailingZeros().toPlainString()).append(':')
            append(item.discount.normalizeCheckoutMoney().toPlainString()).append(':')
            appendDiscount(item.discountRequest)
            append(':').append(item.note?.trim().orEmpty())
            append(';')
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun StringBuilder.appendDiscount(discount: DiscountRequest?) {
    append(discount?.type?.name.orEmpty()).append(':')
    append(discount?.value?.stripTrailingZeros()?.toPlainString().orEmpty())
}

internal fun BigDecimal.normalizeCheckoutMoney(): BigDecimal = setScale(2, java.math.RoundingMode.HALF_UP)
