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
    }
}
