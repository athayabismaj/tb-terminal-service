package com.service.tbterminal.system

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class PinAttemptState(
    val failedAttempts: Int = 0,
    val lockedUntil: OffsetDateTime? = null
)

data class PinRolePolicy(
    val consecutiveThreshold: Int,
    val maxTotalFailures: Int,
    val backoffSeconds: List<Long>
)

/**
 * Lingkup penghitung percobaan gagal. Disimpan pada kolom `device_id` tabel
 * `system.pin_verification_attempts` dengan prefiks `scope:`.
 *
 * Penghitung sengaja TIDAK lagi dipisah per perangkat: `deviceId` dikirim oleh
 * klien sehingga bisa diganti-ganti untuk mereset hitungan.
 */
enum class CredentialScope(val key: String) {
    /** Verifikasi PIN milik user sendiri (unlock layar, ganti PIN). */
    PIN("scope:pin"),

    /** Verifikasi password milik user sendiri (reset PIN, ganti password). */
    PASSWORD("scope:password"),

    /** PIN manager yang dimasukkan orang lain untuk approval. Dihitung per approver. */
    MANAGER_APPROVAL("scope:manager-approval")
}

interface PinAttemptStore {
    suspend fun getPinAttemptState(userId: UUID, scopeKey: String): PinAttemptState?
    suspend fun savePinAttemptState(userId: UUID, scopeKey: String, failedAttempts: Int, lockedUntil: OffsetDateTime?)
    suspend fun resetPinAttemptState(userId: UUID, scopeKey: String)
    suspend fun resetAllPinAttemptStatesForUser(userId: UUID)

    /**
     * Menambah satu kegagalan secara atomik (aman untuk request paralel) dan
     * mengembalikan jumlah kegagalan terbaru.
     */
    suspend fun incrementPinFailure(userId: UUID, scopeKey: String): Int
}

sealed interface AttemptOutcome {
    data class Invalid(val failedAttempts: Int, val attemptsLeft: Int) : AttemptOutcome
    data class LockedNow(val failedAttempts: Int, val lockedUntil: OffsetDateTime) : AttemptOutcome
    data class MaxExceeded(val failedAttempts: Int) : AttemptOutcome
}

/**
 * Mesin status lockout bersama untuk semua verifikasi kredensial (PIN, password,
 * PIN approver). Tidak bergantung pada database maupun Ktor sehingga mudah diuji.
 */
class CredentialAttemptGuard(
    private val store: PinAttemptStore,
    private val clock: Clock = Clock.systemUTC()
) {
    fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    /** State saat ini bila lingkup tersebut sedang terkunci, selain itu `null`. */
    suspend fun activeLock(userId: UUID, scope: CredentialScope): PinAttemptState? {
        val state = store.getPinAttemptState(userId, scope.key) ?: return null
        val lockedUntil = state.lockedUntil ?: return null
        return if (lockedUntil.isAfter(now())) state else null
    }

    suspend fun currentState(userId: UUID, scope: CredentialScope): PinAttemptState =
        store.getPinAttemptState(userId, scope.key) ?: PinAttemptState()

    suspend fun registerFailure(userId: UUID, scope: CredentialScope, policy: PinRolePolicy): AttemptOutcome {
        val failures = store.incrementPinFailure(userId, scope.key)
        if (failures >= policy.maxTotalFailures) {
            store.resetPinAttemptState(userId, scope.key)
            return AttemptOutcome.MaxExceeded(failures)
        }
        if (failures >= policy.consecutiveThreshold) {
            val lockedUntil = now().plusSeconds(lockoutDurationSeconds(policy, failures))
            store.savePinAttemptState(userId, scope.key, failures, lockedUntil)
            return AttemptOutcome.LockedNow(failures, lockedUntil)
        }
        return AttemptOutcome.Invalid(failures, (policy.consecutiveThreshold - failures).coerceAtLeast(0))
    }

    suspend fun registerSuccess(userId: UUID, scope: CredentialScope) {
        store.resetPinAttemptState(userId, scope.key)
    }

    companion object {
        fun lockoutDurationSeconds(policy: PinRolePolicy, failedAttempts: Int): Long {
            val index = (failedAttempts - policy.consecutiveThreshold)
                .coerceIn(0, policy.backoffSeconds.lastIndex)
            return policy.backoffSeconds[index]
        }
    }
}
