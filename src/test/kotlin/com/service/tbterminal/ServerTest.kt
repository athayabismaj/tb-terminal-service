package com.service.tbterminal

import com.service.tbterminal.plugins.configureDI
import com.service.tbterminal.plugins.configureRouting
import com.service.tbterminal.plugins.configureSecurity
import com.service.tbterminal.plugins.configureSerialization
import com.service.tbterminal.plugins.configureStatusPages
import com.service.tbterminal.shared.JwtHelper
import com.service.tbterminal.system.UserRow
import com.auth0.jwt.JWT
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*
import java.time.OffsetDateTime
import java.util.UUID
import org.koin.core.context.stopKoin

class ServerTest {

    @BeforeTest
    fun resetDependencyInjection() = stopKoin()

    @AfterTest
    fun stopDependencyInjection() = stopKoin()

    @Test
    fun `test root endpoint`() = testApplication {
        application {
            configureDI()
            configureSerialization()
            configureSecurity()
            configureStatusPages()
            configureRouting()
        }

        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `access and refresh tokens cannot be interchanged`() {
        val user = UserRow(
            id = UUID.randomUUID(),
            username = "owner-test",
            name = "Owner Test",
            passwordHash = "unused",
            pinHash = "unused",
            email = null,
            roleName = "owner",
            isActive = true,
            tokenVersion = 3,
            createdAt = OffsetDateTime.now(),
            lastLoginAt = null
        )

        val accessToken = JwtHelper.generateAccessToken(user)
        val refreshToken = JwtHelper.generateRefreshToken(user)

        assertEquals(JwtHelper.ACCESS_TOKEN_TYPE, JWT.decode(accessToken).getClaim(JwtHelper.TOKEN_TYPE_CLAIM).asString())
        assertEquals(JwtHelper.REFRESH_TOKEN_TYPE, JwtHelper.verifyRefreshToken(refreshToken).getClaim(JwtHelper.TOKEN_TYPE_CLAIM).asString())
        assertFails { JwtHelper.verifyRefreshToken(accessToken) }
    }

}
