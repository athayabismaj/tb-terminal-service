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

    @Test
    fun `split payment supports cash plus electronic methods with change calculation`() {
        val resolution = resolveCheckoutPayments(
            paymentMethod = null,
            requestedAmount = BigDecimal.ZERO,
            paymentLines = listOf(
                CheckoutPaymentLineRequest(method = "qris", amount = BigDecimal("150000.00"), reference = "QR-01"),
                CheckoutPaymentLineRequest(method = "tunai", amount = BigDecimal("200000.00"))
            ),
            total = BigDecimal("300000.00"),
            customerId = null,
            dueDays = 30
        )

        assertEquals(TrxStatus.LUNAS, resolution.status)
        assertEquals(BigDecimal("300000.00"), resolution.paidAmount)
        assertEquals(BigDecimal("350000.00"), resolution.amountTendered)
        assertEquals(BigDecimal("50000.00"), resolution.changeAmount)
        assertEquals(BigDecimal("150000.00"), resolution.cashAppliedTotal)
        assertEquals(2, resolution.appliedLines.size)
        assertEquals(BigDecimal("150000.00"), resolution.appliedLines[0].amount)
        assertEquals(BigDecimal("150000.00"), resolution.appliedLines[1].amount)
    }

    @Test
    fun `split payment supports DP and rejects invalid combinations`() {
        val dpResolution = resolveCheckoutPayments(
            paymentMethod = PaymentMethod.DP,
            requestedAmount = BigDecimal.ZERO,
            paymentLines = listOf(
                CheckoutPaymentLineRequest(method = "tunai", amount = BigDecimal("100000.00")),
                CheckoutPaymentLineRequest(method = "transfer", amount = BigDecimal("200000.00"), reference = "TRF-99")
            ),
            total = BigDecimal("500000.00"),
            customerId = customerId,
            dueDays = 30
        )
        assertEquals(TrxStatus.DP, dpResolution.status)
        assertEquals(BigDecimal("300000.00"), dpResolution.paidAmount)
        assertEquals(BigDecimal("200000.00"), dpResolution.receivableAmount)
        assertEquals(BigDecimal("100000.00"), dpResolution.cashAppliedTotal)

        // Non-cash exceeding total must fail
        assertFailsWith<ValidationException> {
            resolveCheckoutPayments(
                paymentMethod = null,
                requestedAmount = BigDecimal.ZERO,
                paymentLines = listOf(
                    CheckoutPaymentLineRequest(method = "transfer", amount = BigDecimal("350000.00")),
                    CheckoutPaymentLineRequest(method = "qris", amount = BigDecimal("200000.00"))
                ),
                total = BigDecimal("500000.00"),
                customerId = null,
                dueDays = 30
            )
        }

        // Multiple cash rows in split payment must fail
        assertFailsWith<ValidationException> {
            validateCheckoutRequest(
                request().copy(
                    payments = listOf(
                        CheckoutPaymentLineRequest(method = "tunai", amount = BigDecimal("50.00")),
                        CheckoutPaymentLineRequest(method = "tunai", amount = BigDecimal("50.00"))
                    )
                )
            )
        }
    }

    @Test
    fun `item note over 300 characters is rejected`() {
        val longNote = "a".repeat(301)
        assertFailsWith<ValidationException> {
            validateCheckoutRequest(
                request(items = listOf(CheckoutItemRequest(productId = productId, qty = BigDecimal("1.00"), note = longNote)))
            )
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
