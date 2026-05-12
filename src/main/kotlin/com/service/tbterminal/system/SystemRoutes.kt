package com.service.tbterminal.system

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.requireRole
import com.service.tbterminal.shared.getUserId

fun Application.systemRoutes() {
    val service: SystemService by inject()

    routing {
        route("/api/auth") {
            post("/login") {
                try {
                    val req = call.receive<LoginRequest>()
                    val response = service.login(req)
                    if (response.success) {
                        call.respond(HttpStatusCode.OK, response)
                    } else {
                        call.respond(HttpStatusCode.Unauthorized, response)
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("Request tidak valid", "VALIDATION_ERROR"))
                }
            }

            authenticate("jwt-auth") {
                post("/unlock") {
                    try {
                        val userId = call.getUserId()
                        val req = call.receive<UnlockRequest>()
                        val response = service.unlock(userId, req)
                        if (response.success) {
                            call.respond(HttpStatusCode.OK, response)
                        } else {
                            call.respond(HttpStatusCode.Unauthorized, response)
                        }
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("Request tidak valid", "VALIDATION_ERROR"))
                    }
                }

                get("/me") {
                    val principal = call.principal<JWTPrincipal>()
                    val username = principal?.payload?.getClaim("username")?.asString()
                    val role = principal?.payload?.getClaim("role")?.asString()
                    
                    if (username != null && role != null) {
                        call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("username" to username, "role" to role)))
                    } else {
                        call.respond(HttpStatusCode.Unauthorized, ApiResponse.error<Unit>("Token tidak valid", "UNAUTHORIZED"))
                    }
                }
            }
        }

        // ==========================================
        // SYSTEM MANAGEMENT ROUTES
        // ==========================================
        authenticate("jwt-auth") {
            route("/api/system") {
                
                // ROLES
                get("/roles") {
                    call.requireRole(com.service.tbterminal.shared.Role.ADMIN, com.service.tbterminal.shared.Role.OWNER)
                    val roles = service.getRoles()
                    call.respond(HttpStatusCode.OK, ApiResponse.success(roles))
                }

                // USERS
                route("/users") {
                    // Update Password sendiri (bisa oleh Kasir, Admin, Owner)
                    put("/me/password") {
                        val userId = call.getUserId()
                        val request = call.receive<ChangePasswordRequest>()
                        service.changeMyPassword(userId, request)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Password berhasil diubah"))
                    }

                    // Update PIN sendiri (bisa oleh Kasir, Admin, Owner)
                    put("/me/pin") {
                        val userId = call.getUserId()
                        val request = call.receive<ChangePinRequest>()
                        service.changeMyPin(userId, request)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "PIN berhasil diubah"))
                    }

                    // List Users
                    get {
                        call.requireRole(com.service.tbterminal.shared.Role.ADMIN, com.service.tbterminal.shared.Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        val search = call.request.queryParameters["search"]
                        
                        val users = service.getUsers(page, limit, search)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(users))
                    }

                    // Create User
                    post {
                        call.requireRole(com.service.tbterminal.shared.Role.ADMIN, com.service.tbterminal.shared.Role.OWNER)
                        val request = call.receive<UserCreateRequest>()
                        val user = service.createUser(request)
                        call.respond(HttpStatusCode.Created, ApiResponse.success(user, "User berhasil dibuat"))
                    }

                    // Update User
                    put("/{id}") {
                        call.requireRole(com.service.tbterminal.shared.Role.ADMIN, com.service.tbterminal.shared.Role.OWNER)
                        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
                        val request = call.receive<UserUpdateRequest>()
                        val user = service.updateUser(id, request)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(user, "User berhasil diperbarui"))
                    }

                    // Delete User (Soft Delete)
                    delete("/{id}") {
                        call.requireRole(com.service.tbterminal.shared.Role.OWNER) // Hanya owner yg boleh hapus
                        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>("ID tidak ditemukan"))
                        service.deleteUser(id)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "User berhasil dihapus"))
                    }
                }

                // ==========================================
                // STORE SETTINGS
                // ==========================================
                route("/settings") {
                    // KASIR butuh untuk baca footer dan ukuran printer sebelum print struk
                    get {
                        val settings = service.getStoreSettings()
                        call.respond(HttpStatusCode.OK, ApiResponse.success(settings))
                    }

                    // Update hanya boleh ADMIN/OWNER
                    put {
                        call.requireRole(com.service.tbterminal.shared.Role.ADMIN, com.service.tbterminal.shared.Role.OWNER)
                        val userId = call.getUserId()
                        val request = call.receive<StoreSettingsUpdateRequest>()
                        val updated = service.updateStoreSettings(userId, request)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(updated, "Pengaturan toko berhasil diperbarui"))
                    }
                }
            }
        }
    }
}
