package com.service.tbterminal

import com.service.tbterminal.plugins.configureDI
import com.service.tbterminal.plugins.configureRouting
import com.service.tbterminal.plugins.configureSecurity
import com.service.tbterminal.plugins.configureSerialization
import com.service.tbterminal.plugins.configureStatusPages
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

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

}
