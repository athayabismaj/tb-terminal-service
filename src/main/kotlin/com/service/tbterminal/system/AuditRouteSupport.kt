package com.service.tbterminal.system

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import com.service.tbterminal.shared.clientIpAddress
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
            ipAddress = call.clientIpAddress()
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
