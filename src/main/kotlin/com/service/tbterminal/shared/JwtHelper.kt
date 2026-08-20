package com.service.tbterminal.shared

import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.algorithms.Algorithm
import com.service.tbterminal.system.UserRow
import java.time.Duration
import java.util.*

object JwtHelper {
    const val TOKEN_VERSION_CLAIM = "token_version"
    const val TOKEN_TYPE_CLAIM = "token_type"
    const val ACCESS_TOKEN_TYPE = "access"
    const val REFRESH_TOKEN_TYPE = "refresh"

    fun generateAccessToken(user: UserRow): String {
        return generateToken(
            user = user,
            tokenType = ACCESS_TOKEN_TYPE,
            validity = Duration.ofMinutes(EnvironmentConfig.accessTokenMinutes)
        )
    }

    fun generateRefreshToken(user: UserRow): String {
        return generateToken(
            user = user,
            tokenType = REFRESH_TOKEN_TYPE,
            validity = Duration.ofDays(EnvironmentConfig.refreshTokenDays)
        )
    }

    fun verifyRefreshToken(token: String): DecodedJWT {
        return JWT.require(Algorithm.HMAC256(EnvironmentConfig.jwtSecret))
            .withAudience(EnvironmentConfig.jwtAudience)
            .withIssuer(EnvironmentConfig.jwtIssuer)
            .withClaim(TOKEN_TYPE_CLAIM, REFRESH_TOKEN_TYPE)
            .build()
            .verify(token)
    }

    @Deprecated("Gunakan generateAccessToken")
    fun generateToken(user: UserRow): String = generateAccessToken(user)

    private fun generateToken(user: UserRow, tokenType: String, validity: Duration): String {
        return JWT.create()
            .withAudience(EnvironmentConfig.jwtAudience)
            .withIssuer(EnvironmentConfig.jwtIssuer)
            .withSubject(user.id.toString())
            .withClaim(TOKEN_VERSION_CLAIM, user.tokenVersion)
            .withClaim(TOKEN_TYPE_CLAIM, tokenType)
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + validity.toMillis()))
            .sign(Algorithm.HMAC256(EnvironmentConfig.jwtSecret))
    }
}
