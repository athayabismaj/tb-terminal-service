package com.service.tbterminal.system

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialAttemptGuardTest {
    private val userId = UUID.fromString("44444444-4444-4444-4444-444444444444")
    private val clock = Clock.fixed(Instant.parse("2026-11-24T05:00:00Z"), ZoneOffset.UTC)
    private val policy = PinRolePolicy(consecutiveThreshold = 3, maxTotalFailures = 10, backoffSeconds = listOf(60L, 300L))

    @Test
    fun `failures below threshold report attempts left`() = runBlocking {
        val guard = CredentialAttemptGuard(InMemoryAttemptStore(), clock)
        val first = guard.registerFailure(userId, CredentialScope.PIN, policy)
        val second = guard.registerFailure(userId, CredentialScope.PIN, policy)
        assertEquals(AttemptOutcome.Invalid(1, 2), first)
        assertEquals(AttemptOutcome.Invalid(2, 1), second)
        assertNull(guard.activeLock(userId, CredentialScope.PIN))
    }

    @Test
    fun `reaching threshold locks with backoff`() = runBlocking<Unit> {
        val guard = CredentialAttemptGuard(InMemoryAttemptStore(), clock)
        repeat(2) { guard.registerFailure(userId, CredentialScope.PIN, policy) }
        val third = guard.registerFailure(userId, CredentialScope.PIN, policy)
        assertTrue(third is AttemptOutcome.LockedNow)
        assertEquals(
            OffsetDateTime.ofInstant(clock.instant().plusSeconds(60), ZoneOffset.UTC),
            (third as AttemptOutcome.LockedNow).lockedUntil
        )
        assertNotNull(guard.activeLock(userId, CredentialScope.PIN))
    }

    @Test
    fun `scopes are counted independently`() = runBlocking {
        val guard = CredentialAttemptGuard(InMemoryAttemptStore(), clock)
        repeat(3) { guard.registerFailure(userId, CredentialScope.PASSWORD, policy) }
        assertNotNull(guard.activeLock(userId, CredentialScope.PASSWORD))
        assertNull(guard.activeLock(userId, CredentialScope.PIN))
    }

    @Test
    fun `parallel failures are all counted`() = runBlocking {
        val store = InMemoryAttemptStore()
        val guard = CredentialAttemptGuard(store, clock)
        val bigPolicy = policy.copy(consecutiveThreshold = 1000, maxTotalFailures = 1000)
        (1..50).map { async { guard.registerFailure(userId, CredentialScope.PIN, bigPolicy) } }.awaitAll()
        assertEquals(50, store.getPinAttemptState(userId, CredentialScope.PIN.key)?.failedAttempts)
    }

    @Test
    fun `max failures resets counter and success clears state`() = runBlocking {
        val store = InMemoryAttemptStore()
        val guard = CredentialAttemptGuard(store, clock)
        val strict = policy.copy(maxTotalFailures = 2)
        guard.registerFailure(userId, CredentialScope.PIN, strict)
        assertEquals(AttemptOutcome.MaxExceeded(2), guard.registerFailure(userId, CredentialScope.PIN, strict))
        assertNull(store.getPinAttemptState(userId, CredentialScope.PIN.key))

        guard.registerFailure(userId, CredentialScope.PIN, strict)
        guard.registerSuccess(userId, CredentialScope.PIN)
        assertNull(store.getPinAttemptState(userId, CredentialScope.PIN.key))
    }
}

/** Aturan yang sama dengan SQL di SystemRepository.registerPinFailure, untuk store palsu di test. */
internal fun nextFailureState(current: PinAttemptState?, policy: PinRolePolicy, now: OffsetDateTime): PinAttemptState {
    val failures = (current?.failedAttempts ?: 0) + 1
    val lock = listOfNotNull(current?.lockedUntil, CredentialAttemptGuard.lockFor(policy, failures, now)).maxOrNull()
    return PinAttemptState(failures, lock)
}

private class InMemoryAttemptStore : PinAttemptStore {
    private val states = ConcurrentHashMap<Pair<UUID, String>, PinAttemptState>()

    override suspend fun getPinAttemptState(userId: UUID, scopeKey: String) = states[userId to scopeKey]

    override suspend fun resetPinAttemptState(userId: UUID, scopeKey: String) {
        states.remove(userId to scopeKey)
    }

    override suspend fun resetAllPinAttemptStatesForUser(userId: UUID) {
        states.keys.removeIf { it.first == userId }
    }

    override suspend fun registerPinFailure(
        userId: UUID,
        scopeKey: String,
        policy: PinRolePolicy,
        now: OffsetDateTime
    ): PinAttemptState = states.compute(userId to scopeKey) { _, current -> nextFailureState(current, policy, now) }!!
}
