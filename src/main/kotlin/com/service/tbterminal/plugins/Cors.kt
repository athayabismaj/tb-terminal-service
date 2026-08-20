package com.service.tbterminal.plugins

import com.service.tbterminal.shared.EnvironmentConfig
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureCors() {
    if (EnvironmentConfig.isProduction && EnvironmentConfig.corsAllowedOrigins.isEmpty()) {
        log.info("CORS disabled: native Android client only")
        return
    }
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        if (EnvironmentConfig.isProduction) {
            EnvironmentConfig.corsAllowedOrigins.forEach { origin ->
                val uri = java.net.URI(origin)
                val authority = if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host
                allowHost(authority, schemes = listOf(uri.scheme))
            }
        } else {
            anyHost()
        }
    }
}
