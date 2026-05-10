package com.service.tbterminal.system

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.JwtHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt

class SystemService(private val repo: SystemRepository) {

    suspend fun login(req: LoginRequest): ApiResponse<LoginResponse> {
        val user = repo.findUserByUsername(req.username)
        if (user == null) {
            println("LOGIN FAILED: User not found for username ${req.username}")
            return ApiResponse.error("Username atau PIN salah", "UNAUTHORIZED")
        }

        if (!user.isActive) {
            println("LOGIN FAILED: User inactive ${req.username}")
            return ApiResponse.error("Akun tidak aktif", "UNAUTHORIZED")
        }

        // WAJIB: BCrypt di Dispatchers.IO — operasi berat, jangan blocking thread
        val isValid = withContext(Dispatchers.IO) {
            BCrypt.checkpw(req.pin, user.pinHash)
        }


        if (!isValid) {
            println("LOGIN FAILED: Invalid PIN for user ${req.username}")
            return ApiResponse.error("Username atau PIN salah", "UNAUTHORIZED")
        }

        repo.updateLastLogin(user.id)

        val token = JwtHelper.generateToken(UserDto.from(user))
        return ApiResponse.success(
            LoginResponse(token = token, user = UserDto.from(user)),
            "Login berhasil"
        )
    }
}
