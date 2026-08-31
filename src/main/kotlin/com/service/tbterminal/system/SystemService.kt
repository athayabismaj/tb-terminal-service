package com.service.tbterminal.system

import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.AccessPolicy
import com.service.tbterminal.shared.EnvironmentConfig
import com.service.tbterminal.shared.JwtHelper
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.Permission
import com.service.tbterminal.shared.ValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

class SystemService(private val repo: SystemRepository) {

    suspend fun login(req: LoginRequest): ApiResponse<LoginResponse> {
        val username = req.username.trim()
        if (username.isBlank() || req.password.isBlank()) {
            throw ValidationException("Username dan password wajib diisi")
        }
        if (EnvironmentConfig.isProduction && req.password in DEFAULT_PASSWORDS) {
            return ApiResponse.error("Kredensial default tidak diizinkan pada production", "DEFAULT_CREDENTIAL_FORBIDDEN")
        }

        val user = repo.findUserByUsername(username)
        if (user == null) {
            return ApiResponse.error("Username atau Password salah", "UNAUTHORIZED")
        }

        if (!user.isActive) {
            return ApiResponse.error("Akun tidak aktif", "UNAUTHORIZED")
        }

        // WAJIB: BCrypt di Dispatchers.IO
        val isValid = withContext(Dispatchers.IO) {
            BCrypt.checkpw(req.password, user.passwordHash)
        }

        if (!isValid) {
            return ApiResponse.error("Username atau Password salah", "UNAUTHORIZED")
        }

        repo.updateLastLogin(user.id)

        val token = JwtHelper.generateAccessToken(user)
        val refreshToken = JwtHelper.generateRefreshToken(user)
        return ApiResponse.success(
            LoginResponse(token = token, refreshToken = refreshToken, user = UserDto.from(user)),
            "Login berhasil"
        )
    }

    suspend fun refresh(request: RefreshTokenRequest): ApiResponse<RefreshTokenResponse> {
        if (request.refreshToken.isBlank()) {
            throw ValidationException("Refresh token wajib diisi")
        }

        val decoded = runCatching { JwtHelper.verifyRefreshToken(request.refreshToken) }
            .getOrElse { return ApiResponse.error("Refresh token tidak valid atau telah berakhir", "UNAUTHORIZED") }
        val userId = decoded.subject
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return ApiResponse.error("Refresh token tidak valid", "UNAUTHORIZED")
        val tokenVersion = decoded.getClaim(JwtHelper.TOKEN_VERSION_CLAIM).asInt()
            ?: return ApiResponse.error("Refresh token tidak valid", "UNAUTHORIZED")
        val user = repo.findUserById(userId)
            ?: return ApiResponse.error("Refresh token tidak valid", "UNAUTHORIZED")

        if (!user.isActive || user.tokenVersion != tokenVersion) {
            return ApiResponse.error("Sesi telah berakhir", "UNAUTHORIZED")
        }

        return ApiResponse.success(
            RefreshTokenResponse(
                token = JwtHelper.generateAccessToken(user),
                refreshToken = JwtHelper.generateRefreshToken(user)
            ),
            "Token berhasil diperbarui"
        )
    }

    suspend fun logout(userId: UUID) {
        if (!repo.incrementTokenVersion(userId)) {
            throw NotFoundException("User tidak ditemukan")
        }
    }

    suspend fun unlock(userId: UUID, request: UnlockRequest): ApiResponse<Unit> {
        if (request.pin.isBlank()) throw ValidationException("PIN wajib diisi")
        if (EnvironmentConfig.isProduction && request.pin in DEFAULT_PINS) {
            return ApiResponse.error("PIN default tidak diizinkan pada production", "DEFAULT_CREDENTIAL_FORBIDDEN")
        }
        val userResponse = repo.getUserById(userId) ?: throw NotFoundException("User tidak ditemukan")
        val userRow = repo.findUserByUsername(userResponse.username) ?: throw NotFoundException("User tidak ditemukan")

        if (!userRow.isActive) {
            return ApiResponse.error("Akun tidak aktif", "UNAUTHORIZED")
        }

        val isValid = withContext(Dispatchers.IO) {
            BCrypt.checkpw(request.pin, userRow.pinHash)
        }

        if (!isValid) {
            return ApiResponse.error("PIN salah", "UNAUTHORIZED")
        }

        return ApiResponse.success(Unit, "Unlock berhasil")
    }

