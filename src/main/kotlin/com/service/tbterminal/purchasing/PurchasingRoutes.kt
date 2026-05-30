package com.service.tbterminal.purchasing

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.ErrorResponse
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.shared.requireRole
import com.service.tbterminal.system.AuditAction
import com.service.tbterminal.system.SystemService
import com.service.tbterminal.system.recordOperationalAudit
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import org.koin.ktor.ext.inject
import java.util.UUID

fun Application.purchasingRoutes() {
    val service by inject<PurchasingService>()
    val systemService by inject<SystemService>()

    routing {
        authenticate("jwt-auth") {
            route("/api/purchasing") {

                // ==========================================
                // SUPPLIER ROUTES
                // ==========================================
                route("/suppliers") {

                    get {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        val search = call.request.queryParameters["search"]

                        val suppliers = service.getSuppliers(page, limit, search)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(suppliers))
                    }

                    get("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val id = call.parameters["id"]
                            ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan")
                            )
                        val supplier = service.getSupplierById(id)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(supplier))
                    }

                    post {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val actorUserId = call.getUserId()
                        val request = call.receive<SupplierRequest>()
                        val supplier = service.createSupplier(request)
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = actorUserId,
                            action = AuditAction.INSERT,
                            schemaName = "purchasing",
                            tableName = "suppliers",
                            recordId = supplier.id
                        )
                        call.respond(HttpStatusCode.Created, ApiResponse.success(supplier, "Supplier berhasil ditambahkan"))
                    }

                    put("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val actorUserId = call.getUserId()
                        val id = call.parameters["id"]
                            ?: return@put call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan")
                        )
                        val request = call.receive<SupplierRequest>()
                        val supplier = service.updateSupplier(id, request)
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = actorUserId,
                            action = AuditAction.UPDATE,
                            schemaName = "purchasing",
                            tableName = "suppliers",
                            recordId = supplier.id
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(supplier, "Supplier berhasil diperbarui"))
                    }

                    delete("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val actorUserId = call.getUserId()
                        val id = call.parameters["id"]
                            ?: return@delete call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan")
                            )
                        service.deleteSupplier(id)
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = actorUserId,
                            action = AuditAction.DELETE,
                            schemaName = "purchasing",
                            tableName = "suppliers",
                            recordId = id
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Supplier berhasil dihapus"))
                    }
                }

                // ==========================================
                // PURCHASE ROUTES
                // ==========================================
                route("/purchases") {

                    // GET list nota beli — ADMIN/OWNER
                    get {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        val supplierId = call.request.queryParameters["supplierId"]

                        val purchases = service.getPurchases(page, limit, supplierId)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(purchases))
                    }

                    // GET detail nota beli — ADMIN/OWNER
                    get("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val id = call.parameters["id"]
                            ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan")
                            )
                        val purchase = service.getPurchaseById(id)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(purchase))
                    }

                    // POST buat nota beli — ADMIN/OWNER
                    post {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<PurchaseRequest>()
                        val purchase = service.purchase(userId, request)
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = userId,
                            action = AuditAction.INSERT,
                            schemaName = "purchasing",
                            tableName = "purchases",
                            recordId = purchase.id
                        )
                        recordPurchaseDerivedAudits(
                            call = call,
                            service = service,
                            systemService = systemService,
                            actorUserId = userId,
                            purchase = purchase
                        )
                        call.respond(HttpStatusCode.Created, ApiResponse.success(purchase, "Pembelian berhasil dicatat"))
                    }
                }

                // ==========================================
                // PAYABLE ROUTES
                // ==========================================
                route("/payables") {

                    // GET list hutang — ADMIN/OWNER
                    get {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        val supplierId = call.request.queryParameters["supplierId"]
                        val status = call.request.queryParameters["status"]

                        val payables = service.getPayables(page, limit, supplierId, status)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(payables))
                    }

                    // GET detail hutang — ADMIN/OWNER
                    get("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val id = call.parameters["id"]
                            ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan")
                            )
                        val payable = service.getPayableById(id)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(payable))
                    }

                    // POST bayar hutang supplier — ADMIN/OWNER
                    route("/payments") {
                        post {
                            call.requireRole(Role.ADMIN, Role.OWNER)
                            val userId = call.getUserId()
                            val request = call.receive<SupplierPaymentRequest>()
                            val payment = service.paySupplier(userId, request)
                            systemService.recordOperationalAudit(
                                call = call,
                                actorUserId = userId,
                                action = AuditAction.INSERT,
                                schemaName = "purchasing",
                                tableName = "supplier_payments",
                                recordId = payment.id
                            )
                            systemService.recordOperationalAudit(
                                call = call,
                                actorUserId = userId,
                                action = AuditAction.UPDATE,
                                schemaName = "purchasing",
                                tableName = "supplier_payables",
                                recordId = payment.payableId
                            )
                            call.respond(HttpStatusCode.Created, ApiResponse.success(payment, "Pembayaran hutang berhasil dicatat"))
                        }
                    }
                }
            }
        }
    }
}

private suspend fun recordPurchaseDerivedAudits(
    call: ApplicationCall,
    service: PurchasingService,
    systemService: SystemService,
    actorUserId: UUID,
    purchase: PurchaseResponse
) {
    recordPurchasePayableAudit(
        call = call,
        service = service,
        systemService = systemService,
        actorUserId = actorUserId,
        purchaseId = purchase.id
    )

    purchase.items
        .filter { item -> item.priceAtTransaction.compareTo(item.cogsAtTransaction) != 0 }
        .distinctBy { item -> item.productId }
        .forEach { item ->
            systemService.recordOperationalAudit(
                call = call,
                actorUserId = actorUserId,
                action = AuditAction.UPDATE,
                schemaName = "inventory",
                tableName = "products_price",
                recordId = item.productId
            )
        }
}

private suspend fun recordPurchasePayableAudit(
    call: ApplicationCall,
    service: PurchasingService,
    systemService: SystemService,
    actorUserId: UUID,
    purchaseId: String
) {
    try {
        val payable = service.getPayableByPurchaseId(purchaseId) ?: return
        systemService.recordOperationalAudit(
            call = call,
            actorUserId = actorUserId,
            action = AuditAction.INSERT,
            schemaName = "purchasing",
            tableName = "supplier_payables",
            recordId = payable.id
        )
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        call.application.log.error("Purchase payable audit lookup failed: purchaseId=$purchaseId", cause)
    }
}
