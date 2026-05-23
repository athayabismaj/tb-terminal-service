package com.service.tbterminal

import com.service.tbterminal.plugins.configureDI
import com.service.tbterminal.plugins.configureRouting
import com.service.tbterminal.plugins.configureSecurity
import com.service.tbterminal.plugins.configureSerialization
import com.service.tbterminal.plugins.configureStatusPages
import com.service.tbterminal.system.SystemRepository
import com.service.tbterminal.system.UserSessionService
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationTest {
    private val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
        withDatabaseName("tb_terminal_integration")
        withUsername("tb_terminal")
        withPassword("tb_terminal")
    }

    private lateinit var dataSource: HikariDataSource

    @BeforeAll
    fun startPostgres() {
        postgres.start()

        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 6
            connectionInitSql = "SET search_path TO system,inventory,sales,receivable,purchasing,public"
        })

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas("system", "inventory", "sales", "receivable", "purchasing")
            .defaultSchema("system")
            .createSchemas(true)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()

        Database.connect(dataSource)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
        postgres.stop()
    }

    @BeforeEach
    fun resetMutableState() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE TABLE sales.cash_sessions CASCADE")
                statement.execute("UPDATE system.users SET token_version = 0 WHERE username = 'owner'")
            }
        }
    }

    @Test
    fun test_concurrent_open_session_should_fail() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val releaseBothRequests = CompletableDeferred<Unit>()

        val responses = coroutineScope {
            val first = async {
                releaseBothRequests.await()
                openSession(token)
            }
            val second = async {
                releaseBothRequests.await()
                openSession(token)
            }

            releaseBothRequests.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(1, responses.count { it.status.value in 200..299 })
        assertEquals(1, responses.count { it.status.value >= 400 })
        assertEquals(1, openSessionCountForOwner())
    }

    @Test
    fun test_financial_constraint_violation_should_rollback() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture()

        assertTrue(openSession(token).status.value in 200..299)

        val invalidCheckout = client.post("/api/sales/checkout") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """
                {
                  "items": [
                    {
                      "productId": "${product.id}",
                      "qty": "1.00",
                      "discount": "200.00"
                    }
                  ],
                  "paymentMethod": "tunai",
                  "amountPaid": "0.00"
                }
                """.trimIndent()
            )
        }

        assertTrue(invalidCheckout.status.value >= 400, invalidCheckout.bodyAsText())
        assertEquals(0, transactionCount())

        // Endpoint validation returns before SQL; this write proves PostgreSQL is still the final barrier.
        val checkViolation = assertFailsWith<SQLException> {
            insertTransactionWithInvalidDiscount(product)
        }
        assertEquals("23514", checkViolation.sqlState)
        assertEquals(0, transactionCount())
    }

    @Test
    fun test_revoked_token_should_return_401() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()

        val initialRequest = client.get("/api/auth/me") {
            bearer(token)
        }
        assertEquals(HttpStatusCode.OK, initialRequest.status, initialRequest.bodyAsText())

        UserSessionService(SystemRepository()).revokeAllSessionsForUser(ownerId())

        val revokedRequest = client.get("/api/auth/me") {
            bearer(token)
        }
        assertEquals(HttpStatusCode.Unauthorized, revokedRequest.status, revokedRequest.bodyAsText())
    }

    @Test
    fun test_create_system_user_should_return_created() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val adminRoleId = dataSource.connection.use { connection ->
            firstUuid(connection, "SELECT id FROM system.roles WHERE name = 'admin'")
        }
        val username = "integration_admin_${UUID.randomUUID()}"

        val response = client.post("/api/system/users") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """
                {
                  "name": "Integration Admin",
                  "username": "$username",
                  "password": "admin123",
                  "pin": "123456",
                  "email": "$username@example.test",
                  "roleId": "$adminRoleId"
                }
                """.trimIndent()
            )
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.Created, response.status, body)
        assertTrue(body.contains(username), body)
    }

    private fun Application.configureIntegrationApplication() {
        configureDI()
        configureSerialization()
        configureSecurity()
        configureStatusPages()
        configureRouting()
    }

    private suspend fun ApplicationTestBuilder.loginAsOwner(): String {
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"owner","password":"owner123"}""")
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status, body)
        return Json.parseToJsonElement(body)
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue("token")
            .jsonPrimitive
            .content
    }

    private suspend fun ApplicationTestBuilder.openSession(token: String): HttpResponse {
        return client.post("/api/sales/sessions/open") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody("""{"startingCash":"100000.00"}""")
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun ownerId(): UUID {
        return dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT id FROM system.users WHERE username = 'owner'").use { statement ->
                statement.executeQuery().use { result ->
                    assertTrue(result.next(), "Seeded owner user must exist")
                    result.getObject("id", UUID::class.java)
                }
            }
        }
    }

    private fun openSessionCountForOwner(): Int {
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM sales.cash_sessions
                WHERE user_id = ? AND closed_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, ownerId())
                statement.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }
    }

    private fun transactionCount(): Int {
        return dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM sales.transactions").use { statement ->
                statement.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }
    }

    private fun createProductFixture(): ProductFixture {
        return dataSource.connection.use { connection ->
            val categoryId = firstUuid(connection, "SELECT id FROM inventory.categories ORDER BY created_at LIMIT 1")
            val unitId = firstUuid(connection, "SELECT id FROM inventory.units ORDER BY created_at LIMIT 1")
            val sku = "IT-${UUID.randomUUID()}"

            val productId = connection.prepareStatement(
                """
                INSERT INTO inventory.products (
                    category_id,
                    base_unit_id,
                    sku,
                    name,
                    price_buy,
                    price_retail,
                    price_contractor,
                    min_stock
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, categoryId)
                statement.setObject(2, unitId)
                statement.setString(3, sku)
                statement.setString(4, "Integration Test Product $sku")
                statement.setBigDecimal(5, BigDecimal("50.00"))
                statement.setBigDecimal(6, BigDecimal("100.00"))
                statement.setBigDecimal(7, BigDecimal("90.00"))
                statement.setBigDecimal(8, BigDecimal.ZERO)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getObject("id", UUID::class.java)
                }
            }

            connection.prepareStatement(
                "INSERT INTO inventory.stock (product_id, unit_id, quantity) VALUES (?, ?, ?)"
            ).use { statement ->
                statement.setObject(1, productId)
                statement.setObject(2, unitId)
                statement.setBigDecimal(3, BigDecimal("10.00"))
                statement.executeUpdate()
            }

            ProductFixture(id = productId, unitId = unitId)
        }
    }

    private fun insertTransactionWithInvalidDiscount(product: ProductFixture) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val transactionId = connection.prepareStatement(
                    """
                    INSERT INTO sales.transactions (session_id, user_id, total, paid_amount)
                    VALUES (?, ?, ?, ?)
                    RETURNING id
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, activeSessionIdForOwner())
                    statement.setObject(2, ownerId())
                    statement.setBigDecimal(3, BigDecimal("100.00"))
                    statement.setBigDecimal(4, BigDecimal("100.00"))
                    statement.executeQuery().use { result ->
                        result.next()
                        result.getObject("id", UUID::class.java)
                    }
                }

                connection.prepareStatement(
                    """
                    INSERT INTO sales.transaction_items (
                        transaction_id,
                        product_id,
                        unit_id,
                        quantity,
                        price_at_transaction,
                        cogs_at_transaction,
                        discount,
                        subtotal
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, transactionId)
                    statement.setObject(2, product.id)
                    statement.setObject(3, product.unitId)
                    statement.setBigDecimal(4, BigDecimal.ONE)
                    statement.setBigDecimal(5, BigDecimal("100.00"))
                    statement.setBigDecimal(6, BigDecimal("50.00"))
                    statement.setBigDecimal(7, BigDecimal("200.00"))
                    statement.setBigDecimal(8, BigDecimal("-100.00"))
                    statement.executeUpdate()
                }

                connection.commit()
            } catch (error: SQLException) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun activeSessionIdForOwner(): UUID {
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id
                FROM sales.cash_sessions
                WHERE user_id = ? AND closed_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, ownerId())
                statement.executeQuery().use { result ->
                    assertTrue(result.next(), "Open session fixture must exist")
                    result.getObject("id", UUID::class.java)
                }
            }
        }
    }

    private fun firstUuid(connection: Connection, query: String): UUID {
        return connection.prepareStatement(query).use { statement ->
            statement.executeQuery().use { result ->
                assertTrue(result.next(), "Fixture query returned no rows: $query")
                result.getObject("id", UUID::class.java)
            }
        }
    }

    private data class ProductFixture(
        val id: UUID,
        val unitId: UUID
    )
}
