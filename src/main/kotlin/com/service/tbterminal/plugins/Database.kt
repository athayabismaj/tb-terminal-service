package com.service.tbterminal.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.flywaydb.core.Flyway

/**
 * Configures HikariCP connection pool and runs Flyway migrations.
 * This module MUST be loaded BEFORE any other database-related modules
 * so that schemas and tables are ready before the application uses them.
 */
fun Application.configureDatabase() {
    val url = environment.config.property("postgres.url").getString()
    val user = environment.config.property("postgres.user").getString()
    val password = environment.config.property("postgres.password").getString()

    // Setup HikariCP DataSource
    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url
        username = user
        this.password = password
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 10
    })

    // Run Flyway migrations
    val flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas("system")
        .createSchemas(true)
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .load()

    val result = flyway.migrate()
    log.info("Flyway: Successfully applied ${result.migrationsExecuted} migration(s)")

    // Connect Exposed to the HikariDataSource
    org.jetbrains.exposed.sql.Database.connect(dataSource)
}
