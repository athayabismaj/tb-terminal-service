package com.service.tbterminal.purchasing

import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PurchasingIdempotencyTest {
    private val purchase = PurchaseRequest(
        supplierId = "5B1F2E0C-0000-4000-8000-000000000001",
        invoiceNo = " INV-1 ",
        paymentMethod = "Hutang",
        amountPaid = BigDecimal("0.00"),
        notes = null,
        dueDays = 30,
        items = listOf(
            PurchaseItemRequest("5b1f2e0c-0000-4000-8000-0000000000a1", BigDecimal("5.00"), BigDecimal("60.00")),
            PurchaseItemRequest("5b1f2e0c-0000-4000-8000-0000000000a2", BigDecimal("1"), BigDecimal("10"))
        ),
        idempotencyKey = "purchase-key-1"
    )

    private val payment = SupplierPaymentRequest(
        payableId = "5b1f2e0c-0000-4000-8000-0000000000b1",
        amount = BigDecimal("100.00"),
        method = "transfer",
        reference = null,
        notes = "  ",
        idempotencyKey = "payment-key-1"
    )

    @Test
    fun `blank key means legacy request without idempotency`() {
        assertNull(purchaseIdempotency(purchase.copy(idempotencyKey = null)))
        assertNull(purchaseIdempotency(purchase.copy(idempotencyKey = "   ")))
        assertNull(supplierPaymentIdempotency(payment.copy(idempotencyKey = "")))
    }

    @Test
    fun `key uses the same format rule as debt payment`() {
        listOf("short", "a".repeat(101), "key with space", "kunci/salah").forEach { key ->
            val error = assertFailsWith<ValidationException> { purchaseIdempotency(purchase.copy(idempotencyKey = key)) }
            assertEquals(
                "Idempotency key harus 8-100 karakter dan hanya berisi huruf, angka, titik, garis, titik dua, atau underscore",
                error.message
            )
        }
        assertEquals("purchase-key-1", purchaseIdempotency(purchase.copy(idempotencyKey = "  purchase-key-1 "))?.key)
    }

    @Test
    fun `equivalent purchase payloads share a fingerprint`() {
        val equivalent = purchase.copy(
            supplierId = purchase.supplierId.lowercase(),
            invoiceNo = "INV-1",
            paymentMethod = "hutang",
            amountPaid = BigDecimal("0"),
            notes = "",
            items = listOf(
                PurchaseItemRequest("5B1F2E0C-0000-4000-8000-0000000000A1", BigDecimal("5"), BigDecimal("60.0")),
                PurchaseItemRequest("5b1f2e0c-0000-4000-8000-0000000000a2", BigDecimal("1.00"), BigDecimal("10.00"))
            ),
            idempotencyKey = " purchase-key-1"
        )
        assertEquals(purchaseIdempotency(purchase), purchaseIdempotency(equivalent))
    }

    @Test
    fun `any business field change alters the purchase fingerprint`() {
        val base = purchaseIdempotency(purchase)!!.fingerprint
        val item = purchase.items.first()
        listOf(
            purchase.copy(supplierId = "5b1f2e0c-0000-4000-8000-000000000002"),
            purchase.copy(invoiceNo = "INV-2"),
            purchase.copy(paymentMethod = "dp"),
            purchase.copy(amountPaid = BigDecimal("0.01")),
            purchase.copy(notes = "catatan"),
            purchase.copy(dueDays = 31),
            purchase.copy(items = listOf(item.copy(qty = BigDecimal("5.01"))) + purchase.items.drop(1)),
            purchase.copy(items = listOf(item.copy(price = BigDecimal("59.99"))) + purchase.items.drop(1)),
            purchase.copy(items = purchase.items.reversed()),
            purchase.copy(items = purchase.items.take(1)),
            purchase.copy(idempotencyKey = "purchase-key-2")
        ).forEach { changed ->
            assertNotEquals(base, purchaseIdempotency(changed)!!.fingerprint, changed.toString())
        }
    }

    @Test
    fun `separators inside text cannot make two payloads collide`() {
        val left = purchase.copy(invoiceNo = "A;1:B", notes = "C")
        val right = purchase.copy(invoiceNo = "A", notes = "1:B;C")
        assertNotEquals(purchaseIdempotency(left)!!.fingerprint, purchaseIdempotency(right)!!.fingerprint)
    }

    @Test
    fun `supplier payment fingerprint follows service normalization`() {
        val base = supplierPaymentIdempotency(payment)!!
        assertEquals(
            base,
            supplierPaymentIdempotency(
                payment.copy(amount = BigDecimal("100"), method = "TRANSFER", reference = " ", notes = null)
            )
        )
        listOf(
            payment.copy(payableId = "5b1f2e0c-0000-4000-8000-0000000000b2"),
            payment.copy(amount = BigDecimal("99.99")),
            payment.copy(method = "tunai"),
            payment.copy(reference = "TRF-1"),
            payment.copy(notes = "lunas")
        ).forEach { changed ->
            assertNotEquals(base.fingerprint, supplierPaymentIdempotency(changed)!!.fingerprint, changed.toString())
        }
    }
}
