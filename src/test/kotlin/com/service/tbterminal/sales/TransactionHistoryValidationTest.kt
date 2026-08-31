package com.service.tbterminal.sales

import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.shared.ManagerApprovalError
import com.service.tbterminal.shared.ManagerApprovalException
import com.service.tbterminal.shared.Role
import com.service.tbterminal.system.ManagerApprovalAction
import com.service.tbterminal.system.ManagerApprovalResourceType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    @Test
    fun `owner and admin void directly without approval`() {
        val actorId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()

        assertNull(resolveVoidApprovalScope(actorId, Role.OWNER, transactionId, null))
        assertNull(resolveVoidApprovalScope(actorId, Role.ADMIN, transactionId, null))
    }

    @Test
    fun `cashier void requires a valid scoped approval id`() {
        val actorId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val approvalId = UUID.randomUUID()

        val required = assertFailsWith<ManagerApprovalException> {
            resolveVoidApprovalScope(actorId, Role.KASIR, transactionId, null)
        }
        assertEquals(ManagerApprovalError.REQUIRED, required.reason)

        val invalid = assertFailsWith<ManagerApprovalException> {
            resolveVoidApprovalScope(actorId, Role.KASIR, transactionId, "not-a-uuid")
        }
        assertEquals(ManagerApprovalError.INVALID, invalid.reason)

        val scope = assertNotNull(
            resolveVoidApprovalScope(actorId, Role.KASIR, transactionId, approvalId.toString())
        )
        assertEquals(approvalId, scope.approvalId)
        assertEquals(actorId, scope.requesterUserId)
        assertEquals(ManagerApprovalAction.VOID_TRANSACTION, scope.action)
        assertEquals(ManagerApprovalResourceType.TRANSACTION, scope.resourceType)
        assertEquals(transactionId, scope.resourceId)
    }
}
