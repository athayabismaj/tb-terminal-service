package com.service.tbterminal.system

import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefreshTokenRotationPolicyTest {
    private val userId = UUID.fromString("55555555-5555-5555-5555-555555555555")
    private val now = OffsetDateTime.parse("2026-11-24T12:00:00+07:00")

    private fun record(
        usedAt: OffsetDateTime? = null,
        revokedAt: OffsetDateTime? = null,
        expiresAt: OffsetDateTime = now.plusDays(7),
        tokenVersion: Int = 3,
        owner: UUID = userId
    ) = RefreshTokenRecord(
        id = UUID.randomUUID(),
        userId = owner,
        familyId = UUID.randomUUID(),
        tokenVersion = tokenVersion,
        expiresAt = expiresAt,
        usedAt = usedAt,
        revokedAt = revokedAt
    )

    @Test
    fun `fresh token rotates`() {
        assertEquals(RefreshDecision.Rotate, RefreshTokenRotationPolicy.evaluate(record(), userId, 3, now))
    }

    @Test
    fun `reused token is detected`() {
        assertEquals(
            RefreshDecision.ReuseDetected,
            RefreshTokenRotationPolicy.evaluate(record(usedAt = now.minusMinutes(1)), userId, 3, now)
        )
    }

    @Test
    fun `revoked expired mismatched tokens are rejected`() {
        assertTrue(RefreshTokenRotationPolicy.evaluate(record(revokedAt = now), userId, 3, now) is RefreshDecision.Reject)
        assertTrue(RefreshTokenRotationPolicy.evaluate(record(expiresAt = now), userId, 3, now) is RefreshDecision.Reject)
        assertTrue(RefreshTokenRotationPolicy.evaluate(record(tokenVersion = 2), userId, 3, now) is RefreshDecision.Reject)
        assertTrue(RefreshTokenRotationPolicy.evaluate(record(owner = UUID.randomUUID()), userId, 3, now) is RefreshDecision.Reject)
    }
}
