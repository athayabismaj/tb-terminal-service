package com.service.tbterminal.backup

import com.service.tbterminal.shared.SqlFragment
import com.service.tbterminal.shared.execute
import com.service.tbterminal.shared.queryList
import com.service.tbterminal.shared.sql
import com.service.tbterminal.shared.sqlParam
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Transaction
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
        execute(
            sql(
                """
                INSERT INTO system.database_backup_jobs
                    (id, operation, status, file_name, requested_by, source_backup_id, confirmation_hash, confirmation_expires_at)
                VALUES (
                """
            ) + sqlParam("?::uuid, ", id) + sqlParam("?, ", operation) + sqlParam("?, ", status) +
                sqlParam("?, ", fileName) + sqlParam("?::uuid, ", requestedBy) + sqlParam("?::uuid, ", sourceBackupId) +
                sqlParam("?, ", confirmationHash) + sqlParam("?::timestamptz)", confirmationExpiresAt)
        )
        findInternal(id) ?: error("Backup metadata gagal dibuat")
    }

    suspend fun markRunning(id: UUID) = update(id, sql("status='RUNNING', started_at=NOW(), error_message=NULL"))
    suspend fun transitionStatus(id: UUID, expected: String, next: String): Boolean =
        newSuspendedTransaction(Dispatchers.IO) {
            queryList(
                sql(
                    """WITH changed AS (
                           UPDATE system.database_backup_jobs
                    """
                ) + sqlParam("SET status=?, ", next) +
                    sqlParam("started_at=CASE WHEN ?='RUNNING' THEN NOW() ELSE started_at END, error_message=NULL ", next) +
                    sqlParam("WHERE id=?::uuid ", id) + sqlParam("AND status=? ", expected) +
                    """
                           RETURNING 1
                       ) SELECT EXISTS(SELECT 1 FROM changed) AS updated"""
            ) { rows -> rows.getBoolean("updated") }.firstOrNull() ?: false
        }
    suspend fun markSucceeded(id: UUID, size: Long, sha256: String) = update(
        id,
        sqlParam("status='SUCCEEDED', file_size=?, ", size) + sqlParam("sha256=?, ", sha256) +
            "completed_at=NOW(), confirmation_hash=NULL, confirmation_expires_at=NULL"
    )
    suspend fun setValidatedMetadata(id: UUID, size: Long, sha256: String) =
        update(id, sqlParam("file_size=?, ", size) + sqlParam("sha256=?", sha256))
    suspend fun markFailed(id: UUID, message: String) = update(
        id,
        sqlParam("status='FAILED', error_message=?, ", message.take(500)) +
            "completed_at=NOW(), confirmation_hash=NULL, confirmation_expires_at=NULL"
    )
    suspend fun find(id: UUID): BackupJobRecord? = newSuspendedTransaction(Dispatchers.IO) { findInternal(id) }
    suspend fun list(limit: Int): List<BackupJobResponse> = newSuspendedTransaction(Dispatchers.IO) {
        queryList(
            sqlParam("SELECT * FROM system.database_backup_jobs ORDER BY created_at DESC LIMIT ?", limit.coerceIn(1, 100))
        ) { it.toRecord().response }
    }
    suspend fun retentionCandidates(before: OffsetDateTime): List<BackupJobResponse> = newSuspendedTransaction(Dispatchers.IO) {
        queryList(
            sql(
                """SELECT * FROM system.database_backup_jobs
                   WHERE operation='BACKUP' AND status='SUCCEEDED' AND removed_at IS NULL
                     AND """
            ) + sqlParam("completed_at < ?::timestamptz", before) + " ORDER BY completed_at"
        ) { it.toRecord().response }
    }
    suspend fun markRemoved(id: UUID) = update(id, sql("removed_at=NOW()"))
    suspend fun latestSuccessfulBackupAt(): OffsetDateTime? = newSuspendedTransaction(Dispatchers.IO) {
        queryList(
            sql("SELECT MAX(completed_at) AS completed_at FROM system.database_backup_jobs WHERE operation='BACKUP' AND status='SUCCEEDED'")
        ) { rs -> rs.getObject("completed_at", OffsetDateTime::class.java) }.firstOrNull()
    }
    suspend fun findSuccessfulBackupByChecksum(sha256: String): BackupJobRecord? = newSuspendedTransaction(Dispatchers.IO) {
        queryList(
            sqlParam("SELECT * FROM system.database_backup_jobs WHERE operation='BACKUP' AND status='SUCCEEDED' AND sha256=? ", sha256) +
                "ORDER BY completed_at DESC LIMIT 1"
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
        execute(
            sql(
                """
                INSERT INTO system.database_backup_jobs (
                    id, operation, status, file_name, file_size, sha256, requested_by,
                    source_backup_id, completed_at, created_at
                ) VALUES (
                """
            ) + sqlParam("?::uuid, ", UUID.fromString(response.id)) + sqlParam("?, 'SUCCEEDED', ", response.operation) +
                sqlParam("?, ", response.fileName) + sqlParam("?, ", response.fileSize) + sqlParam("?,", response.sha256) +
                sqlParam(" CASE WHEN EXISTS (SELECT 1 FROM system.users WHERE id=?::uuid)", requestedBy) +
                sqlParam(" THEN ?::uuid ELSE NULL END,", requestedBy) +
                sqlParam(" CASE WHEN EXISTS (SELECT 1 FROM system.database_backup_jobs WHERE id=?::uuid)", sourceBackupId) +
                sqlParam(" THEN ?::uuid ELSE NULL END,", sourceBackupId) +
                sqlParam(" NOW(), ?::timestamptz)", OffsetDateTime.parse(response.createdAt)) +
                """
                ON CONFLICT (id) DO UPDATE SET
                    status='SUCCEEDED', file_size=EXCLUDED.file_size, sha256=EXCLUDED.sha256,
                    requested_by=EXCLUDED.requested_by, completed_at=NOW(), error_message=NULL,
                    confirmation_hash=NULL, confirmation_expires_at=NULL
                """
        )
    }

    private suspend fun update(id: UUID, assignments: SqlFragment) = newSuspendedTransaction(Dispatchers.IO) {
        execute(sql("UPDATE system.database_backup_jobs SET ") + assignments + sqlParam(" WHERE id=?::uuid", id))
    }
    private fun Transaction.findInternal(id: UUID): BackupJobRecord? =
        queryList(sqlParam("SELECT * FROM system.database_backup_jobs WHERE id=?::uuid", id)) { it.toRecord() }.firstOrNull()
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
}
