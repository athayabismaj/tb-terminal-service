package com.service.tbterminal.shared

import java.io.File
import java.util.Properties

/**
 * Hybrid Environment Config:
 * - Production: Baca dari Environment Variable (System.getenv)
 * - Development: Jika env var tidak ada, baca local.properties, lalu default localhost
 *
 * Cara pakai di Production (server/Docker):
 *   export DB_URL=jdbc:postgresql://prod-host:5432/tb_terminal_db
 *   export DB_USER=prod_user
 *   export DB_PASSWORD=prod_password
 *   export JWT_SECRET=super-secret-production-key
 *   export JWT_ISSUER=tb-terminal
 *   export JWT_AUDIENCE=tb-terminal-client
 */
object EnvironmentConfig {
    // ─── Database ─────────────────────────────────────
    val dbUrl: String by lazy { resolve("DB_URL", "jdbc:postgresql://localhost:5432/tbterminal_backend") }
    val dbUser: String by lazy { resolve("DB_USER", "postgres") }
    val dbPassword: String by lazy { resolve("DB_PASSWORD", "postgres") }

    // ─── JWT ──────────────────────────────────────────
    val jwtSecret: String by lazy { resolve("JWT_SECRET", "tb-terminal-dev-secret-key-min-32-chars!!") }
    val jwtIssuer: String by lazy { resolve("JWT_ISSUER", "tb-terminal") }
    val jwtAudience: String by lazy { resolve("JWT_AUDIENCE", "tb-terminal-client") }

    /**
     * Cek System.getenv() terlebih dahulu (untuk production).
     * Jika kosong/null, gunakan nilai default (untuk development).
     */
    private val localProperties: Properties by lazy {
        Properties().apply {
            val file = listOf(
                File("local.properties"),
                File(System.getProperty("user.dir"), "local.properties")
            ).firstOrNull(File::exists)

            if (file != null) {
                file.inputStream().use(::load)
            }
        }
    }

    private fun resolve(name: String, default: String): String {
        return System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: default
    }
}
