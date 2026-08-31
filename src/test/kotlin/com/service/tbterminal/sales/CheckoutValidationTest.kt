package com.service.tbterminal.sales

import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheckoutValidationTest {
    private val productId = UUID.randomUUID().toString()
    private val customerId = UUID.randomUUID()

    @Test
    fun `request rejects zero negative and over precision quantity`() {
        listOf("0", "-1", "1.001").forEach { quantity ->
            assertFailsWith<ValidationException> {
                validateCheckoutRequest(request(quantity = quantity))
            }
        }
    }

    @Test
    fun `request rejects duplicate product and malformed idempotency key`() {
        assertFailsWith<ValidationException> {
            validateCheckoutRequest(request(items = listOf(item(), item())))
        }
        assertFailsWith<ValidationException> {
            validateCheckoutRequest(request(idempotencyKey = "short"))
        }
    }

    @Test
    fun `cash accepts overpayment and calculates change`() {
        val result = resolveCheckoutPayment(
            PaymentMethod.TUNAI,
            BigDecimal("125.00"),
            BigDecimal("100.00"),
            customerId = null,
            dueDays = 30
        )

        assertEquals(TrxStatus.LUNAS, result.status)
        assertEquals(BigDecimal("100.00"), result.paidAmount)
        assertEquals(BigDecimal("125.00"), result.amountTendered)
        assertEquals(BigDecimal("25.00"), result.changeAmount)
    }

    @Test
    fun `cash underpayment and non exact electronic payment are rejected`() {
        assertFailsWith<ValidationException> {
            resolveCheckoutPayment(PaymentMethod.TUNAI, BigDecimal("99"), BigDecimal("100"), null, 30)
        }
        listOf(PaymentMethod.TRANSFER, PaymentMethod.QRIS).forEach { method ->
            assertFailsWith<ValidationException> {
                resolveCheckoutPayment(method, BigDecimal("99"), BigDecimal("100"), null, 30)
            }
        }
    }

    @Test
    fun `credit payment requires customer and valid amount`() {
        assertFailsWith<ValidationException> {
            resolveCheckoutPayment(PaymentMethod.HUTANG, BigDecimal.ZERO, BigDecimal("100"), null, 30)
        }
        assertFailsWith<ValidationException> {
            resolveCheckoutPayment(PaymentMethod.HUTANG, BigDecimal.ONE, BigDecimal("100"), customerId, 30)
        }
        listOf("0", "100").forEach { amount ->
            assertFailsWith<ValidationException> {
                resolveCheckoutPayment(PaymentMethod.DP, BigDecimal(amount), BigDecimal("100"), customerId, 30)
            }
        }
    }

    @Test
    fun `fully discounted transaction accepts zero non credit payment only`() {
        val free = resolveCheckoutPayment(
            PaymentMethod.TUNAI, BigDecimal.ZERO, BigDecimal.ZERO, null, 30
        )
        assertEquals(TrxStatus.LUNAS, free.status)
        assertEquals(BigDecimal("0.00"), free.paidAmount)

        assertFailsWith<ValidationException> {
            resolveCheckoutPayment(PaymentMethod.HUTANG, BigDecimal.ZERO, BigDecimal.ZERO, customerId, 30)
        }
        assertFailsWith<ValidationException> {
            resolveCheckoutPayment(PaymentMethod.TUNAI, BigDecimal.ONE, BigDecimal.ZERO, null, 30)
        }
    }

    private fun request(
        quantity: String = "1.00",
        idempotencyKey: String = "checkout-test-key",
        items: List<CheckoutItemRequest> = listOf(item(quantity))
    ) = CheckoutRequest(
        items = items,
        customerId = null,
        paymentMethod = "tunai",
        amountPaid = BigDecimal("100.00"),
        dueDays = 30,
        notes = null,
        idempotencyKey = idempotencyKey
    )

    private fun item(quantity: String = "1.00") = CheckoutItemRequest(
        productId = productId,
        qty = BigDecimal(quantity),
        discount = BigDecimal.ZERO
    )
}
