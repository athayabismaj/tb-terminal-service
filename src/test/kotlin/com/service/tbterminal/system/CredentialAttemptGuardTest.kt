package com.service.tbterminal.system

import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialAttemptGuardTest {
    private val userId = UUID.fromString("44444444-4444-4444-4444-444444444444")
    private val clock = Clock.fixed(Instant.parse("2026-11-24T05:00:00Z"), ZoneOffset.UTC)
    private val policy = PinRolePolicy(consecutiveThreshold = 3, maxTotalFailures = 10, backoffSeconds = listOf(60L, 300L))

    @Test
    fun `failures below threshold report attempts left`() = runBlocking {
        val store = InMemoryAttemptStore()
        val guard = CredentialAttemptGuard(store, clock)
        assertEquals(AttemptOutcome.Invalid(1, 2), guard.fail(CredentialScope.PIN))
        assertEquals(AttemptOutcome.Invalid(2, 1), guard.fail(CredentialScope.PIN))
        assertNull(store.getPinAttemptState(userId, CredentialScope.PIN.key)?.lockedUntil)
    }

    @Test
    fun `reaching threshold locks with backoff and denies the next reservation`() = runBlocking {
        val guard = CredentialAttemptGuard(InMemoryAttemptStore(), clock)
        repeat(2) { guard.fail(CredentialScope.PIN) }
        val lockedUntil = OffsetDateTime.ofInstant(clock.instant().plusSeconds(60), ZoneOffset.UTC)
        assertEquals(AttemptOutcome.LockedNow(3, lockedUntil), guard.fail(CredentialScope.PIN))
        assertEquals(
            AttemptReservation.Locked(3, lockedUntil),
            guard.reserve(userId, CredentialScope.PIN, policy)
        )
    }

    @Test
    fun `scopes are counted independently`() = runBlocking {
        val guard = CredentialAttemptGuard(InMemoryAttemptStore(), clock)
        repeat(3) { guard.fail(CredentialScope.PASSWORD) }
        assertTrue(guard.reserve(userId, CredentialScope.PASSWORD, policy) is AttemptReservation.Locked)
        assertTrue(guard.reserve(userId, CredentialScope.PIN, policy) is AttemptReservation.Granted)
    }

    @Test
    fun `parallel reservations never exceed the threshold`() = runBlocking {
        val store = InMemoryAttemptStore()
        val guard = CredentialAttemptGuard(store, clock)
        val results = (1..50).map {
            async(Dispatchers.IO) { guard.reserve(userId, CredentialScope.PIN, policy) }
        }.awaitAll()
        assertEquals(3, results.count { it is AttemptReservation.Granted })
        assertEquals(47, results.count { it is AttemptReservation.Locked })
        assertEquals(3, store.getPinAttemptState(userId, CredentialScope.PIN.key)?.failedAttempts)
    }

    @Test
    fun `release keeps a lock set by another attempt but clears its own`() = runBlocking {
        val store = InMemoryAttemptStore()
        val guard = CredentialAttemptGuard(store, clock)
        val reservations = (1..3).map { guard.granted(CredentialScope.PIN) }

        guard.release(userId, CredentialScope.PIN, reservations.first())
        assertTrue(guard.reserve(userId, CredentialScope.PIN, policy) is AttemptReservation.Locked)

        guard.release(userId, CredentialScope.PIN, reservations.last())
        assertNull(store.getPinAttemptState(userId, CredentialScope.PIN.key))
    }

    @Test
    fun `release without active lock clears the counter`() = runBlocking {
        val store = InMemoryAttemptStore()
        val guard = CredentialAttemptGuard(store, clock)
        val first = guard.granted(CredentialScope.PIN)
        guard.granted(CredentialScope.PIN)
        guard.release(userId, CredentialScope.PIN, first)
        assertNull(store.getPinAttemptState(userId, CredentialScope.PIN.key))
    }

    @Test
    fun `max failures is reported without clearing the counter`() = runBlocking {
        val store = InMemoryAttemptStore()
        val guard = CredentialAttemptGuard(store, clock)
        val strict = policy.copy(maxTotalFailures = 2)
        guard.fail(CredentialScope.PIN, strict)
        assertEquals(AttemptOutcome.MaxExceeded(2), guard.fail(CredentialScope.PIN, strict))
        assertEquals(2, store.getPinAttemptState(userId, CredentialScope.PIN.key)?.failedAttempts)
    }

    private suspend fun CredentialAttemptGuard.granted(
        scope: CredentialScope,
        rolePolicy: PinRolePolicy = policy
    ): AttemptReservation.Granted = reserve(userId, scope, rolePolicy) as AttemptReservation.Granted

    private suspend fun CredentialAttemptGuard.fail(
        scope: CredentialScope,
        rolePolicy: PinRolePolicy = policy
    ): AttemptOutcome = failureOutcome(granted(scope, rolePolicy), rolePolicy)
}

/** Store palsu dengan aturan yang sama dengan SQL di SystemRepository (reserve/release). */
internal class InMemoryAttemptStore : PinAttemptStore {
    val states = ConcurrentHashMap<Pair<UUID, String>, PinAttemptState>()

    override suspend fun getPinAttemptState(userId: UUID, scopeKey: String) = states[userId to scopeKey]

    override suspend fun resetAllPinAttemptStatesForUser(userId: UUID) {
        states.keys.removeIf { it.first == userId }
    }

    override suspend fun resetPinAttemptStates(userId: UUID, scopeKeys: Collection<String>) {
        states.keys.removeIf { it.first == userId && it.second in scopeKeys }
    }

    override suspend fun reservePinAttempt(
        userId: UUID,
        scopeKey: String,
        policy: PinRolePolicy,
        now: OffsetDateTime
    ): AttemptReservation {
        var result: AttemptReservation? = null
        states.compute(userId to scopeKey) { _, current ->
            val activeLock = current?.lockedUntil?.takeIf { it.isAfter(now) }
            if (activeLock != null) {
                result = AttemptReservation.Locked(current.failedAttempts, activeLock)
                current
            } else {
                val failures = (current?.failedAttempts ?: 0) + 1
                PinAttemptState(failures, CredentialAttemptGuard.lockFor(policy, failures, now) ?: current?.lockedUntil)
                    .also { result = AttemptReservation.Granted(it) }
            }
        }
        return requireNotNull(result)
    }

    override suspend fun releasePinAttempt(
        userId: UUID,
        scopeKey: String,
        reserved: PinAttemptState,
        now: OffsetDateTime
    ) {
        states.computeIfPresent(userId to scopeKey) { _, current ->
            val lockedByOther = current.lockedUntil?.isAfter(now) == true && current != reserved
            if (lockedByOther) current else null
        }
    }
}
