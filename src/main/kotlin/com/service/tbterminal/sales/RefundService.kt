package com.service.tbterminal.sales

import com.service.tbterminal.shared.ValidationException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.util.UUID

class RefundService(
    private val repository: RefundRepository
) {
    suspend fun refundTransaction(
        actorUserId: UUID,
        actorRole: String,
        transactionIdRaw: String,
        request: RefundTransactionRequest,
        ipAddress: String?
    ): RefundTransactionResponse {
        val transactionId = runCatching { UUID.fromString(transactionIdRaw) }.getOrNull()
            ?: throw ValidationException("Format Transaction ID tidak valid")
        val validated = validateRefundRequest(request)
        val approvalScope = resolveRefundApprovalScope(
            actorUserId = actorUserId,
            actorRole = actorRole,
            transactionId = transactionId,
            managerApprovalId = request.managerApprovalId
        )

        repository.findByIdempotencyKey(validated.idempotencyKey)?.let { existing ->
            ensureSameRequest(existing, actorUserId, transactionId, validated, approvalScope?.approvalId)
            return existing.copy(idempotentReplay = true)
        }

        return try {
            repository.refundTransaction(
                actorUserId = actorUserId,
                transactionId = transactionId,
                request = validated,
                approvalScope = approvalScope,
                ipAddress = ipAddress
            )
        } catch (error: ExposedSQLException) {
            if (error.sqlState == "23505") {
                repository.findByIdempotencyKey(validated.idempotencyKey)?.let { existing ->
                    ensureSameRequest(existing, actorUserId, transactionId, validated, approvalScope?.approvalId)
                    return existing.copy(idempotentReplay = true)
                }
            }
            throw error
        }
    }

    private fun ensureSameRequest(
        existing: RefundTransactionResponse,
        actorUserId: UUID,
        transactionId: UUID,
        request: ValidatedRefundRequest,
        managerApprovalId: UUID?
    ) {
        if (existing.requestedByUserId != actorUserId.toString() ||
            existing.transactionId != transactionId.toString() ||
            existing.reason != request.reason ||
            existing.returnDisposition != request.disposition.name ||
            existing.managerApprovalId != managerApprovalId?.toString()
        ) {
            throw ValidationException("idempotencyKey sudah digunakan untuk permintaan refund yang berbeda")
        }
    }
}
