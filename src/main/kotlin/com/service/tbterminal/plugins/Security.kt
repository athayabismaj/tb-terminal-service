package com.service.tbterminal.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.service.tbterminal.shared.AuthenticatedUserPrincipal
import com.service.tbterminal.shared.EnvironmentConfig
import com.service.tbterminal.shared.ErrorResponse
import com.service.tbterminal.shared.JwtHelper
import com.service.tbterminal.system.SystemRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.respond
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.minutes
import org.koin.ktor.ext.inject
import java.util.UUID

fun Application.configureSecurity() {
    val systemRepository: SystemRepository by inject()
    val jwtAudience = EnvironmentConfig.jwtAudience
    val jwtIssuer = EnvironmentConfig.jwtIssuer
    val jwtSecret = EnvironmentConfig.jwtSecret

    install(RateLimit) {
        register(RateLimitName("login")) {
            rateLimiter(limit = 5, refillPeriod = 1.minutes)
            requestKey { call -> call.request.local.remoteHost }
        }
        register(RateLimitName("manager-approval")) {
            rateLimiter(limit = 5, refillPeriod = 1.minutes)
            requestKey { call -> call.request.local.remoteHost }
        }
    }

    authentication {
        jwt("jwt-auth") {
            realm = "tb_terminal"
            verifier(
                JWT
                    .require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build()
            )
            validate { credential ->
                val userId = credential.payload.subject
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@validate null
                val tokenVersion = credential.payload
                    .getClaim(JwtHelper.TOKEN_VERSION_CLAIM)
                    .asInt()
                    ?: return@validate null
                val tokenType = credential.payload
                    .getClaim(JwtHelper.TOKEN_TYPE_CLAIM)
                    .asString()
                if (tokenType != JwtHelper.ACCESS_TOKEN_TYPE) {
                    return@validate null
                }

                val user = systemRepository.findAuthenticationUserById(userId)
                    ?: return@validate null

                if (!user.isActive || user.tokenVersion != tokenVersion) {
                    return@validate null
                }

                AuthenticatedUserPrincipal(
                    userId = user.id,
                    username = user.username,
                    role = user.roleName,
                    tokenVersion = user.tokenVersion
                )
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("UNAUTHORIZED", "Token tidak valid atau telah berakhir")
                )
            }
        }
    }
}
