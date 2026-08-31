package com.service.tbterminal.shared

import java.io.File
import java.net.URI
import java.util.Properties

/**
 * Hybrid Environment Config:
 * - Production: Baca dari Environment Variable (System.getenv)
 * - Development: Jika env var tidak ada, baca local.properties.
 *
 * Cara pakai di Production (server/Docker):
 *   export DB_URL=jdbc:postgresql://prod-host:5432/tb_terminal_db
 *   export DB_USER=prod_user
 *   export DB_PASSWORD='<from-secret-manager>'
 *   export JWT_SECRET='<from-secret-manager>'
 *   export JWT_ISSUER=tb-terminal
 *   export JWT_AUDIENCE=tb-terminal-client
 */
object EnvironmentConfig {
    val environment: String by lazy { resolve("APP_ENV", "development").lowercase() }
    val isProduction: Boolean get() = environment == "production"

    // ─── Database ─────────────────────────────────────
    val dbUrl: String by lazy { resolveRequired("DB_URL") }
    val dbUser: String by lazy { resolveRequired("DB_USER") }
    val dbPassword: String by lazy { resolveRequired("DB_PASSWORD") }
    val dbMaximumPoolSize: Int by lazy { resolve("DB_MAX_POOL_SIZE", "10").toIntOrNull()?.coerceIn(2, 50) ?: 10 }
    val dbMinimumIdle: Int by lazy { resolve("DB_MIN_IDLE", "2").toIntOrNull()?.coerceIn(0, dbMaximumPoolSize) ?: 2 }
    val dbConnectionTimeoutMs: Long by lazy { resolve("DB_CONNECTION_TIMEOUT_MS", "10000").toLongOrNull()?.coerceIn(1000, 60000) ?: 10000 }
    val appVersion: String by lazy { resolve("APP_VERSION", "1.0.0") }
    val logLevel: String by lazy { resolve("LOG_LEVEL", if (isProduction) "WARN" else "INFO").uppercase() }
    val corsAllowedOrigins: List<String> by lazy {
        resolve("CORS_ALLOWED_ORIGINS", "").split(',').map(String::trim).filter(String::isNotBlank)
    }
    val bootstrapOwnerPassword: String? by lazy { resolveOptional("BOOTSTRAP_OWNER_PASSWORD") }
    val bootstrapOwnerPin: String? by lazy { resolveOptional("BOOTSTRAP_OWNER_PIN") }
    val productionStoreName: String? by lazy { resolveOptional("STORE_NAME") }
    val productionStoreAddress: String? by lazy { resolveOptional("STORE_ADDRESS") }
    val productionStorePhone: String? by lazy { resolveOptional("STORE_PHONE") }

    // ─── JWT ──────────────────────────────────────────
    val jwtSecret: String by lazy { resolveRequired("JWT_SECRET") }
    val jwtIssuer: String by lazy { resolve("JWT_ISSUER", "tb-terminal") }
    val jwtAudience: String by lazy { resolve("JWT_AUDIENCE", "tb-terminal-client") }
    val accessTokenMinutes: Long by lazy {
        resolve("JWT_ACCESS_TOKEN_MINUTES", "15").toLongOrNull()?.coerceIn(5, 480) ?: 15
    }
    val refreshTokenDays: Long by lazy {
        resolve("JWT_REFRESH_TOKEN_DAYS", "7").toLongOrNull()?.coerceIn(1, 30) ?: 7
    }
    val managerApprovalTtlMinutes: Long by lazy {
        resolve("MANAGER_APPROVAL_TTL_MINUTES", "5").toLongOrNull()?.coerceIn(1, 15) ?: 5
    }

    // PostgreSQL backup files must live outside the application repository/public web roots.
    val backupDirectory: String by lazy { resolve("BACKUP_DIRECTORY", "") }
    val backupEnabled: Boolean by lazy { resolve("BACKUP_ENABLED", "false").toBooleanStrictOrNull() ?: false }
    val backupRetentionDays: Long by lazy { resolve("BACKUP_RETENTION_DAYS", "30").toLongOrNull()?.coerceIn(1, 3650) ?: 30 }
    val backupIntervalHours: Long by lazy { resolve("BACKUP_INTERVAL_HOURS", "24").toLongOrNull()?.coerceIn(1, 168) ?: 24 }
    val backupMaxUploadMb: Long by lazy { resolve("BACKUP_MAX_UPLOAD_MB", "1024").toLongOrNull()?.coerceIn(1, 10240) ?: 1024 }
    val pgDumpExecutable: String by lazy { resolve("PG_DUMP_EXECUTABLE", "pg_dump") }
    val pgRestoreExecutable: String by lazy { resolve("PG_RESTORE_EXECUTABLE", "pg_restore") }
    val restoreEnabled: Boolean by lazy { resolve("RESTORE_ENABLED", "false").toBooleanStrictOrNull() ?: false }

    fun validateProductionConfiguration() {
        if (!isProduction) return

        require(jwtSecret.length >= 32) {
            "JWT_SECRET production wajib unik dan minimal 32 karakter"
        }
        require(dbPassword.length >= 12) {
            "DB_PASSWORD production wajib minimal 12 karakter"
        }
        require(dbUrl.startsWith("jdbc:postgresql://")) {
            "DB_URL production wajib berupa JDBC PostgreSQL"
        }
        require(logLevel !in setOf("DEBUG", "TRACE")) {
            "LOG_LEVEL DEBUG/TRACE tidak boleh digunakan pada production"
        }
        corsAllowedOrigins.forEach { origin ->
            val uri = runCatching { URI(origin) }.getOrNull()
            require(uri?.scheme.equals("https", true) && !uri?.host.isNullOrBlank() && uri.userInfo == null) {
                "CORS production hanya boleh menggunakan origin HTTPS yang valid"
            }
        }
        require(!productionStoreName.isNullOrBlank() && !productionStoreAddress.isNullOrBlank() && !productionStorePhone.isNullOrBlank()) {
            "STORE_NAME, STORE_ADDRESS, dan STORE_PHONE wajib diisi pada production"
        }
        if (backupEnabled) require(backupDirectory.isNotBlank()) {
            "BACKUP_DIRECTORY wajib diisi ketika backup production diaktifkan"
        }
        if (backupEnabled) {
            val backupPath = File(backupDirectory).absoluteFile.normalize()
            val applicationPath = File(System.getProperty("user.dir")).absoluteFile.normalize()
            require(File(backupDirectory).isAbsolute && backupPath != applicationPath && !backupPath.path.startsWith(applicationPath.path + File.separator)) {
                "BACKUP_DIRECTORY production wajib absolut dan berada di luar repository aplikasi"
            }
        }
    }

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

    private fun resolveOptional(name: String): String? {
        val processValue = System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(name)?.takeIf { it.isNotBlank() }
        if (processValue != null) return processValue

        val productionRequested = (
            System.getenv("APP_ENV")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("APP_ENV")?.takeIf { it.isNotBlank() }
            ).equals("production", ignoreCase = true)
        if (productionRequested && name != "APP_ENV") return null
        return localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
    }

    private fun resolveRequired(name: String): String = resolveOptional(name)
        ?: error("$name wajib dikonfigurasi melalui environment variable atau local.properties")
}
