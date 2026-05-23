package com.service.tbterminal.shared

import java.util.UUID

data class AuthenticatedUserPrincipal(
    val userId: UUID,
    val username: String,
    val role: String,
    val tokenVersion: Int
)
