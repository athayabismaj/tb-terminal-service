package com.service.tbterminal.sales

import com.service.tbterminal.shared.BusinessConflictException

/** Kode konflik sync offline yang dijawab HTTP 409. */
internal enum class OfflineConflictCode {
    OFFLINE_OPENED_AT_INVALID,
    OFFLINE_OCCURRED_AT_INVALID,
    OFFLINE_ROUNDING_CONFLICT,
    OFFLINE_TAX_CONFLICT,
    OFFLINE_PRICE_CONFLICT
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
