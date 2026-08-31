package com.service.tbterminal.backup

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Permission
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.shared.getUserRole
import com.service.tbterminal.shared.requirePermission
import com.service.tbterminal.system.AuditAction
import com.service.tbterminal.system.SystemService
import com.service.tbterminal.system.recordOperationalAudit
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject
import java.util.UUID

fun Application.backupRoutes() {
    val application = this
    val service: BackupService by inject()
    val systemService: SystemService by inject()
    routing {
        authenticate("jwt-auth") {
            route("/api/system/database-backups") {
                get {
                    call.requirePermission(Permission.MANAGE_DATABASE_BACKUPS)
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
                    call.respond(ApiResponse.success(service.list(call.getUserRole(), limit)))
                }
                get("/{id}") {
                    call.requirePermission(Permission.MANAGE_DATABASE_BACKUPS)
                    call.respond(ApiResponse.success(service.get(call.getUserRole(), call.uuidParameter())))
                }
                post {
                    call.requirePermission(Permission.MANAGE_DATABASE_BACKUPS)
                    val actor = call.getUserId()
                    val result = service.queueBackup(call.getUserRole(), actor)
                    systemService.recordOperationalAudit(call, actor, AuditAction.INSERT, "system", "database_backup_jobs", result.id)
                    application.launch {
                        runCatching { service.executeBackup(UUID.fromString(result.id)) }
                            .onFailure { application.environment.log.error("Background database backup failed for job ${result.id}", it) }
                    }
                    call.respond(HttpStatusCode.Accepted, ApiResponse.success(result))
                }
                get("/{id}/download") {
                    call.requirePermission(Permission.MANAGE_DATABASE_BACKUPS)
                    val id = call.uuidParameter()
                    val path = service.downloadablePath(call.getUserRole(), id)
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, path.fileName.toString()).toString()
                    )
                    call.respondFile(path.toFile())
                }
                post("/restore/validate") {
                    call.requirePermission(Permission.MANAGE_DATABASE_BACKUPS)
                    val actor = call.getUserId()
                    val actorRole = call.getUserRole()
                    var result: RestoreValidationResponse? = null
                    call.receiveMultipart().forEachPart { part ->
                        try {
                            if (part is PartData.FileItem && result == null) {
                                result = service.stageRestore(actorRole, part.provider(), actor)
                            }
                        } finally {
                            part.dispose()
                        }
                    }
                    val validated = result ?: throw ValidationException("File backup wajib diunggah")
                    systemService.recordOperationalAudit(call, actor, AuditAction.INSERT, "system", "database_backup_jobs", validated.job.id)
                    call.respond(HttpStatusCode.Created, ApiResponse.success(validated))
                }
                post("/restore/{id}/confirm") {
                    call.requirePermission(Permission.MANAGE_DATABASE_BACKUPS)
                    val actor = call.getUserId()
                    val actorRole = call.getUserRole()
                    val id = call.uuidParameter()
                    val auditIpAddress = call.request.headers["X-Forwarded-For"]
                        ?.substringBefore(",")?.trim()?.takeIf(String::isNotBlank)
                        ?: call.request.headers["X-Real-IP"]?.trim()?.takeIf(String::isNotBlank)
                    val result = service.queueRestore(actorRole, id, call.receive(), actor)
                    systemService.recordOperationalAudit(call, actor, AuditAction.UPDATE, "system", "database_backup_jobs", result.id)
                    application.launch {
                        runCatching { service.executeRestore(actorRole, id, actor) }
                            .onSuccess { restored ->
                                runCatching {
                                    systemService.recordAuditLog(
                                        restored.requestedBy?.let(UUID::fromString),
                                        AuditAction.UPDATE,
                                        "system",
                                        "database_backup_jobs",
                                        restored.id,
                                        auditIpAddress
                                    )
                                }.onFailure {
                                    application.environment.log.error("Post-restore audit failed for job ${result.id}", it)
                                }
                            }
                            .onFailure { application.environment.log.error("Background database restore failed for job ${result.id}", it) }
                    }
                    call.respond(HttpStatusCode.Accepted, ApiResponse.success(result))
                }
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.uuidParameter(): UUID =
    try {
        UUID.fromString(parameters["id"])
    } catch (_: IllegalArgumentException) {
        throw ValidationException("ID backup tidak valid")
    }
