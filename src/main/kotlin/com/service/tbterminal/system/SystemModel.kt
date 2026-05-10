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
