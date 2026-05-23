package com.service.tbterminal.system

import com.service.tbterminal.shared.NotFoundException
import java.util.UUID

class UserSessionService(private val repo: SystemRepository) {
    suspend fun revokeAllSessionsForUser(userId: UUID) {
        if (!repo.incrementTokenVersion(userId)) {
            throw NotFoundException("User tidak ditemukan")
        }
    }
}
