package com.service.tbterminal.sales

import com.service.tbterminal.shared.BusinessConflictException
import java.time.OffsetDateTime

/** Kode konflik sync offline yang dijawab HTTP 409. */
internal enum class OfflineConflictCode {
    OFFLINE_OPENED_AT_INVALID,
    OFFLINE_CLOSED_AT_INVALID,
    OFFLINE_OCCURRED_AT_INVALID,
    OFFLINE_ROUNDING_CONFLICT,
    OFFLINE_TAX_CONFLICT,
    OFFLINE_PRICE_CONFLICT,
    OFFLINE_UNIT_CONFLICT
}

/** Kejadian di dalam sesi kas yang waktunya berasal dari jam perangkat. */
internal enum class OfflineSessionEvent(val label: String, val conflictCode: OfflineConflictCode) {
    TRANSACTION("transaksi", OfflineConflictCode.OFFLINE_OCCURRED_AT_INVALID),
    CASH_EXPENSE("pengeluaran kas", OfflineConflictCode.OFFLINE_OCCURRED_AT_INVALID),
    SESSION_CLOSE("tutup sesi kas", OfflineConflictCode.OFFLINE_CLOSED_AT_INVALID)
}

private const val BUSINESS_RULE_PHRASE = "business rule"

/**
 * Satu-satunya pembuat error `OFFLINE_*`. `SyncErrorClassifier` di Android mengenali
 * konflik dari frasa "business rule", jadi setiap pesan wajib memuatnya.
 */
internal fun offlineConflict(code: OfflineConflictCode, detail: String): BusinessConflictException {
    val message = if (detail.contains(BUSINESS_RULE_PHRASE)) detail else "Pelanggaran $BUSINESS_RULE_PHRASE: $detail"
    return BusinessConflictException(code.name, message)
}

internal fun requireValidSessionTime(
    event: OfflineSessionEvent,
    occurredAt: OffsetDateTime,
    sessionOpenedAt: OffsetDateTime
) {
    OfflineCheckoutGuard.validateSessionTime(event, occurredAt, sessionOpenedAt, OffsetDateTime.now())
        ?.let { throw offlineConflict(event.conflictCode, it) }
}
