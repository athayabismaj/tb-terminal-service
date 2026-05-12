package com.service.tbterminal.receivable

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

fun Application.receivableRoutes() {
    val service by inject<ReceivableService>()

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
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
                        val customer = service.getCustomerById(id)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(customer))
                    }

                    // POST buat pelanggan baru — hanya ADMIN/OWNER
                    post {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val request = call.receive<CustomerRequest>()
                        val customer = service.createCustomer(request)
                        call.respond(HttpStatusCode.Created, ApiResponse.success(customer, "Pelanggan berhasil ditambahkan"))
                    }

                    // PUT update pelanggan — hanya ADMIN/OWNER
                    put("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val id = call.parameters["id"]
                            ?: return@put call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
                        val request = call.receive<CustomerRequest>()
                        val customer = service.updateCustomer(id, request)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(customer, "Pelanggan berhasil diperbarui"))
                    }

                    // DELETE soft delete pelanggan — hanya ADMIN/OWNER
                    delete("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val id = call.parameters["id"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
                        service.deleteCustomer(id)
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
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
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
                        call.respond(HttpStatusCode.Created, ApiResponse.success(payment, "Pembayaran berhasil dicatat"))
                    }
                }
            }
        }
    }
}