    suspend fun getRoles(actorRole: String): List<RoleResponse> {
        AccessPolicy.require(actorRole, Permission.MANAGE_ROLES)
        return repo.getRoles()
    }

    suspend fun getUsers(actorRole: String, page: Int, limit: Int, search: String?): PaginatedResponse<UserResponse> {
        AccessPolicy.require(actorRole, Permission.MANAGE_USERS)
        val safePage = if (page < 1) 1 else page
        val safeLimit = limit.coerceIn(1, 100)
        return repo.getPaginatedUsers(safePage, safeLimit, search)
    }

    suspend fun getAuditLogs(actorRole: String, page: Int, limit: Int, action: String?, range: String?): PaginatedResponse<AuditLogResponse> {
        AccessPolicy.require(actorRole, Permission.VIEW_AUDIT_LOG)
        val safePage = if (page < 1) 1 else page
        val safeLimit = limit.coerceIn(1, 100)
        val auditAction = action
            ?.takeIf { it.isNotBlank() }
            ?.let { rawAction ->
                runCatching { AuditAction.valueOf(rawAction.uppercase()) }
                    .getOrElse { throw ValidationException("Tipe aktivitas tidak valid") }
            }
        val since = range.toAuditRangeStart()

        return repo.getPaginatedAuditLogs(safePage, safeLimit, auditAction, since)
    }

    suspend fun getUserById(actorRole: String, id: String): UserResponse {
        AccessPolicy.require(actorRole, Permission.MANAGE_USERS)
        val uuid = parseUUID(id)
        return repo.getUserById(uuid) ?: throw NotFoundException("User tidak ditemukan")
    }

    suspend fun createUser(actorRole: String, request: UserCreateRequest): UserResponse {
        AccessPolicy.require(actorRole, Permission.MANAGE_USERS)
        validateUserIdentity(request.name, request.username, request.email)
        if (request.password.isBlank()) throw ValidationException("Password tidak boleh kosong")
        if (request.password.length < 6) throw ValidationException("Password minimal 6 karakter")
        if (request.pin.isBlank()) throw ValidationException("PIN tidak boleh kosong")
        if (request.pin.length < 4) throw ValidationException("PIN minimal 4 karakter")
        validateProductionCredentials(request.password, request.pin)

        val existingUser = repo.findUserByUsername(request.username.trim())
        if (existingUser != null) {
            throw ValidationException("Username '${request.username}' sudah digunakan")
        }

        val roleId = parseUUID(request.roleId)
        
        val hashedPassword = withContext(Dispatchers.IO) {
            BCrypt.hashpw(request.password, BCrypt.gensalt())
        }

        val hashedPin = withContext(Dispatchers.IO) {
            BCrypt.hashpw(request.pin, BCrypt.gensalt())
        }

        val newId = repo.createUser(request.name.trim(), request.username.trim(), hashedPassword, hashedPin, request.email?.trim(), roleId)
        return repo.getUserById(newId)!!
    }

    suspend fun updateUser(actorRole: String, id: String, request: UserUpdateRequest): UserResponse {
        AccessPolicy.require(actorRole, Permission.MANAGE_USERS)
        val uuid = parseUUID(id)
        val existingUser = repo.getUserById(uuid) ?: throw NotFoundException("User tidak ditemukan")

        validateUserIdentity(request.name, request.username, request.email)

        // Cek username duplikat (kecuali punya sendiri)
        if (request.username.trim() != existingUser.username) {
            val userWithSameUsername = repo.findUserByUsername(request.username.trim())
            if (userWithSameUsername != null) {
                throw ValidationException("Username '${request.username}' sudah digunakan")
            }
        }

        val roleId = parseUUID(request.roleId)

        var newHashedPassword: String? = null
        if (!request.newPassword.isNullOrBlank()) {
            if (request.newPassword.length < 6) throw ValidationException("Password minimal 6 karakter")
            validateProductionCredentials(request.newPassword, null)
            newHashedPassword = withContext(Dispatchers.IO) {
                BCrypt.hashpw(request.newPassword, BCrypt.gensalt())
            }
        }

        var newHashedPin: String? = null
        if (!request.newPin.isNullOrBlank()) {
            if (request.newPin.length < 4) throw ValidationException("PIN minimal 4 karakter")
            validateProductionCredentials(null, request.newPin)
            newHashedPin = withContext(Dispatchers.IO) {
                BCrypt.hashpw(request.newPin, BCrypt.gensalt())
            }
        }

        repo.updateUser(uuid, request.name.trim(), request.username.trim(), roleId, request.isActive, request.email?.trim(), newHashedPassword, newHashedPin)
        return repo.getUserById(uuid)!!
    }

