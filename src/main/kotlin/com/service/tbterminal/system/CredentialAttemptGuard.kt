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
    suspend fun resetAllPinAttemptStatesForUser(userId: UUID)

    /** Menghapus penghitung pada [scopeKeys] saja, mis. lingkup yang kredensialnya baru diganti. */
    suspend fun resetPinAttemptStates(userId: UUID, scopeKeys: Collection<String>)

    /**
     * Mencatat satu percobaan sebagai gagal SEBELUM kredensial diperiksa, dalam satu operasi
     * atomik: bila lingkup sedang terkunci hasilnya [AttemptReservation.Locked] dan hitungan
     * tidak berubah; bila tidak, hitungan naik dan kunci dipasang begitu ambang [policy] tercapai.
     */
    suspend fun reservePinAttempt(
        userId: UUID,
        scopeKey: String,
        policy: PinRolePolicy,
        now: OffsetDateTime
    ): AttemptReservation

    /**
     * Mereset hitungan setelah percobaan [reserved] terbukti sah. Kunci aktif yang dipasang
     * percobaan lain tidak dihapus; kunci yang dipasang [reserved] sendiri boleh.
     */
    suspend fun releasePinAttempt(
        userId: UUID,
        scopeKey: String,
        reserved: PinAttemptState,
        now: OffsetDateTime
    )
}

sealed interface AttemptReservation {
    /** Percobaan boleh memeriksa kredensial. [state] sudah memuat percobaan ini sebagai kegagalan. */
    data class Granted(val state: PinAttemptState) : AttemptReservation

    /** Lingkup sedang terkunci; kredensial tidak boleh diperiksa. */
    data class Locked(val failedAttempts: Int, val lockedUntil: OffsetDateTime) : AttemptReservation
}

sealed interface AttemptOutcome {
    data class Invalid(val failedAttempts: Int, val attemptsLeft: Int) : AttemptOutcome
    data class LockedNow(val failedAttempts: Int, val lockedUntil: OffsetDateTime) : AttemptOutcome
    data class MaxExceeded(val failedAttempts: Int) : AttemptOutcome
}

/**
 * Mesin status lockout bersama untuk semua verifikasi kredensial (PIN, password,
 * PIN approver). Tidak bergantung pada database maupun Ktor sehingga mudah diuji.
 *
 * Urutan wajib: [reserve] → periksa kredensial → [release] bila benar, atau
 * [failureOutcome] bila salah. Reservasi yang tidak diselesaikan (bcrypt gagal, request
 * dibatalkan) tetap terhitung sebagai kegagalan.
 */
class CredentialAttemptGuard(
    private val store: PinAttemptStore,
    private val clock: Clock = Clock.systemUTC()
) {
    fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    suspend fun reserve(userId: UUID, scope: CredentialScope, policy: PinRolePolicy): AttemptReservation =
        store.reservePinAttempt(userId, scope.key, policy, now())

    /** Hasil percobaan salah menurut reservasinya; tidak mengubah state (sudah dicatat [reserve]). */
    fun failureOutcome(reservation: AttemptReservation.Granted, policy: PinRolePolicy): AttemptOutcome {
        val state = reservation.state
        val failures = state.failedAttempts
        if (failures >= policy.maxTotalFailures) return AttemptOutcome.MaxExceeded(failures)
        if (failures >= policy.consecutiveThreshold) {
            val lockedUntil = requireNotNull(state.lockedUntil) { "lockedUntil wajib ada setelah ambang terlampaui" }
            return AttemptOutcome.LockedNow(failures, lockedUntil)
        }
        return AttemptOutcome.Invalid(failures, (policy.consecutiveThreshold - failures).coerceAtLeast(0))
    }

    /**
     * Memulai hitungan baru: kredensial benar, atau [AttemptOutcome.MaxExceeded] dan semua sesi
     * sudah dicabut. Untuk kasus kedua, panggil SETELAH pencabutan supaya kunci reservasi
     * terakhir menahan request paralel selama pencabutan.
     */
    suspend fun release(userId: UUID, scope: CredentialScope, reservation: AttemptReservation.Granted) {
        store.releasePinAttempt(userId, scope.key, reservation.state, now())
    }

    companion object {
        fun lockoutDurationSeconds(policy: PinRolePolicy, failedAttempts: Int): Long {
            val index = (failedAttempts - policy.consecutiveThreshold)
                .coerceIn(0, policy.backoffSeconds.lastIndex)
            return policy.backoffSeconds[index]
        }

        /** Kunci untuk kegagalan ke-[failedAttempts], atau `null` bila ambang belum tercapai. */
        fun lockFor(policy: PinRolePolicy, failedAttempts: Int, now: OffsetDateTime): OffsetDateTime? =
            if (failedAttempts < policy.consecutiveThreshold) null
            else now.plusSeconds(lockoutDurationSeconds(policy, failedAttempts))
    }
}
