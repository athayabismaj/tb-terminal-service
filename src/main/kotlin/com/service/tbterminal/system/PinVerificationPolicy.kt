package com.service.tbterminal.system

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.EnvironmentConfig
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.TooManyAttemptsException
import com.service.tbterminal.shared.UnauthorizedException
import com.service.tbterminal.shared.ValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mindrot.jbcrypt.BCrypt
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

interface PinVerificationGateway : PinAttemptStore {
    suspend fun findPinUserById(userId: UUID): UserRow?
    suspend fun revokeSessionsForUser(userId: UUID): Boolean
    suspend fun recordPinAuditLog(
        actorUserId: UUID,
        action: AuditAction,
        tableName: String,
        recordId: UUID,
        ipAddress: String?,
        metadataJson: String
    )
}

object PinVerificationPolicy {
    const val MAX_DEVICE_ID_LENGTH = 128
    const val DEFAULT_DEVICE_ID = "default-device"
    private val DEVICE_ID_ALLOWED = Regex("[^A-Za-z0-9._:-]")
    val PIN_PATTERN = Regex("^[0-9]{4,6}$")

    private val CASHIER_POLICY = PinRolePolicy(
        consecutiveThreshold = 5,
        maxTotalFailures = 10,
        backoffSeconds = listOf(30L, 60L, 300L, 900L)
    )

    private val PRIVILEGED_POLICY = PinRolePolicy(
        consecutiveThreshold = 3,
        maxTotalFailures = 10,
        backoffSeconds = listOf(60L, 300L, 900L, 1800L)
    )

    /**
     * PIN approver dimasukkan oleh orang lain (biasanya kasir). Tidak ada "force logout"
     * karena itu akan memberi kasir cara mengeluarkan manager; sebagai gantinya kunci
     * terus memanjang hingga 30 menit per percobaan sampai PIN benar dimasukkan.
     */
    val MANAGER_APPROVAL_POLICY = PinRolePolicy(
        consecutiveThreshold = 3,
        maxTotalFailures = Int.MAX_VALUE,
        backoffSeconds = listOf(60L, 300L, 900L, 1800L)
    )

    fun forRole(role: String): PinRolePolicy {
        return when (role.trim().lowercase()) {
            Role.ADMIN, Role.OWNER -> PRIVILEGED_POLICY
            else -> CASHIER_POLICY
        }
    }

    /**
     * deviceId hanya dipakai sebagai metadata audit. Karakter di luar allowlist
     * diganti `_` agar aman disimpan/di-log dan tidak bisa merusak JSON audit.
     */
    fun normalizeDeviceId(rawDeviceId: String?): String {
        val trimmed = rawDeviceId?.trim()?.takeIf(String::isNotBlank) ?: return DEFAULT_DEVICE_ID
        return trimmed.take(MAX_DEVICE_ID_LENGTH).replace(DEVICE_ID_ALLOWED, "_")
    }

    /** Validasi format PIN tunggal yang dipakai di semua endpoint. */
    fun validatePinFormat(pin: String, fieldLabel: String = "PIN") {
        if (!pin.matches(PIN_PATTERN)) {
            throw ValidationException("$fieldLabel harus terdiri dari 4 sampai 6 digit angka")
        }
    }

    fun lockoutDurationSeconds(policy: PinRolePolicy, failedAttempts: Int): Long =
        CredentialAttemptGuard.lockoutDurationSeconds(policy, failedAttempts)
}

