package com.service.tbterminal.sales

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.getUserId
import com.service.tbterminal.shared.requireRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.salesRoutes() {
    val service by inject<SalesService>()

    routing {
        authenticate("jwt-auth") {
            route("/api/sales") {

                // ==========================================
                // CASH SESSION ROUTES
                // ==========================================
                route("/sessions") {

                    // GET sesi aktif milik user yang sedang login
                    get("/active") {
                        call.requireRole("KASIR", "ADMIN", "OWNER")
                        val userId = call.getUserId()
                        val session = service.getActiveSession(userId)
                        if (session != null) {
                            call.respond(HttpStatusCode.OK, ApiResponse.success(session))
                        } else {
                            call.respond(HttpStatusCode.OK, ApiResponse.success<CashSessionResponse?>(null, "Tidak ada sesi aktif"))
                        }
                    }

                    // POST buka sesi kasir baru
                    post("/open") {
                        call.requireRole("KASIR", "ADMIN", "OWNER")
                        val userId = call.getUserId()
                        val request = call.receive<OpenSessionRequest>()
                        val session = service.openSession(userId, request)
                        call.respond(HttpStatusCode.Created, ApiResponse.success(session, "Sesi kasir berhasil dibuka"))
                    }

                    // POST tutup sesi kasir yang aktif
                    post("/close") {
                        call.requireRole("KASIR", "ADMIN", "OWNER")
                        val userId = call.getUserId()
                        val request = call.receive<CloseSessionRequest>()
                        val session = service.closeSession(userId, request)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(session, "Sesi kasir berhasil ditutup"))
                    }
                }
            }
        }
    }
}
