package com.service.tbterminal.sales

import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.system.ManagerApprovalScope
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RefundServiceTest {
    @Test
    fun `identical cashier retry is replayed before used approval needs validation`() = runBlocking {
        val actorId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val approvalId = UUID.randomUUID()
        val repository = FakeRefundRepository(
            refundResponse(actorId, transactionId, approvalId)
        )
        val service = RefundService(repository)

        val response = service.refundTransaction(
            actorUserId = actorId,
            actorRole = Role.KASIR,
            transactionIdRaw = transactionId.toString(),
            request = RefundTransactionRequest(
                idempotencyKey = "refund-replay-123",
                reason = "Barang dikembalikan utuh",
                managerApprovalId = approvalId.toString()
            ),
            ipAddress = null
        )

        assertTrue(response.idempotentReplay)
        assertEquals(0, repository.refundCalls)
    }

    @Test
    fun `same idempotency key rejects a changed payload or actor`() = runBlocking {
        val actorId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val repository = FakeRefundRepository(
            refundResponse(actorId, transactionId, null)
        )
        val service = RefundService(repository)

        assertFailsWith<ValidationException> {
            service.refundTransaction(
                actorUserId = actorId,
                actorRole = Role.OWNER,
                transactionIdRaw = transactionId.toString(),
                request = RefundTransactionRequest(
                    idempotencyKey = "refund-conflict-123",
                    reason = "Alasan payload sudah diubah"
                ),
                ipAddress = null
            )
        }
        assertFailsWith<ValidationException> {
            service.refundTransaction(
                actorUserId = UUID.randomUUID(),
                actorRole = Role.OWNER,
                transactionIdRaw = transactionId.toString(),
                request = RefundTransactionRequest(
                    idempotencyKey = "refund-conflict-123",
                    reason = "Barang dikembalikan utuh"
                ),
                ipAddress = null
            )
        }
        assertEquals(0, repository.refundCalls)
    }

    private fun refundResponse(
        actorId: UUID,
        transactionId: UUID,
        approvalId: UUID?
    ) = RefundTransactionResponse(
        refundId = UUID.randomUUID().toString(),
        refundNumber = "RFD-20260830-00000001",
        transactionId = transactionId.toString(),
        status = "REFUNDED",
        transactionAmount = BigDecimal("100.00"),
        refundedAmount = BigDecimal("100.00"),
        returnDisposition = RefundDisposition.RETURN_TO_STOCK.name,
        reason = "Barang dikembalikan utuh",
        requestedByUserId = actorId.toString(),
        createdAt = "2026-08-30T00:00:00Z",
        managerApprovalId = approvalId?.toString()
    )

    private class FakeRefundRepository(
        private val existing: RefundTransactionResponse?
    ) : RefundRepository {
        var refundCalls = 0

        override suspend fun findByIdempotencyKey(idempotencyKey: String): RefundTransactionResponse? = existing

        override suspend fun refundTransaction(
            actorUserId: UUID,
            transactionId: UUID,
            request: ValidatedRefundRequest,
            approvalScope: ManagerApprovalScope?,
            ipAddress: String?
        ): RefundTransactionResponse {
            refundCalls++
            error("Tidak boleh dipanggil pada skenario replay")
        }
    }
}
