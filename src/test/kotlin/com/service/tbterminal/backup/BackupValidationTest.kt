package com.service.tbterminal.backup

import com.service.tbterminal.shared.ValidationException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BackupValidationTest {
    @Test
    fun `manifest aplikasi lengkap diterima`() {
        val manifest = """
            TABLE system users
            TABLE inventory products
            TABLE inventory stock
            TABLE sales transactions
            TABLE receivable receivables
            TABLE receivable receivable_payments
            TABLE system database_backup_jobs
        """.trimIndent()

        BackupValidation.validateApplicationDumpManifest(manifest)
    }

    @Test
    fun `manifest database asing ditolak`() {
        assertFailsWith<ValidationException> {
            BackupValidation.validateApplicationDumpManifest("TABLE public unrelated_table")
        }
    }

    private val id = UUID.randomUUID()
    private val token = "one-time-token"
    private val now = OffsetDateTime.now(ZoneOffset.UTC)

    @Test
    fun `valid restore requires acknowledgement exact phrase token and unexpired window`() {
        BackupValidation.validateConfirmation(
            id,
            RestoreConfirmRequest(token, "RESTORE $id", true),
            BackupValidation.hash(token),
            now.plusMinutes(10),
            now
        )
    }

    @Test
    fun `restore rejects missing acknowledgement wrong phrase token and expiry`() {
        assertInvalid(RestoreConfirmRequest(token, "RESTORE $id", false), BackupValidation.hash(token), now.plusMinutes(10))
        assertInvalid(RestoreConfirmRequest(token, "restore $id", true), BackupValidation.hash(token), now.plusMinutes(10))
        assertInvalid(RestoreConfirmRequest("wrong", "RESTORE $id", true), BackupValidation.hash(token), now.plusMinutes(10))
        assertInvalid(RestoreConfirmRequest(token, "RESTORE $id", true), BackupValidation.hash(token), now.minusSeconds(1))
    }

    private fun assertInvalid(request: RestoreConfirmRequest, hash: String?, expiry: OffsetDateTime?) {
        assertFailsWith<ValidationException> {
            BackupValidation.validateConfirmation(id, request, hash, expiry, now)
        }
    }
}
