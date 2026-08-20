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

                    get("/{id}/payments") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val customerId = call.parameters["id"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan"))
                        val payments = service.getPayments(
                            page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1,
                            limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20,
                            customerId = customerId,
                            method = call.request.queryParameters["method"],
                            userId = call.request.queryParameters["userId"],
                            receiverSearch = call.request.queryParameters["receiverSearch"],
                            status = call.request.queryParameters["status"],
                            dateFrom = call.request.queryParameters["dateFrom"],
                            dateTo = call.request.queryParameters["dateTo"]
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(payments))
                    }

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

                    get("/{id}/payments") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val receivableId = call.parameters["id"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan"))
                        val payments = service.getPayments(
                            page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1,
                            limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20,
                            receivableId = receivableId,
                            method = call.request.queryParameters["method"],
                            userId = call.request.queryParameters["userId"],
                            status = call.request.queryParameters["status"],
                            dateFrom = call.request.queryParameters["dateFrom"],
                            dateTo = call.request.queryParameters["dateTo"]
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(payments))
                    }

                    // GET list piutang — akses semua role
                    get {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        val customerId = call.request.queryParameters["customerId"]
                        val status = call.request.queryParameters["status"]
                        val dueFilter = call.request.queryParameters["dueFilter"]
                        val dueFrom = call.request.queryParameters["dueFrom"]
                        val dueTo = call.request.queryParameters["dueTo"]

                        val receivables = service.getReceivables(
                            page, limit, customerId, status, dueFilter, dueFrom, dueTo
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(receivables))
                    }

                    get("/summary/customers") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        val dueFilter = call.request.queryParameters["dueFilter"]
                        val summaries = service.getCustomerSummaries(page, limit, dueFilter)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(summaries))
                    }

                    post("/opening-balance") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<CreateStandaloneReceivableRequest>()
                        val receivable = service.createStandaloneReceivable(
                            userId,
                            request.copy(source = ReceivableSource.OPENING_BALANCE.name)
                        )
                        call.respond(
                            HttpStatusCode.Created,
                            ApiResponse.success(receivable, "Saldo awal piutang berhasil dicatat")
                        )
                    }

                    post("/adjustment") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<CreateStandaloneReceivableRequest>()
                        val receivable = service.createStandaloneReceivable(
                            userId,
                            request.copy(source = ReceivableSource.ADJUSTMENT.name)
                        )
                        call.respond(
                            HttpStatusCode.Created,
                            ApiResponse.success(receivable, "Penyesuaian piutang berhasil dicatat")
                        )
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

                    // GET riwayat pembayaran piutang - audit operasional admin/owner
                    get {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        val payments = service.getPayments(
                            page = page,
                            limit = limit,
                            receivableId = call.request.queryParameters["receivableId"],
                            customerId = call.request.queryParameters["customerId"],
                            method = call.request.queryParameters["method"],
                            userId = call.request.queryParameters["userId"],
                            customerSearch = call.request.queryParameters["customerSearch"],
                            receiverSearch = call.request.queryParameters["receiverSearch"],
                            status = call.request.queryParameters["status"],
                            dateFrom = call.request.queryParameters["dateFrom"],
                            dateTo = call.request.queryParameters["dateTo"]
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(payments))
                    }

                    get("/{id}/receipt") {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val paymentId = call.parameters["id"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan"))
                        call.respond(HttpStatusCode.OK, ApiResponse.success(service.getPaymentReceipt(paymentId)))
                    }

                    post("/{id}/reversal") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val paymentId = call.parameters["id"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan"))
                        val reversal = service.reversePayment(
                            userId = call.getUserId(),
                            paymentId = paymentId,
                            request = call.receive<ReversePaymentRequest>()
                        )
                        call.respond(HttpStatusCode.Created, ApiResponse.success(reversal, "Reversal pembayaran berhasil dicatat"))
                    }

                    // POST bayar piutang — akses semua role (kasir di lapangan bisa terima cicilan)
                    post {
                        call.requireRole(Role.KASIR, Role.ADMIN, Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<PaymentRequest>()
                        val payment = service.pay(userId, request)
                        call.respond(HttpStatusCode.Created, ApiResponse.success(payment, "Pembayaran berhasil dicatat"))
                    }
                }
            }
        }
    }
}
