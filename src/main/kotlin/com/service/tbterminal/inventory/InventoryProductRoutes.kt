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
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

internal fun Route.productRoutes(service: InventoryService, systemService: SystemService) {
    route("/products") {
        get {
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val products = service.getProducts(page, limit, call.request.queryParameters["search"])
            call.respond(HttpStatusCode.OK, ApiResponse.success(products))
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(HttpStatusCode.OK, ApiResponse.success(service.getProductById(id)))
        }

        post {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val actorUserId = call.getUserId()
            val product = service.createProduct(call.receive())
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.INSERT,
                schemaName = "inventory",
                tableName = "products",
                recordId = product.id
            )
            call.respond(HttpStatusCode.Created, ApiResponse.success(product, "Produk berhasil ditambahkan"))
        }

        put("/{id}") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            val previous = service.getProductById(id)
            val request = call.receive<ProductUpdateRequest>()
            val product = service.updateProduct(id, request)
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.UPDATE,
                schemaName = "inventory",
                tableName = "products",
                recordId = product.id
            )
            if (previous.hasDifferentPriceThan(product)) {
                systemService.recordOperationalAudit(
                    call = call,
                    actorUserId = actorUserId,
                    action = AuditAction.UPDATE,
                    schemaName = "inventory",
                    tableName = "products_price",
                    recordId = product.id
                )
            }
            call.respond(HttpStatusCode.OK, ApiResponse.success(product, "Produk berhasil diperbarui"))
        }

        delete("/{id}") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            service.deleteProduct(id)
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.DELETE,
                schemaName = "inventory",
                tableName = "products",
                recordId = id
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Produk berhasil dihapus"))
        }

        patch("/{id}/activate") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val actorUserId = call.getUserId()
            val product = service.activateProduct(id)
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.UPDATE,
                schemaName = "inventory",
                tableName = "products",
                recordId = product.id
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(product, "Produk berhasil diaktifkan kembali"))
        }
    }
}

private fun ProductResponse.hasDifferentPriceThan(other: ProductResponse): Boolean {
    return priceBuy.compareTo(other.priceBuy) != 0 ||
        priceRetail.compareTo(other.priceRetail) != 0 ||
        priceContractor.compareTo(other.priceContractor) != 0 ||
        discount.compareTo(other.discount) != 0
}
