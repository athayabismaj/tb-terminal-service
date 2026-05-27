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
                "version" to "1.0.0-SNAPSHOT"
            ))
        }
    }
    
    // Modules that extend Application
    inventoryRoutes()
    systemRoutes()
    salesRoutes()
    receivableRoutes()
    purchasingRoutes()
    analyticsRoutes()
}
