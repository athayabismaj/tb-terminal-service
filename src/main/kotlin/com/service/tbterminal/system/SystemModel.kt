package com.service.tbterminal.system

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LoginRequest(
    val username: String,
    val pin: String
)

@Serializable
data class LoginResponse(
    val token: String,
    val user: UserDto
)

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val name: String,
    val role: String,
    val isActive: Boolean
) {
    companion object {
        fun from(row: UserRow): UserDto {
            return UserDto(
                id = row.id.toString(),
                username = row.username,
                name = row.name,
                role = row.roleName,
                isActive = row.isActive
            )
        }
    }
}

// Internal row model mapped from DB
data class UserRow(
    val id: UUID,
    val username: String,
    val name: String,
    val pinHash: String,
    val roleName: String,
    val isActive: Boolean
)

// ==========================================
// DTOs - User & Role Management
// ==========================================

@Serializable
data class RoleResponse(
    val id: String,
    val name: String
)

@Serializable
data class UserResponse(
    val id: String,
    val roleId: String,
    val roleName: String,
    val name: String,
    val username: String,
    val isActive: Boolean,
    val lastLogin: String?,
    val createdAt: String
)

@Serializable
data class UserCreateRequest(
    val name: String,
    val username: String,
    val pin: String,
    val roleId: String
)

@Serializable
data class UserUpdateRequest(
    val name: String,
    val username: String,
    val isActive: Boolean,
    val roleId: String,
    val newPin: String? = null // Optional if admin wants to reset Kasir's PIN
)

@Serializable
data class ChangePinRequest(
    val oldPin: String,
    val newPin: String
)

// ==========================================
// DTOs - Store Settings
// ==========================================

@Serializable
data class StoreSettingsResponse(
    val id: String,
    val storeName: String,
    val address: String?,
    val phone: String?,
    val receiptHeader: String?,
    val receiptFooter: String?,
    val printerSize: String,
    val updatedAt: String
)

@Serializable
data class StoreSettingsUpdateRequest(
    val storeName: String,
    val address: String? = null,
    val phone: String? = null,
    val receiptHeader: String? = null,
    val receiptFooter: String? = null,
    val printerSize: String
)
