package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

internal fun Route.unitRoutes(service: InventoryService) {
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
            val unit = service.createUnit(call.receive())
            call.respond(HttpStatusCode.Created, ApiResponse.success(unit, "Satuan berhasil ditambahkan"))
        }

        put("/{id}") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val unit = service.updateUnit(id, call.receive())
            call.respond(HttpStatusCode.OK, ApiResponse.success(unit, "Satuan berhasil diperbarui"))
        }

        delete("/{id}") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            service.deleteUnit(id)
            call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Satuan berhasil dihapus"))
        }
    }
}
