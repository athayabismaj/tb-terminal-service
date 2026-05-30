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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Route.stockRoutes(service: InventoryService, systemService: SystemService) {
    route("/stock") {
        get {
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val stock = service.getStockDetails(page, limit, call.request.queryParameters["search"])
            call.respond(HttpStatusCode.OK, ApiResponse.success(stock))
        }

        get("/adjustments") {
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val adjustments = service.getStockAdjustments(
                page = page,
                limit = limit,
                search = call.request.queryParameters["search"],
                type = call.request.queryParameters["type"]
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(adjustments))
        }

        post("/opname") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val actorUserId = call.getUserId()
            val request = call.receive<StockOpnameRequest>()
            service.executeOpname(actorUserId.toString(), request)
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.INSERT,
                schemaName = "inventory",
                tableName = request.operationalAuditTableName(),
                recordId = request.productId
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Penyesuaian stok berhasil disimpan"))
        }
    }
}

private fun StockOpnameRequest.operationalAuditTableName(): String {
    return when (adjustmentType.uppercase()) {
        "OPNAME" -> "stock_opname"
        "CORRECTION" -> "stock_correction"
        "DAMAGE" -> "stock_damage"
        else -> "stock_adjustments"
    }
}
