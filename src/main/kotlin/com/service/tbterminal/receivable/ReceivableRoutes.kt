package com.service.tbterminal.receivable

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
import org.koin.ktor.ext.inject

fun Application.receivableRoutes() {
    val service by inject<ReceivableService>()
    val systemService by inject<SystemService>()

    routing {
        authenticate("jwt-auth") {
            route("/api/receivable") {

                // ==========================================
                // CUSTOMER ROUTES
                // ==========================================
                route("/customers") {

                    // GET list pelanggan — akses semua role
                    get {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        val search = call.request.queryParameters["search"]

                        val customers = service.getCustomers(page, limit, search)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(customers))
                    }

                    // GET detail pelanggan — akses semua role
                    get("/{id}") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val id = call.parameters["id"]
                            ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan")
                            )
                        val customer = service.getCustomerById(id)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(customer))
                    }

                    // POST buat pelanggan baru — hanya ADMIN/OWNER
                    post {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val actorUserId = call.getUserId()
                        val request = call.receive<CustomerRequest>()
                        val customer = service.createCustomer(request)
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = actorUserId,
                            action = AuditAction.INSERT,
                            schemaName = "receivable",
                            tableName = "customers",
                            recordId = customer.id
                        )
                        call.respond(HttpStatusCode.Created, ApiResponse.success(customer, "Pelanggan berhasil ditambahkan"))
                    }

                    // PUT update pelanggan — hanya ADMIN/OWNER
                    put("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val actorUserId = call.getUserId()
                        val id = call.parameters["id"]
                            ?: return@put call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan")
                        )
                        val request = call.receive<CustomerRequest>()
                        val customer = service.updateCustomer(id, request)
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = actorUserId,
                            action = AuditAction.UPDATE,
                            schemaName = "receivable",
                            tableName = "customers",
                            recordId = customer.id
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(customer, "Pelanggan berhasil diperbarui"))
                    }

                    // DELETE soft delete pelanggan — hanya ADMIN/OWNER
                    delete("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val actorUserId = call.getUserId()
                        val id = call.parameters["id"]
                            ?: return@delete call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan")
                            )
                        service.deleteCustomer(id)
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = actorUserId,
                            action = AuditAction.DELETE,
                            schemaName = "receivable",
                            tableName = "customers",
                            recordId = id
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Pelanggan berhasil dihapus"))
                    }
                }

                // ==========================================
                // RECEIVABLES ROUTES
                // ==========================================
                route("/receivables") {

                    // GET list piutang — akses semua role
                    get {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        val customerId = call.request.queryParameters["customerId"]
                        val status = call.request.queryParameters["status"]

                        val receivables = service.getReceivables(page, limit, customerId, status)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(receivables))
                    }

                    // GET detail piutang — akses semua role
                    get("/{id}") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val id = call.parameters["id"]
                            ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan")
                            )
                        val receivable = service.getReceivableById(id)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(receivable))
                    }
                }

                // ==========================================
                // PAYMENTS ROUTES
                // ==========================================
                route("/payments") {

                    // POST bayar piutang — akses semua role (kasir di lapangan bisa terima cicilan)
                    post {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<PaymentRequest>()
                        val payment = service.pay(userId, request)
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = userId,
                            action = AuditAction.INSERT,
                            schemaName = "receivable",
                            tableName = "receivable_payments",
                            recordId = payment.id
                        )
                        systemService.recordOperationalAudit(
                            call = call,
                            actorUserId = userId,
                            action = AuditAction.UPDATE,
                            schemaName = "receivable",
                            tableName = "receivables",
                            recordId = payment.receivableId
                        )
                        call.respond(HttpStatusCode.Created, ApiResponse.success(payment, "Pembayaran berhasil dicatat"))
                    }
                }
            }
        }
    }
}
