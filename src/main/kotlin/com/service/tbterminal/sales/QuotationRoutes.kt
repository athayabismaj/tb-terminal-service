package com.service.tbterminal.sales

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Permission
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.shared.getUserRole
import com.service.tbterminal.shared.requirePermission
import com.service.tbterminal.system.AuditAction
import com.service.tbterminal.system.SystemService
import com.service.tbterminal.system.recordOperationalAudit
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject

fun Application.quotationRoutes() {
    val quotationService by inject<QuotationService>()
    val systemService by inject<SystemService>()

    routing {
        authenticate("jwt-auth") {
            route("/api/sales/quotations") {
                get {
                    call.requirePermission(Permission.OPERATE_POS)
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                    val status = call.request.queryParameters["status"]
                    val search = call.request.queryParameters["search"]
                    val result = quotationService.getQuotations(
                        page = page,
                        limit = limit,
                        status = status,
                        search = search,
                        actorUserId = call.getUserId(),
                        actorRole = call.getUserRole(),
                    )
                    call.respond(HttpStatusCode.OK, ApiResponse.success(result))
                }

                post {
                    call.requirePermission(Permission.OPERATE_POS)
                    val userId = call.getUserId()
                    val request = call.receive<CreateQuotationRequest>()
                    val created = quotationService.createQuotation(userId, request)
                    systemService.recordOperationalAudit(
                        call = call,
                        actorUserId = userId,
                        action = AuditAction.INSERT,
                        schemaName = "sales",
                        tableName = "quotations",
                        recordId = created.id,
                    )
                    call.respond(
                        HttpStatusCode.Created,
                        ApiResponse.success(created, "Penawaran harga berhasil disimpan")
                    )
                }

                get("/{id}") {
                    call.requirePermission(Permission.OPERATE_POS)
                    val id = call.parameters["id"]
                        ?: throw ValidationException("ID penawaran wajib diisi")
                    val detail = quotationService.getQuotationById(
                        quotationIdRaw = id,
                        actorUserId = call.getUserId(),
                        actorRole = call.getUserRole(),
                    )
                    call.respond(HttpStatusCode.OK, ApiResponse.success(detail))
                }

                patch("/{id}/status") {
                    call.requirePermission(Permission.OPERATE_POS)
                    val userId = call.getUserId()
                    val id = call.parameters["id"]
                        ?: throw ValidationException("ID penawaran wajib diisi")
                    val request = call.receive<UpdateQuotationStatusRequest>()
                    val updated = quotationService.updateQuotationStatus(
                        quotationIdRaw = id,
                        actorUserId = userId,
                        actorRole = call.getUserRole(),
                        request = request,
                    )
                    systemService.recordOperationalAudit(
                        call = call,
                        actorUserId = userId,
                        action = AuditAction.UPDATE,
                        schemaName = "sales",
                        tableName = "quotations",
                        recordId = updated.id,
                    )
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse.success(updated, "Status penawaran harga diperbarui")
                    )
                }
            }
        }
    }
}
