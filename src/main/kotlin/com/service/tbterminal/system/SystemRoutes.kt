package com.service.tbterminal.system

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import org.koin.ktor.ext.inject
import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.AuthenticatedUserPrincipal
import com.service.tbterminal.shared.ErrorResponse
import com.service.tbterminal.shared.requireRole
import com.service.tbterminal.shared.getUserId

fun Application.systemRoutes() {
    val service: SystemService by inject()

    routing {
        route("/api/auth") {
            rateLimit(RateLimitName("login")) {
                post("/login") {
                val req = call.receive<LoginRequest>()
                val response = service.login(req)
                if (response.success) {
                    response.data?.user?.id?.let { userId ->
                        service.recordOperationalAudit(
                            call = call,
                            actorUserId = java.util.UUID.fromString(userId),
                            action = AuditAction.INSERT,
                            schemaName = "system",
                            tableName = "auth_login",
                            recordId = userId
                        )
                    }
                    call.respond(HttpStatusCode.OK, response)
                } else {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(
                            code = response.code ?: "UNAUTHORIZED",
                            message = response.error ?: "Autentikasi gagal"
                        )
                    )
                }
            }
            }

            post("/refresh") {
                val response = service.refresh(call.receive<RefreshTokenRequest>())
                if (response.success) {
                    call.respond(HttpStatusCode.OK, response)
                } else {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(
                            code = response.code ?: "UNAUTHORIZED",
                            message = response.error ?: "Refresh token tidak valid"
                        )
                    )
                }
            }

            authenticate("jwt-auth") {
                post("/unlock") {
                    val userId = call.getUserId()
                    val response = service.unlock(userId, call.receive<UnlockRequest>())
                    if (response.success) {
                        call.respond(HttpStatusCode.OK, response)
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse(
                                code = response.code ?: "UNAUTHORIZED",
                                message = response.error ?: "Unlock gagal"
                            )
                        )
                    }
                }

                post("/logout") {
                    val userId = call.getUserId()
                    service.recordOperationalAudit(
                        call = call,
                        actorUserId = userId,
                        action = AuditAction.UPDATE,
                        schemaName = "system",
                        tableName = "auth_logout",
                        recordId = userId.toString()
                    )
                    service.logout(userId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Logout berhasil"))
                }

                get("/me") {
                    val principal = call.principal<AuthenticatedUserPrincipal>()
                    val username = principal?.username
                    val role = principal?.role
                    
                    if (username != null && role != null) {
                        call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("username" to username, "role" to role)))
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("UNAUTHORIZED", "Token tidak valid")
                        )
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
                    call.requireRole(com.service.tbterminal.shared.Role.OWNER)
                    val roles = service.getRoles()
                    call.respond(HttpStatusCode.OK, ApiResponse.success(roles))
                }

                get("/audit-logs") {
                    call.requireRole(com.service.tbterminal.shared.Role.ADMIN, com.service.tbterminal.shared.Role.OWNER)
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                    val action = call.request.queryParameters["action"]
                    val range = call.request.queryParameters["range"]

                    val logs = service.getAuditLogs(page, limit, action, range)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(logs))
                }

                // USERS
                route("/users") {
                    // Update Password sendiri (bisa oleh Kasir, Admin, Owner)
                    put("/me/password") {
                        val userId = call.getUserId()
                        val request = call.receive<ChangePasswordRequest>()
                        service.changeMyPassword(userId, request)
                        service.recordAuditLog(
                            actorUserId = userId,
                            action = AuditAction.UPDATE,
                            schemaName = "system",
                            tableName = "user_password",
                            recordId = userId.toString(),
                            ipAddress = call.clientIpAddress()
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "Password berhasil diubah"))
                    }

                    // Update PIN sendiri (bisa oleh Kasir, Admin, Owner)
                    put("/me/pin") {
                        val userId = call.getUserId()
                        val request = call.receive<ChangePinRequest>()
                        service.changeMyPin(userId, request)
                        service.recordAuditLog(
                            actorUserId = userId,
                            action = AuditAction.UPDATE,
                            schemaName = "system",
                            tableName = "user_pin",
                            recordId = userId.toString(),
                            ipAddress = call.clientIpAddress()
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(Unit, "PIN berhasil diubah"))
                    }

                    // List Users
                    get {
                        call.requireRole(com.service.tbterminal.shared.Role.OWNER)
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        val search = call.request.queryParameters["search"]
                        
                        val users = service.getUsers(page, limit, search)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(users))
                    }

                    // Create User
                    post {
                        call.requireRole(com.service.tbterminal.shared.Role.OWNER)
                        val actorUserId = call.getUserId()
                        val request = call.receive<UserCreateRequest>()
                        val user = service.createUser(request)
                        service.recordAuditLog(
                            actorUserId = actorUserId,
                            action = AuditAction.INSERT,
                            schemaName = "system",
                            tableName = "users",
                            recordId = user.id,
                            ipAddress = call.clientIpAddress()
                        )
                        call.respond(HttpStatusCode.Created, ApiResponse.success(user, "User berhasil dibuat"))
                    }

                    // Update User
                    put("/{id}") {
                        call.requireRole(com.service.tbterminal.shared.Role.OWNER)
                        val actorUserId = call.getUserId()
                        val id = call.parameters["id"] ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan")
                        )
                        val request = call.receive<UserUpdateRequest>()
                        val user = service.updateUser(id, request)
                        service.recordAuditLog(
                            actorUserId = actorUserId,
                            action = AuditAction.UPDATE,
                            schemaName = "system",
                            tableName = request.auditTableName(),
                            recordId = user.id,
                            ipAddress = call.clientIpAddress()
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(user, "User berhasil diperbarui"))
                    }

                    // Delete User (Soft Delete)
                    delete("/{id}") {
                        call.requireRole(com.service.tbterminal.shared.Role.OWNER) // Hanya owner yg boleh hapus
                        val actorUserId = call.getUserId()
                        val id = call.parameters["id"] ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("VALIDATION_ERROR", "ID tidak ditemukan")
                        )
                        service.deleteUser(id)
                        service.recordAuditLog(
                            actorUserId = actorUserId,
                            action = AuditAction.DELETE,
                            schemaName = "system",
                            tableName = "users",
                            recordId = id,
                            ipAddress = call.clientIpAddress()
                        )
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
                        service.recordAuditLog(
                            actorUserId = userId,
                            action = AuditAction.UPDATE,
                            schemaName = "system",
                            tableName = "store_settings",
                            recordId = updated.id,
                            ipAddress = call.clientIpAddress()
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success(updated, "Pengaturan toko berhasil diperbarui"))
                    }
                }
            }
        }
    }
}

private fun ApplicationCall.clientIpAddress(): String? {
    return request.headers["X-Forwarded-For"]
        ?.substringBefore(",")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: request.headers["X-Real-IP"]?.trim()?.takeIf(String::isNotBlank)
}

private fun UserUpdateRequest.auditTableName(): String {
    val hasPasswordChange = !newPassword.isNullOrBlank()
    val hasPinChange = !newPin.isNullOrBlank()

    return when {
        hasPasswordChange && hasPinChange -> "user_credentials"
        hasPasswordChange -> "user_password"
        hasPinChange -> "user_pin"
        else -> "users"
    }
}
