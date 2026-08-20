package com.service.tbterminal.sales

import com.service.tbterminal.shared.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class TransactionHistoryValidationTest {
    @Test
    fun `validates all supported filters`() {
        val filter = validateTransactionHistoryFilter(
            sessionId = null,
            search = "TRX-001",
            receiptNumber = "TRX-001",
            cashierId = "00000000-0000-0000-0000-000000000001",
            customerId = "00000000-0000-0000-0000-000000000002",
            paymentMethod = "tunai",
            status = "VOIDED",
            startDate = "2026-08-01",
            endDate = "2026-08-02"
        )
        assertEquals(PaymentMethod.TUNAI, filter.paymentMethod)
        assertEquals(TrxStatus.VOIDED, filter.status)
        assertNotNull(filter.startAt)
        assertNotNull(filter.endExclusive)
    }

    @Test
    fun `rejects invalid date status and reversed range`() {
        assertFailsWith<ValidationException> {
            validateTransactionHistoryFilter(null, null, null, null, null, null, "unknown", null, null)
        }
        assertFailsWith<ValidationException> {
            validateTransactionHistoryFilter(null, null, null, null, null, null, null, "2026-08-02", "2026-08-01")
        }
    }

    @Test
    fun `void reason and idempotency key are mandatory and normalized`() {
        val (key, reason) = validateVoidRequest(VoidTransactionRequest(" void-key-123 ", " Salah input "))
        assertEquals("void-key-123", key)
        assertEquals("Salah input", reason)
        assertFailsWith<ValidationException> { validateVoidRequest(VoidTransactionRequest("short", "x")) }
    }
}
