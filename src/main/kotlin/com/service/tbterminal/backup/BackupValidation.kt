package com.service.tbterminal.backup

import com.service.tbterminal.shared.ValidationException
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal object BackupValidation {
    fun validateApplicationDumpManifest(manifest: String) {
        val requiredObjects = listOf(
            "system users",
            "inventory products",
            "inventory stock",
            "sales transactions",
            "receivable receivables",
            "receivable receivable_payments",
            "system database_backup_jobs"
        )
        val normalized = manifest.lowercase().replace(Regex("\\s+"), " ")
        if (requiredObjects.any { it !in normalized }) {
            throw ValidationException("Backup tidak kompatibel dengan TB Terminal (objek wajib tidak lengkap)")
        }
    }

    fun validateConfirmation(
        id: UUID,
        request: RestoreConfirmRequest,
        expectedHash: String?,
        expiresAt: OffsetDateTime?,
        now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)
    ) {
        if (!request.acknowledgeDowntimeAndOverwrite) throw ValidationException("Konfirmasi dampak restore wajib disetujui")
        if (request.confirmationPhrase != "RESTORE $id") throw ValidationException("Frasa konfirmasi restore tidak sesuai")
        if (expiresAt?.isBefore(now) != false) throw ValidationException("Token konfirmasi restore sudah kedaluwarsa")
        val expected = expectedHash ?: throw ValidationException("Token konfirmasi restore tidak tersedia")
        if (!MessageDigest.isEqual(expected.toByteArray(), hash(request.confirmationToken).toByteArray())) {
            throw ValidationException("Token konfirmasi restore tidak valid")
        }
    }

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
