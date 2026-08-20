package com.service.tbterminal.deployment

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeploymentConfigTest {
    @Test
    fun `production compose has expected services and references protected values`() {
        val compose = Path.of("deploy", "docker-compose.production.yml").readText()

        assertContains(compose, "services:")
        assertContains(compose, "  postgres:")
        assertContains(compose, "  backend:")
        assertContains(compose, "  gateway:")
        assertContains(compose, "\${DB_PASSWORD:?")
        assertContains(compose, "\${JWT_SECRET:?")
        assertContains(compose, "APP_ENV: production")
        assertFalse('\t' in compose, "Compose tidak boleh memakai tab untuk indentasi YAML")
        assertFalse(Regex("(?m)^\\s*(DB_PASSWORD|JWT_SECRET):\\s*[^$\\s][^#]*$").containsMatchIn(compose))
    }

    @Test
    fun `runtime image pins compatible postgres client and non-root user`() {
        val dockerfile = Path.of("deploy", "Dockerfile").readText()

        assertContains(dockerfile, "postgresql-client-16")
        assertContains(dockerfile, "USER 10001")
        assertContains(dockerfile, "HEALTHCHECK")
        assertTrue("local.properties" in Path.of(".dockerignore").readText())
    }
}
