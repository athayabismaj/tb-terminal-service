package com.service.tbterminal.shared

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.service.tbterminal.system.UserRow
import java.util.*

object JwtHelper {
    const val TOKEN_VERSION_CLAIM = "token_version"

    fun generateToken(user: UserRow): String {
        return JWT.create()
            .withAudience(EnvironmentConfig.jwtAudience)
            .withIssuer(EnvironmentConfig.jwtIssuer)
            .withSubject(user.id.toString())
            .withClaim(TOKEN_VERSION_CLAIM, user.tokenVersion)
            .withExpiresAt(Date(System.currentTimeMillis() + 86400000)) // 24 hours
            .sign(Algorithm.HMAC256(EnvironmentConfig.jwtSecret))
    }
}
