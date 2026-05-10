package com.service.tbterminal.shared

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.service.tbterminal.system.UserDto
import java.util.*

object JwtHelper {
    private const val jwtSecret = "secret" // @TODO: Move to env variable
    private const val jwtAudience = "jwt-audience" // @TODO: Move to env variable
    private const val jwtDomain = "https://jwt-provider-domain/" // @TODO: Move to env variable

    fun generateToken(user: UserDto): String {
        return JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtDomain)
            .withClaim("id", user.id)
            .withClaim("username", user.username)
            .withClaim("role", user.role)
            .withExpiresAt(Date(System.currentTimeMillis() + 86400000)) // 24 hours
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}
