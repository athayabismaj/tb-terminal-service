package com.service.tbterminal.system

import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.JwtHelper
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID

class SystemService(private val repo: SystemRepository) {

    suspend fun login(req: LoginRequest): ApiResponse<LoginResponse> {
        val user = repo.findUserByUsername(req.username)
        if (user == null) {
            println("LOGIN FAILED: User not found for username ${req.username}")
            return ApiResponse.error("Username atau Password salah", "UNAUTHORIZED")
        }

        if (!user.isActive) {
            println("LOGIN FAILED: User inactive ${req.username}")
            return ApiResponse.error("Akun tidak aktif", "UNAUTHORIZED")
        }

        // WAJIB: BCrypt di Dispatchers.IO
        val isValid = withContext(Dispatchers.IO) {
            BCrypt.checkpw(req.password, user.passwordHash)
        }

        if (!isValid) {
            println("LOGIN FAILED: Invalid Password for user ${req.username}")
            return ApiResponse.error("Username atau Password salah", "UNAUTHORIZED")
        }

        repo.updateLastLogin(user.id)

        val token = JwtHelper.generateToken(UserDto.from(user))
        return ApiResponse.success(
            LoginResponse(token = token, user = UserDto.from(user)),
            "Login berhasil"
        )
    }

    suspend fun unlock(userId: UUID, request: UnlockRequest): ApiResponse<Unit> {
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

    suspend fun getRoles(): List<RoleResponse> {
        return repo.getRoles()
    }

    suspend fun getUsers(page: Int, limit: Int, search: String?): PaginatedResponse<UserResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit
        return repo.getPaginatedUsers(safePage, safeLimit, search)
    }

    suspend fun getUserById(id: String): UserResponse {
        val uuid = parseUUID(id)
        return repo.getUserById(uuid) ?: throw NotFoundException("User tidak ditemukan")
    }

    suspend fun createUser(request: UserCreateRequest): UserResponse {
        if (request.username.isBlank()) throw ValidationException("Username tidak boleh kosong")
        if (request.name.isBlank()) throw ValidationException("Nama tidak boleh kosong")
        if (request.password.isBlank()) throw ValidationException("Password tidak boleh kosong")
        if (request.password.length < 6) throw ValidationException("Password minimal 6 karakter")
        if (request.pin.isBlank()) throw ValidationException("PIN tidak boleh kosong")
        if (request.pin.length < 4) throw ValidationException("PIN minimal 4 karakter")

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

    suspend fun updateUser(id: String, request: UserUpdateRequest): UserResponse {
        val uuid = parseUUID(id)
        val existingUser = repo.getUserById(uuid) ?: throw NotFoundException("User tidak ditemukan")

        if (request.username.isBlank()) throw ValidationException("Username tidak boleh kosong")
        if (request.name.isBlank()) throw ValidationException("Nama tidak boleh kosong")

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
            newHashedPassword = withContext(Dispatchers.IO) {
                BCrypt.hashpw(request.newPassword, BCrypt.gensalt())
            }
        }

        var newHashedPin: String? = null
        if (!request.newPin.isNullOrBlank()) {
            if (request.newPin.length < 4) throw ValidationException("PIN minimal 4 karakter")
            newHashedPin = withContext(Dispatchers.IO) {
                BCrypt.hashpw(request.newPin, BCrypt.gensalt())
            }
        }

        repo.updateUser(uuid, request.name.trim(), request.username.trim(), roleId, request.isActive, request.email?.trim(), newHashedPassword, newHashedPin)
        return repo.getUserById(uuid)!!
    }

    suspend fun deleteUser(id: String) {
        val uuid = parseUUID(id)
        repo.getUserById(uuid) ?: throw NotFoundException("User tidak ditemukan")
        repo.softDeleteUser(uuid)
    }

    suspend fun changeMyPassword(userId: UUID, request: ChangePasswordRequest) {
        if (request.newPassword.isBlank() || request.newPassword.length < 6) {
            throw ValidationException("Password baru minimal 6 karakter")
        }

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

    // ==========================================
    // STORE SETTINGS
    // ==========================================

    suspend fun getStoreSettings(): StoreSettingsResponse {
        return repo.getStoreSettings()
    }

    suspend fun updateStoreSettings(userId: UUID, request: StoreSettingsUpdateRequest): StoreSettingsResponse {
        if (request.storeName.isBlank()) {
            throw ValidationException("Nama toko tidak boleh kosong")
        }

        val printerSize = PrinterSize.entries.firstOrNull { it.dbValue == request.printerSize }
            ?: throw ValidationException("Ukuran printer tidak valid. Gunakan '58mm' atau '80mm'")

        return repo.updateStoreSettings(
            userId = userId,
            storeName = request.storeName.trim(),
            address = request.address?.trim(),
            phone = request.phone?.trim(),
            receiptHeader = request.receiptHeader?.trim(),
            receiptFooter = request.receiptFooter?.trim(),
            printerSize = printerSize
        )
    }
}
