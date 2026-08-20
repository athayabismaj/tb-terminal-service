package com.service.tbterminal.backup

import com.service.tbterminal.shared.EnvironmentConfig
import com.service.tbterminal.plugins.DatabaseHealth
import com.service.tbterminal.shared.ForbiddenException
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BackupService(private val repo: BackupRepository) {
    suspend fun list(limit: Int): List<BackupJobResponse> = repo.list(limit)
    suspend fun get(id: UUID): BackupJobResponse =
        repo.find(id)?.response ?: throw NotFoundException("Job backup/restore tidak ditemukan")

    suspend fun queueBackup(requestedBy: UUID?): BackupJobResponse {
        val directory = requireBackupDirectory()
        val fileName = "tb-terminal-${OffsetDateTime.now().format(FILE_TIME)}-${UUID.randomUUID()}.dump"
        return repo.create("BACKUP", "PENDING", fileName, requestedBy).response
    }

    suspend fun executeBackup(id: UUID): BackupJobResponse {
        val job = repo.find(id) ?: throw NotFoundException("Job backup tidak ditemukan")
        if (job.response.operation != "BACKUP") throw ValidationException("Job bukan operasi backup")
        if (!repo.transitionStatus(id, "PENDING", "RUNNING")) {
            throw ValidationException("Job backup sudah diproses")
        }
        val directory = requireBackupDirectory()
        val fileName = job.response.fileName
        val target = resolveSafe(directory, fileName)
        val temporary = resolveSafe(directory, "$fileName.part")
        try {
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(temporary)
                runPostgresTool(
                    executable = EnvironmentConfig.pgDumpExecutable,
                    arguments = listOf(
                        "--format=custom", "--no-owner", "--no-acl",
                        // A dump cannot contain its own final checksum/status. Operational
                        // metadata is reconstructed after restore instead of restoring stale RUNNING rows.
                        "--exclude-table-data=system.database_backup_jobs",
                        "--file=${temporary.toAbsolutePath()}"
                    )
                )
                require(Files.size(temporary) > POSTGRES_HEADER.size) { "File backup kosong" }
                require(hasPostgresHeader(temporary)) { "Output pg_dump bukan format custom PostgreSQL" }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            }
            val size = withContext(Dispatchers.IO) { Files.size(target) }
            val checksum = sha256(target)
            repo.markSucceeded(id, size, checksum)
            enforceRetention()
            return repo.find(id)!!.response
        } catch (cause: Exception) {
            withContext(Dispatchers.IO) { Files.deleteIfExists(temporary) }
            repo.markFailed(id, safeError(cause))
            throw ValidationException("Backup PostgreSQL gagal: ${safeError(cause)}")
        }
    }

    suspend fun createBackup(requestedBy: UUID?): BackupJobResponse {
        val queued = queueBackup(requestedBy)
        return executeBackup(UUID.fromString(queued.id))
    }

    suspend fun stageRestore(input: ByteReadChannel, requestedBy: UUID): RestoreValidationResponse {
        val directory = requireBackupDirectory()
        val id = UUID.randomUUID()
        val fileName = "restore-$id.dump"
        val target = resolveSafe(directory, fileName)
        val maxBytes = EnvironmentConfig.backupMaxUploadMb * 1024L * 1024L
        try {
            copyLimited(input, target, maxBytes)
            if (!hasPostgresHeader(target)) throw ValidationException("File bukan backup custom PostgreSQL yang valid")
            val manifest = withContext(Dispatchers.IO) {
                runPostgresTool(EnvironmentConfig.pgRestoreExecutable, listOf("--list", target.toAbsolutePath().toString()), includeConnection = false)
            }
            BackupValidation.validateApplicationDumpManifest(manifest)
            val fileSize = withContext(Dispatchers.IO) { Files.size(target) }
            val checksum = sha256(target)
            val sourceBackup = repo.findSuccessfulBackupByChecksum(checksum)
            val token = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            val expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10)
            val job = repo.create(
                operation = "RESTORE", status = "VALIDATED", fileName = fileName, requestedBy = requestedBy,
                sourceBackupId = sourceBackup?.response?.id?.let(UUID::fromString),
                confirmationHash = BackupValidation.hash(token), confirmationExpiresAt = expiresAt
            )
            repo.setValidatedMetadata(UUID.fromString(job.response.id), fileSize, checksum)
            return RestoreValidationResponse(
                job = job.response.copy(fileSize = fileSize, sha256 = checksum),
                confirmationToken = token,
                confirmationPhrase = "RESTORE ${job.response.id}",
                expiresAt = expiresAt.toString()
            )
        } catch (cause: Exception) {
            withContext(Dispatchers.IO) { Files.deleteIfExists(target) }
            if (cause is ValidationException) throw cause
            throw ValidationException("Validasi backup gagal: ${safeError(cause)}")
        }
    }

    suspend fun queueRestore(id: UUID, request: RestoreConfirmRequest, actorId: UUID): BackupJobResponse {
        if (!EnvironmentConfig.restoreEnabled) throw ForbiddenException("Restore server dinonaktifkan; set RESTORE_ENABLED=true saat maintenance")
        val job = repo.find(id) ?: throw NotFoundException("Permintaan restore tidak ditemukan")
        if (job.response.operation != "RESTORE" || job.response.status != "VALIDATED") throw ValidationException("Permintaan restore tidak siap dijalankan")
        if (job.response.requestedBy != actorId.toString()) throw ForbiddenException("Konfirmasi harus dilakukan oleh pengguna yang memvalidasi file")
        BackupValidation.validateConfirmation(id, request, job.confirmationHash, job.confirmationExpiresAt)

        val target = resolveSafe(requireBackupDirectory(), job.response.fileName)
        if (!Files.exists(target) || !hasPostgresHeader(target)) throw ValidationException("File restore tidak tersedia atau berubah")
        if (job.response.sha256 == null || sha256(target) != job.response.sha256) throw ValidationException("Checksum file restore berubah setelah validasi")
        if (!repo.transitionStatus(id, "VALIDATED", "PENDING")) {
            throw ValidationException("Konfirmasi restore sudah diproses")
        }
        return repo.find(id)!!.response
    }

    suspend fun executeRestore(id: UUID, actorId: UUID): BackupJobResponse {
        val job = repo.find(id) ?: throw NotFoundException("Permintaan restore tidak ditemukan")
        if (job.response.operation != "RESTORE" || job.response.status != "PENDING") {
            throw ValidationException("Permintaan restore tidak siap dijalankan")
        }
        if (job.response.requestedBy != actorId.toString()) throw ForbiddenException("Restore harus dijalankan oleh pengguna yang memvalidasi file")
        if (!repo.transitionStatus(id, "PENDING", "RUNNING")) throw ValidationException("Restore sudah diproses")
        val target = resolveSafe(requireBackupDirectory(), job.response.fileName)
        if (!Files.exists(target) || !hasPostgresHeader(target)) {
            repo.markFailed(id, "File restore tidak tersedia atau berubah")
            throw ValidationException("File restore tidak tersedia atau berubah")
        }
        if (job.response.sha256 == null || sha256(target) != job.response.sha256) {
            repo.markFailed(id, "Checksum file restore berubah setelah validasi")
            throw ValidationException("Checksum file restore berubah setelah validasi")
        }
        // A fresh safety backup is mandatory immediately before destructive restore.
        // Both records must be persisted again because pg_restore replaces the metadata table too.
        val restoreSnapshot = job.response
        val sourceBackup = restoreSnapshot.sourceBackupId?.let(UUID::fromString)?.let { repo.find(it)?.response }
        val safetyBackup = createBackup(actorId)
        try {
            withContext(Dispatchers.IO) {
                runPostgresTool(
                    EnvironmentConfig.pgRestoreExecutable,
                    listOf(
                        "--clean", "--if-exists", "--no-owner", "--no-acl", "--exit-on-error",
                        "--single-transaction", target.toAbsolutePath().toString()
                    )
                )
            }
            // Enum/type OIDs are recreated by pg_restore. Every pooled JDBC connection
            // must be replaced before Exposed issues another statement.
            DatabaseHealth.evictConnectionsAfterRestore()
            sourceBackup?.let { repo.persistCompletedAfterRestore(it) }
            repo.persistCompletedAfterRestore(safetyBackup)
            repo.persistCompletedAfterRestore(
                restoreSnapshot.copy(fileSize = Files.size(target), sha256 = sha256(target), status = "SUCCEEDED")
            )
            return repo.find(id)!!.response
        } catch (cause: Exception) {
            repo.markFailed(id, safeError(cause))
            throw ValidationException("Restore PostgreSQL gagal: ${safeError(cause)}")
        }
    }

    suspend fun downloadablePath(id: UUID): Path {
        val job = repo.find(id) ?: throw NotFoundException("Backup tidak ditemukan")
        if (job.response.operation != "BACKUP" || job.response.status != "SUCCEEDED" || job.response.removedAt != null) {
            throw ValidationException("File backup tidak tersedia")
        }
        val path = resolveSafe(requireBackupDirectory(), job.response.fileName)
        if (!Files.isRegularFile(path) || sha256(path) != job.response.sha256) throw ValidationException("Checksum file backup tidak sesuai")
        return path
    }

    suspend fun runScheduledBackupIfDue(): BackupJobResponse? {
        if (!EnvironmentConfig.backupEnabled) return null
        val latest = repo.latestSuccessfulBackupAt()
        if (latest != null && latest.plusHours(EnvironmentConfig.backupIntervalHours).isAfter(OffsetDateTime.now())) return null
        return createBackup(null)
    }

    suspend fun enforceRetention() {
        val directory = requireBackupDirectory()
        val before = OffsetDateTime.now().minusDays(EnvironmentConfig.backupRetentionDays)
        repo.retentionCandidates(before).forEach { job ->
            val id = UUID.fromString(job.id)
            withContext(Dispatchers.IO) { Files.deleteIfExists(resolveSafe(directory, job.fileName)) }
            repo.markRemoved(id)
        }
    }

    private fun requireBackupDirectory(): Path {
        if (EnvironmentConfig.backupDirectory.isBlank()) throw ValidationException("BACKUP_DIRECTORY belum dikonfigurasi")
        val directory = Path.of(EnvironmentConfig.backupDirectory).toAbsolutePath().normalize()
        val applicationDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        if (directory.startsWith(applicationDirectory)) throw ValidationException("Direktori backup wajib berada di luar repository aplikasi")
        Files.createDirectories(directory)
        return directory
    }

    private fun resolveSafe(directory: Path, fileName: String): Path {
        if (fileName.contains('/') || fileName.contains('\\')) throw ValidationException("Nama file backup tidak valid")
        val result = directory.resolve(fileName).normalize()
        if (result.parent != directory) throw ValidationException("Lokasi file backup tidak valid")
        return result
    }

    private fun runPostgresTool(executable: String, arguments: List<String>, includeConnection: Boolean = true): String {
        val uri = parseJdbcUri(EnvironmentConfig.dbUrl)
        val command = mutableListOf(executable)
        if (includeConnection) {
            command += listOf("--host=${uri.host}", "--port=${if (uri.port > 0) uri.port else 5432}", "--username=${EnvironmentConfig.dbUser}", "--dbname=${uri.path.removePrefix("/")}")
        }
        command += arguments
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["PGPASSWORD"] = EnvironmentConfig.dbPassword
        }.start()
        var output = ""
        var readFailure: Throwable? = null
        val outputReader = thread(name = "postgres-tool-output", isDaemon = true) {
            try {
                output = process.inputStream.bufferedReader().use { it.readText() }
            } catch (cause: Throwable) {
                readFailure = cause
            }
        }
        val completed = process.waitFor(30, TimeUnit.MINUTES)
        if (!completed) {
            process.destroyForcibly()
            outputReader.join(5_000)
            throw IllegalStateException("PostgreSQL utility melewati timeout 30 menit")
        }
        outputReader.join(5_000)
        if (outputReader.isAlive) throw IllegalStateException("Output PostgreSQL utility tidak selesai dibaca")
        readFailure?.let { throw IllegalStateException("Output PostgreSQL utility gagal dibaca", it) }
        if (process.exitValue() != 0) throw IllegalStateException(output.takeLast(2000).ifBlank { "PostgreSQL utility keluar dengan status gagal" })
        return output
    }

    private fun parseJdbcUri(value: String): URI {
        val uri = runCatching { URI(value.removePrefix("jdbc:")) }.getOrNull()
            ?: throw ValidationException("DB_URL tidak valid untuk backup")
        if (uri.scheme != "postgresql" || uri.host.isNullOrBlank() || uri.path.removePrefix("/").isBlank()) throw ValidationException("DB_URL PostgreSQL tidak valid")
        return uri
    }

    private suspend fun copyLimited(input: ByteReadChannel, target: Path, maxBytes: Long) = withContext(Dispatchers.IO) {
        Files.newOutputStream(target).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.readAvailable(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw ValidationException("Ukuran file melebihi batas ${EnvironmentConfig.backupMaxUploadMb} MB")
                output.write(buffer, 0, read)
            }
        }
    }

    private suspend fun sha256(path: Path): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hasPostgresHeader(path: Path): Boolean = Files.newInputStream(path).use { input ->
        val bytes = ByteArray(POSTGRES_HEADER.size)
        input.read(bytes) == bytes.size && bytes.contentEquals(POSTGRES_HEADER)
    }
    private fun safeError(cause: Throwable) = (cause.message ?: cause::class.simpleName ?: "unknown error").replace(EnvironmentConfig.dbPassword, "***").take(500)

    private companion object {
        val POSTGRES_HEADER = "PGDMP".toByteArray(Charsets.US_ASCII)
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
