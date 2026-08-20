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
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Route.inventoryImportRoutes(service: InventoryService, systemService: SystemService) {
    route("/imports/products") {
        post("/preview") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val preview = service.previewProductImport(call.receive())
            call.respond(HttpStatusCode.OK, ApiResponse.success(preview, "Preview CSV berhasil dibuat"))
        }

        post("/commit") {
            call.requireRole(Role.OWNER, Role.ADMIN)
            val actorUserId = call.getUserId()
            val result = service.importProducts(actorUserId.toString(), call.receive())
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.INSERT,
                schemaName = "inventory",
                tableName = "product_csv_import",
                recordId = null
            )
            if (result.openingBalances > 0) {
                systemService.recordOperationalAudit(
                    call = call,
                    actorUserId = actorUserId,
                    action = AuditAction.INSERT,
                    schemaName = "inventory",
                    tableName = "opening_stock_csv_import",
                    recordId = null
                )
            }
            call.respond(HttpStatusCode.Created, ApiResponse.success(result, "Impor produk berhasil disimpan secara atomik"))
        }
    }
}
