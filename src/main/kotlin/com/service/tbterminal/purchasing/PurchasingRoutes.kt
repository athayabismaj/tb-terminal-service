package com.service.tbterminal.purchasing

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.shared.requireRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.purchasingRoutes() {
    val service by inject<PurchasingService>()

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
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
                        val supplier = service.getSupplierById(id)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(supplier))
                    }

                    post {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val request = call.receive<SupplierRequest>()
                        val supplier = service.createSupplier(request)
                        call.respond(HttpStatusCode.Created, ApiResponse.success(supplier, "Supplier berhasil ditambahkan"))
                    }

                    put("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val id = call.parameters["id"]
                            ?: return@put call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
                        val request = call.receive<SupplierRequest>()
                        val supplier = service.updateSupplier(id, request)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(supplier, "Supplier berhasil diperbarui"))
                    }

                    delete("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val id = call.parameters["id"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
                        service.deleteSupplier(id)
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
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
                        val purchase = service.getPurchaseById(id)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(purchase))
                    }

                    // POST buat nota beli — ADMIN/OWNER
                    post {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<PurchaseRequest>()
                        val purchase = service.purchase(userId, request)
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
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
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
                            call.respond(HttpStatusCode.Created, ApiResponse.success(payment, "Pembayaran hutang berhasil dicatat"))
                        }
                    }
                }
            }
        }
    }
}
