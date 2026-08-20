package com.service.tbterminal.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.service.tbterminal.system.systemRoutes
import com.service.tbterminal.inventory.inventoryRoutes
import com.service.tbterminal.sales.salesRoutes
import com.service.tbterminal.receivable.receivableRoutes
import com.service.tbterminal.purchasing.purchasingRoutes
import com.service.tbterminal.analytics.analyticsRoutes
import com.service.tbterminal.backup.backupRoutes
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneId
import com.service.tbterminal.shared.EnvironmentConfig
import io.ktor.http.HttpStatusCode

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respond(mapOf(
                "status" to "running",
                "service" to "tb-terminal-service"
            ))
        }

        get("/health") {
            call.respond(mapOf(
                "status" to "running",
                "service" to "tb-terminal-service",
                "version" to EnvironmentConfig.appVersion
            ))
        }

        get("/api/health") {
            call.respond(
                HealthResponse(
                    status = "ok",
                    service = "tb-terminal-service",
                    version = EnvironmentConfig.appVersion,
                    time = OffsetDateTime.now(ZoneId.of("Asia/Jakarta")).toString()
                )
            )
        }

        suspend fun io.ktor.server.application.ApplicationCall.respondReadiness() {
            val ready = DatabaseHealth.isReady()
            respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                ReadinessResponse(
                    status = if (ready) "ready" else "not_ready",
                    service = "tb-terminal-service",
                    version = EnvironmentConfig.appVersion,
                    database = if (ready) "up" else "down",
                    time = OffsetDateTime.now(ZoneId.of("Asia/Jakarta")).toString()
                )
            )
        }
        get("/ready") { call.respondReadiness() }
        get("/api/readiness") { call.respondReadiness() }
    }
    
    // Modules that extend Application
    inventoryRoutes()
    systemRoutes()
    salesRoutes()
    receivableRoutes()
    purchasingRoutes()
    analyticsRoutes()
    backupRoutes()
}

@Serializable
private data class HealthResponse(
    val status: String,
    val service: String,
    val version: String,
    val time: String
)

@Serializable
private data class ReadinessResponse(
    val status: String,
    val service: String,
    val version: String,
    val database: String,
    val time: String
)