    suspend fun deleteUser(actorRole: String, id: String) {
        AccessPolicy.require(actorRole, Permission.MANAGE_USERS)
        val uuid = parseUUID(id)
        repo.getUserById(uuid) ?: throw NotFoundException("User tidak ditemukan")
        repo.softDeleteUser(uuid)
    }

    suspend fun changeMyPassword(userId: UUID, request: ChangePasswordRequest) {
        if (request.newPassword.isBlank() || request.newPassword.length < 6) {
            throw ValidationException("Password baru minimal 6 karakter")
        }
        validateProductionCredentials(request.newPassword, null)

        val userResponse = repo.getUserById(userId) ?: throw NotFoundException("User tidak ditemukan")
        val userRow = repo.findUserByUsername(userResponse.username) ?: throw NotFoundException("User tidak ditemukan")

        val isOldPasswordValid = withContext(Dispatchers.IO) {
            BCrypt.checkpw(request.oldPassword, userRow.passwordHash)
        }

        if (!isOldPasswordValid) {
            throw ValidationException("Password lama tidak valid")
        }

        val newHashedPassword = withContext(Dispatchers.IO) {
            BCrypt.hashpw(request.newPassword, BCrypt.gensalt())
        }

        repo.updatePassword(userId, newHashedPassword)
    }

    suspend fun changeMyPin(userId: UUID, request: ChangePinRequest) {
        if (request.newPin.isBlank() || request.newPin.length < 4) {
            throw ValidationException("PIN baru minimal 4 karakter")
        }
        validateProductionCredentials(null, request.newPin)

        val userResponse = repo.getUserById(userId) ?: throw NotFoundException("User tidak ditemukan")
        val userRow = repo.findUserByUsername(userResponse.username) ?: throw NotFoundException("User tidak ditemukan")

        val isOldPinValid = withContext(Dispatchers.IO) {
            BCrypt.checkpw(request.oldPin, userRow.pinHash)
        }

        if (!isOldPinValid) {
            throw ValidationException("PIN lama tidak valid")
        }

        val newHashedPin = withContext(Dispatchers.IO) {
            BCrypt.hashpw(request.newPin, BCrypt.gensalt())
        }

        repo.updatePin(userId, newHashedPin)
    }

    private fun parseUUID(id: String): UUID {
        return try {
            UUID.fromString(id)
        } catch (e: Exception) {
            throw ValidationException("Format ID tidak valid")
        }
    }

    private fun String?.toAuditRangeStart(): OffsetDateTime? {
        val value = this?.lowercase()?.takeIf(String::isNotBlank) ?: return null
        val now = OffsetDateTime.now()
        val zone = ZoneId.systemDefault()

        return when (value) {
            "today" -> LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime()
            "7d" -> now.minus(Duration.ofDays(7))
            "30d" -> now.minus(Duration.ofDays(30))
            else -> throw ValidationException("Filter tanggal tidak valid")
        }
    }

    // ==========================================
    // STORE SETTINGS
    // ==========================================

    suspend fun getStoreSettings(actorRole: String): StoreSettingsResponse {
        AccessPolicy.require(actorRole, Permission.READ_STORE_PROFILE)
        return repo.getStoreSettings()
    }

    suspend fun updateStoreSettings(actorRole: String, userId: UUID, request: StoreSettingsUpdateRequest): StoreSettingsResponse {
        AccessPolicy.require(actorRole, Permission.UPDATE_STORE_PROFILE)
        if (request.storeName.isBlank()) {
            throw ValidationException("Nama toko tidak boleh kosong")
        }
        request.cashierDiscountLimitPercent?.let { limit ->
            if (limit < java.math.BigDecimal.ZERO || limit > java.math.BigDecimal("100") || limit.scale() > 2) {
                throw ValidationException("Batas diskon Kasir harus 0-100 dan maksimal 2 angka desimal")
            }
        }

        // printerSize dipertahankan pada DTO legacy agar klien lama tidak gagal
        // melakukan decoding. Nilainya tidak lagi menjadi konfigurasi global server.
        return repo.updateStoreProfile(
            userId = userId,
            storeName = request.storeName.trim(),
            address = request.address?.trim(),
            phone = request.phone?.trim(),
            receiptHeader = request.receiptHeader?.trim(),
            receiptFooter = request.receiptFooter?.trim(),
            cashierDiscountLimitPercent = request.cashierDiscountLimitPercent
                ?.setScale(2, java.math.RoundingMode.HALF_UP)
        )
    }

