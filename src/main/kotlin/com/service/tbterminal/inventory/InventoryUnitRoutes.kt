package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.shared.requireRole
import com.service.tbterminal.system.AuditAction
import com.service.tbterminal.system.SystemService
import com.service.tbterminal.system.recordOperationalAudit
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

internal fun Route.unitRoutes(service: InventoryService, systemService: SystemService) {
    route("/units") {
        get {
            val page = call.request.queryParameters["page"]?.toIntOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
            val search = call.request.queryParameters["search"]

            if (page != null || limit != null || !search.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(service.getUnits(page ?: 1, limit ?: 10, search))
                )
            } else {
                call.respond(HttpStatusCode.OK, ApiResponse.success(service.getAllUnits()))
            }
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.getUnitById(id)))
        }

        post {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val actorUserId = call.getUserId()
            val unit = service.createUnit(call.receive())
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.INSERT,
                schemaName = "inventory",
                tableName = "units",
                recordId = unit.id
            )
            call.respond(HttpStatusCode.Created, ApiResponse.success(unit, "Satuan berhasil ditambahkan"))
        }

        put("/{id}") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            val unit = service.updateUnit(id, call.receive())
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.UPDATE,
                schemaName = "inventory",
                tableName = "units",
                recordId = unit.id
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(unit, "Satuan berhasil diperbarui"))
        }

        delete("/{id}") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            service.deleteUnit(id)
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.DELETE,
                schemaName = "inventory",
                tableName = "units",
                recordId = id
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Satuan berhasil dihapus"))
        }
    }
}
