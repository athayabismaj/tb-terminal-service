package com.service.tbterminal.sales

import com.service.tbterminal.shared.AccessPolicy
import com.service.tbterminal.shared.ManagerApprovalError
import com.service.tbterminal.shared.ManagerApprovalException
import com.service.tbterminal.shared.Permission
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.system.ManagerApprovalAction
import com.service.tbterminal.system.ManagerApprovalResourceType
import com.service.tbterminal.system.ManagerApprovalScope
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

data class TransactionHistoryFilter(
    val sessionId: UUID?,
    val search: String?,
    val receiptNumber: String?,
    val cashierId: UUID?,
    val customerId: UUID?,
    val paymentMethod: PaymentMethod?,
    val status: TrxStatus?,
    val startAt: OffsetDateTime?,
    val endExclusive: OffsetDateTime?
)

internal fun validateTransactionHistoryFilter(
    sessionId: String?,
    search: String?,
    receiptNumber: String?,
    cashierId: String?,
    customerId: String?,
    paymentMethod: String?,
    status: String?,
    startDate: String?,
    endDate: String?
): TransactionHistoryFilter {
    fun uuid(value: String?, label: String): UUID? = value?.trim()?.takeIf(String::isNotBlank)?.let {
        runCatching { UUID.fromString(it) }.getOrElse { throw ValidationException("Format $label tidak valid") }
    }
    fun date(value: String?, label: String, plusDay: Boolean): OffsetDateTime? {
        val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val parsed = runCatching { LocalDate.parse(raw) }.getOrNull()
            ?: throw ValidationException("Format $label harus YYYY-MM-DD")
        return parsed.plusDays(if (plusDay) 1 else 0)
            .atStartOfDay(ZoneId.of("Asia/Jakarta")).toOffsetDateTime()
    }
    val method = paymentMethod?.trim()?.takeIf { it.isNotBlank() && !it.equals("SEMUA", true) }?.let { raw ->
        PaymentMethod.entries.firstOrNull { it.dbValue.equals(raw, true) }
            ?: throw ValidationException("Metode pembayaran tidak valid")
    }
    val trxStatus = status?.trim()?.takeIf { it.isNotBlank() && !it.equals("SEMUA", true) }?.let { raw ->
        TrxStatus.entries.firstOrNull { it.dbValue.equals(raw, true) || it.name.equals(raw, true) }
            ?: throw ValidationException("Status transaksi tidak valid")
    }
    val startAt = date(startDate, "startDate", false)
    val endExclusive = date(endDate, "endDate", true)
    if (startAt != null && endExclusive != null && !startAt.isBefore(endExclusive)) {
        throw ValidationException("startDate tidak boleh setelah endDate")
    }
    fun text(value: String?, max: Int, label: String): String? = value?.trim()?.takeIf(String::isNotBlank)?.also {
        if (it.length > max) throw ValidationException("$label maksimal $max karakter")
    }
    return TransactionHistoryFilter(
        sessionId = uuid(sessionId, "Session ID"),
        search = text(search, 100, "Pencarian"),
        receiptNumber = text(receiptNumber, 50, "Nomor transaksi"),
        cashierId = uuid(cashierId, "Cashier ID"),
        customerId = uuid(customerId, "Customer ID"),
        paymentMethod = method,
        status = trxStatus,
        startAt = startAt,
        endExclusive = endExclusive
    )
}

internal fun validateVoidRequest(request: VoidTransactionRequest): Pair<String, String> {
    val key = request.idempotencyKey.trim()
    if (key.length !in 8..100 || !key.matches(Regex("^[A-Za-z0-9._:-]+$"))) {
        throw ValidationException("idempotencyKey wajib 8-100 karakter dan hanya boleh berisi huruf, angka, titik, garis, titik dua, atau underscore")
    }
    val reason = request.reason.trim()
    if (reason.length !in 5..1000) throw ValidationException("Alasan void wajib 5-1000 karakter")
    return key to reason
}

internal fun resolveVoidApprovalScope(
    actorUserId: UUID,
    actorRole: String,
    transactionId: UUID,
    managerApprovalId: String?
): ManagerApprovalScope? {
    if (AccessPolicy.isAllowed(actorRole, Permission.VOID_TRANSACTION)) return null

    AccessPolicy.require(actorRole, Permission.REQUEST_MANAGER_APPROVAL)
    val rawApprovalId = managerApprovalId?.trim()?.takeIf(String::isNotBlank)
        ?: throw ManagerApprovalException(
            ManagerApprovalError.REQUIRED,
            "Manager approval diperlukan untuk membatalkan transaksi ini"
        )
    val approvalId = runCatching { UUID.fromString(rawApprovalId) }.getOrNull()
        ?: throw ManagerApprovalException(
            ManagerApprovalError.INVALID,
            "Manager approval tidak valid"
        )

    return ManagerApprovalScope(
        approvalId = approvalId,
        requesterUserId = actorUserId,
        action = ManagerApprovalAction.VOID_TRANSACTION,
        resourceType = ManagerApprovalResourceType.TRANSACTION,
        resourceId = transactionId
    )
}
