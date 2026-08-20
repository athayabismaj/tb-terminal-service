package com.service.tbterminal.backup

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

class BackupRepository {
    suspend fun create(
        operation: String,
        status: String,
        fileName: String,
        requestedBy: UUID?,
        sourceBackupId: UUID? = null,
        confirmationHash: String? = null,
        confirmationExpiresAt: OffsetDateTime? = null
    ): BackupJobRecord = newSuspendedTransaction(Dispatchers.IO) {
        val id = UUID.randomUUID()
        exec(
            """
            INSERT INTO system.database_backup_jobs
                (id, operation, status, file_name, requested_by, source_backup_id, confirmation_hash, confirmation_expires_at)
            VALUES ('$id'::uuid, '$operation', '$status', '${fileName.sqlText()}',
                    ${requestedBy.sqlUuid()}, ${sourceBackupId.sqlUuid()}, ${confirmationHash.sqlNullableText()}, ${confirmationExpiresAt.sqlTimestamp()})
            """.trimIndent()
        )
        findInternal(id) ?: error("Backup metadata gagal dibuat")
    }

    suspend fun markRunning(id: UUID) = update(id, "status='RUNNING', started_at=NOW(), error_message=NULL")
    suspend fun transitionStatus(id: UUID, expected: String, next: String): Boolean =
        newSuspendedTransaction(Dispatchers.IO) {
            var updated = false
            exec(
                """WITH changed AS (
                       UPDATE system.database_backup_jobs
                       SET status='${next.sqlText()}',
                           started_at=CASE WHEN '${next.sqlText()}'='RUNNING' THEN NOW() ELSE started_at END,
                           error_message=NULL
                       WHERE id='$id'::uuid AND status='${expected.sqlText()}'
                       RETURNING 1
                   ) SELECT EXISTS(SELECT 1 FROM changed) AS updated""".trimIndent(),
                explicitStatementType = StatementType.SELECT
            ) { rows -> if (rows.next()) updated = rows.getBoolean("updated") }
            updated
        }
    suspend fun markSucceeded(id: UUID, size: Long, sha256: String) =
        update(id, "status='SUCCEEDED', file_size=$size, sha256='$sha256', completed_at=NOW(), confirmation_hash=NULL, confirmation_expires_at=NULL")
    suspend fun setValidatedMetadata(id: UUID, size: Long, sha256: String) =
        update(id, "file_size=$size, sha256='$sha256'")
    suspend fun markFailed(id: UUID, message: String) =
        update(id, "status='FAILED', error_message='${message.take(500).sqlText()}', completed_at=NOW(), confirmation_hash=NULL, confirmation_expires_at=NULL")
    suspend fun find(id: UUID): BackupJobRecord? = newSuspendedTransaction(Dispatchers.IO) { findInternal(id) }
    suspend fun list(limit: Int): List<BackupJobResponse> = newSuspendedTransaction(Dispatchers.IO) {
        queryList("SELECT * FROM system.database_backup_jobs ORDER BY created_at DESC LIMIT ${limit.coerceIn(1, 100)}") { it.toRecord().response }
    }
    suspend fun retentionCandidates(before: OffsetDateTime): List<BackupJobResponse> = newSuspendedTransaction(Dispatchers.IO) {
        queryList(
            """SELECT * FROM system.database_backup_jobs
               WHERE operation='BACKUP' AND status='SUCCEEDED' AND removed_at IS NULL
                 AND completed_at < ${before.sqlTimestamp()}
               ORDER BY completed_at"""
        ) { it.toRecord().response }
    }
    suspend fun markRemoved(id: UUID) = update(id, "removed_at=NOW()")
    suspend fun latestSuccessfulBackupAt(): OffsetDateTime? = newSuspendedTransaction(Dispatchers.IO) {
        var latest: OffsetDateTime? = null
        exec(
            "SELECT MAX(completed_at) AS completed_at FROM system.database_backup_jobs WHERE operation='BACKUP' AND status='SUCCEEDED'",
            explicitStatementType = StatementType.SELECT
        ) { rs -> if (rs.next()) latest = rs.getObject("completed_at", OffsetDateTime::class.java) }
        latest
    }
    suspend fun findSuccessfulBackupByChecksum(sha256: String): BackupJobRecord? = newSuspendedTransaction(Dispatchers.IO) {
        queryList(
            "SELECT * FROM system.database_backup_jobs WHERE operation='BACKUP' AND status='SUCCEEDED' AND sha256='${sha256.sqlText()}' ORDER BY completed_at DESC LIMIT 1"
        ) { it.toRecord() }.firstOrNull()
    }

