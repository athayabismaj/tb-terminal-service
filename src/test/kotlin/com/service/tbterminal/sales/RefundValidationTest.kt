package com.service.tbterminal.sales

import com.service.tbterminal.shared.ManagerApprovalError
import com.service.tbterminal.shared.ManagerApprovalException
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.system.ManagerApprovalAction
import com.service.tbterminal.system.ManagerApprovalResourceType
import java.util.UUID
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RefundValidationTest {
    @Test
    fun `refund request is normalized and has an explicit disposition`() {
        val request = validateRefundRequest(
            RefundTransactionRequest(
                idempotencyKey = " refund-123456 ",
                reason = " Barang dikembalikan utuh ",
                returnDisposition = "return_to_stock"
            )
        )

        assertEquals("refund-123456", request.idempotencyKey)
        assertEquals("Barang dikembalikan utuh", request.reason)
        assertEquals(RefundDisposition.RETURN_TO_STOCK, request.disposition)
    }

    @Test
    fun `refund rejects invalid key reason and disposition`() {
        assertFailsWith<ValidationException> {
            validateRefundRequest(RefundTransactionRequest("short", "valid reason"))
        }
        assertFailsWith<ValidationException> {
            validateRefundRequest(RefundTransactionRequest("refund-valid-1", "x"))
        }
        assertFailsWith<ValidationException> {
            validateRefundRequest(
                RefundTransactionRequest("refund-valid-2", "Alasan valid", "UNKNOWN")
            )
        }
    }

    @Test
    fun `owner and admin refund directly`() {
        val actorId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()

        assertNull(resolveRefundApprovalScope(actorId, Role.OWNER, transactionId, null))
        assertNull(resolveRefundApprovalScope(actorId, Role.ADMIN, transactionId, null))
    }

    @Test
    fun `cashier refund requires approval scoped to refund action and transaction`() {
        val actorId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val approvalId = UUID.randomUUID()

        val missing = assertFailsWith<ManagerApprovalException> {
            resolveRefundApprovalScope(actorId, Role.KASIR, transactionId, null)
        }
        assertEquals(ManagerApprovalError.REQUIRED, missing.reason)

        val invalid = assertFailsWith<ManagerApprovalException> {
            resolveRefundApprovalScope(actorId, Role.KASIR, transactionId, "invalid")
        }
        assertEquals(ManagerApprovalError.INVALID, invalid.reason)

        val scope = assertNotNull(
            resolveRefundApprovalScope(actorId, Role.KASIR, transactionId, approvalId.toString())
        )
        assertEquals(approvalId, scope.approvalId)
        assertEquals(actorId, scope.requesterUserId)
        assertEquals(ManagerApprovalAction.REFUND_TRANSACTION, scope.action)
        assertEquals(ManagerApprovalResourceType.TRANSACTION, scope.resourceType)
        assertEquals(transactionId, scope.resourceId)
    }

    @Test
    fun `financial refund is actual received amount and never exceeds transaction`() {
        assertEquals(
            BigDecimal("300.00"),
            calculateRefundedAmount(
                transactionAmount = BigDecimal("1000.00"),
                initialPaidAmount = BigDecimal("300.00"),
                receivablePayments = emptyList()
            )
        )
        assertEquals(
            BigDecimal("0.00"),
            calculateRefundedAmount(
                transactionAmount = BigDecimal("1000.00"),
                initialPaidAmount = BigDecimal.ZERO,
                receivablePayments = emptyList()
            )
        )
        assertEquals(
            BigDecimal("1000.00"),
            calculateRefundedAmount(
                transactionAmount = BigDecimal("1000.00"),
                initialPaidAmount = BigDecimal("300.00"),
                receivablePayments = listOf(BigDecimal("800.00"))
            )
        )
    }
}