    suspend fun getStoreProfile(actorRole: String): StoreProfileResponse {
        AccessPolicy.require(actorRole, Permission.READ_STORE_PROFILE)
        return repo.getStoreSettings().toStoreProfileResponse()
    }

    suspend fun updateStoreProfile(
        actorRole: String,
        userId: UUID,
        request: StoreProfileUpdateRequest
    ): StoreProfileResponse {
        AccessPolicy.require(actorRole, Permission.UPDATE_STORE_PROFILE)
        if (request.storeName.isBlank()) {
            throw ValidationException("Nama toko tidak boleh kosong")
        }
        return repo.updateStoreProfile(
            userId = userId,
            storeName = request.storeName.trim(),
            address = request.address?.trim(),
            phone = request.phone?.trim(),
            receiptHeader = request.receiptHeader?.trim(),
            receiptFooter = request.receiptFooter?.trim()
        ).toStoreProfileResponse()
    }

    fun getSecuritySettings(actorRole: String): SecuritySettingsResponse {
        AccessPolicy.require(actorRole, Permission.MANAGE_SECURITY_SETTINGS)
        return SecuritySettingsResponse(
            environment = EnvironmentConfig.environment,
            accessTokenMinutes = EnvironmentConfig.accessTokenMinutes,
            refreshTokenDays = EnvironmentConfig.refreshTokenDays,
            backupEnabled = EnvironmentConfig.backupEnabled,
            restoreEnabled = EnvironmentConfig.restoreEnabled,
            backupRetentionDays = EnvironmentConfig.backupRetentionDays,
            backupIntervalHours = EnvironmentConfig.backupIntervalHours,
            backupMaxUploadMb = EnvironmentConfig.backupMaxUploadMb
        )
    }

    suspend fun recordAuditLog(
        actorUserId: UUID?,
        action: AuditAction,
        schemaName: String,
        tableName: String,
        recordId: String?,
        ipAddress: String?
    ) {
        val recordUuid = recordId?.let { parseUUID(it) }
        repo.insertAuditLog(
            actorUserId = actorUserId,
            action = action,
            schemaName = schemaName,
            tableName = tableName,
            recordId = recordUuid,
            ipAddress = ipAddress
        )
    }

    private fun validateProductionCredentials(password: String?, pin: String?) {
        if (!EnvironmentConfig.isProduction) return
        if (password != null && password in DEFAULT_PASSWORDS) {
            throw ValidationException("Password default tidak boleh digunakan pada production")
        }
        if (pin != null && pin in DEFAULT_PINS) {
            throw ValidationException("PIN default tidak boleh digunakan pada production")
        }
    }

    private fun validateUserIdentity(name: String, username: String, email: String?) {
        val normalizedName = name.trim()
        val normalizedUsername = username.trim()
        if (normalizedName.length !in 2..100) throw ValidationException("Nama harus 2-100 karakter")
        if (normalizedUsername.length !in 3..50) throw ValidationException("Username harus 3-50 karakter")
        if (!normalizedUsername.matches(Regex("^[A-Za-z0-9._-]+$"))) {
            throw ValidationException("Username hanya boleh berisi huruf, angka, titik, garis bawah, atau strip")
        }
        if (email != null && email.trim().length > 150) throw ValidationException("Email maksimal 150 karakter")
    }

    private companion object {
        val DEFAULT_PASSWORDS = setOf("owner123", "admin123", "password")
        val DEFAULT_PINS = setOf("1234", "123456", "0000", "000000")
    }
}

private fun StoreSettingsResponse.toStoreProfileResponse() = StoreProfileResponse(
    id = id,
    storeName = storeName,
    address = address,
    phone = phone,
    receiptHeader = receiptHeader,
    receiptFooter = receiptFooter,
    updatedAt = updatedAt
)