    /**
     * A database restore replaces this table as well. Re-create the operational
     * records after pg_restore so the safety backup and restore result remain
     * traceable even when they did not exist inside the restored dump.
     */
    suspend fun persistCompletedAfterRestore(response: BackupJobResponse) = newSuspendedTransaction(Dispatchers.IO) {
        val requestedBy = response.requestedBy?.let(UUID::fromString)
        val sourceBackupId = response.sourceBackupId?.let(UUID::fromString)
        exec(
            """
            INSERT INTO system.database_backup_jobs (
                id, operation, status, file_name, file_size, sha256, requested_by,
                source_backup_id, completed_at, created_at
            ) VALUES (
                '${response.id}'::uuid, '${response.operation.sqlText()}', 'SUCCEEDED',
                '${response.fileName.sqlText()}', ${response.fileSize ?: "NULL"}, ${response.sha256.sqlNullableText()},
                CASE WHEN EXISTS (SELECT 1 FROM system.users WHERE id=${requestedBy.sqlUuid()})
                     THEN ${requestedBy.sqlUuid()} ELSE NULL END,
                CASE WHEN EXISTS (SELECT 1 FROM system.database_backup_jobs WHERE id=${sourceBackupId.sqlUuid()})
                     THEN ${sourceBackupId.sqlUuid()} ELSE NULL END,
                NOW(), '${response.createdAt}'::timestamptz
            )
            ON CONFLICT (id) DO UPDATE SET
                status='SUCCEEDED', file_size=EXCLUDED.file_size, sha256=EXCLUDED.sha256,
                requested_by=EXCLUDED.requested_by, completed_at=NOW(), error_message=NULL,
                confirmation_hash=NULL, confirmation_expires_at=NULL
            """.trimIndent()
        )
    }

    private suspend fun update(id: UUID, values: String) = newSuspendedTransaction(Dispatchers.IO) {
        exec("UPDATE system.database_backup_jobs SET $values WHERE id='$id'::uuid")
    }
    private fun Transaction.findInternal(id: UUID): BackupJobRecord? =
        queryList("SELECT * FROM system.database_backup_jobs WHERE id='$id'::uuid") { it.toRecord() }.firstOrNull()
    private fun ResultSet.toRecord(): BackupJobRecord = BackupJobRecord(
        response = BackupJobResponse(
            id = getObject("id", UUID::class.java).toString(), operation = getString("operation"), status = getString("status"),
            fileName = getString("file_name"), fileSize = getObject("file_size")?.let { getLong("file_size") }, sha256 = getString("sha256"),
            requestedBy = getObject("requested_by")?.toString(), sourceBackupId = getObject("source_backup_id")?.toString(),
            errorMessage = getString("error_message"), createdAt = getObject("created_at", OffsetDateTime::class.java).toString(),
            completedAt = getObject("completed_at", OffsetDateTime::class.java)?.toString(), removedAt = getObject("removed_at", OffsetDateTime::class.java)?.toString()
        ),
        confirmationHash = getString("confirmation_hash"),
        confirmationExpiresAt = getObject("confirmation_expires_at", OffsetDateTime::class.java)
    )
    private fun <T> Transaction.queryList(sql: String, mapper: (ResultSet) -> T): List<T> =
        exec(sql.trimIndent(), explicitStatementType = StatementType.SELECT) { rs -> buildList { while (rs.next()) add(mapper(rs)) } } ?: emptyList()
}

private fun String.sqlText() = replace("'", "''")
private fun UUID?.sqlUuid() = this?.let { "'$it'::uuid" } ?: "NULL::uuid"
private fun String?.sqlNullableText() = this?.let { "'${it.sqlText()}'" } ?: "NULL"
private fun OffsetDateTime?.sqlTimestamp() = this?.let { "'$it'::timestamptz" } ?: "NULL"
