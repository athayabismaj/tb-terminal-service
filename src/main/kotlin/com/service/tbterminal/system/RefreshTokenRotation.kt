package com.service.tbterminal.system

import java.time.OffsetDateTime
import java.util.UUID

/** Baris `system.refresh_tokens` (tanpa token mentah; hanya ID/jti). */
data class RefreshTokenRecord(
    val id: UUID,
    val userId: UUID,
    val familyId: UUID,
    val tokenVersion: Int,
    val expiresAt: OffsetDateTime,
    val usedAt: OffsetDateTime?,
    val revokedAt: OffsetDateTime?
)

sealed interface RefreshDecision {
    /** Token sah dan belum pernah dipakai: tandai terpakai lalu terbitkan token baru di keluarga yang sama. */
    data object Rotate : RefreshDecision

    /** Token yang sudah pernah dirotasi dipakai lagi: indikasi pencurian, cabut seluruh keluarga dan semua sesi user. */
    data object ReuseDetected : RefreshDecision

    data class Reject(val reason: String) : RefreshDecision
}

sealed interface RefreshConsumeResult {
    data class Rotated(val familyId: UUID) : RefreshConsumeResult
    data class ReuseDetected(val familyId: UUID) : RefreshConsumeResult
    data class Rejected(val reason: String) : RefreshConsumeResult
}

object RefreshTokenRotationPolicy {
    fun evaluate(
        record: RefreshTokenRecord,
        expectedUserId: UUID,
        expectedTokenVersion: Int,
        now: OffsetDateTime
    ): RefreshDecision {
        if (record.userId != expectedUserId) return RefreshDecision.Reject("subject_mismatch")
        if (record.revokedAt != null) return RefreshDecision.Reject("revoked")
        if (record.usedAt != null) return RefreshDecision.ReuseDetected
        if (!record.expiresAt.isAfter(now)) return RefreshDecision.Reject("expired")
        if (record.tokenVersion != expectedTokenVersion) return RefreshDecision.Reject("token_version")
        return RefreshDecision.Rotate
    }
}
