package com.service.tbterminal.plugins

import com.service.tbterminal.shared.EnvironmentConfig
import com.zaxxer.hikari.HikariDataSource
import org.mindrot.jbcrypt.BCrypt

object ProductionDataGuard {
    fun applyAndValidate(dataSource: HikariDataSource) {
        if (!EnvironmentConfig.isProduction) return
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                applyOwnerBootstrap(connection)
                applyStoreBootstrap(connection)
                rejectDefaultCredentials(connection)
                rejectPlaceholderStore(connection)
                connection.commit()
            } catch (cause: Exception) {
                connection.rollback()
                throw cause
            }
        }
    }

    private fun applyOwnerBootstrap(connection: java.sql.Connection) {
        val password = EnvironmentConfig.bootstrapOwnerPassword ?: return
        val pin = EnvironmentConfig.bootstrapOwnerPin
            ?: error("BOOTSTRAP_OWNER_PIN wajib diisi bersama BOOTSTRAP_OWNER_PASSWORD")
        require(password.length >= 12 && password !in DEFAULT_PASSWORDS) { "Password bootstrap owner minimal 12 karakter dan tidak boleh default" }
        require(pin.matches(Regex("^[0-9]{6}$")) && pin !in DEFAULT_PINS) { "PIN bootstrap owner wajib 6 digit non-default" }
        connection.prepareStatement(
            "UPDATE system.users SET password_hash=?, pin_hash=?, updated_at=NOW() WHERE username='owner'"
        ).use { statement ->
            statement.setString(1, BCrypt.hashpw(password, BCrypt.gensalt(12)))
            statement.setString(2, BCrypt.hashpw(pin, BCrypt.gensalt(12)))
            check(statement.executeUpdate() == 1) { "Akun owner bootstrap tidak ditemukan" }
        }
    }

    private fun applyStoreBootstrap(connection: java.sql.Connection) {
        val name = EnvironmentConfig.productionStoreName ?: return
        require(name.length in 2..150 && !name.contains("placeholder", true)) { "STORE_NAME production tidak valid" }
        connection.prepareStatement(
            "UPDATE system.store_settings SET store_name=?, address=?, phone=?, updated_at=NOW()"
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, EnvironmentConfig.productionStoreAddress)
            statement.setString(3, EnvironmentConfig.productionStorePhone)
            statement.executeUpdate()
        }
    }

    private fun rejectDefaultCredentials(connection: java.sql.Connection) {
        connection.prepareStatement("SELECT username, password_hash, pin_hash FROM system.users WHERE is_active=TRUE").use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val passwordHash = rows.getString("password_hash")
                    val pinHash = rows.getString("pin_hash")
                    check(DEFAULT_PASSWORDS.none { BCrypt.checkpw(it, passwordHash) }) { "User aktif masih memakai password default: ${rows.getString("username")}" }
                    check(DEFAULT_PINS.none { BCrypt.checkpw(it, pinHash) }) { "User aktif masih memakai PIN default: ${rows.getString("username")}" }
                }
            }
        }
    }

    private fun rejectPlaceholderStore(connection: java.sql.Connection) {
        connection.prepareStatement("SELECT store_name, address, phone FROM system.store_settings LIMIT 1").use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Store settings production belum tersedia" }
                val combined = listOf(rows.getString(1), rows.getString(2), rows.getString(3)).joinToString(" ")
                check(!combined.contains("placeholder", true) && !combined.contains("081234567890")) { "Data toko production masih berupa placeholder" }
            }
        }
    }

    private val DEFAULT_PASSWORDS = setOf("owner123", "admin123", "password")
    private val DEFAULT_PINS = setOf("1234", "123456", "0000", "000000")
}
