package com.service.tbterminal.sales

import com.service.tbterminal.shared.AccessPolicy
import com.service.tbterminal.shared.ManagerApprovalError
import com.service.tbterminal.shared.ManagerApprovalException
import com.service.tbterminal.shared.Permission
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.system.ManagerApprovalAction
import com.service.tbterminal.system.ManagerApprovalResourceType
import com.service.tbterminal.system.ManagerApprovalScope
import java.util.UUID
import java.math.BigDecimal
import java.math.RoundingMode

data class ValidatedRefundRequest(
    val idempotencyKey: String,
    val reason: String,
    val disposition: RefundDisposition
)

internal fun validateRefundRequest(request: RefundTransactionRequest): ValidatedRefundRequest {
    val key = request.idempotencyKey.trim()
    if (key.length !in 8..100 || !key.matches(Regex("^[A-Za-z0-9._:-]+$"))) {
        throw ValidationException(
            "idempotencyKey wajib 8-100 karakter dan hanya boleh berisi huruf, angka, titik, garis, titik dua, atau underscore"
        )
    }
    val reason = request.reason.trim()
    if (reason.length !in 5..1000) {
        throw ValidationException("Alasan refund wajib 5-1000 karakter")
    }
    val disposition = RefundDisposition.entries.firstOrNull {
        it.name.equals(request.returnDisposition.trim(), ignoreCase = true)
    } ?: throw ValidationException(
        "Disposisi refund tidak valid. Gunakan RETURN_TO_STOCK, NOT_RETURNED, atau DAMAGED"
    )
    return ValidatedRefundRequest(key, reason, disposition)
}

internal fun resolveRefundApprovalScope(
    actorUserId: UUID,
    actorRole: String,
    transactionId: UUID,
    managerApprovalId: String?
): ManagerApprovalScope? {
    if (AccessPolicy.isAllowed(actorRole, Permission.REFUND_TRANSACTION)) return null

    AccessPolicy.require(actorRole, Permission.REQUEST_MANAGER_APPROVAL)
    val rawApprovalId = managerApprovalId?.trim()?.takeIf(String::isNotBlank)
        ?: throw ManagerApprovalException(
            ManagerApprovalError.REQUIRED,
            "Manager approval diperlukan untuk melakukan refund transaksi ini"
        )
    val approvalId = runCatching { UUID.fromString(rawApprovalId) }.getOrNull()
        ?: throw ManagerApprovalException(
            ManagerApprovalError.INVALID,
            "Manager approval tidak valid"
        )
    return ManagerApprovalScope(
        approvalId = approvalId,
        requesterUserId = actorUserId,
        action = ManagerApprovalAction.REFUND_TRANSACTION,
        resourceType = ManagerApprovalResourceType.TRANSACTION,
        resourceId = transactionId
    )
}

internal fun calculateRefundedAmount(
    transactionAmount: BigDecimal,
    initialPaidAmount: BigDecimal,
    receivablePayments: List<BigDecimal>
): BigDecimal {
    val paid = receivablePayments.fold(initialPaidAmount) { total, amount -> total.add(amount) }
    return paid.max(BigDecimal.ZERO)
        .min(transactionAmount.max(BigDecimal.ZERO))
        .setScale(2, RoundingMode.HALF_UP)
}
