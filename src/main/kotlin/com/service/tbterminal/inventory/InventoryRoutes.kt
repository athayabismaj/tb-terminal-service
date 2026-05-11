package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.requireRole
import com.service.tbterminal.shared.getUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.inventoryRoutes() {
    val service by inject<InventoryService>()

    authenticate("jwt-auth") {
        route("/api/inventory") {
            
            // ==========================================
            // CATEGORIES ROUTES
            // ==========================================
            route("/categories") {
                // GET is open to all authenticated users (owner, admin, kasir)
                get {
                    val categories = service.getAllCategories()
                    call.respond(HttpStatusCode.OK, ApiResponse.success(categories))
                }

                get("/{id}") {
                    val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val category = service.getCategoryById(id)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(category))
                }

                // POST, PUT, DELETE restricted to Management roles
                post {
                    call.requireRole(Role.OWNER, Role.ADMIN)
                    val request = call.receive<CategoryRequest>()
                    val category = service.createCategory(request)
                    call.respond(HttpStatusCode.Created, ApiResponse.success(category, "Kategori berhasil ditambahkan"))
                }

                put("/{id}") {
                    call.requireRole(Role.OWNER, Role.ADMIN)
                    val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val request = call.receive<CategoryRequest>()
                    val category = service.updateCategory(id, request)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(category, "Kategori berhasil diperbarui"))
                }

                delete("/{id}") {
                    call.requireRole(Role.OWNER, Role.ADMIN)
                    val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    service.deleteCategory(id)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Kategori berhasil dihapus"))
                }
            }

            // ==========================================
            // UNITS ROUTES
            // ==========================================
            route("/units") {
                // GET is open to all authenticated users
                get {
                    val units = service.getAllUnits()
                    call.respond(HttpStatusCode.OK, ApiResponse.success(units))
                }

                get("/{id}") {
                    val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val unit = service.getUnitById(id)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(unit))
                }

                // POST, PUT, DELETE restricted to Management roles
                post {
                    call.requireRole(Role.OWNER, Role.ADMIN)
                    val request = call.receive<UnitRequest>()
                    val unit = service.createUnit(request)
                    call.respond(HttpStatusCode.Created, ApiResponse.success(unit, "Satuan berhasil ditambahkan"))
                }

                put("/{id}") {
                    call.requireRole(Role.OWNER, Role.ADMIN)
                    val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val request = call.receive<UnitRequest>()
                    val unit = service.updateUnit(id, request)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(unit, "Satuan berhasil diperbarui"))
                }

                delete("/{id}") {
                    call.requireRole(Role.OWNER, Role.ADMIN)
                    val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    service.deleteUnit(id)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Satuan berhasil dihapus"))
                }
            }
            // ==========================================
            // PRODUCTS ROUTES
            // ==========================================
            route("/products") {
                // GET is open to all authenticated users
                get {
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                    val search = call.request.queryParameters["search"]
                    
                    val products = service.getProducts(page, limit, search)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(products))
                }

                get("/{id}") {
                    val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val product = service.getProductById(id)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(product))
                }

                // POST, PUT, DELETE restricted to Management roles
                post {
                    call.requireRole(Role.OWNER, Role.ADMIN)
                    val request = call.receive<ProductCreateRequest>()
                    val product = service.createProduct(request)
                    call.respond(HttpStatusCode.Created, ApiResponse.success(product, "Produk berhasil ditambahkan"))
                }

                put("/{id}") {
                    call.requireRole(Role.OWNER, Role.ADMIN)
                    val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val request = call.receive<ProductUpdateRequest>()
                    val product = service.updateProduct(id, request)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(product, "Produk berhasil diperbarui"))
                }

                delete("/{id}") {
                    call.requireRole(Role.OWNER, Role.ADMIN)
                    val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    service.deleteProduct(id)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Produk berhasil dihapus"))
                }
            }
            // ==========================================
            // STOCK ROUTES
            // ==========================================
            route("/stock") {
                // GET is open to all authenticated users
                get {
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                    val search = call.request.queryParameters["search"]
                    
                    val stockDetails = service.getStockDetails(page, limit, search)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(stockDetails))
                }

                // POST restricted to Management roles
                post("/opname") {
                    call.requireRole(Role.OWNER, Role.ADMIN)
                    val userId = call.getUserId().toString()
                    val request = call.receive<StockOpnameRequest>()
                    
                    service.executeOpname(userId, request)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Penyesuaian stok berhasil disimpan"))
                }
            }
        }
    }
}
