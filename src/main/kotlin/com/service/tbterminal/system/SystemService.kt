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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mindrot.jbcrypt.BCrypt
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

class SystemService(
    private val repo: SystemRepository,
    clock: java.time.Clock = java.time.Clock.systemUTC()
) {
    private val pinVerificationService = PinVerificationService(repo, clock)

    suspend fun login(req: LoginRequest): ApiResponse<LoginResponse> {
        val username = req.username.trim()
        if (username.isBlank() || req.password.isBlank()) {
            throw ValidationException("Username dan password wajib diisi")
        }
        if (EnvironmentConfig.isProduction && req.password in DEFAULT_PASSWORDS) {
            return ApiResponse.error("Kredensial default tidak diizinkan pada production", "DEFAULT_CREDENTIAL_FORBIDDEN")
        }

        val user = repo.findUserByUsername(username)
        // Selalu jalankan bcrypt (dengan hash dummy bila user tidak ada) agar waktu
        // respons tidak membocorkan username mana yang terdaftar.
        val isValid = withContext(Dispatchers.IO) {
            BCrypt.checkpw(req.password, user?.passwordHash ?: DUMMY_PASSWORD_HASH)
        }

        // Status akun baru diungkap setelah password terbukti benar.
        if (user == null || !isValid) {
            return ApiResponse.error("Username atau Password salah", "UNAUTHORIZED")
        }
        if (!user.isActive) {
            return ApiResponse.error("Akun tidak aktif", "UNAUTHORIZED")
        }

        repo.updateLastLogin(user.id)

        val token = JwtHelper.generateAccessToken(user)
        val refreshToken = issueRefreshToken(user, familyId = UUID.randomUUID())
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
        // Refresh token lama (tanpa jti) tidak lagi diterima: user cukup login ulang sekali.
        val tokenId = decoded.id
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return ApiResponse.error("Sesi telah berakhir. Silakan login kembali.", "UNAUTHORIZED")
        val user = repo.findUserById(userId)
            ?: return ApiResponse.error("Refresh token tidak valid", "UNAUTHORIZED")

        if (!user.isActive || user.tokenVersion != tokenVersion) {
            return ApiResponse.error("Sesi telah berakhir", "UNAUTHORIZED")
        }

        val newTokenId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val validity = JwtHelper.refreshTokenValidity()
        return when (
            val result = repo.consumeRefreshToken(
                tokenId = tokenId,
                expectedUserId = user.id,
                expectedTokenVersion = user.tokenVersion,
                newTokenId = newTokenId,
                newExpiresAt = now.plus(validity),
                now = now
            )
        ) {
            is RefreshConsumeResult.Rotated -> ApiResponse.success(
                RefreshTokenResponse(
                    token = JwtHelper.generateAccessToken(user),
                    refreshToken = JwtHelper.generateRefreshToken(
                        user = user,
                        tokenId = newTokenId,
                        familyId = result.familyId,
                        validity = validity
                    )
                ),
                "Token berhasil diperbarui"
            )

            is RefreshConsumeResult.ReuseDetected -> {
                repo.insertAuditLog(
                    actorUserId = user.id,
                    action = AuditAction.UPDATE,
                    schemaName = "system",
                    tableName = "refresh_token_reuse",
                    recordId = user.id,
                    ipAddress = null,
                    newData = buildJsonObject { put("familyId", result.familyId.toString()) }.toString()
                )
                ApiResponse.error("Sesi telah berakhir. Silakan login kembali.", "UNAUTHORIZED")
            }

            is RefreshConsumeResult.Rejected ->
                ApiResponse.error("Sesi telah berakhir. Silakan login kembali.", "UNAUTHORIZED")
        }
    }

    private suspend fun issueRefreshToken(user: UserRow, familyId: UUID): String {
        val tokenId = UUID.randomUUID()
        val validity = JwtHelper.refreshTokenValidity()
        repo.insertRefreshToken(
            tokenId = tokenId,
            userId = user.id,
            familyId = familyId,
            tokenVersion = user.tokenVersion,
            expiresAt = OffsetDateTime.now().plus(validity)
        )
        return JwtHelper.generateRefreshToken(user, tokenId = tokenId, familyId = familyId, validity = validity)
    }

    suspend fun logout(userId: UUID) {
        if (!repo.incrementTokenVersion(userId)) {
            throw NotFoundException("User tidak ditemukan")
        }
    }

    suspend fun unlock(
        userId: UUID,
        request: UnlockRequest,
        headerDeviceId: String? = null,
        ipAddress: String? = null
    ): ApiResponse<PinUnlockResponse> {
        return pinVerificationService.verifyUnlock(
            userId = userId,
            request = request,
            headerDeviceId = headerDeviceId,
            ipAddress = ipAddress
        )
    }

    suspend fun resetPinWithPassword(
        userId: UUID,
        request: ResetPinRequest,
        headerDeviceId: String? = null,
        ipAddress: String? = null
    ) {
        if (request.currentPassword.isBlank()) {
            throw ValidationException("Password wajib diisi")
        }
        PinVerificationPolicy.validatePinFormat(request.newPin, "PIN baru")
        validateProductionCredentials(null, request.newPin)

        val userRow = repo.findUserById(userId) ?: throw NotFoundException("User tidak ditemukan")
        if (!userRow.isActive) {
            throw ValidationException("Akun tidak aktif")
        }

        val deviceId = PinVerificationPolicy.normalizeDeviceId(request.deviceId ?: headerDeviceId)
        pinVerificationService.verifyCredentialForSensitiveChange(
            userRow = userRow,
            scope = CredentialScope.PASSWORD,
            candidate = request.currentPassword,
            deviceId = deviceId,
            ipAddress = ipAddress,
            invalidMessage = "Password tidak valid"
        )

        val newHashedPin = withContext(Dispatchers.IO) {
            BCrypt.hashpw(request.newPin, BCrypt.gensalt())
        }
        repo.updatePin(userId, newHashedPin)
        repo.resetAllPinAttemptStatesForUser(userId)
        repo.insertAuditLog(
            actorUserId = userId,
            action = AuditAction.UPDATE,
            schemaName = "system",
            tableName = "pin_reset",
            recordId = userId,
            ipAddress = ipAddress,
            newData = buildJsonObject { put("deviceId", deviceId) }.toString()
        )
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

    suspend fun getAuditLogs(
        actorRole: String,
        page: Int,
        limit: Int,
        action: String?,
        range: String?,
        startDate: String? = null,
        endDate: String? = null
    ): PaginatedResponse<AuditLogResponse> {
        AccessPolicy.require(actorRole, Permission.VIEW_AUDIT_LOG)
        val safePage = if (page < 1) 1 else page
        val safeLimit = limit.coerceIn(1, 100)
        val auditAction = action
            ?.takeIf { it.isNotBlank() }
            ?.let { rawAction ->
                runCatching { AuditAction.valueOf(rawAction.uppercase()) }
                    .getOrElse { throw ValidationException("Tipe aktivitas tidak valid") }
            }
        val zone = ZoneId.of("Asia/Jakarta")
        val since = when {
            !startDate.isNullOrBlank() -> runCatching { LocalDate.parse(startDate).atStartOfDay(zone).toOffsetDateTime() }.getOrNull()
            else -> range.toAuditRangeStart()
        }
        val until = when {
            !endDate.isNullOrBlank() -> runCatching { LocalDate.parse(endDate).plusDays(1).atStartOfDay(zone).toOffsetDateTime() }.getOrNull()
            !startDate.isNullOrBlank() -> runCatching { LocalDate.parse(startDate).plusDays(1).atStartOfDay(zone).toOffsetDateTime() }.getOrNull()
            else -> null
        }

        return repo.getPaginatedAuditLogs(safePage, safeLimit, auditAction, since, until)
    }

    suspend fun getUserById(actorRole: String, id: String): UserResponse {
        AccessPolicy.require(actorRole, Permission.MANAGE_USERS)
        val uuid = parseUUID(id)
        return repo.getUserById(uuid) ?: throw NotFoundException("User tidak ditemukan")
    }

    suspend fun getMyProfile(userId: UUID): UserDto {
        val user = repo.findUserById(userId) ?: throw NotFoundException("User tidak ditemukan")
        return UserDto.from(user)
    }

    suspend fun updateMyProfile(userId: UUID, request: UpdateMyProfileRequest): UserDto {
        val normalizedName = request.name.trim()
        val normalizedEmail = request.email?.trim()?.takeIf(String::isNotEmpty)
        validateProfileIdentity(normalizedName, normalizedEmail)

        repo.findUserById(userId) ?: throw NotFoundException("User tidak ditemukan")
        if (!repo.updateOwnProfile(userId, normalizedName, normalizedEmail)) {
            throw NotFoundException("User tidak ditemukan")
        }
        return getMyProfile(userId)
    }

    suspend fun createUser(actorRole: String, request: UserCreateRequest): UserResponse {
        AccessPolicy.require(actorRole, Permission.MANAGE_USERS)
        validateUserIdentity(request.name, request.username, request.email)
        if (request.password.isBlank()) throw ValidationException("Password tidak boleh kosong")
        validatePasswordStrength(request.password)
        PinVerificationPolicy.validatePinFormat(request.pin)
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
            validatePasswordStrength(request.newPassword)
            validateProductionCredentials(request.newPassword, null)
            newHashedPassword = withContext(Dispatchers.IO) {
                BCrypt.hashpw(request.newPassword, BCrypt.gensalt())
            }
        }

        var newHashedPin: String? = null
        if (!request.newPin.isNullOrBlank()) {
            PinVerificationPolicy.validatePinFormat(request.newPin)
            validateProductionCredentials(null, request.newPin)
            newHashedPin = withContext(Dispatchers.IO) {
                BCrypt.hashpw(request.newPin, BCrypt.gensalt())
            }
        }

        repo.updateUser(uuid, request.name.trim(), request.username.trim(), roleId, request.isActive, request.email?.trim(), newHashedPassword, newHashedPin)
        if (newHashedPin != null) {
            repo.resetAllPinAttemptStatesForUser(uuid)
        }
        // Password baru atau perubahan role wajib mengakhiri semua sesi lama user tersebut
        // (token curian tidak boleh tetap berlaku setelah owner mereset kredensial).
        if (newHashedPassword != null || existingUser.roleId != roleId.toString()) {
            repo.incrementTokenVersion(uuid)
        }
        return repo.getUserById(uuid)!!
    }

    suspend fun deleteUser(actorRole: String, id: String) {
        AccessPolicy.require(actorRole, Permission.MANAGE_USERS)
        val uuid = parseUUID(id)
        repo.getUserById(uuid) ?: throw NotFoundException("User tidak ditemukan")
        repo.softDeleteUser(uuid)
    }

    suspend fun changeMyPassword(
        userId: UUID,
        request: ChangePasswordRequest,
        deviceId: String? = null,
        ipAddress: String? = null
    ) {
        if (request.newPassword.isBlank()) {
            throw ValidationException("Password baru wajib diisi")
        }
        validatePasswordStrength(request.newPassword)
        validateProductionCredentials(request.newPassword, null)

        val userRow = repo.findUserById(userId) ?: throw NotFoundException("User tidak ditemukan")
        pinVerificationService.verifyCredentialForSensitiveChange(
            userRow = userRow,
            scope = CredentialScope.PASSWORD,
            candidate = request.oldPassword,
            deviceId = deviceId,
            ipAddress = ipAddress,
            invalidMessage = "Password lama tidak valid"
        )

        val newHashedPassword = withContext(Dispatchers.IO) {
            BCrypt.hashpw(request.newPassword, BCrypt.gensalt())
        }

        repo.updatePassword(userId, newHashedPassword)
        // Semua sesi (termasuk yang mungkin dicuri) berakhir; user login ulang dengan password baru.
        repo.incrementTokenVersion(userId)
    }

    suspend fun changeMyPin(
        userId: UUID,
        request: ChangePinRequest,
        deviceId: String? = null,
        ipAddress: String? = null
    ) {
        PinVerificationPolicy.validatePinFormat(request.newPin, "PIN baru")
        validateProductionCredentials(null, request.newPin)

        val userRow = repo.findUserById(userId) ?: throw NotFoundException("User tidak ditemukan")
        pinVerificationService.verifyCredentialForSensitiveChange(
            userRow = userRow,
            scope = CredentialScope.PIN,
            candidate = request.oldPin,
            deviceId = deviceId,
            ipAddress = ipAddress,
            invalidMessage = "PIN lama tidak valid"
        )

        val newHashedPin = withContext(Dispatchers.IO) {
            BCrypt.hashpw(request.newPin, BCrypt.gensalt())
        }

        repo.updatePin(userId, newHashedPin)
        repo.resetAllPinAttemptStatesForUser(userId)
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
        val zone = ZoneId.of("Asia/Jakarta")

        return when (value) {
            "today" -> LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime()
            "7d" -> now.minus(Duration.ofDays(7))
            "30d" -> now.minus(Duration.ofDays(30))
            else -> runCatching { LocalDate.parse(value).atStartOfDay(zone).toOffsetDateTime() }.getOrNull()
                ?: throw ValidationException("Filter tanggal tidak valid")
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
        val normalizedTaxRate = request.taxRate?.let { rate ->
            if (rate < java.math.BigDecimal.ZERO || rate > java.math.BigDecimal("100") || rate.scale() > 2) {
                throw ValidationException("Tarif pajak harus antara 0-100 dan maksimal 2 angka desimal")
            }
            rate.setScale(2, java.math.RoundingMode.HALF_UP)
        }
        val parsedTaxMode = request.taxMode?.let { raw ->
            runCatching { TaxMode.valueOf(raw.trim().uppercase()) }.getOrElse {
                throw ValidationException("Mode pajak tidak valid. Gunakan EXCLUDE atau INCLUDE")
            }
        }
        val normalizedTaxLabel = request.taxLabel?.let { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > 50) {
                throw ValidationException("Label pajak harus 1-50 karakter")
            }
            trimmed
        }
        val normalizedNpwp = request.storeNpwp?.trim()?.also { npwp ->
            if (npwp.length > 50) {
                throw ValidationException("NPWP toko maksimal 50 karakter")
            }
        }?.takeIf { it.isNotEmpty() }
        request.cashRoundingUnit?.let { unit ->
            if (unit !in setOf(0, 100, 500, 1000)) {
                throw ValidationException("Kelipatan pembulatan tunai harus 0, 100, 500, atau 1000")
            }
        }
        val parsedRoundingRule = request.cashRoundingRule?.let { raw ->
            runCatching { CashRoundingRule.valueOf(raw.trim().uppercase()) }.getOrElse {
                throw ValidationException("Aturan pembulatan tunai tidak valid. Gunakan NEAREST, DOWN, atau UP")
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
                ?.setScale(2, java.math.RoundingMode.HALF_UP),
            taxEnabled = request.taxEnabled,
            taxRate = normalizedTaxRate,
            taxMode = parsedTaxMode,
            taxLabel = normalizedTaxLabel,
            updateStoreNpwp = request.storeNpwp != null,
            storeNpwp = normalizedNpwp,
            cashRoundingUnit = request.cashRoundingUnit,
            cashRoundingRule = parsedRoundingRule
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

    private fun validatePasswordStrength(password: String) {
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw ValidationException("Password minimal $MIN_PASSWORD_LENGTH karakter")
        }
        if (password.length > MAX_PASSWORD_LENGTH) {
            // bcrypt hanya memakai 72 byte pertama; batasi agar tidak menyesatkan.
            throw ValidationException("Password maksimal $MAX_PASSWORD_LENGTH karakter")
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
        validateProfileIdentity(normalizedName, email?.trim()?.takeIf(String::isNotEmpty))
    }

    private fun validateProfileIdentity(name: String, email: String?) {
        if (name.length !in 2..100) throw ValidationException("Nama harus 2-100 karakter")
        if (email != null) {
            if (email.length > 150) throw ValidationException("Email maksimal 150 karakter")
            if (!email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
                throw ValidationException("Format email tidak valid")
            }
        }
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
        const val MAX_PASSWORD_LENGTH = 72
        val DUMMY_PASSWORD_HASH: String by lazy { BCrypt.hashpw("dummy-password-for-timing", BCrypt.gensalt()) }
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
