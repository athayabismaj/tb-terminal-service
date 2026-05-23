package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.shared.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Route.stockRoutes(service: InventoryService) {
    route("/stock") {
        get {
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val stock = service.getStockDetails(page, limit, call.request.queryParameters["search"])
            call.respond(HttpStatusCode.OK, ApiResponse.success(stock))
        }

        post("/opname") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            service.executeOpname(call.getUserId().toString(), call.receive())
            call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Penyesuaian stok berhasil disimpan"))
        }
    }
}
