package com.service.tbterminal.inventory

import com.service.tbterminal.system.SystemService
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject

fun Application.inventoryRoutes() {
    val service: InventoryService by inject()
    val systemService: SystemService by inject()

    routing {
        authenticate("jwt-auth") {
            route("/api/inventory") {
                categoryRoutes(service, systemService)
                unitRoutes(service, systemService)
                productRoutes(service, systemService)
                stockRoutes(service, systemService)
            }
        }
    }
}
