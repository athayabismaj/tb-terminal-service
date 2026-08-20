package com.service.tbterminal.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupJobResponse(
    val id: String,
    val operation: String,
    val status: String,
    val fileName: String,
    val fileSize: Long? = null,
    val sha256: String? = null,
    val requestedBy: String? = null,
    val sourceBackupId: String? = null,
    val errorMessage: String? = null,
    val createdAt: String,
    val completedAt: String? = null,
    val removedAt: String? = null
)

@Serializable
data class RestoreValidationResponse(
    val job: BackupJobResponse,
    val confirmationToken: String,
    val confirmationPhrase: String,
    val expiresAt: String
)

@Serializable
data class RestoreConfirmRequest(
    val confirmationToken: String,
    val confirmationPhrase: String,
    val acknowledgeDowntimeAndOverwrite: Boolean
)

data class BackupJobRecord(
    val response: BackupJobResponse,
    val confirmationHash: String? = null,
    val confirmationExpiresAt: java.time.OffsetDateTime? = null
)

