package com.service.tbterminal.purchasing

import com.service.tbterminal.receivable.validatePaymentIdempotencyKey
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Key idempotency yang sudah divalidasi beserta sidik jari request-nya.
 * Retry dengan key sama dianggap request yang sama hanya bila sidik jarinya sama.
 */
data class PurchasingIdempotency(val key: String, val fingerprint: String)

/** Prefix lock advisory agar key yang sama di tabel lain (mis. pembayaran piutang) tidak saling menunggu. */
internal const val PURCHASE_IDEMPOTENCY_LOCK_SCOPE = "purchasing.purchase:"
internal const val SUPPLIER_PAYMENT_IDEMPOTENCY_LOCK_SCOPE = "purchasing.supplier-payment:"

internal const val PURCHASE_IDEMPOTENCY_CONFLICT_MESSAGE =
    "Idempotency key sudah digunakan untuk request pembelian yang berbeda"
internal const val SUPPLIER_PAYMENT_IDEMPOTENCY_CONFLICT_MESSAGE =
    "Idempotency key sudah digunakan untuk request pembayaran yang berbeda"

/** Key kosong atau hanya spasi diperlakukan seperti tidak dikirim (perilaku lama), sama dengan `/pay`. */
internal fun normalizeOptionalIdempotencyKey(value: String?): String? =
    value?.takeIf(String::isNotBlank)?.let(::validatePaymentIdempotencyKey)

internal fun purchaseIdempotency(request: PurchaseRequest): PurchasingIdempotency? =
    normalizeOptionalIdempotencyKey(request.idempotencyKey)?.let { key ->
        PurchasingIdempotency(key, purchaseRequestFingerprint(request, key))
    }

internal fun supplierPaymentIdempotency(request: SupplierPaymentRequest): PurchasingIdempotency? =
    normalizeOptionalIdempotencyKey(request.idempotencyKey)?.let { key ->
        PurchasingIdempotency(key, supplierPaymentRequestFingerprint(request, key))
    }

/**
 * Normalisasi mengikuti cara service membaca request: teks di-trim (kosong = null), metode
 * huruf kecil, angka dibandingkan nilainya (`"10"` sama dengan `"10.00"`). Urutan item ikut dihitung.
 */
internal fun purchaseRequestFingerprint(request: PurchaseRequest, normalizedKey: String): String =
    fingerprintOf(
        buildList {
            add(normalizedKey)
            add(request.supplierId.trim().lowercase())
            add(request.invoiceNo.normalizedText())
            add(request.paymentMethod.trim().lowercase())
            add(request.amountPaid.canonical())
            add(request.notes.normalizedText())
            add(request.dueDays.toString())
            add(request.items.size.toString())
            request.items.forEach { item ->
                add(item.productId.trim().lowercase())
                add(item.qty.canonical())
                add(item.price.canonical())
            }
        }
    )

internal fun supplierPaymentRequestFingerprint(request: SupplierPaymentRequest, normalizedKey: String): String =
    fingerprintOf(
        listOf(
            normalizedKey,
            request.payableId.trim().lowercase(),
            request.amount.canonical(),
            request.method.trim().lowercase(),
            request.reference.normalizedText(),
            request.notes.normalizedText()
        )
    )

private fun String?.normalizedText(): String = this?.trim().orEmpty()

private fun BigDecimal.canonical(): String = stripTrailingZeros().toPlainString()

/** Setiap bagian diberi awalan panjang supaya pemisah di dalam teks (mis. catatan) tidak menimbulkan tabrakan. */
private fun fingerprintOf(parts: List<String>): String {
    val canonical = buildString {
        parts.forEach { part -> append(part.length).append(':').append(part).append(';') }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
