package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.shared.Permission
import com.service.tbterminal.shared.requirePermission
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

internal fun Route.categoryRoutes(service: InventoryService, systemService: SystemService) {
    route("/categories") {
        get {
            val page = call.request.queryParameters["page"]?.toIntOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
            val search = call.request.queryParameters["search"]

            if (page != null || limit != null || !search.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(service.getCategories(page ?: 1, limit ?: 10, search))
                )
            } else {
                call.respond(HttpStatusCode.OK, ApiResponse.success(service.getAllCategories()))
            }
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.getCategoryById(id)))
        }

        post {
            call.requirePermission(Permission.MANAGE_INVENTORY)
            val actorUserId = call.getUserId()
            val category = service.createCategory(call.receive())
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.INSERT,
                schemaName = "inventory",
                tableName = "categories",
                recordId = category.id
            )
            call.respond(HttpStatusCode.Created, ApiResponse.success(category, "Kategori berhasil ditambahkan"))
        }

        put("/{id}") {
            call.requirePermission(Permission.MANAGE_INVENTORY)
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            val category = service.updateCategory(id, call.receive())
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.UPDATE,
                schemaName = "inventory",
                tableName = "categories",
                recordId = category.id
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(category, "Kategori berhasil diperbarui"))
        }

        delete("/{id}") {
            call.requirePermission(Permission.MANAGE_INVENTORY)
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            service.deleteCategory(id)
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.DELETE,
                schemaName = "inventory",
                tableName = "categories",
                recordId = id
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Kategori berhasil dihapus"))
        }
    }
}
