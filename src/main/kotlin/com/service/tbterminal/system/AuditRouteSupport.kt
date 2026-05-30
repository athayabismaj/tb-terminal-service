package com.service.tbterminal.system

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException
import java.util.UUID

suspend fun SystemService.recordOperationalAudit(
    call: ApplicationCall,
    actorUserId: UUID?,
    action: AuditAction,
    schemaName: String,
    tableName: String,
    recordId: String?
) {
    try {
        recordAuditLog(
            actorUserId = actorUserId,
            action = action,
            schemaName = schemaName,
            tableName = tableName,
            recordId = recordId,
            ipAddress = call.auditClientIpAddress()
        )
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        call.application.log.error(
            "Operational audit logging failed: action=$action schema=$schemaName table=$tableName recordId=$recordId actorUserId=$actorUserId",
            cause
        )
    }
}

private fun ApplicationCall.auditClientIpAddress(): String? {
    return request.headers["X-Forwarded-For"]
        ?.substringBefore(",")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: request.headers["X-Real-IP"]?.trim()?.takeIf(String::isNotBlank)
}
