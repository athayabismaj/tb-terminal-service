package com.service.tbterminal.backup

import com.service.tbterminal.system.AuditAction
import com.service.tbterminal.system.SystemService
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject
import kotlin.time.Duration.Companion.hours

fun Application.configureBackupScheduler() {
    val backupService: BackupService by inject()
    val systemService: SystemService by inject()
    launch {
        delay(1.hours)
        while (true) {
            runCatching { backupService.runScheduledBackupIfDue() }
                .onSuccess { job ->
                    if (job != null) systemService.recordAuditLog(null, AuditAction.INSERT, "system", "database_backup_jobs", job.id, null)
                }
                .onFailure { log.error("Scheduled database backup failed: ${it.message?.take(300)}") }
            delay(1.hours)
        }
    }
}

