package com.service.tbterminal.system

import com.service.tbterminal.shared.EnvironmentConfig
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.ValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt
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

interface PinAttemptStore {
    suspend fun getPinAttemptState(userId: UUID, deviceId: String): PinAttemptState?
    suspend fun savePinAttemptState(userId: UUID, deviceId: String, failedAttempts: Int, lockedUntil: OffsetDateTime?)
    suspend fun resetPinAttemptState(userId: UUID, deviceId: String)
    suspend fun resetAllPinAttemptStatesForUser(userId: UUID)
}

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

    fun forRole(role: String): PinRolePolicy {
        return when (role.trim().lowercase()) {
            Role.ADMIN, Role.OWNER -> PRIVILEGED_POLICY
            else -> CASHIER_POLICY
        }
    }

    fun normalizeDeviceId(rawDeviceId: String?): String {
        val trimmed = rawDeviceId?.trim()?.takeIf(String::isNotBlank) ?: return DEFAULT_DEVICE_ID
        return trimmed.take(MAX_DEVICE_ID_LENGTH)
    }

    fun lockoutDurationSeconds(policy: PinRolePolicy, failedAttempts: Int): Long {
        val index = (failedAttempts - policy.consecutiveThreshold)
            .coerceIn(0, policy.backoffSeconds.lastIndex)
        return policy.backoffSeconds[index]
    }
}

class PinVerificationService(
    private val gateway: PinVerificationGateway,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun verifyUnlock(
        userId: UUID,
        request: UnlockRequest,
        headerDeviceId: String? = null,
        ipAddress: String? = null
    ): com.service.tbterminal.shared.ApiResponse<PinUnlockResponse> {
        if (request.pin.isBlank()) throw ValidationException("PIN wajib diisi")

        val userRow = gateway.findPinUserById(userId) ?: throw NotFoundException("User tidak ditemukan")
        val policy = PinVerificationPolicy.forRole(userRow.roleName)
        val deviceId = PinVerificationPolicy.normalizeDeviceId(request.deviceId ?: headerDeviceId)

        if (!userRow.isActive) {
            gateway.revokeSessionsForUser(userId)
            return com.service.tbterminal.shared.ApiResponse(
                success = false,
                data = PinUnlockResponse(valid = false, attemptsLeft = 0, lockedUntil = null, forceLogout = true),
                error = "Akun tidak aktif. Silakan login kembali.",
                code = "UNAUTHORIZED"
            )
        }

        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val currentState = gateway.getPinAttemptState(userId, deviceId) ?: PinAttemptState()

        val activeLockUntil = currentState.lockedUntil
        if (activeLockUntil != null && activeLockUntil.isAfter(now)) {
            recordPinSecurityAudit(
                userId = userId,
                action = AuditAction.UPDATE,
                tableName = "pin_unlock_locked",
                ipAddress = ipAddress,
                deviceId = deviceId,
                failedAttempts = currentState.failedAttempts,
                attemptsLeft = 0,
                lockedUntil = activeLockUntil
            )
            return com.service.tbterminal.shared.ApiResponse(
                success = false,
                data = PinUnlockResponse(
                    valid = false,
                    attemptsLeft = 0,
                    lockedUntil = activeLockUntil.toString(),
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
                return com.service.tbterminal.shared.ApiResponse(
                    success = false,
                    data = PinUnlockResponse(
                        valid = false,
                        attemptsLeft = (policy.consecutiveThreshold - currentState.failedAttempts).coerceAtLeast(0),
                        lockedUntil = null,
                        forceLogout = false
                    ),
                    error = "PIN default tidak diizinkan pada production",
                    code = "DEFAULT_CREDENTIAL_FORBIDDEN"
                )
            }

            gateway.resetPinAttemptState(userId, deviceId)
            return com.service.tbterminal.shared.ApiResponse.success(
                PinUnlockResponse(
                    valid = true,
                    attemptsLeft = policy.consecutiveThreshold,
                    lockedUntil = null,
                    forceLogout = false
                ),
                "Unlock berhasil"
            )
        }

        val nextFailures = currentState.failedAttempts + 1
        if (nextFailures >= policy.maxTotalFailures) {
            gateway.revokeSessionsForUser(userId)
            gateway.resetPinAttemptState(userId, deviceId)
            recordPinSecurityAudit(
                userId = userId,
                action = AuditAction.UPDATE,
                tableName = "pin_unlock_revoked",
                ipAddress = ipAddress,
                deviceId = deviceId,
                failedAttempts = nextFailures,
                attemptsLeft = 0,
                lockedUntil = null
            )
            return com.service.tbterminal.shared.ApiResponse(
                success = false,
                data = PinUnlockResponse(
                    valid = false,
                    attemptsLeft = 0,
                    lockedUntil = null,
                    forceLogout = true
                ),
                error = "Batas percobaan PIN terlampaui. Silakan login kembali.",
                code = "PIN_MAX_ATTEMPTS_EXCEEDED"
            )
        }

        if (nextFailures >= policy.consecutiveThreshold) {
            val lockSeconds = PinVerificationPolicy.lockoutDurationSeconds(policy, nextFailures)
            val lockedUntil = now.plusSeconds(lockSeconds)
            gateway.savePinAttemptState(
                userId = userId,
                deviceId = deviceId,
                failedAttempts = nextFailures,
                lockedUntil = lockedUntil
            )
            recordPinSecurityAudit(
                userId = userId,
                action = AuditAction.UPDATE,
                tableName = "pin_unlock_locked",
                ipAddress = ipAddress,
                deviceId = deviceId,
                failedAttempts = nextFailures,
                attemptsLeft = 0,
                lockedUntil = lockedUntil
            )
            return com.service.tbterminal.shared.ApiResponse(
                success = false,
                data = PinUnlockResponse(
                    valid = false,
                    attemptsLeft = 0,
                    lockedUntil = lockedUntil.toString(),
                    forceLogout = false
                ),
                error = "PIN salah",
                code = "PIN_LOCKED"
            )
        }

        val attemptsLeft = (policy.consecutiveThreshold - nextFailures).coerceAtLeast(0)
        gateway.savePinAttemptState(
            userId = userId,
            deviceId = deviceId,
            failedAttempts = nextFailures,
            lockedUntil = null
        )
        recordPinSecurityAudit(
            userId = userId,
            action = AuditAction.INSERT,
            tableName = "pin_unlock_failed",
            ipAddress = ipAddress,
            deviceId = deviceId,
            failedAttempts = nextFailures,
            attemptsLeft = attemptsLeft,
            lockedUntil = null
        )
        return com.service.tbterminal.shared.ApiResponse(
            success = false,
            data = PinUnlockResponse(
                valid = false,
                attemptsLeft = attemptsLeft,
                lockedUntil = null,
                forceLogout = false
            ),
            error = "PIN salah",
            code = "INVALID_PIN"
        )
    }

    private suspend fun recordPinSecurityAudit(
        userId: UUID,
        action: AuditAction,
        tableName: String,
        ipAddress: String?,
        deviceId: String,
        failedAttempts: Int,
        attemptsLeft: Int,
        lockedUntil: OffsetDateTime?
    ) {
        val escapedDeviceId = deviceId.replace("\\", "\\\\").replace("\"", "\\\"")
        val lockedField = lockedUntil?.let { "\"$it\"" } ?: "null"
        val metadata = """{"deviceId":"$escapedDeviceId","failedAttempts":$failedAttempts,"attemptsLeft":$attemptsLeft,"lockedUntil":$lockedField}"""
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
