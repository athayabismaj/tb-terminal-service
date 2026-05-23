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

internal fun Route.categoryRoutes(service: InventoryService) {
    route("/categories") {
        get {
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.getAllCategories()))
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.getCategoryById(id)))
        }

        post {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val category = service.createCategory(call.receive())
            call.respond(HttpStatusCode.Created, ApiResponse.success(category, "Kategori berhasil ditambahkan"))
        }

        put("/{id}") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val category = service.updateCategory(id, call.receive())
            call.respond(HttpStatusCode.OK, ApiResponse.success(category, "Kategori berhasil diperbarui"))
        }

        delete("/{id}") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            service.deleteCategory(id)
            call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Kategori berhasil dihapus"))
        }
    }
}
