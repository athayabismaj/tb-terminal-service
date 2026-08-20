package com.service.tbterminal.receivable

import com.service.tbterminal.shared.CreditLimitExceededException
import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReceivableValidationTest {
    private val today = LocalDate.of(2026, 8, 1)

    @Test
    fun `opening balance validates positive two-decimal amount`() {
        listOf("0", "-1", "10.001").forEach { amount ->
            assertFailsWith<ValidationException> {
                validateStandaloneReceivableRequest(request(amount = amount), today)
            }
        }
        assertEquals(BigDecimal("10.25"), validateStandaloneReceivableRequest(request("10.25"), today).amount)
    }

    @Test
    fun `opening balance rejects future debt date and due date before debt`() {
        assertFailsWith<ValidationException> {
            validateStandaloneReceivableRequest(request(debtDate = "2026-08-02", dueDate = "2026-08-10"), today)
        }
        assertFailsWith<ValidationException> {
            validateStandaloneReceivableRequest(request(debtDate = "2026-08-01", dueDate = "2026-07-31"), today)
        }
    }

    @Test
    fun `standalone endpoint rejects sale source`() {
        assertFailsWith<ValidationException> {
            validateStandaloneReceivableRequest(request(source = "SALE"), today)
        }
        assertEquals(
            ReceivableSource.OPENING_BALANCE,
            validateStandaloneReceivableRequest(request(), today).source
        )
    }

    @Test
    fun `adjustment requires reference and reason`() {
        assertEquals(
            ReceivableSource.ADJUSTMENT,
            validateStandaloneReceivableRequest(request(source = "ADJUSTMENT"), today).source
        )
        assertFailsWith<ValidationException> {
            validateStandaloneReceivableRequest(request(source = "ADJUSTMENT", reference = null), today)
        }
        assertFailsWith<ValidationException> {
            validateStandaloneReceivableRequest(request(source = "ADJUSTMENT", notes = null), today)
        }
    }

    @Test
    fun `status is derived consistently from amount paid`() {
        assertEquals(ReceivableStatus.UNPAID, deriveReceivableStatus(BigDecimal("100"), BigDecimal.ZERO))
        assertEquals(ReceivableStatus.PARTIAL, deriveReceivableStatus(BigDecimal("100"), BigDecimal("25")))
        assertEquals(ReceivableStatus.PAID, deriveReceivableStatus(BigDecimal("100"), BigDecimal("100")))
    }

    @Test
    fun `credit limit includes existing outstanding and zero means unlimited`() {
        assertFailsWith<CreditLimitExceededException> {
            ensureReceivableCreditLimit(BigDecimal("80"), BigDecimal("30"), BigDecimal("100"))
        }
        ensureReceivableCreditLimit(BigDecimal("8000"), BigDecimal("3000"), BigDecimal.ZERO)
        ensureReceivableCreditLimit(BigDecimal("80"), BigDecimal("20"), BigDecimal("100"))
    }

    @Test
    fun `payment accepts supported methods and requires idempotency`() {
        val validated = validateReceivablePaymentRequest(paymentRequest(method = "QRIS"))
        assertEquals(RecPaymentMethod.QRIS, validated.method)
        assertEquals("payment-key-001", validated.idempotencyKey)
        assertFailsWith<ValidationException> {
            validateReceivablePaymentRequest(paymentRequest(method = "hutang"))
        }
        assertFailsWith<ValidationException> {
            validateReceivablePaymentRequest(paymentRequest(idempotencyKey = "short"))
        }
    }

    @Test
    fun `payment rejects non-positive and more than two decimals`() {
        listOf("0", "-1", "10.001").forEach { amount ->
            assertFailsWith<ValidationException> {
                validateReceivablePaymentRequest(paymentRequest(amount = amount))
            }
        }
    }

    @Test
    fun `reversal requires a meaningful reason and idempotency`() {
        assertEquals(
            "Kesalahan nominal",
            validatePaymentReversalRequest(
                ReversePaymentRequest("reversal-key-001", "Kesalahan nominal")
            ).reason
        )
        assertFailsWith<ValidationException> {
            validatePaymentReversalRequest(ReversePaymentRequest("reversal-key-002", "bad"))
        }
    }

    private fun request(
        amount: String = "10.00",
        debtDate: String = "2026-08-01",
        dueDate: String = "2026-08-31",
        source: String = "OPENING_BALANCE",
        reference: String? = "OLD-001",
        notes: String? = "Migrasi saldo awal"
    ) = CreateStandaloneReceivableRequest(
        customerId = "customer-id",
        amount = BigDecimal(amount),
        debtDate = debtDate,
        dueDate = dueDate,
        legacyInvoiceNumber = reference,
        source = source,
        notes = notes
    )

    private fun paymentRequest(
        amount: String = "10.00",
        method: String = "transfer",
        idempotencyKey: String = "payment-key-001"
    ) = PaymentRequest(
        receivableId = "4d621a94-4e01-47eb-ad22-cbe0a96f79de",
        amount = BigDecimal(amount),
        method = method,
        idempotencyKey = idempotencyKey
    )
}
