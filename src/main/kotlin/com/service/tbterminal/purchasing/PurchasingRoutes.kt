package com.service.tbterminal.purchasing

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Role
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

                    // GET list supplier — ADMIN/OWNER
                    get {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        val search = call.request.queryParameters["search"]

                        val suppliers = service.getSuppliers(page, limit, search)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(suppliers))
                    }

                    // GET detail supplier — ADMIN/OWNER
                    get("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val id = call.parameters["id"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
                        val supplier = service.getSupplierById(id)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(supplier))
                    }

                    // POST buat supplier baru — ADMIN/OWNER
                    post {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val request = call.receive<SupplierRequest>()
                        val supplier = service.createSupplier(request)
                        call.respond(HttpStatusCode.Created, ApiResponse.success(supplier, "Supplier berhasil ditambahkan"))
                    }

                    // PUT update supplier — ADMIN/OWNER
                    put("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val id = call.parameters["id"]
                            ?: return@put call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
                        val request = call.receive<SupplierRequest>()
                        val supplier = service.updateSupplier(id, request)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(supplier, "Supplier berhasil diperbarui"))
                    }

                    // DELETE soft delete supplier — ADMIN/OWNER
                    delete("/{id}") {
                        call.requireRole(Role.ADMIN, Role.OWNER)
                        val id = call.parameters["id"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
                        service.deleteSupplier(id)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Supplier berhasil dihapus"))
                    }
                }
            }
        }
    }
}
