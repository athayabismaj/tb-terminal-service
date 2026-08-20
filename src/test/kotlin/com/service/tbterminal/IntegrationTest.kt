package com.service.tbterminal

import com.service.tbterminal.inventory.ProductCsvImportRequest
import com.service.tbterminal.plugins.configureDI
import com.service.tbterminal.plugins.configureRouting
import com.service.tbterminal.plugins.configureSecurity
import com.service.tbterminal.plugins.configureSerialization
import com.service.tbterminal.plugins.configureStatusPages
import com.service.tbterminal.plugins.DatabaseHealth
import com.service.tbterminal.system.SystemRepository
import com.service.tbterminal.system.UserSessionService
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.DockerClientFactory
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.koin.core.context.stopKoin

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationTest {
    private val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
        withDatabaseName("tb_terminal_integration")
        withUsername("tb_terminal")
        withPassword("tb_terminal")
    }

    private lateinit var dataSource: HikariDataSource
    private var containerStarted = false

    @BeforeAll
    fun startPostgres() {
        val externalUrl = System.getenv("TEST_DB_URL")?.takeIf(String::isNotBlank)
        if (externalUrl == null) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable,
                "Integration test membutuhkan Docker/Testcontainers atau TEST_DB_URL khusus test"
            )
            postgres.start()
            containerStarted = true
        }

        val jdbcUrl = externalUrl ?: postgres.jdbcUrl
        val dbUser = if (externalUrl != null) requireNotNull(System.getenv("TEST_DB_USER")?.takeIf(String::isNotBlank)) {
            "TEST_DB_USER wajib bersama TEST_DB_URL"
        } else postgres.username
        val dbPassword = if (externalUrl != null) requireNotNull(System.getenv("TEST_DB_PASSWORD")?.takeIf(String::isNotBlank)) {
            "TEST_DB_PASSWORD wajib bersama TEST_DB_URL"
        } else postgres.password

        dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = dbUser
            password = dbPassword
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
        DatabaseHealth.attach(dataSource)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) {
            DatabaseHealth.detach(dataSource)
            dataSource.close()
        }
        if (containerStarted) postgres.stop()
    }

    @BeforeEach
    fun resetMutableState() {
        stopKoin()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE TABLE system.database_backup_jobs CASCADE")
                statement.execute("TRUNCATE TABLE receivable.receivable_payments, receivable.receivables CASCADE")
                statement.execute("TRUNCATE TABLE sales.cash_sessions CASCADE")
                statement.execute("DELETE FROM receivable.customers WHERE name LIKE 'Integration Receivable %'")
                statement.execute("UPDATE system.users SET token_version = 0 WHERE username = 'owner'")
            }
        }
    }

    @AfterEach
    fun stopTestDependencyInjection() {
        stopKoin()
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
    fun test_cash_session_close_validates_and_preserves_audit() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val opened = openSession(token)
        assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
        val sessionId = UUID.fromString(checkoutDataId(opened))

        val invalid = closeSession(token, "-1.00")
        assertEquals(HttpStatusCode.BadRequest, invalid.status, invalid.bodyAsText())
        assertEquals(1, openSessionCountForOwner())

        val closed = closeSession(token, "100000.00")
        assertEquals(HttpStatusCode.OK, closed.status, closed.bodyAsText())
        assertEquals(0, openSessionCountForOwner())
        assertEquals(1, auditActionCount("sales", "cash_sessions", sessionId, "INSERT"))
        assertEquals(1, auditActionCount("sales", "cash_sessions", sessionId, "UPDATE"))

        val duplicate = closeSession(token, "100000.00")
        assertTrue(duplicate.status.value >= 400, duplicate.bodyAsText())
    }

    @Test
    fun test_opening_stock_import_preview_commit_and_card_are_reconciled() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture(BigDecimal.ZERO)

        val opening = client.post("/api/inventory/stock/opening-balance") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """{"productId":"${product.id}","date":"2026-07-01","quantity":"12.50","note":"Saldo awal integration"}"""
            )
        }
        assertEquals(HttpStatusCode.Created, opening.status, opening.bodyAsText())
        assertEquals(BigDecimal("12.50"), stockQuantity(product.id))
        assertEquals(1, stockMovementCount(product.id, "OPENING_BALANCE"))

        val duplicateOpening = client.post("/api/inventory/stock/opening-balance") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """{"productId":"${product.id}","date":"2026-07-01","quantity":"1.00","note":"Duplikat"}"""
            )
        }
        assertEquals(HttpStatusCode.BadRequest, duplicateOpening.status, duplicateOpening.bodyAsText())
        assertEquals(BigDecimal("12.50"), stockQuantity(product.id))

        val card = client.get("/api/inventory/stock/card?productId=${product.id}") { bearer(token) }
        assertEquals(HttpStatusCode.OK, card.status, card.bodyAsText())
        assertTrue(card.bodyAsText().contains("\"reconciled\":true"), card.bodyAsText())

        val category = firstString("SELECT name FROM inventory.categories ORDER BY created_at LIMIT 1")
        val unit = firstString("SELECT symbol FROM inventory.units ORDER BY created_at LIMIT 1")
        val sku = "CSV-${UUID.randomUUID().toString().replace("-", "").take(16)}".uppercase()
        val csv = buildString {
            appendLine("sku,name,category,unit,price_buy,price_retail,price_contractor,min_stock,opening_stock,opening_date,opening_note")
            append("$sku,Produk CSV Integration,$category,$unit,10.00,20.00,18.00,1.00,5.00,2026-07-01,Saldo awal CSV")
        }
        val preview = client.post("/api/inventory/imports/products/preview") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(productCsvBody(csv))
        }
        assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
        val previewData = Json.parseToJsonElement(preview.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals("1", previewData.getValue("validRows").jsonPrimitive.content)
        assertEquals("0", previewData.getValue("invalidRows").jsonPrimitive.content)

        val committed = client.post("/api/inventory/imports/products/commit") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(productCsvBody(csv))
        }
        assertEquals(HttpStatusCode.Created, committed.status, committed.bodyAsText())
        val importedProductId = requireNotNull(productIdBySku(sku))
        assertEquals(BigDecimal("5.00"), stockQuantity(importedProductId))
        assertEquals(1, stockMovementCount(importedProductId, "OPENING_BALANCE"))

        val failedSku = "CSV-${UUID.randomUUID().toString().replace("-", "").take(16)}".uppercase()
        val invalidCsv = csv.replace(sku, failedSku).replace("20.00,18.00", "invalid,18.00")
        val invalidPreview = client.post("/api/inventory/imports/products/preview") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(productCsvBody(invalidCsv))
        }
        assertEquals(HttpStatusCode.OK, invalidPreview.status, invalidPreview.bodyAsText())
        assertTrue(invalidPreview.bodyAsText().contains("\"invalidRows\":1"), invalidPreview.bodyAsText())
        val invalidCommit = client.post("/api/inventory/imports/products/commit") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(productCsvBody(invalidCsv))
        }
        assertEquals(HttpStatusCode.BadRequest, invalidCommit.status, invalidCommit.bodyAsText())
        assertEquals(null, productIdBySku(failedSku))
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
                  "amountPaid": "0.00",
                  "idempotencyKey": "integration-invalid-financial"
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
    fun test_duplicate_checkout_should_return_same_transaction_and_reduce_stock_once() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture()
        val key = "checkout-${UUID.randomUUID()}"
        assertTrue(openSession(token).status.value in 200..299)

        val first = checkout(token, product, quantity = "2.00", amountPaid = "200.00", idempotencyKey = key)
        val second = checkout(token, product, quantity = "2.00", amountPaid = "200.00", idempotencyKey = key)

        assertTrue(first.status.value in 200..299, first.bodyAsText())
        assertTrue(second.status.value in 200..299, second.bodyAsText())
        assertEquals(checkoutTransactionId(first), checkoutTransactionId(second))
        assertTrue(checkoutReplay(second), second.bodyAsText())
        assertEquals(1, transactionCount())
        assertEquals(BigDecimal("8.00"), stockQuantity(product.id))
    }

    @Test
    fun test_concurrent_checkout_should_not_oversell_stock() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val cashierToken = createAndLoginCashier(ownerToken)
        val product = createProductFixture()
        assertTrue(openSession(ownerToken).status.value in 200..299)
        assertTrue(openSession(cashierToken).status.value in 200..299)
        val releaseBothRequests = CompletableDeferred<Unit>()

        val responses = coroutineScope {
            val first = async {
                releaseBothRequests.await()
                checkout(ownerToken, product, "7.00", "700.00", "checkout-${UUID.randomUUID()}")
            }
            val second = async {
                releaseBothRequests.await()
                checkout(cashierToken, product, "7.00", "700.00", "checkout-${UUID.randomUUID()}")
            }
            releaseBothRequests.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(1, responses.count { it.status.value in 200..299 })
        assertEquals(1, responses.count { it.status.value >= 400 })
        assertEquals(1, transactionCount())
        assertEquals(BigDecimal("3.00"), stockQuantity(product.id))
    }

    @Test
    fun test_insufficient_stock_should_rollback_checkout() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture()
        assertTrue(openSession(token).status.value in 200..299)

        val response = checkout(
            token,
            product,
            quantity = "11.00",
            amountPaid = "1100.00",
            idempotencyKey = "checkout-${UUID.randomUUID()}"
        )

        assertTrue(response.status.value >= 400, response.bodyAsText())
        assertEquals(0, transactionCount())
        assertEquals(BigDecimal("10.00"), stockQuantity(product.id))
    }

    @Test
    fun test_invalid_payment_should_not_create_transaction() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture()
        assertTrue(openSession(token).status.value in 200..299)

        val response = checkout(
            token,
            product,
            quantity = "1.00",
            amountPaid = "99.00",
            idempotencyKey = "checkout-${UUID.randomUUID()}",
            paymentMethod = "transfer"
        )

        assertTrue(response.status.value >= 400, response.bodyAsText())
        assertEquals(0, transactionCount())
        assertEquals(BigDecimal("10.00"), stockQuantity(product.id))
    }

    @Test
    fun test_void_should_restore_stock_be_idempotent_and_reconcile_stock_card() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture()
        assertTrue(openSession(token).status.value in 200..299)
        val checkout = checkout(token, product, "2.00", "200.00", "checkout-${UUID.randomUUID()}")
        val transactionId = checkoutTransactionId(checkout)
        val key = "void-${UUID.randomUUID()}"

        val first = voidTransaction(token, transactionId, key, "Kesalahan input barang")
        val replay = voidTransaction(token, transactionId, key, "Kesalahan input barang")

        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertEquals("voided", transactionStatus(transactionId))
        assertEquals(BigDecimal("10.00"), stockQuantity(product.id))
        assertEquals(1, transactionVoidCount(transactionId))
        assertTrue(voidReplay(replay))
        assertEquals(stockQuantity(product.id), latestLedgerBalance(product.id))
        assertEquals(1, stockMovementCount(product.id, "VOID"))
    }

    @Test
    fun test_report_separates_voided_totals_and_csv_neutralizes_formula() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture()
        assertTrue(openSession(token).status.value in 200..299)

        val active = checkout(token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        assertTrue(active.status.value in 200..299, active.bodyAsText())
        val voided = checkout(token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        val voidId = checkoutTransactionId(voided)
        assertEquals(HttpStatusCode.OK, voidTransaction(token, voidId, "void-${UUID.randomUUID()}", "Uji pemisahan laporan").status)

        val report = client.get("/api/analytics/sales/report") { bearer(token) }
        assertEquals(HttpStatusCode.OK, report.status, report.bodyAsText())
        val data = Json.parseToJsonElement(report.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals("1", data["totals"]!!.jsonObject["transactionCount"]!!.jsonPrimitive.content)
        assertEquals("1", data["voided"]!!.jsonObject["transactionCount"]!!.jsonPrimitive.content)
        assertEquals("100.00", data["totals"]!!.jsonObject["grossRevenue"]!!.jsonPrimitive.content)
        assertEquals("100.00", data["voided"]!!.jsonObject["amount"]!!.jsonPrimitive.content)

        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE inventory.products SET name='=1+1' WHERE id=?").use { statement ->
                statement.setObject(1, product.id)
                statement.executeUpdate()
            }
        }
        val csv = client.get("/api/analytics/exports/stock.csv") { bearer(token) }
        assertEquals(HttpStatusCode.OK, csv.status, csv.bodyAsText())
        assertTrue(csv.bodyAsText().contains("\"'=1+1\""), csv.bodyAsText())
    }

    @Test
    fun test_concurrent_void_should_create_one_compensation_only() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture()
        assertTrue(openSession(token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(token, product, "3.00", "300.00", "checkout-${UUID.randomUUID()}")
        )
        val release = CompletableDeferred<Unit>()
        val responses = coroutineScope {
            val first = async { release.await(); voidTransaction(token, transactionId, "void-${UUID.randomUUID()}", "Void bersamaan pertama") }
            val second = async { release.await(); voidTransaction(token, transactionId, "void-${UUID.randomUUID()}", "Void bersamaan kedua") }
            release.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(1, responses.count { it.status.value in 200..299 })
        assertEquals(1, responses.count { it.status.value >= 400 })
        assertEquals(1, transactionVoidCount(transactionId))
        assertEquals(BigDecimal("10.00"), stockQuantity(product.id))
        assertEquals(1, stockMovementCount(product.id, "VOID"))
    }

    @Test
    fun test_void_failure_should_rollback_status_event_and_compensations() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture()
        assertTrue(openSession(token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        )
        deleteStockFixture(product.id)

        val response = voidTransaction(token, transactionId, "void-${UUID.randomUUID()}", "Paksa rollback tanpa baris stok")

        assertTrue(response.status.value >= 400, response.bodyAsText())
        assertEquals("lunas", transactionStatus(transactionId))
        assertEquals(0, transactionVoidCount(transactionId))
        assertEquals(0, stockMovementCount(product.id, "VOID"))
    }

    @Test
    fun test_transaction_history_filters_should_match_receipt_method_status_and_cashier() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture()
        assertTrue(openSession(token).status.value in 200..299)
        val checkout = checkout(token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        val data = Json.parseToJsonElement(checkout.bodyAsText()).jsonObject.getValue("data").jsonObject
        val receipt = data.getValue("receiptId").jsonPrimitive.content

        val history = client.get("/api/sales/transactions") {
            bearer(token)
            url.parameters.append("receiptNumber", receipt)
            url.parameters.append("paymentMethod", "tunai")
            url.parameters.append("status", "lunas")
            url.parameters.append("cashierId", ownerId().toString())
        }
        assertEquals(HttpStatusCode.OK, history.status, history.bodyAsText())
        assertTrue(history.bodyAsText().contains(receipt), history.bodyAsText())
    }

    @Test
    fun test_opening_receivable_should_not_create_pos_transaction() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val customerId = createReceivableCustomerFixture(BigDecimal("1000.00"))

        val response = createOpeningReceivable(token, customerId, "250.00", "OLD-2025-001")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.Created, response.status, body)
        val data = Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject
        assertEquals("OPENING_BALANCE", data.getValue("source").jsonPrimitive.content)
        assertEquals("UNPAID", data.getValue("status").jsonPrimitive.content)
        assertEquals("null", data.getValue("transactionId").toString())
        assertEquals("OLD-2025-001", data.getValue("legacyInvoiceNumber").jsonPrimitive.content)
        val receivableId = UUID.fromString(data.getValue("id").jsonPrimitive.content)
        assertEquals(1, auditCount("receivable", "receivables", receivableId))
        assertFailsWith<SQLException> { permanentlyDeleteReceivable(receivableId) }
        assertEquals(0, transactionCount())
        assertEquals(BigDecimal("250.00"), receivableRemaining(customerId))
    }

    @Test
    fun test_receivable_adjustment_requires_reason_is_audited_and_rejects_cashier() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val cashierToken = createAndLoginCashier(ownerToken)
        val customerId = createReceivableCustomerFixture(BigDecimal("1000.00"))

        val forbidden = createReceivableAdjustment(cashierToken, customerId, "100.00", "ADJ-FORBIDDEN", "Koreksi saldo")
        assertEquals(HttpStatusCode.Forbidden, forbidden.status, forbidden.bodyAsText())

        val missingReason = createReceivableAdjustment(ownerToken, customerId, "100.00", "ADJ-NO-REASON", "")
        assertEquals(HttpStatusCode.BadRequest, missingReason.status, missingReason.bodyAsText())
        assertTrue(receivableRemaining(customerId).compareTo(BigDecimal.ZERO) == 0)

        val created = createReceivableAdjustment(ownerToken, customerId, "100.25", "ADJ-VALID-001", "Koreksi migrasi piutang")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val data = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals("ADJUSTMENT", data.getValue("source").jsonPrimitive.content)
        assertEquals("null", data.getValue("transactionId").toString())
        assertEquals("ADJ-VALID-001", data.getValue("legacyInvoiceNumber").jsonPrimitive.content)
        val receivableId = UUID.fromString(data.getValue("id").jsonPrimitive.content)
        assertEquals(1, auditCount("receivable", "receivables", receivableId))
        assertEquals(BigDecimal("100.25"), receivableRemaining(customerId))

        val summary = client.get("/api/receivable/receivables/summary/customers") { bearer(ownerToken) }
        assertEquals(HttpStatusCode.OK, summary.status, summary.bodyAsText())
        assertTrue(summary.bodyAsText().contains(customerId.toString()), summary.bodyAsText())
    }

    @Test
    fun test_cashier_role_cannot_call_privileged_inventory_reports_users_or_backup() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val cashierToken = createAndLoginCashier(ownerToken)

        val responses = listOf(
            client.get("/api/system/users") { bearer(cashierToken) },
            client.get("/api/analytics/sales/report") { bearer(cashierToken) },
            client.get("/api/system/database-backups") { bearer(cashierToken) },
            client.post("/api/inventory/imports/products/preview") {
                contentType(ContentType.Application.Json)
                bearer(cashierToken)
                setBody(productCsvBody("sku"))
            },
            client.post("/api/inventory/stock/opening-balance") {
                contentType(ContentType.Application.Json)
                bearer(cashierToken)
                setBody("{}")
            }
        )
        responses.forEach { response ->
            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        }

        assertEquals(HttpStatusCode.OK, client.get("/api/analytics/sales/report") { bearer(ownerToken) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/system/database-backups") { bearer(ownerToken) }.status)
    }

    @Test
    fun test_concurrent_opening_receivable_should_enforce_credit_limit() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val customerId = createReceivableCustomerFixture(BigDecimal("100.00"))
        val release = CompletableDeferred<Unit>()

        val responses = coroutineScope {
            val first = async { release.await(); createOpeningReceivable(token, customerId, "60.00", "LIMIT-A") }
            val second = async { release.await(); createOpeningReceivable(token, customerId, "60.00", "LIMIT-B") }
            release.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(1, responses.count { it.status.value in 200..299 })
        assertEquals(1, responses.count { it.status.value >= 400 })
        assertEquals(BigDecimal("60.00"), receivableRemaining(customerId))
    }

    @Test
    fun test_receivable_payment_should_transition_partial_to_paid() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val customerId = createReceivableCustomerFixture(BigDecimal("500.00"))
        val opening = createOpeningReceivable(token, customerId, "100.00", "STATUS-001")
        val receivableId = checkoutDataId(opening)

        val partial = payReceivable(token, receivableId, "40.00")
        assertEquals(HttpStatusCode.Created, partial.status, partial.bodyAsText())
        assertEquals("PARTIAL", receivableStatus(receivableId))
        val paid = payReceivable(token, receivableId, "60.00")
        assertEquals(HttpStatusCode.Created, paid.status, paid.bodyAsText())
        assertEquals("PAID", receivableStatus(receivableId))
        assertEquals(BigDecimal("0.00"), receivableRemaining(customerId))
    }

    @Test
    fun test_duplicate_receivable_payment_should_return_same_receipt_once() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val customerId = createReceivableCustomerFixture(BigDecimal("500.00"))
        val receivableId = checkoutDataId(createOpeningReceivable(token, customerId, "100.00", "IDEMPOTENT-PAY"))
        val key = "receivable-pay-${UUID.randomUUID()}"

        val first = payReceivable(token, receivableId, "40.00", key)
        val second = payReceivable(token, receivableId, "40.00", key)

        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.Created, second.status, second.bodyAsText())
        assertEquals(checkoutDataId(first), checkoutDataId(second))
        assertTrue(paymentReplay(second), second.bodyAsText())
        assertEquals(1, receivablePaymentCount(receivableId))
        assertEquals(BigDecimal("60.00"), receivableRemaining(customerId))
    }

    @Test
    fun test_receivable_payment_reversal_should_restore_balance_and_be_immutable() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val customerId = createReceivableCustomerFixture(BigDecimal("500.00"))
        val receivableId = checkoutDataId(createOpeningReceivable(token, customerId, "100.00", "REVERSAL-PAY"))
        val payment = payReceivable(token, receivableId, "40.00")
        val paymentId = checkoutDataId(payment)

        val reversal = reverseReceivablePayment(token, paymentId, "Salah pencatatan nominal")

        assertEquals(HttpStatusCode.Created, reversal.status, reversal.bodyAsText())
        val reversalData = Json.parseToJsonElement(reversal.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals("REVERSAL", reversalData.getValue("entryType").jsonPrimitive.content)
        assertEquals("100.00", reversalData.getValue("balanceAfter").jsonPrimitive.content)
        assertEquals("UNPAID", receivableStatus(receivableId))
        assertEquals(2, receivablePaymentCount(receivableId))
        assertFailsWith<SQLException> { permanentlyDeletePayment(UUID.fromString(paymentId)) }
    }

    @Test
    fun test_concurrent_receivable_payment_should_not_overpay() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val customerId = createReceivableCustomerFixture(BigDecimal("500.00"))
        val receivableId = checkoutDataId(createOpeningReceivable(token, customerId, "100.00", "PAY-CONCURRENT"))
        val release = CompletableDeferred<Unit>()

        val responses = coroutineScope {
            val first = async { release.await(); payReceivable(token, receivableId, "60.00") }
            val second = async { release.await(); payReceivable(token, receivableId, "60.00") }
            release.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(1, responses.count { it.status.value in 200..299 })
        assertEquals(1, responses.count { it.status.value >= 400 })
        assertEquals(BigDecimal("40.00"), receivableRemaining(customerId))
        assertEquals("PARTIAL", receivableStatus(receivableId))
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
    fun test_refresh_then_logout_should_revoke_refresh_token() = testApplication {
        application { configureIntegrationApplication() }
        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"owner","password":"owner123"}""")
        }
        val loginData = Json.parseToJsonElement(loginResponse.bodyAsText())
            .jsonObject.getValue("data").jsonObject
        val accessToken = loginData.getValue("token").jsonPrimitive.content
        val refreshToken = loginData.getValue("refreshToken").jsonPrimitive.content

        val refreshed = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.OK, refreshed.status, refreshed.bodyAsText())

        val logout = client.post("/api/auth/logout") { bearer(accessToken) }
        assertEquals(HttpStatusCode.OK, logout.status, logout.bodyAsText())

        val revokedRefresh = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, revokedRefresh.status, revokedRefresh.bodyAsText())
    }

    @Test
    fun test_login_should_be_rate_limited_after_five_attempts() = testApplication {
        application { configureIntegrationApplication() }

        val responses = (1..6).map {
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"owner","password":"wrong-password"}""")
            }
        }

        assertEquals(HttpStatusCode.TooManyRequests, responses.last().status, responses.last().bodyAsText())
    }

    @Test
    fun test_create_system_user_should_return_created() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val adminRoleId = dataSource.connection.use { connection ->
            firstUuid(connection, "SELECT id FROM system.roles WHERE name = 'admin'")
        }
        val username = "integration_admin_${UUID.randomUUID().toString().replace("-", "").take(24)}"

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

        val adminLogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"admin123"}""")
        }
        val adminToken = Json.parseToJsonElement(adminLogin.bodyAsText())
            .jsonObject.getValue("data").jsonObject.getValue("token").jsonPrimitive.content
        val forbiddenUsers = client.get("/api/system/users") { bearer(adminToken) }
        assertEquals(HttpStatusCode.Forbidden, forbiddenUsers.status, forbiddenUsers.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/analytics/sales/report") { bearer(adminToken) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/system/database-backups") { bearer(adminToken) }.status)
    }

    @Test
    fun test_system_user_validation_should_reject_database_overflow_before_insert() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val adminRoleId = dataSource.connection.use { connection ->
            firstUuid(connection, "SELECT id FROM system.roles WHERE name = 'admin'")
        }
        val username = "u".repeat(51)

        val response = client.post("/api/system/users") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """{"name":"Valid Name","username":"$username","password":"Strong-Test-789!","pin":"846291","roleId":"$adminRoleId"}"""
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertEquals(0, userCount(username))
    }

    @Test
    fun test_health_and_database_readiness_should_be_available_without_authentication() = testApplication {
        application { configureIntegrationApplication() }

        val liveness = client.get("/health")
        val readiness = client.get("/ready")
        val apiReadiness = client.get("/api/readiness")

        assertEquals(HttpStatusCode.OK, liveness.status, liveness.bodyAsText())
        assertEquals(HttpStatusCode.OK, readiness.status, readiness.bodyAsText())
        assertEquals(HttpStatusCode.OK, apiReadiness.status, apiReadiness.bodyAsText())
        assertTrue(readiness.bodyAsText().contains("\"database\":\"up\""), readiness.bodyAsText())

        DatabaseHealth.detach(dataSource)
        try {
            val unavailable = client.get("/ready")
            assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status, unavailable.bodyAsText())
        } finally {
            DatabaseHealth.attach(dataSource)
        }
    }

    @Test
    fun test_e2e_supported_offline_sync_is_idempotent_and_audited() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val userId = ownerId()
        val product = createProductFixture()
        val deviceId = "integration-device-${UUID.randomUUID()}"
        val openKey = "offline-open-${UUID.randomUUID()}"
        val openBody =
            """{"clientGeneratedId":"$openKey","deviceId":"$deviceId","cashierUserId":"$userId","openedAt":"2026-07-01T08:00:00+07:00","startingCash":"100.00","openingNote":"Offline integration"}"""

        val opened = client.post("/api/sales/sessions/sync/open") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(openBody)
        }
        assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
        val openData = Json.parseToJsonElement(opened.bodyAsText()).jsonObject.getValue("data").jsonObject
        val sessionId = openData.getValue("serverCashSessionId").jsonPrimitive.content
        assertEquals("CREATED", openData.getValue("syncStatus").jsonPrimitive.content)

        val openReplay = client.post("/api/sales/sessions/sync/open") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(openBody)
        }
        assertEquals(HttpStatusCode.OK, openReplay.status, openReplay.bodyAsText())
        assertTrue(openReplay.bodyAsText().contains("\"syncStatus\":\"DUPLICATE\""), openReplay.bodyAsText())

        val checkoutKey = "offline-checkout-${UUID.randomUUID()}"
        val checkoutBody =
            """{"clientGeneratedId":"$checkoutKey","deviceId":"$deviceId","localTransactionCode":"LOCAL-001","cashierUserId":"$userId","cashSessionId":"$sessionId","paymentMethod":"tunai","subtotal":"100.00","discount":"0.00","total":"100.00","paidAmount":"100.00","remainingAmount":"0.00","occurredAt":"2026-07-01T09:00:00+07:00","items":[{"productId":"${product.id}","productNameSnapshot":"Offline Product","quantity":"1.00","priceAtTransaction":"100.00","cogsAtTransaction":"50.00","discount":"0.00","subtotal":"100.00"}]}"""
        val syncedCheckout = client.post("/api/sales/checkout/sync") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(checkoutBody)
        }
        assertEquals(HttpStatusCode.Created, syncedCheckout.status, syncedCheckout.bodyAsText())
        val checkoutReplay = client.post("/api/sales/checkout/sync") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(checkoutBody)
        }
        assertEquals(HttpStatusCode.OK, checkoutReplay.status, checkoutReplay.bodyAsText())
        assertTrue(checkoutReplay.bodyAsText().contains("\"syncStatus\":\"DUPLICATE\""), checkoutReplay.bodyAsText())
        assertEquals(1, transactionCount())
        assertEquals(BigDecimal("9.00"), stockQuantity(product.id))

        val expenseKey = "offline-expense-${UUID.randomUUID()}"
        val expenseBody =
            """{"clientGeneratedId":"$expenseKey","deviceId":"$deviceId","cashierUserId":"$userId","serverCashSessionId":"$sessionId","amount":"10.00","category":"Operasional","note":"Biaya offline integration","occurredAt":"2026-07-01T10:00:00+07:00"}"""
        val expense = client.post("/api/sales/cash-expenses/sync") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(expenseBody)
        }
        assertEquals(HttpStatusCode.Created, expense.status, expense.bodyAsText())
        val expenseReplay = client.post("/api/sales/cash-expenses/sync") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(expenseBody)
        }
        assertEquals(HttpStatusCode.OK, expenseReplay.status, expenseReplay.bodyAsText())
        assertTrue(expenseReplay.bodyAsText().contains("\"syncStatus\":\"DUPLICATE\""), expenseReplay.bodyAsText())

        val closeKey = "offline-close-${UUID.randomUUID()}"
        val closeBody =
            """{"deviceId":"$deviceId","clientGeneratedId":"$closeKey","serverCashSessionId":"$sessionId","cashierUserId":"$userId","closedAt":"2026-07-01T17:00:00+07:00","actualCash":"190.00","expectedCash":"190.00","difference":"0.00","closingNote":"Offline close integration"}"""
        val closed = client.post("/api/sales/sessions/sync/close") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(closeBody)
        }
        assertEquals(HttpStatusCode.OK, closed.status, closed.bodyAsText())
        assertTrue(closed.bodyAsText().contains("\"syncStatus\":\"UPDATED\""), closed.bodyAsText())
        val closeReplay = client.post("/api/sales/sessions/sync/close") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(closeBody)
        }
        assertEquals(HttpStatusCode.OK, closeReplay.status, closeReplay.bodyAsText())
        assertTrue(closeReplay.bodyAsText().contains("\"syncStatus\":\"DUPLICATE\""), closeReplay.bodyAsText())

        val sessionUuid = UUID.fromString(sessionId)
        assertEquals(1, auditActionCount("sales", "cash_sessions", sessionUuid, "INSERT"))
        assertEquals(2, auditActionCount("sales", "cash_sessions", sessionUuid, "UPDATE"))
    }

    @Test
    fun test_e2e_pos_stock_receivable_payment_void_and_report() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture()
        val customerId = createReceivableCustomerFixture(BigDecimal("1000.00"))
        assertEquals(HttpStatusCode.Created, openSession(token).status)

        val sale = checkout(token, product, "2.00", "200.00", "e2e-checkout-${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.Created, sale.status, sale.bodyAsText())
        assertEquals(BigDecimal("8.00"), stockQuantity(product.id))

        val receivable = createOpeningReceivable(token, customerId, "150.00", "E2E-LEGACY-001")
        assertEquals(HttpStatusCode.Created, receivable.status, receivable.bodyAsText())
        val receivableId = checkoutDataId(receivable)
        val payment = payReceivable(token, receivableId, "50.00", "e2e-payment-${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.Created, payment.status, payment.bodyAsText())
        assertEquals("PARTIAL", receivableStatus(receivableId))

        val transactionId = checkoutTransactionId(sale)
        val void = voidTransaction(token, transactionId, "e2e-void-${UUID.randomUUID()}", "Uji akhir alur terpadu")
        assertEquals(HttpStatusCode.OK, void.status, void.bodyAsText())
        assertEquals(BigDecimal("10.00"), stockQuantity(product.id))
        assertEquals(stockQuantity(product.id), latestLedgerBalance(product.id))

        val report = client.get("/api/analytics/sales/report") { bearer(token) }
        assertEquals(HttpStatusCode.OK, report.status, report.bodyAsText())
        val reportData = Json.parseToJsonElement(report.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals("0", reportData.getValue("totals").jsonObject.getValue("transactionCount").jsonPrimitive.content)
        assertEquals("1", reportData.getValue("voided").jsonObject.getValue("transactionCount").jsonPrimitive.content)
    }

    @Test
    fun test_e2e_backup_download_validate_and_restore_with_persisted_metadata() = testApplication {
        assumeTrue(System.getenv("RESTORE_ENABLED").equals("true", ignoreCase = true), "RESTORE_ENABLED=true diperlukan")
        assumeTrue(!System.getenv("BACKUP_DIRECTORY").isNullOrBlank(), "BACKUP_DIRECTORY khusus test diperlukan")
        application { configureIntegrationApplication() }
        val token = loginAsOwner()

        val backup = client.post("/api/system/database-backups") { bearer(token) }
        assertEquals(HttpStatusCode.Accepted, backup.status, backup.bodyAsText())
        val backupData = Json.parseToJsonElement(backup.bodyAsText()).jsonObject.getValue("data").jsonObject
        val backupId = backupData.getValue("id").jsonPrimitive.content
        assertEquals("PENDING", backupData.getValue("status").jsonPrimitive.content)
        assertEquals("SUCCEEDED", awaitBackupJob(token, backupId).getValue("status").jsonPrimitive.content)

        val download = client.get("/api/system/database-backups/$backupId/download") { bearer(token) }
        assertEquals(HttpStatusCode.OK, download.status)
        val dump = download.bodyAsBytes()
        assertTrue(dump.size > 5)
        assertEquals("PGDMP", dump.copyOfRange(0, 5).toString(Charsets.US_ASCII))

        val validation = client.submitFormWithBinaryData(
            url = "/api/system/database-backups/restore/validate",
            formData = formData {
                append("file", dump, Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=tb-terminal-e2e.dump")
                })
            }
        ) { bearer(token) }
        assertEquals(HttpStatusCode.Created, validation.status, validation.bodyAsText())
        val validationData = Json.parseToJsonElement(validation.bodyAsText()).jsonObject.getValue("data").jsonObject
        val restoreId = validationData.getValue("job").jsonObject.getValue("id").jsonPrimitive.content
        val confirmationToken = validationData.getValue("confirmationToken").jsonPrimitive.content
        val confirmationPhrase = validationData.getValue("confirmationPhrase").jsonPrimitive.content

        val restored = client.post("/api/system/database-backups/restore/$restoreId/confirm") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """{"confirmationToken":"$confirmationToken","confirmationPhrase":"$confirmationPhrase","acknowledgeDowntimeAndOverwrite":true}"""
            )
        }
        assertEquals(HttpStatusCode.Accepted, restored.status, restored.bodyAsText())
        val restoredData = Json.parseToJsonElement(restored.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals("PENDING", restoredData.getValue("status").jsonPrimitive.content)

        val duplicate = client.post("/api/system/database-backups/restore/$restoreId/confirm") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """{"confirmationToken":"$confirmationToken","confirmationPhrase":"$confirmationPhrase","acknowledgeDowntimeAndOverwrite":true}"""
            )
        }
        assertEquals(HttpStatusCode.BadRequest, duplicate.status, duplicate.bodyAsText())

        assertEquals("SUCCEEDED", awaitBackupJob(token, restoreId).getValue("status").jsonPrimitive.content)
        assertEquals(1, backupJobCount(UUID.fromString(restoreId)))
        assertTrue(successfulBackupCount() >= 2, "Backup sumber dan safety backup harus tercatat")
        awaitRestoreAudit(UUID.fromString(restoreId))
    }

    private suspend fun ApplicationTestBuilder.awaitBackupJob(token: String, id: String): kotlinx.serialization.json.JsonObject {
        repeat(150) {
            val response = client.get("/api/system/database-backups/$id") { bearer(token) }
            if (response.status in setOf(HttpStatusCode.NotFound, HttpStatusCode.Unauthorized, HttpStatusCode.ServiceUnavailable)) {
                // pg_restore replaces metadata/auth tables before the service reconstructs
                // the current job. Polling must tolerate this short maintenance window.
                delay(200)
                return@repeat
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonObject
            when (data.getValue("status").jsonPrimitive.content) {
                "SUCCEEDED" -> return data
                "FAILED" -> error("Job backup/restore gagal: ${data["errorMessage"]}")
            }
            delay(200)
        }
        error("Job backup/restore tidak selesai dalam batas test")
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

    private suspend fun ApplicationTestBuilder.closeSession(token: String, endingCash: String): HttpResponse =
        client.post("/api/sales/sessions/close") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody("""{"endingCashPhysical":"$endingCash","notes":"Integration close session"}""")
        }

    private suspend fun ApplicationTestBuilder.createAndLoginCashier(ownerToken: String): String {
        val cashierRoleId = dataSource.connection.use { connection ->
            firstUuid(connection, "SELECT id FROM system.roles WHERE name = 'kasir'")
        }
        val username = "integration_cashier_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val createResponse = client.post("/api/system/users") {
            contentType(ContentType.Application.Json)
            bearer(ownerToken)
            setBody(
                """
                {
                  "name":"Integration Cashier",
                  "username":"$username",
                  "password":"Integration-Checkout-789!",
                  "pin":"846291",
                  "roleId":"$cashierRoleId"
                }
                """.trimIndent()
            )
        }
        assertTrue(createResponse.status.value in 200..299, createResponse.bodyAsText())

        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"Integration-Checkout-789!"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status, loginResponse.bodyAsText())
        return Json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("token").jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.checkout(
        token: String,
        product: ProductFixture,
        quantity: String,
        amountPaid: String,
        idempotencyKey: String,
        paymentMethod: String = "tunai"
    ): HttpResponse {
        return client.post("/api/sales/checkout") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """
                {
                  "items": [{"productId":"${product.id}","qty":"$quantity","discount":"0.00"}],
                  "paymentMethod":"$paymentMethod",
                  "amountPaid":"$amountPaid",
                  "idempotencyKey":"$idempotencyKey"
                }
                """.trimIndent()
            )
        }
    }

    private suspend fun checkoutTransactionId(response: HttpResponse): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("id").jsonPrimitive.content

    private suspend fun checkoutReplay(response: HttpResponse): Boolean =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("idempotentReplay").jsonPrimitive.content.toBoolean()

    private fun productCsvBody(csv: String): String =
        Json.encodeToString(ProductCsvImportRequest.serializer(), ProductCsvImportRequest(csv))

    private suspend fun ApplicationTestBuilder.voidTransaction(
        token: String,
        transactionId: String,
        idempotencyKey: String,
        reason: String
    ): HttpResponse = client.post("/api/sales/transactions/$transactionId/void") {
        contentType(ContentType.Application.Json)
        bearer(token)
        setBody("""{"idempotencyKey":"$idempotencyKey","reason":"$reason"}""")
    }

    private suspend fun voidReplay(response: HttpResponse): Boolean =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("idempotentReplay").jsonPrimitive.content.toBoolean()

    private suspend fun ApplicationTestBuilder.createOpeningReceivable(
        token: String,
        customerId: UUID,
        amount: String,
        legacyInvoice: String
    ): HttpResponse = client.post("/api/receivable/receivables/opening-balance") {
        contentType(ContentType.Application.Json)
        bearer(token)
        setBody(
            """
            {
              "customerId":"$customerId",
              "amount":"$amount",
              "debtDate":"2026-07-01",
              "dueDate":"2026-08-31",
              "legacyInvoiceNumber":"$legacyInvoice",
              "notes":"Integration opening balance"
            }
            """.trimIndent()
        )
    }

    private suspend fun ApplicationTestBuilder.createReceivableAdjustment(
        token: String,
        customerId: UUID,
        amount: String,
        reference: String,
        reason: String
    ): HttpResponse = client.post("/api/receivable/receivables/adjustment") {
        contentType(ContentType.Application.Json)
        bearer(token)
        setBody(
            """
            {
              "customerId":"$customerId",
              "amount":"$amount",
              "debtDate":"2026-07-01",
              "dueDate":"2026-08-31",
              "legacyInvoiceNumber":"$reference",
              "notes":"$reason"
            }
            """.trimIndent()
        )
    }

    private suspend fun ApplicationTestBuilder.payReceivable(
        token: String,
        receivableId: String,
        amount: String,
        idempotencyKey: String = "receivable-pay-${UUID.randomUUID()}"
    ): HttpResponse = client.post("/api/receivable/payments") {
        contentType(ContentType.Application.Json)
        bearer(token)
        setBody("""{"receivableId":"$receivableId","amount":"$amount","method":"transfer","idempotencyKey":"$idempotencyKey"}""")
    }

    private suspend fun ApplicationTestBuilder.reverseReceivablePayment(
        token: String,
        paymentId: String,
        reason: String,
        idempotencyKey: String = "receivable-reversal-${UUID.randomUUID()}"
    ): HttpResponse = client.post("/api/receivable/payments/$paymentId/reversal") {
        contentType(ContentType.Application.Json)
        bearer(token)
        setBody("""{"idempotencyKey":"$idempotencyKey","reason":"$reason"}""")
    }

    private suspend fun paymentReplay(response: HttpResponse): Boolean =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("idempotentReplay").jsonPrimitive.content.toBoolean()

    private suspend fun checkoutDataId(response: HttpResponse): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("id").jsonPrimitive.content

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

    private fun userCount(username: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM system.users WHERE username = ?").use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    private fun backupJobCount(id: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM system.database_backup_jobs WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    private fun restoreAuditCount(id: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM system.audit_logs WHERE table_name='database_backup_jobs' AND record_id=? AND action='UPDATE'"
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private suspend fun awaitRestoreAudit(id: UUID) {
        repeat(50) {
            if (restoreAuditCount(id) >= 1) return
            delay(100)
        }
        assertTrue(false, "Restore selesai wajib meninggalkan audit log")
    }

    private fun successfulBackupCount(): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM system.database_backup_jobs WHERE operation='BACKUP' AND status='SUCCEEDED'").use { statement ->
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    private fun stockQuantity(productId: UUID): BigDecimal {
        return dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT quantity FROM inventory.stock WHERE product_id = ?").use { statement ->
                statement.setObject(1, productId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next(), "Product stock fixture must exist")
                    result.getBigDecimal(1)
                }
            }
        }
    }

    private fun transactionStatus(transactionId: String): String = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT status::text FROM sales.transactions WHERE id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(transactionId))
            statement.executeQuery().use { result -> result.next(); result.getString(1) }
        }
    }

    private fun transactionVoidCount(transactionId: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM sales.transaction_voids WHERE transaction_id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(transactionId))
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    private fun latestLedgerBalance(productId: UUID): BigDecimal = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT balance_after FROM inventory.stock_movements WHERE product_id = ? ORDER BY sequence_no DESC LIMIT 1"
        ).use { statement ->
            statement.setObject(1, productId)
            statement.executeQuery().use { result -> assertTrue(result.next()); result.getBigDecimal(1) }
        }
    }

    private fun stockMovementCount(productId: UUID, type: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM inventory.stock_movements WHERE product_id = ? AND movement_type::text = ?"
        ).use { statement ->
            statement.setObject(1, productId)
            statement.setString(2, type)
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    private fun deleteStockFixture(productId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM inventory.stock WHERE product_id = ?").use { statement ->
                statement.setObject(1, productId)
                statement.executeUpdate()
            }
        }
    }

    private fun createProductFixture(initialStock: BigDecimal = BigDecimal("10.00")): ProductFixture {
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
                statement.setBigDecimal(3, initialStock)
                statement.executeUpdate()
            }

            ProductFixture(id = productId, unitId = unitId)
        }
    }

    private fun firstString(sql: String): String = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { result -> assertTrue(result.next()); result.getString(1) }
        }
    }

    private fun productIdBySku(sku: String): UUID? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT id FROM inventory.products WHERE sku = ?").use { statement ->
            statement.setString(1, sku)
            statement.executeQuery().use { result -> if (result.next()) result.getObject(1, UUID::class.java) else null }
        }
    }

    private fun createReceivableCustomerFixture(creditLimit: BigDecimal): UUID {
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO receivable.customers (name, credit_limit, payment_term_days, is_active)
                VALUES (?, ?, 30, TRUE)
                RETURNING id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, "Integration Receivable ${UUID.randomUUID()}")
                statement.setBigDecimal(2, creditLimit)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getObject(1, UUID::class.java)
                }
            }
        }
    }

    private fun receivableRemaining(customerId: UUID): BigDecimal = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COALESCE(SUM(amount - paid_amount), 0) FROM receivable.receivables WHERE customer_id = ?"
        ).use { statement ->
            statement.setObject(1, customerId)
            statement.executeQuery().use { result -> result.next(); result.getBigDecimal(1) }
        }
    }

    private fun receivableStatus(receivableId: String): String = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT status::text FROM receivable.receivables WHERE id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(receivableId))
            statement.executeQuery().use { result ->
                assertTrue(result.next(), "Receivable fixture must exist")
                when (result.getString(1)) {
                    "belum_lunas" -> "UNPAID"
                    "sebagian" -> "PARTIAL"
                    "lunas" -> "PAID"
                    else -> error("Unknown receivable status")
                }
            }
        }
    }

    private fun receivablePaymentCount(receivableId: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM receivable.receivable_payments WHERE receivable_id = ?"
        ).use { statement ->
            statement.setObject(1, UUID.fromString(receivableId))
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    private fun permanentlyDeletePayment(paymentId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM receivable.receivable_payments WHERE id = ?").use { statement ->
                statement.setObject(1, paymentId)
                statement.executeUpdate()
            }
        }
    }

    private fun auditCount(schema: String, table: String, recordId: UUID): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM system.audit_logs WHERE schema_name = ? AND table_name = ? AND record_id = ?"
            ).use { statement ->
                statement.setString(1, schema)
                statement.setString(2, table)
                statement.setObject(3, recordId)
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
        }

    private fun auditActionCount(schema: String, table: String, recordId: UUID, action: String): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM system.audit_logs WHERE schema_name = ? AND table_name = ? AND record_id = ? AND action::text = ?"
            ).use { statement ->
                statement.setString(1, schema)
                statement.setString(2, table)
                statement.setObject(3, recordId)
                statement.setString(4, action)
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
        }

    private fun permanentlyDeleteReceivable(receivableId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM receivable.receivables WHERE id = ?").use { statement ->
                statement.setObject(1, receivableId)
                statement.executeUpdate()
            }
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
