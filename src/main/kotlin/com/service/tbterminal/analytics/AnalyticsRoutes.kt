package com.service.tbterminal.analytics

import com.service.tbterminal.shared.ApiResponse
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.analyticsRoutes() {
    val service: AnalyticsService by inject()

    routing {
        authenticate("jwt-auth") {
            route("/api/analytics") {
                
                // ==========================================
                // DASHBOARD METRICS
                // ==========================================
                get("/dashboard") {
                    // MUTLAK: Hanya ADMIN dan OWNER
                    call.requireRole(Role.ADMIN, Role.OWNER)
                    val metrics = service.getDashboardMetrics()
                    call.respond(HttpStatusCode.OK, ApiResponse.success(metrics))
                }

                // ==========================================
                // DAILY SALES
                // ==========================================
                get("/sales") {
                    // MUTLAK: Hanya ADMIN dan OWNER
                    call.requireRole(Role.ADMIN, Role.OWNER)
                    
                    val startDate = call.request.queryParameters["startDate"]
                    val endDate = call.request.queryParameters["endDate"]
                    
                    val sales = service.getDailySales(startDate, endDate)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(sales))
                }
            }
        }
    }
}
