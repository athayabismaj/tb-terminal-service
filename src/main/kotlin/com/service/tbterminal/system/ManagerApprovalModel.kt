package com.service.tbterminal.system

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
enum class ManagerApprovalResourceType {
    TRANSACTION,
    RECEIVABLE_PAYMENT
}

@Serializable
enum class ManagerApprovalAction(val requiredResourceType: ManagerApprovalResourceType) {
    VOID_TRANSACTION(ManagerApprovalResourceType.TRANSACTION),
    REFUND_TRANSACTION(ManagerApprovalResourceType.TRANSACTION),
    DISCOUNT_OVERRIDE(ManagerApprovalResourceType.TRANSACTION),
    RECEIVABLE_REVERSAL(ManagerApprovalResourceType.RECEIVABLE_PAYMENT)
}

enum class ManagerApprovalStatus {
    APPROVED,
    USED,
    EXPIRED
}

@Serializable
data class CreateManagerApprovalRequest(
    val action: ManagerApprovalAction,
    val resourceType: ManagerApprovalResourceType? = null,
    val resourceId: String? = null,
    val approverUsername: String,
    val approverPin: String
)

@Serializable
data class ManagerApprovalResponse(
    val approvalId: String,
    val action: ManagerApprovalAction,
    val resourceType: ManagerApprovalResourceType?,
    val resourceId: String?,
    val status: ManagerApprovalStatus,
    val createdAt: String,
    val expiresAt: String,
    val usedAt: String? = null
)

data class ManagerApprovalApprover(
    val userId: UUID,
    val role: String,
    val pinHash: String,
    val isActive: Boolean
)

data class ManagerApprovalRecord(
    val id: UUID,
    val requestedByUserId: UUID,
    val approvedByUserId: UUID,
    val action: ManagerApprovalAction,
    val resourceType: ManagerApprovalResourceType?,
    val resourceId: UUID?,
    val status: ManagerApprovalStatus,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val usedAt: OffsetDateTime?
) {
    fun toResponse() = ManagerApprovalResponse(
        approvalId = id.toString(),
        action = action,
        resourceType = resourceType,
        resourceId = resourceId?.toString(),
        status = status,
        createdAt = createdAt.toString(),
        expiresAt = expiresAt.toString(),
        usedAt = usedAt?.toString()
    )
}

data class ManagerApprovalScope(
    val approvalId: UUID,
    val requesterUserId: UUID,
    val action: ManagerApprovalAction,
    val resourceType: ManagerApprovalResourceType?,
    val resourceId: UUID?
)

data class ManagerApprovalConsumeResult(
    val consumed: Boolean,
    val record: ManagerApprovalRecord?
)