class PinVerificationService(
    private val gateway: PinVerificationGateway,
    private val clock: Clock = Clock.systemUTC()
) {
    private val guard = CredentialAttemptGuard(gateway, clock)

    suspend fun verifyUnlock(
        userId: UUID,
        request: UnlockRequest,
        headerDeviceId: String? = null,
        ipAddress: String? = null
    ): ApiResponse<PinUnlockResponse> {
        if (request.pin.isBlank()) throw ValidationException("PIN wajib diisi")

        val userRow = gateway.findPinUserById(userId) ?: throw NotFoundException("User tidak ditemukan")
        val policy = PinVerificationPolicy.forRole(userRow.roleName)
        val deviceId = PinVerificationPolicy.normalizeDeviceId(request.deviceId ?: headerDeviceId)

        if (!userRow.isActive) {
            gateway.revokeSessionsForUser(userId)
            return ApiResponse(
                success = false,
                data = PinUnlockResponse(valid = false, attemptsLeft = 0, lockedUntil = null, forceLogout = true),
                error = "Akun tidak aktif. Silakan login kembali.",
                code = "UNAUTHORIZED"
            )
        }

        guard.activeLock(userId, CredentialScope.PIN)?.let { lockedState ->
            val lockedUntil = requireNotNull(lockedState.lockedUntil)
            recordSecurityAudit(
                userId, AuditAction.UPDATE, "pin_unlock_locked", ipAddress, deviceId,
                lockedState.failedAttempts, attemptsLeft = 0, lockedUntil = lockedUntil
            )
            return ApiResponse(
                success = false,
                data = PinUnlockResponse(
                    valid = false,
                    attemptsLeft = 0,
                    lockedUntil = lockedUntil.toString(),
                    forceLogout = false
                ),
                error = "Terlalu banyak percobaan gagal. Tunggu hingga masa tunggu berakhir.",
                code = "PIN_LOCKED"
            )
        }

        val isValid = withContext(Dispatchers.IO) {
            BCrypt.checkpw(request.pin, userRow.pinHash)
        }

        if (isValid) {
            if (EnvironmentConfig.isProduction && request.pin in DEFAULT_PINS) {
                val currentFailures = guard.currentState(userId, CredentialScope.PIN).failedAttempts
                return ApiResponse(
                    success = false,
                    data = PinUnlockResponse(
                        valid = false,
                        attemptsLeft = (policy.consecutiveThreshold - currentFailures).coerceAtLeast(0),
                        lockedUntil = null,
                        forceLogout = false
                    ),
                    error = "PIN default tidak diizinkan pada production",
                    code = "DEFAULT_CREDENTIAL_FORBIDDEN"
                )
            }

            guard.registerSuccess(userId, CredentialScope.PIN)
            return ApiResponse.success(
                PinUnlockResponse(
                    valid = true,
                    attemptsLeft = policy.consecutiveThreshold,
                    lockedUntil = null,
                    forceLogout = false
                ),
                "Unlock berhasil"
            )
        }

        return when (val outcome = guard.registerFailure(userId, CredentialScope.PIN, policy)) {
            is AttemptOutcome.MaxExceeded -> {
                gateway.revokeSessionsForUser(userId)
                recordSecurityAudit(
                    userId, AuditAction.UPDATE, "pin_unlock_revoked", ipAddress, deviceId,
                    outcome.failedAttempts, attemptsLeft = 0, lockedUntil = null
                )
                ApiResponse(
                    success = false,
                    data = PinUnlockResponse(valid = false, attemptsLeft = 0, lockedUntil = null, forceLogout = true),
                    error = "Batas percobaan PIN terlampaui. Silakan login kembali.",
                    code = "PIN_MAX_ATTEMPTS_EXCEEDED"
                )
            }

            is AttemptOutcome.LockedNow -> {
                recordSecurityAudit(
                    userId, AuditAction.UPDATE, "pin_unlock_locked", ipAddress, deviceId,
                    outcome.failedAttempts, attemptsLeft = 0, lockedUntil = outcome.lockedUntil
                )
                ApiResponse(
                    success = false,
                    data = PinUnlockResponse(
                        valid = false,
                        attemptsLeft = 0,
                        lockedUntil = outcome.lockedUntil.toString(),
                        forceLogout = false
                    ),
                    error = "PIN salah",
                    code = "PIN_LOCKED"
                )
            }

            is AttemptOutcome.Invalid -> {
                recordSecurityAudit(
                    userId, AuditAction.INSERT, "pin_unlock_failed", ipAddress, deviceId,
                    outcome.failedAttempts, attemptsLeft = outcome.attemptsLeft, lockedUntil = null
                )
                ApiResponse(
                    success = false,
                    data = PinUnlockResponse(
                        valid = false,
                        attemptsLeft = outcome.attemptsLeft,
                        lockedUntil = null,
                        forceLogout = false
                    ),
                    error = "PIN salah",
                    code = "INVALID_PIN"
                )
            }
        }
    }

    /**
     * Memverifikasi PIN/password lama sebelum perubahan kredensial (ganti PIN,
     * reset PIN, ganti password). Memakai penghitung yang sama dengan unlock
     * sehingga endpoint ini tidak bisa dipakai untuk brute force tanpa batas.
     *
     * @throws TooManyAttemptsException bila sedang/baru saja terkunci.
     * @throws UnauthorizedException bila batas total terlampaui (semua sesi dicabut).
     * @throws ValidationException bila kredensial salah.
     */
    suspend fun verifyCredentialForSensitiveChange(
        userRow: UserRow,
        scope: CredentialScope,
        candidate: String,
        deviceId: String?,
        ipAddress: String?,
        invalidMessage: String
    ) {
        require(scope == CredentialScope.PIN || scope == CredentialScope.PASSWORD) {
            "Scope $scope tidak didukung untuk verifikasi kredensial milik sendiri"
        }
        val normalizedDeviceId = PinVerificationPolicy.normalizeDeviceId(deviceId)
        val auditPrefix = if (scope == CredentialScope.PIN) "pin_change" else "password_check"

        guard.activeLock(userRow.id, scope)?.let { lockedState ->
            throw lockedException(requireNotNull(lockedState.lockedUntil))
        }

        val hash = if (scope == CredentialScope.PIN) userRow.pinHash else userRow.passwordHash
        val isValid = candidate.isNotEmpty() && withContext(Dispatchers.IO) {
            runCatching { BCrypt.checkpw(candidate, hash) }.getOrDefault(false)
        }
        if (isValid) {
            guard.registerSuccess(userRow.id, scope)
            return
        }

        when (val outcome = guard.registerFailure(userRow.id, scope, PinVerificationPolicy.forRole(userRow.roleName))) {
            is AttemptOutcome.MaxExceeded -> {
                gateway.revokeSessionsForUser(userRow.id)
                recordSecurityAudit(
                    userRow.id, AuditAction.UPDATE, "${auditPrefix}_revoked", ipAddress, normalizedDeviceId,
                    outcome.failedAttempts, attemptsLeft = 0, lockedUntil = null
                )
                throw UnauthorizedException("Batas percobaan terlampaui. Silakan login kembali.")
            }

            is AttemptOutcome.LockedNow -> {
                recordSecurityAudit(
                    userRow.id, AuditAction.UPDATE, "${auditPrefix}_locked", ipAddress, normalizedDeviceId,
                    outcome.failedAttempts, attemptsLeft = 0, lockedUntil = outcome.lockedUntil
                )
                throw lockedException(outcome.lockedUntil)
            }

            is AttemptOutcome.Invalid -> {
                recordSecurityAudit(
                    userRow.id, AuditAction.INSERT, "${auditPrefix}_failed", ipAddress, normalizedDeviceId,
                    outcome.failedAttempts, attemptsLeft = outcome.attemptsLeft, lockedUntil = null
                )
                throw ValidationException(invalidMessage)
            }
        }
    }

    private fun lockedException(lockedUntil: OffsetDateTime): TooManyAttemptsException {
        val retryAfter = Duration.between(guard.now(), lockedUntil).seconds.coerceAtLeast(1)
        return TooManyAttemptsException(
            message = "Terlalu banyak percobaan gagal. Coba lagi setelah $lockedUntil",
            retryAfterSeconds = retryAfter
        )
    }

    private suspend fun recordSecurityAudit(
        userId: UUID,
        action: AuditAction,
        tableName: String,
        ipAddress: String?,
        deviceId: String,
        failedAttempts: Int,
        attemptsLeft: Int,
        lockedUntil: OffsetDateTime?
    ) {
        val metadata = buildJsonObject {
            put("deviceId", deviceId)
            put("failedAttempts", failedAttempts)
            put("attemptsLeft", attemptsLeft)
            if (lockedUntil != null) put("lockedUntil", lockedUntil.toString()) else put("lockedUntil", JsonNull)
        }.toString()
        gateway.recordPinAuditLog(
            actorUserId = userId,
            action = action,
            tableName = tableName,
            recordId = userId,
            ipAddress = ipAddress,
            metadataJson = metadata
        )
    }

    private companion object {
        val DEFAULT_PINS = setOf("1234", "123456", "0000", "000000")
    }
}
