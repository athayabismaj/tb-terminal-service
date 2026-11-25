package com.service.tbterminal

import com.service.tbterminal.inventory.ProductCsvImportRequest
import com.service.tbterminal.plugins.configureDI
import com.service.tbterminal.plugins.configureRouting
import com.service.tbterminal.plugins.configureSecurity
import com.service.tbterminal.plugins.configureSerialization
import com.service.tbterminal.plugins.configureStatusPages
import com.service.tbterminal.plugins.DatabaseHealth
import com.service.tbterminal.sales.OfflineCashSessionOpenSyncRequest
import com.service.tbterminal.sales.OfflineCheckoutSyncRequest
import com.service.tbterminal.sales.SalesRepositoryImpl
import com.service.tbterminal.sales.SalesService
import com.service.tbterminal.system.AttemptReservation
import com.service.tbterminal.system.CreateManagerApprovalRequest
import com.service.tbterminal.system.CredentialAttemptGuard
import com.service.tbterminal.system.CredentialScope
import com.service.tbterminal.system.PinAttemptState
import com.service.tbterminal.system.PinRolePolicy
import com.service.tbterminal.system.PinVerificationPolicy
import com.service.tbterminal.system.PinVerificationService
import com.service.tbterminal.system.UnlockRequest
import com.service.tbterminal.system.SystemRepository
import com.service.tbterminal.system.UserSessionService
import com.service.tbterminal.system.ManagerApprovalAction
import com.service.tbterminal.system.ManagerApprovalResourceType
import com.service.tbterminal.system.ManagerApprovalScope
import com.service.tbterminal.system.ManagerApprovalService
import com.service.tbterminal.shared.BusinessConflictException
import com.service.tbterminal.shared.EnvironmentConfig
import com.service.tbterminal.shared.ManagerApprovalError
import com.service.tbterminal.shared.ManagerApprovalException
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.TooManyAttemptsException
import com.service.tbterminal.shared.ValidationException
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.koin.core.context.GlobalContext
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
            val dockerAvailable = DockerClientFactory.instance().isDockerAvailable
            check(dockerAvailable || !System.getProperty("requireIntegrationTests").toBoolean()) {
                "requireIntegrationTests aktif, tetapi Docker/Testcontainers maupun TEST_DB_URL tidak tersedia"
            }
            assumeTrue(
                dockerAvailable,
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
                statement.execute("TRUNCATE TABLE sales.checkout_discount_attempts CASCADE")
                statement.execute("TRUNCATE TABLE system.manager_approvals CASCADE")
                statement.execute("TRUNCATE TABLE receivable.receivable_payments, receivable.receivables CASCADE")
                statement.execute("TRUNCATE TABLE sales.cash_sessions CASCADE")
                statement.execute("DELETE FROM receivable.customers WHERE name LIKE 'Integration Receivable %'")
                statement.execute("UPDATE system.users SET token_version = 0, name = 'Pemilik Toko', email = NULL WHERE username = 'owner'")
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
        val cashier = loginAsCashier(loginAsOwner())
        val token = cashier.token
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
        assertEquals(1, openSessionCount(cashier.userId))
    }

    @Test
    fun test_cash_session_close_validates_and_preserves_audit() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val token = cashier.token
        val opened = openSession(token)
        assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
        val sessionId = UUID.fromString(checkoutDataId(opened))

        val invalid = closeSession(token, "-1.00")
        assertEquals(HttpStatusCode.BadRequest, invalid.status, invalid.bodyAsText())
        assertEquals(1, openSessionCount(cashier.userId))

        val closed = closeSession(token, "100000.00")
        assertEquals(HttpStatusCode.OK, closed.status, closed.bodyAsText())
        assertEquals(0, openSessionCount(cashier.userId))
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
        val cashier = loginAsCashier(loginAsOwner())
        val token = cashier.token
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
            insertTransactionWithInvalidDiscount(product, cashier.userId)
        }
        assertEquals("23514", checkViolation.sqlState)
        assertEquals(0, transactionCount())
    }

    @Test
    fun test_duplicate_checkout_should_return_same_transaction_and_reduce_stock_once() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsCashier(loginAsOwner()).token
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
        val firstCashierToken = loginAsCashier(ownerToken).token
        val secondCashierToken = createAndLoginCashier(ownerToken)
        val product = createProductFixture()
        assertTrue(openSession(firstCashierToken).status.value in 200..299)
        assertTrue(openSession(secondCashierToken).status.value in 200..299)
        val releaseBothRequests = CompletableDeferred<Unit>()

        val responses = coroutineScope {
            val first = async {
                releaseBothRequests.await()
                checkout(firstCashierToken, product, "7.00", "700.00", "checkout-${UUID.randomUUID()}")
            }
            val second = async {
                releaseBothRequests.await()
                checkout(secondCashierToken, product, "7.00", "700.00", "checkout-${UUID.randomUUID()}")
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
        val token = loginAsCashier(loginAsOwner()).token
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
        val token = loginAsCashier(loginAsOwner()).token
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
        val cashier = loginAsCashier(token)
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)
        val checkout = checkout(cashier.token, product, "2.00", "200.00", "checkout-${UUID.randomUUID()}")
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
    fun test_full_refund_should_compensate_cash_restore_stock_and_be_idempotent() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val cashier = loginAsCashier(token)
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(cashier.token, product, "2.00", "200.00", "checkout-${UUID.randomUUID()}")
        )
        val key = "refund-${UUID.randomUUID()}"

        val first = refundTransaction(token, transactionId, key, "Barang dikembalikan utuh")
        val replay = refundTransaction(token, transactionId, key, "Barang dikembalikan utuh")

        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertTrue(refundReplay(replay))
        assertEquals("refunded", transactionStatus(transactionId))
        assertEquals(1, transactionRefundCount(transactionId))
        assertEquals(BigDecimal("200.00"), refundFinancialAmount(transactionId))
        assertEquals(BigDecimal("-200.00"), refundCompensationTotal(transactionId))
        assertEquals(BigDecimal("10.00"), stockQuantity(product.id))
        assertEquals(stockQuantity(product.id), latestLedgerBalance(product.id))
        assertEquals(1, stockMovementCount(product.id, "REFUND"))
        assertEquals(BigDecimal("100000.00"), activeCashSystemCash(cashier.userId))

        val voidAfterRefund = voidTransaction(
            token, transactionId, "void-${UUID.randomUUID()}", "Void tidak boleh sesudah refund"
        )
        assertTrue(voidAfterRefund.status.value >= 400, voidAfterRefund.bodyAsText())
        assertEquals(0, transactionVoidCount(transactionId))

        val report = client.get("/api/analytics/sales/report") { bearer(token) }
        assertEquals(HttpStatusCode.OK, report.status, report.bodyAsText())
        val reportData = Json.parseToJsonElement(report.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals("200.00", reportData.getValue("totals").jsonObject.getValue("grossRevenue").jsonPrimitive.content)
        assertEquals("200.00", reportData.getValue("totals").jsonObject.getValue("refundAmount").jsonPrimitive.content)
        assertEquals("0.00", reportData.getValue("totals").jsonObject.getValue("netRevenue").jsonPrimitive.content)
        assertEquals("1", reportData.getValue("refunded").jsonObject.getValue("transactionCount").jsonPrimitive.content)
    }

    @Test
    fun test_refund_without_physical_return_should_not_restore_stock() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val cashier = loginAsCashier(token)
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        )

        val response = refundTransaction(
            token,
            transactionId,
            "refund-${UUID.randomUUID()}",
            "Barang tidak kembali ke toko",
            disposition = "NOT_RETURNED"
        )

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertEquals(BigDecimal("9.00"), stockQuantity(product.id))
        assertEquals(0, stockMovementCount(product.id, "REFUND"))
    }

    @Test
    fun test_refund_hutang_and_dp_use_actual_received_amount_and_cancel_receivable() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val cashier = loginAsCashier(token)
        val product = createProductFixture()
        val customerId = createReceivableCustomerFixture(BigDecimal("1000.00"))
        assertTrue(openSession(cashier.token).status.value in 200..299)

        val debtId = checkoutTransactionId(
            checkoutWithCustomer(
                cashier.token, product, "1.00", "0.00", "checkout-${UUID.randomUUID()}", "hutang", customerId
            )
        )
        val debtRefund = refundTransaction(
            token, debtId, "refund-${UUID.randomUUID()}", "Membatalkan piutang tanpa pembayaran"
        )
        assertEquals(HttpStatusCode.Created, debtRefund.status, debtRefund.bodyAsText())
        assertEquals(BigDecimal("0.00"), refundFinancialAmount(debtId))
        assertFalse(receivableActive(debtId))

        val dpId = checkoutTransactionId(
            checkoutWithCustomer(
                cashier.token, product, "1.00", "30.00", "checkout-${UUID.randomUUID()}", "dp", customerId
            )
        )
        val dpRefund = refundTransaction(
            token, dpId, "refund-${UUID.randomUUID()}", "Mengembalikan uang muka pelanggan"
        )
        assertEquals(HttpStatusCode.Created, dpRefund.status, dpRefund.bodyAsText())
        assertEquals(BigDecimal("30.00"), refundFinancialAmount(dpId))
        assertFalse(receivableActive(dpId))
    }

    @Test
    fun test_concurrent_refund_should_create_one_compensation_and_one_stock_return() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val cashier = loginAsCashier(token)
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(cashier.token, product, "2.00", "200.00", "checkout-${UUID.randomUUID()}")
        )
        val release = CompletableDeferred<Unit>()
        val responses = coroutineScope {
            val first = async {
                release.await()
                refundTransaction(token, transactionId, "refund-${UUID.randomUUID()}", "Refund concurrent pertama")
            }
            val second = async {
                release.await()
                refundTransaction(token, transactionId, "refund-${UUID.randomUUID()}", "Refund concurrent kedua")
            }
            release.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(1, responses.count { it.status.value in 200..299 })
        assertEquals(1, responses.count { it.status.value >= 400 })
        assertEquals(1, transactionRefundCount(transactionId))
        assertEquals(BigDecimal("10.00"), stockQuantity(product.id))
        assertEquals(1, stockMovementCount(product.id, "REFUND"))
        assertEquals(1, refundCompensationCount(transactionId))
    }

    @Test
    fun test_cashier_refund_requires_and_consumes_refund_scoped_approval() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "refund_admin")
        val cashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "refund_cashier")
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        )

        val missing = refundTransaction(
            cashier.token, transactionId, "refund-${UUID.randomUUID()}", "Kasir tanpa persetujuan"
        )
        assertEquals(HttpStatusCode.Forbidden, missing.status, missing.bodyAsText())
        assertTrue(missing.bodyAsText().contains("MANAGER_APPROVAL_REQUIRED"), missing.bodyAsText())

        val approvalId = managerApprovalId(
            requestManagerApproval(
                cashier.token,
                UUID.fromString(transactionId),
                admin.username,
                admin.pin,
                action = "REFUND_TRANSACTION"
            )
        )
        val key = "refund-${UUID.randomUUID()}"
        val approved = refundTransaction(
            cashier.token, transactionId, key, "Kasir refund dengan persetujuan", managerApprovalId = approvalId
        )
        val replay = refundTransaction(
            cashier.token, transactionId, key, "Kasir refund dengan persetujuan", managerApprovalId = approvalId
        )

        assertEquals(HttpStatusCode.Created, approved.status, approved.bodyAsText())
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertEquals("USED", managerApprovalStatus(approvalId))
        assertEquals(1, transactionRefundCount(transactionId))
    }

    @Test
    fun test_refund_failure_should_rollback_event_status_stock_and_approval() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "refund_rollback_admin")
        val cashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "refund_rollback_cashier")
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        )
        val approvalId = managerApprovalId(
            requestManagerApproval(
                cashier.token, UUID.fromString(transactionId), admin.username, admin.pin,
                action = "REFUND_TRANSACTION"
            )
        )
        deleteStockFixture(product.id)

        val response = refundTransaction(
            cashier.token,
            transactionId,
            "refund-${UUID.randomUUID()}",
            "Paksa rollback refund tanpa stok",
            managerApprovalId = approvalId
        )

        assertTrue(response.status.value >= 400, response.bodyAsText())
        assertEquals("lunas", transactionStatus(transactionId))
        assertEquals(0, transactionRefundCount(transactionId))
        assertEquals("APPROVED", managerApprovalStatus(approvalId))
    }

    @Test
    fun test_report_separates_voided_totals_and_csv_neutralizes_formula() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val cashier = loginAsCashier(token)
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)

        val active = checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        assertTrue(active.status.value in 200..299, active.bodyAsText())
        val voided = checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
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
        val cashier = loginAsCashier(token)
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(cashier.token, product, "3.00", "300.00", "checkout-${UUID.randomUUID()}")
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
        val cashier = loginAsCashier(token)
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        )
        deleteStockFixture(product.id)

        val response = voidTransaction(token, transactionId, "void-${UUID.randomUUID()}", "Paksa rollback tanpa baris stok")

        assertTrue(response.status.value >= 400, response.bodyAsText())
        assertEquals("lunas", transactionStatus(transactionId))
        assertEquals(0, transactionVoidCount(transactionId))
        assertEquals(0, stockMovementCount(product.id, "VOID"))
    }

    @Test
    fun test_admin_voids_directly_while_cashier_requires_and_consumes_scoped_approval() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "void_direct_admin")
        val cashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "void_cashier")
        val product = createProductFixture()

        assertTrue(openSession(cashier.token).status.value in 200..299)
        val adminTransactionId = checkoutTransactionId(
            checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        )
        val adminVoid = voidTransaction(
            admin.token,
            adminTransactionId,
            "void-${UUID.randomUUID()}",
            "Admin membatalkan transaksi"
        )
        assertEquals(HttpStatusCode.OK, adminVoid.status, adminVoid.bodyAsText())
        assertFalse(adminVoid.bodyAsText().contains("managerApprovalId\":\""), adminVoid.bodyAsText())

        val cashierTransactionId = checkoutTransactionId(
            checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        )
        val missingApproval = voidTransaction(
            cashier.token,
            cashierTransactionId,
            "void-${UUID.randomUUID()}",
            "Kasir mencoba tanpa approval"
        )
        assertEquals(HttpStatusCode.Forbidden, missingApproval.status, missingApproval.bodyAsText())
        assertTrue(missingApproval.bodyAsText().contains("MANAGER_APPROVAL_REQUIRED"), missingApproval.bodyAsText())
        assertEquals("lunas", transactionStatus(cashierTransactionId))

        val createdApproval = requestManagerApproval(
            cashier.token,
            UUID.fromString(cashierTransactionId),
            admin.username,
            admin.pin
        )
        val approvalId = managerApprovalId(createdApproval)
        val cashierVoidKey = "void-${UUID.randomUUID()}"
        val approvedVoid = voidTransaction(
            cashier.token,
            cashierTransactionId,
            cashierVoidKey,
            "Kasir membatalkan dengan approval",
            approvalId
        )
        val replay = voidTransaction(
            cashier.token,
            cashierTransactionId,
            cashierVoidKey,
            "Kasir membatalkan dengan approval",
            approvalId
        )

        assertEquals(HttpStatusCode.OK, approvedVoid.status, approvedVoid.bodyAsText())
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertTrue(voidReplay(replay))
        assertTrue(approvedVoid.bodyAsText().contains(approvalId.toString()), approvedVoid.bodyAsText())
        assertEquals("USED", managerApprovalStatus(approvalId))
        assertEquals(1, transactionVoidCount(cashierTransactionId))
        val voidAudit = voidAuditMetadata(UUID.fromString(cashierTransactionId))
        assertTrue(voidAudit.contains(approvalId.toString()), voidAudit)
        assertTrue(voidAudit.contains(admin.userId.toString()), voidAudit)
    }

    @Test
    fun test_cashier_void_rejects_foreign_action_and_transaction_scope_mismatches() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "void_scope_admin")
        val cashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "void_scope_cashier")
        val otherCashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "void_scope_other")
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)
        val transactionId = UUID.fromString(
            checkoutTransactionId(
                checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
            )
        )

        val foreignApproval = managerApprovalId(
            requestManagerApproval(otherCashier.token, transactionId, admin.username, admin.pin)
        )
        val foreign = voidTransaction(
            cashier.token, transactionId.toString(), "void-${UUID.randomUUID()}",
            "Approval milik kasir lain", foreignApproval
        )
        assertEquals(HttpStatusCode.Forbidden, foreign.status, foreign.bodyAsText())
        assertTrue(foreign.bodyAsText().contains("MANAGER_APPROVAL_REQUESTER_MISMATCH"), foreign.bodyAsText())

        val actionApproval = managerApprovalId(
            requestManagerApproval(
                cashier.token,
                transactionId,
                admin.username,
                admin.pin,
                action = "REFUND_TRANSACTION"
            )
        )
        val wrongAction = voidTransaction(
            cashier.token, transactionId.toString(), "void-${UUID.randomUUID()}",
            "Approval action tidak sesuai", actionApproval
        )
        assertEquals(HttpStatusCode.Forbidden, wrongAction.status, wrongAction.bodyAsText())
        assertTrue(wrongAction.bodyAsText().contains("MANAGER_APPROVAL_ACTION_MISMATCH"), wrongAction.bodyAsText())

        val wrongResourceApproval = managerApprovalId(
            requestManagerApproval(cashier.token, UUID.randomUUID(), admin.username, admin.pin)
        )
        val wrongResource = voidTransaction(
            cashier.token, transactionId.toString(), "void-${UUID.randomUUID()}",
            "Approval transaksi tidak sesuai", wrongResourceApproval
        )
        assertEquals(HttpStatusCode.Forbidden, wrongResource.status, wrongResource.bodyAsText())
        assertTrue(wrongResource.bodyAsText().contains("MANAGER_APPROVAL_SCOPE_MISMATCH"), wrongResource.bodyAsText())
        assertEquals("lunas", transactionStatus(transactionId.toString()))
        assertEquals(0, transactionVoidCount(transactionId.toString()))
    }

    @Test
    fun test_void_business_failure_rolls_back_approval_and_void_audit() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "void_rollback_admin")
        val cashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "void_rollback_cashier")
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        )
        val approvalId = managerApprovalId(
            requestManagerApproval(cashier.token, UUID.fromString(transactionId), admin.username, admin.pin)
        )
        deleteStockFixture(product.id)

        val response = voidTransaction(
            cashier.token,
            transactionId,
            "void-${UUID.randomUUID()}",
            "Paksa rollback Void berapproval",
            approvalId
        )

        assertTrue(response.status.value >= 400, response.bodyAsText())
        assertEquals("lunas", transactionStatus(transactionId))
        assertEquals("APPROVED", managerApprovalStatus(approvalId))
        assertEquals(0, transactionVoidCount(transactionId))
        assertEquals(0, voidAuditCount(UUID.fromString(transactionId)))
    }

    @Test
    fun test_cashier_void_rejects_expired_and_already_used_approval() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "void_state_admin")
        val cashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "void_state_cashier")
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)

        val expiredTransactionId = UUID.fromString(
            checkoutTransactionId(
                checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
            )
        )
        val expiredApprovalId = managerApprovalId(
            requestManagerApproval(cashier.token, expiredTransactionId, admin.username, admin.pin)
        )
        expireManagerApproval(expiredApprovalId)
        val expiredResponse = voidTransaction(
            cashier.token,
            expiredTransactionId.toString(),
            "void-${UUID.randomUUID()}",
            "Approval sudah kedaluwarsa",
            expiredApprovalId
        )
        assertEquals(HttpStatusCode.Conflict, expiredResponse.status, expiredResponse.bodyAsText())
        assertTrue(expiredResponse.bodyAsText().contains("MANAGER_APPROVAL_EXPIRED"), expiredResponse.bodyAsText())
        assertEquals("lunas", transactionStatus(expiredTransactionId.toString()))

        val usedTransactionId = UUID.fromString(
            checkoutTransactionId(
                checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
            )
        )
        val usedApprovalId = managerApprovalId(
            requestManagerApproval(cashier.token, usedTransactionId, admin.username, admin.pin)
        )
        val approvalService = org.koin.core.context.GlobalContext.get().get<ManagerApprovalService>()
        approvalService.consumeApproval(
            ManagerApprovalScope(
                approvalId = usedApprovalId,
                requesterUserId = cashier.userId,
                action = ManagerApprovalAction.VOID_TRANSACTION,
                resourceType = ManagerApprovalResourceType.TRANSACTION,
                resourceId = usedTransactionId
            ),
            null
        )
        val usedResponse = voidTransaction(
            cashier.token,
            usedTransactionId.toString(),
            "void-${UUID.randomUUID()}",
            "Approval sudah pernah dipakai",
            usedApprovalId
        )
        assertEquals(HttpStatusCode.Conflict, usedResponse.status, usedResponse.bodyAsText())
        assertTrue(usedResponse.bodyAsText().contains("MANAGER_APPROVAL_ALREADY_USED"), usedResponse.bodyAsText())
        assertEquals("lunas", transactionStatus(usedTransactionId.toString()))
    }

    @Test
    fun test_concurrent_cashier_void_with_one_approval_has_exactly_one_success() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "void_concurrent_admin")
        val cashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "void_concurrent_cashier")
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(cashier.token, product, "2.00", "200.00", "checkout-${UUID.randomUUID()}")
        )
        val approvalId = managerApprovalId(
            requestManagerApproval(cashier.token, UUID.fromString(transactionId), admin.username, admin.pin)
        )
        val release = CompletableDeferred<Unit>()

        val responses = coroutineScope {
            val first = async {
                release.await()
                voidTransaction(cashier.token, transactionId, "void-${UUID.randomUUID()}", "Void approval bersamaan A", approvalId)
            }
            val second = async {
                release.await()
                voidTransaction(cashier.token, transactionId, "void-${UUID.randomUUID()}", "Void approval bersamaan B", approvalId)
            }
            release.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(1, responses.count { it.status.value in 200..299 })
        assertEquals(1, responses.count { it.status.value >= 400 })
        assertEquals("USED", managerApprovalStatus(approvalId))
        assertEquals(1, transactionVoidCount(transactionId))
        assertEquals(BigDecimal("10.00"), stockQuantity(product.id))
        assertEquals(1, stockMovementCount(product.id, "VOID"))
    }

    @Test
    fun test_transaction_history_filters_should_match_receipt_method_status_and_cashier() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val cashier = loginAsCashier(token)
        val product = createProductFixture()
        assertTrue(openSession(cashier.token).status.value in 200..299)
        val checkout = checkout(cashier.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        val data = Json.parseToJsonElement(checkout.bodyAsText()).jsonObject.getValue("data").jsonObject
        val receipt = data.getValue("receiptId").jsonPrimitive.content

        val history = client.get("/api/sales/transactions") {
            bearer(token)
            url.parameters.append("receiptNumber", receipt)
            url.parameters.append("paymentMethod", "tunai")
            url.parameters.append("status", "lunas")
            url.parameters.append("cashierId", cashier.userId.toString())
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
            client.get("/api/system/security-settings") { bearer(cashierToken) },
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
        assertEquals(HttpStatusCode.OK, client.get("/api/system/security-settings") { bearer(ownerToken) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/system/store-profile") { bearer(cashierToken) }.status)
    }

    @Test
    fun test_protected_routes_require_authentication() = testApplication {
        application { configureIntegrationApplication() }

        val responses = listOf(
            client.get("/api/system/users"),
            client.get("/api/system/roles"),
            client.get("/api/system/store-profile"),
            client.get("/api/system/security-settings"),
            client.get("/api/system/database-backups"),
            client.get("/api/inventory/products"),
            client.get("/api/analytics/sales/report")
        )

        responses.forEach { response ->
            assertEquals(HttpStatusCode.Unauthorized, response.status, response.bodyAsText())
        }
    }

    @Test
    fun test_manager_approval_route_uses_jwt_requester_and_admin_approver_without_exposing_credentials() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val requester = createAndLoginApprovalUser(ownerToken, Role.KASIR, "approval_requester")
        val approver = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "approval_admin")
        val resourceId = UUID.randomUUID()

        val unauthenticated = requestManagerApproval(
            token = null,
            resourceId = resourceId,
            approverUsername = approver.username,
            approverPin = approver.pin
        )
        assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status, unauthenticated.bodyAsText())

        val created = requestManagerApproval(
            token = requester.token,
            resourceId = resourceId,
            approverUsername = approver.username,
            approverPin = approver.pin
        )
        val body = created.bodyAsText()
        assertEquals(HttpStatusCode.Created, created.status, body)
        assertFalse(body.contains("approverPin", ignoreCase = true), body)
        assertFalse(body.contains("pinHash", ignoreCase = true), body)
        assertFalse(body.contains(approver.pin), body)
        assertFalse(body.contains("token", ignoreCase = true), body)
        val approvalId = UUID.fromString(
            Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject
                .getValue("approvalId").jsonPrimitive.content
        )

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT requested_by_user_id, approved_by_user_id, action, resource_type, resource_id, status
                FROM system.manager_approvals WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, approvalId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(requester.userId, rows.getObject("requested_by_user_id", UUID::class.java))
                    assertEquals(approver.userId, rows.getObject("approved_by_user_id", UUID::class.java))
                    assertEquals("VOID_TRANSACTION", rows.getString("action"))
                    assertEquals("TRANSACTION", rows.getString("resource_type"))
                    assertEquals(resourceId, rows.getObject("resource_id", UUID::class.java))
                    assertEquals("APPROVED", rows.getString("status"))
                }
            }
            connection.prepareStatement(
                "SELECT new_data::text FROM system.audit_logs WHERE table_name = 'manager_approvals' AND record_id = ?"
            ).use { statement ->
                statement.setObject(1, approvalId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val audit = rows.getString(1)
                    assertTrue(audit.contains(requester.userId.toString()), audit)
                    assertTrue(audit.contains(approver.userId.toString()), audit)
                    assertFalse(audit.contains(approver.pin), audit)
                }
            }
        }

        val invalidAction = client.post("/api/system/manager-approvals") {
            contentType(ContentType.Application.Json)
            bearer(requester.token)
            setBody(
                """{"action":"BECOME_OWNER","resourceType":"TRANSACTION","resourceId":"$resourceId","approverUsername":"${approver.username}","approverPin":"${approver.pin}"}"""
            )
        }
        assertEquals(HttpStatusCode.BadRequest, invalidAction.status, invalidAction.bodyAsText())
    }

    @Test
    fun test_manager_approval_rejects_cashier_inactive_invalid_pin_and_self_approval() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val requester = createAndLoginApprovalUser(ownerToken, Role.KASIR, "approval_requester_rules")
        val cashierApprover = createAndLoginApprovalUser(ownerToken, Role.KASIR, "approval_cashier")
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "approval_self_admin")
        val inactiveAdmin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "approval_inactive")
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE system.users SET is_active = false WHERE id = ?").use { statement ->
                statement.setObject(1, inactiveAdmin.userId)
                statement.executeUpdate()
            }
        }

        val responses = listOf(
            requestManagerApproval(requester.token, UUID.randomUUID(), cashierApprover.username, cashierApprover.pin),
            requestManagerApproval(requester.token, UUID.randomUUID(), admin.username, "000999"),
            requestManagerApproval(requester.token, UUID.randomUUID(), inactiveAdmin.username, inactiveAdmin.pin),
            requestManagerApproval(admin.token, UUID.randomUUID(), admin.username, admin.pin)
        )
        responses.forEach { response ->
            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        }
        assertEquals(0, managerApprovalCount())
    }

    @Test
    fun test_owner_can_approve_manager_approval() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val requester = createAndLoginApprovalUser(ownerToken, Role.KASIR, "approval_owner_requester")
        val ownerApprover = createAndLoginApprovalUser(ownerToken, Role.OWNER, "approval_owner")

        val response = requestManagerApproval(
            requester.token,
            UUID.randomUUID(),
            ownerApprover.username,
            ownerApprover.pin
        )

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    @Test
    fun test_manager_approval_concurrent_consume_allows_exactly_one_use() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val requester = createAndLoginApprovalUser(ownerToken, Role.KASIR, "approval_concurrent_requester")
        val approver = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "approval_concurrent_admin")
        val resourceId = UUID.randomUUID()
        val created = requestManagerApproval(requester.token, resourceId, approver.username, approver.pin)
        val approvalId = UUID.fromString(
            Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("data").jsonObject
                .getValue("approvalId").jsonPrimitive.content
        )
        val service = org.koin.core.context.GlobalContext.get().get<ManagerApprovalService>()
        val scope = ManagerApprovalScope(
            approvalId = approvalId,
            requesterUserId = requester.userId,
            action = ManagerApprovalAction.VOID_TRANSACTION,
            resourceType = ManagerApprovalResourceType.TRANSACTION,
            resourceId = resourceId
        )
        val release = CompletableDeferred<Unit>()

        val results = coroutineScope {
            val first = async { release.await(); runCatching { service.consumeApproval(scope, null) } }
            val second = async { release.await(); runCatching { service.consumeApproval(scope, null) } }
            release.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(1, results.count(Result<*>::isSuccess))
        assertEquals(1, results.count(Result<*>::isFailure))
        assertTrue(results.single(Result<*>::isFailure).exceptionOrNull() is ManagerApprovalException)
        assertEquals("USED", managerApprovalStatus(approvalId))
        assertEquals(2, auditCount("system", "manager_approvals", approvalId))
    }

    @Test
    fun test_manager_approval_expiry_foreign_key_and_failed_creation_leave_consistent_state() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val requester = createAndLoginApprovalUser(ownerToken, Role.KASIR, "approval_expired_requester")
        val approver = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "approval_expired_admin")
        val resourceId = UUID.randomUUID()

        val invalidPin = requestManagerApproval(requester.token, resourceId, approver.username, "000999")
        assertEquals(HttpStatusCode.Forbidden, invalidPin.status, invalidPin.bodyAsText())
        assertEquals(0, managerApprovalCount())

        val created = requestManagerApproval(requester.token, resourceId, approver.username, approver.pin)
        val approvalId = UUID.fromString(
            Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("data").jsonObject
                .getValue("approvalId").jsonPrimitive.content
        )
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE system.manager_approvals SET created_at = NOW() - INTERVAL '10 minutes', expires_at = NOW() - INTERVAL '5 minutes' WHERE id = ?"
            ).use { statement ->
                statement.setObject(1, approvalId)
                statement.executeUpdate()
            }
        }
        val service = org.koin.core.context.GlobalContext.get().get<ManagerApprovalService>()
        assertFailsWith<ManagerApprovalException> {
            service.validateApproval(
                ManagerApprovalScope(
                    approvalId,
                    requester.userId,
                    ManagerApprovalAction.VOID_TRANSACTION,
                    ManagerApprovalResourceType.TRANSACTION,
                    resourceId
                )
            )
        }
        assertEquals("EXPIRED", managerApprovalStatus(approvalId))

        assertFailsWith<SQLException> {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO system.manager_approvals
                        (requested_by_user_id, approved_by_user_id, action, resource_type, resource_id, expires_at)
                    VALUES (?, ?, 'VOID_TRANSACTION', 'TRANSACTION', ?, NOW() + INTERVAL '5 minutes')
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, approver.userId)
                    statement.setObject(3, UUID.randomUUID())
                    statement.executeUpdate()
                }
            }
        }
        assertEquals(1, managerApprovalCount())
    }

    @Test
    fun test_manager_approval_PIN_verification_is_rate_limited() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val requester = createAndLoginApprovalUser(ownerToken, Role.KASIR, "approval_rate_requester")
        val approver = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "approval_rate_admin")

        val responses = (1..6).map {
            requestManagerApproval(requester.token, UUID.randomUUID(), approver.username, "000999")
        }

        // Dua PIN salah pertama ditolak biasa; yang ketiga mengunci approver (lockout per approver).
        responses.take(2).forEach { response ->
            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("MANAGER_APPROVAL_INVALID"), response.bodyAsText())
        }
        responses.subList(2, 5).forEach { response ->
            assertEquals(HttpStatusCode.TooManyRequests, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("MANAGER_APPROVER_LOCKED"), response.bodyAsText())
        }
        // Permintaan keenam dihentikan rate limit per IP, bukan oleh lockout approver.
        assertEquals(HttpStatusCode.TooManyRequests, responses.last().status, responses.last().bodyAsText())
        assertTrue(responses.last().bodyAsText().contains("RATE_LIMITED"), responses.last().bodyAsText())
        assertEquals(0, managerApprovalCount())
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
    fun test_authenticated_user_can_read_and_update_only_safe_profile_fields() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()

        val initial = client.get("/api/auth/me") { bearer(token) }
        assertEquals(HttpStatusCode.OK, initial.status, initial.bodyAsText())
        val initialData = Json.parseToJsonElement(initial.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals("owner", initialData.getValue("username").jsonPrimitive.content)
        assertEquals("owner", initialData.getValue("role").jsonPrimitive.content)

        val updated = client.put("/api/auth/me") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody("""{"name":"Pemilik Baru","email":"pemilik@tbterminal.test"}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        val updatedData = Json.parseToJsonElement(updated.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals("Pemilik Baru", updatedData.getValue("name").jsonPrimitive.content)
        assertEquals("pemilik@tbterminal.test", updatedData.getValue("email").jsonPrimitive.content)
        assertEquals("owner", updatedData.getValue("username").jsonPrimitive.content)
        assertEquals("owner", updatedData.getValue("role").jsonPrimitive.content)
        assertEquals(1, auditActionCount("system", "user_profile", ownerId(), "UPDATE"))

        val invalid = client.put("/api/auth/me") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody("""{"name":"P","email":"bukan-email"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status, invalid.bodyAsText())
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
        val refreshToken = loginData.getValue("refreshToken").jsonPrimitive.content

        val refreshed = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.OK, refreshed.status, refreshed.bodyAsText())
        val refreshedData = Json.parseToJsonElement(refreshed.bodyAsText())
            .jsonObject.getValue("data").jsonObject
        val latestAccessToken = refreshedData.getValue("token").jsonPrimitive.content
        val latestRefreshToken = refreshedData.getValue("refreshToken").jsonPrimitive.content

        val logout = client.post("/api/auth/logout") { bearer(latestAccessToken) }
        assertEquals(HttpStatusCode.OK, logout.status, logout.bodyAsText())

        // Token terbaru belum pernah dipakai, jadi penolakan ini hanya bisa berasal dari logout.
        val revokedRefresh = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$latestRefreshToken"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, revokedRefresh.status, revokedRefresh.bodyAsText())

        val revokedAccess = client.get("/api/auth/me") { bearer(latestAccessToken) }
        assertEquals(HttpStatusCode.Unauthorized, revokedAccess.status, revokedAccess.bodyAsText())
    }

    @Test
    fun test_refresh_token_is_single_use_and_reuse_revokes_family() = testApplication {
        application { configureIntegrationApplication() }
        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"owner","password":"owner123"}""")
        }
        val firstRefresh = Json.parseToJsonElement(loginResponse.bodyAsText())
            .jsonObject.getValue("data").jsonObject.getValue("refreshToken").jsonPrimitive.content

        val rotated = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$firstRefresh"}""")
        }
        assertEquals(HttpStatusCode.OK, rotated.status, rotated.bodyAsText())
        val secondRefresh = Json.parseToJsonElement(rotated.bodyAsText())
            .jsonObject.getValue("data").jsonObject.getValue("refreshToken").jsonPrimitive.content

        // Token pertama dipakai ulang (mis. dicuri): ditolak dan seluruh keluarga dicabut.
        val reused = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$firstRefresh"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, reused.status, reused.bodyAsText())

        val afterReuse = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$secondRefresh"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, afterReuse.status, afterReuse.bodyAsText())
    }

    @Test
    fun test_refresh_token_reuse_ends_every_session_of_the_user_once() = testApplication {
        application { configureIntegrationApplication() }
        val stolenSession = loginTokens("owner", "owner123")
        val otherDevice = loginTokens("owner", "owner123")
        val versionBefore = tokenVersion(ownerId())
        val auditsBefore = auditActionCount("system", "refresh_token_reuse", ownerId(), "UPDATE")

        val rotated = refreshTokens(stolenSession.refresh)
        assertEquals(HttpStatusCode.OK, rotated.status, rotated.bodyAsText())
        val rotatedAccess = Json.parseToJsonElement(rotated.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("token").jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { bearer(rotatedAccess) }.status)
        assertEquals(versionBefore, tokenVersion(ownerId()))

        assertEquals(HttpStatusCode.Unauthorized, refreshTokens(stolenSession.refresh).status)
        assertEquals(versionBefore + 1, tokenVersion(ownerId()))
        assertEquals(auditsBefore + 1, auditActionCount("system", "refresh_token_reuse", ownerId(), "UPDATE"))

        listOf(stolenSession.access, rotatedAccess, otherDevice.access).forEach { accessToken ->
            val rejected = client.get("/api/auth/me") { bearer(accessToken) }
            assertEquals(HttpStatusCode.Unauthorized, rejected.status, rejected.bodyAsText())
        }
        assertEquals(HttpStatusCode.Unauthorized, refreshTokens(otherDevice.refresh).status)

        // Token lama yang diputar ulang tidak boleh bisa terus-menerus mengeluarkan user.
        assertEquals(HttpStatusCode.Unauthorized, refreshTokens(stolenSession.refresh).status)
        assertEquals(versionBefore + 1, tokenVersion(ownerId()))

        val newLogin = loginTokens("owner", "owner123")
        assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { bearer(newLogin.access) }.status)
        assertEquals(HttpStatusCode.OK, refreshTokens(newLogin.refresh).status)
    }

    @Test
    fun test_parallel_credential_reservations_are_all_counted_and_lock_never_moves_back() = testApplication {
        application { configureIntegrationApplication() }
        val userId = loginAsCashier(loginAsOwner()).userId
        val base = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val repository = SystemRepository()
        val unlimited = PinRolePolicy(1000, 1000, listOf(60L))

        val counted = reserveInParallel(20, userId, CredentialScope.PASSWORD, unlimited) { base }
        assertEquals((1..20).toList(), counted.map { (it as AttemptReservation.Granted).state.failedAttempts }.sorted())

        // Jam tiap request dibuat berbeda untuk meniru request paralel yang selesai tidak berurutan.
        val policy = PinRolePolicy(3, 1000, listOf(60L, 300L, 900L, 1800L))
        val mixed = reserveInParallel(40, userId, CredentialScope.PIN, policy) { index ->
            base.plusSeconds((index * 37L) % 600)
        }
        val granted = mixed.filterIsInstance<AttemptReservation.Granted>().map { it.state }.sortedBy { it.failedAttempts }
        assertEquals((1..granted.size).toList(), granted.map { it.failedAttempts })
        val locks = granted.mapNotNull { it.lockedUntil }
        assertEquals(locks.sorted(), locks, "lockedUntil tidak boleh mundur")
        assertEquals(granted.size - 2, locks.size)
        assertEquals(granted.last(), repository.getPinAttemptState(userId, CredentialScope.PIN.key))
    }

    @Test
    fun test_credential_lock_follows_backoff_and_denies_reservation_while_locked() = testApplication {
        application { configureIntegrationApplication() }
        val userId = loginAsCashier(loginAsOwner()).userId
        val repository = SystemRepository()
        val scope = CredentialScope.PIN.key
        val policy = PinVerificationPolicy.forRole(Role.KASIR)
        val base = OffsetDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC)

        (1..9).forEach { failure ->
            val now = base.plusHours(failure.toLong())
            val expected = PinAttemptState(failure, CredentialAttemptGuard.lockFor(policy, failure, now))
            assertEquals(AttemptReservation.Granted(expected), repository.reservePinAttempt(userId, scope, policy, now))
        }

        // Selama terkunci (termasuk jam request yang mundur) hitungan dan kunci tidak berubah.
        val locked = requireNotNull(repository.getPinAttemptState(userId, scope))
        val lockedUntil = requireNotNull(locked.lockedUntil)
        listOf(lockedUntil.minusSeconds(1), base).forEach { now ->
            val denied = repository.reservePinAttempt(userId, scope, policy, now) as AttemptReservation.Locked
            assertEquals(9, denied.failedAttempts)
            assertEquals(lockedUntil.toInstant(), denied.lockedUntil.toInstant())
        }
        assertEquals(locked, repository.getPinAttemptState(userId, scope))
        // Tepat saat masa kunci berakhir, percobaan berikutnya diterima.
        val next = repository.reservePinAttempt(userId, scope, policy, lockedUntil) as AttemptReservation.Granted
        assertEquals(10, next.state.failedAttempts)

        // Ambang 1: kunci sudah harus ada pada kegagalan pertama (jalur INSERT).
        val strict = policy.copy(consecutiveThreshold = 1)
        val first = repository.reservePinAttempt(userId, CredentialScope.PASSWORD.key, strict, base)
        assertEquals(
            AttemptReservation.Granted(PinAttemptState(1, base.plusSeconds(strict.backoffSeconds.first()))), first
        )
    }

    @Test
    fun test_correct_guess_does_not_clear_a_lock_set_by_another_attempt() = testApplication {
        application { configureIntegrationApplication() }
        val userId = loginAsCashier(loginAsOwner()).userId
        val repository = SystemRepository()
        val scope = CredentialScope.PIN.key
        val policy = PinVerificationPolicy.forRole(Role.KASIR)
        val now = OffsetDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC)
        suspend fun reserveAll() = (1..policy.consecutiveThreshold).map {
            (repository.reservePinAttempt(userId, scope, policy, now) as AttemptReservation.Granted).state
        }

        val attempts = reserveAll()
        val locked = requireNotNull(repository.getPinAttemptState(userId, scope))
        // Tebakan ke-3 benar, tetapi kunci dari tebakan ke-5 sudah aktif: kunci dan hitungan tetap.
        repository.releasePinAttempt(userId, scope, attempts[2], now)
        assertEquals(locked, repository.getPinAttemptState(userId, scope))
        // Tebakan ke-5 sendiri yang benar: kunci buatannya sendiri boleh dilepas.
        repository.releasePinAttempt(userId, scope, attempts.last(), now)
        assertEquals(null, repository.getPinAttemptState(userId, scope))

        // Kunci milik percobaan lain yang sudah kedaluwarsa tidak lagi menahan reset.
        val expired = reserveAll()
        val lockedUntil = requireNotNull(expired.last().lockedUntil)
        repository.releasePinAttempt(userId, scope, expired[2], lockedUntil.minusSeconds(1))
        assertEquals(expired.last(), repository.getPinAttemptState(userId, scope))
        repository.releasePinAttempt(userId, scope, expired[2], lockedUntil)
        assertEquals(null, repository.getPinAttemptState(userId, scope))

        // Lingkup lain dan user lain tidak tersentuh.
        val password = CredentialScope.PASSWORD.key
        val first = repository.reservePinAttempt(userId, scope, policy, now) as AttemptReservation.Granted
        repository.reservePinAttempt(userId, password, policy, now)
        repository.releasePinAttempt(userId, scope, first.state, now)
        assertEquals(null, repository.getPinAttemptState(userId, scope))
        assertEquals(1, repository.getPinAttemptState(userId, password)?.failedAttempts)
    }

    @Test
    fun test_parallel_wrong_guesses_never_evaluate_more_than_the_threshold() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val cashier = loginAsCashier(ownerToken)
        val approver = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "approval_parallel_admin")
        val repository = SystemRepository()
        val service = PinVerificationService(repository)

        val unlocks = coroutineScope {
            (1..30).map { index ->
                async(Dispatchers.IO) { service.verifyUnlock(cashier.userId, UnlockRequest("000999", "device-$index")) }
            }.awaitAll()
        }
        // "PIN salah" hanya dikirim bila PIN benar-benar diperiksa bcrypt.
        assertEquals(5, unlocks.count { it.error == "PIN salah" })
        assertEquals(26, unlocks.count { it.code == "PIN_LOCKED" })
        assertEquals(5, repository.getPinAttemptState(cashier.userId, CredentialScope.PIN.key)?.failedAttempts)
        val correct = service.verifyUnlock(cashier.userId, UnlockRequest(cashier.pin))
        assertEquals("PIN_LOCKED", correct.code)

        val user = requireNotNull(repository.findPinUserById(cashier.userId))
        val passwordErrors = coroutineScope {
            (1..30).map {
                async(Dispatchers.IO) {
                    runCatching {
                        service.verifyCredentialForSensitiveChange(
                            user, CredentialScope.PASSWORD, "tebakan-salah", null, null, "Password tidak valid"
                        )
                    }.exceptionOrNull()
                }
            }.awaitAll()
        }
        assertEquals(4, passwordErrors.count { it is ValidationException })
        assertEquals(26, passwordErrors.count { it is TooManyAttemptsException })
        // Audit hanya ditulis untuk tebakan yang benar-benar diperiksa.
        assertEquals(4, auditCount("system", "password_check_failed", cashier.userId))
        assertEquals(1, auditCount("system", "password_check_locked", cashier.userId))

        val approvals = GlobalContext.get().get<ManagerApprovalService>()
        val approvalErrors = coroutineScope {
            (1..30).map {
                async(Dispatchers.IO) {
                    runCatching {
                        approvals.createApproval(
                            cashier.userId, Role.KASIR,
                            CreateManagerApprovalRequest(
                                action = ManagerApprovalAction.VOID_TRANSACTION,
                                resourceType = ManagerApprovalResourceType.TRANSACTION,
                                resourceId = UUID.randomUUID().toString(),
                                approverUsername = approver.username,
                                approverPin = "000999"
                            ),
                            null
                        )
                    }.exceptionOrNull() as ManagerApprovalException
                }
            }.awaitAll()
        }
        assertEquals(2, approvalErrors.count { it.reason == ManagerApprovalError.INVALID })
        assertEquals(28, approvalErrors.count { it.reason == ManagerApprovalError.APPROVER_LOCKED })
        val approverState = repository.getPinAttemptState(approver.userId, CredentialScope.MANAGER_APPROVAL.key)
        assertEquals(3, approverState?.failedAttempts)
        assertEquals(0, managerApprovalCount())
    }

    @Test
    fun test_changing_pin_keeps_the_password_lock() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val repository = SystemRepository()
        val policy = PinVerificationPolicy.forRole(Role.KASIR)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        suspend fun reserve(scope: CredentialScope, times: Int) = repeat(times) {
            repository.reservePinAttempt(cashier.userId, scope.key, policy, now)
        }
        suspend fun failures(scope: CredentialScope) =
            repository.getPinAttemptState(cashier.userId, scope.key)?.failedAttempts
        reserve(CredentialScope.PASSWORD, 5)
        reserve(CredentialScope.PIN, 2)
        reserve(CredentialScope.MANAGER_APPROVAL, 2)

        val changed = client.put("/api/system/users/me/pin") {
            contentType(ContentType.Application.Json)
            bearer(cashier.token)
            setBody("""{"oldPin":"${cashier.pin}","newPin":"739158"}""")
        }
        assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
        assertEquals(5, failures(CredentialScope.PASSWORD))
        assertEquals(null, failures(CredentialScope.PIN))
        assertEquals(null, failures(CredentialScope.MANAGER_APPROVAL))
        val blocked = resetPin(cashier.token, APPROVAL_TEST_PASSWORD, "739159")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
        assertTrue(blocked.bodyAsText().contains("TOO_MANY_ATTEMPTS"), blocked.bodyAsText())

        // Lupa PIN: password benar membuka kunci PIN yang sedang aktif.
        repository.resetAllPinAttemptStatesForUser(cashier.userId)
        reserve(CredentialScope.PIN, 5)
        val reset = resetPin(cashier.token, APPROVAL_TEST_PASSWORD, "739159")
        assertEquals(HttpStatusCode.OK, reset.status, reset.bodyAsText())
        assertEquals(null, failures(CredentialScope.PIN))
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
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/system/roles") { bearer(adminToken) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/system/security-settings") { bearer(adminToken) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/system/database-backups") { bearer(adminToken) }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/api/system/database-backups/restore/validate") { bearer(adminToken) }.status
        )
        assertEquals(HttpStatusCode.OK, client.get("/api/analytics/sales/report") { bearer(adminToken) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/inventory/products") { bearer(adminToken) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/system/store-profile") { bearer(adminToken) }.status)
    }

    @Test
    fun test_store_profile_is_separated_from_device_settings_and_security_is_sanitized() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()

        val update = client.put("/api/system/store-profile") {
            contentType(ContentType.Application.Json)
            bearer(ownerToken)
            setBody(
                """{"storeName":"TB Integration","address":"Alamat Test","phone":"0800","receiptHeader":"Selamat datang","receiptFooter":"Terima kasih"}"""
            )
        }
        assertEquals(HttpStatusCode.OK, update.status, update.bodyAsText())
        assertFalse(update.bodyAsText().contains("printerSize"), update.bodyAsText())

        val legacy = client.get("/api/system/settings") { bearer(ownerToken) }
        val legacyBody = legacy.bodyAsText()
        assertEquals(HttpStatusCode.OK, legacy.status, legacyBody)
        assertTrue(legacyBody.contains("printerSize"), legacyBody)
        val originalPrinterSize = Json.parseToJsonElement(legacyBody).jsonObject
            .getValue("data").jsonObject.getValue("printerSize").jsonPrimitive.content
        val ignoredPrinterSize = if (originalPrinterSize == "58mm") "80mm" else "58mm"
        val legacyUpdate = client.put("/api/system/settings") {
            contentType(ContentType.Application.Json)
            bearer(ownerToken)
            setBody(
                """{"storeName":"TB Integration","address":"Alamat Test","phone":"0800","receiptHeader":"Selamat datang","receiptFooter":"Terima kasih","printerSize":"$ignoredPrinterSize"}"""
            )
        }
        assertEquals(HttpStatusCode.OK, legacyUpdate.status, legacyUpdate.bodyAsText())
        val printerSizeAfterUpdate = Json.parseToJsonElement(legacyUpdate.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("printerSize").jsonPrimitive.content
        assertEquals(originalPrinterSize, printerSizeAfterUpdate)

        val security = client.get("/api/system/security-settings") { bearer(ownerToken) }
        val securityBody = security.bodyAsText()
        assertEquals(HttpStatusCode.OK, security.status, securityBody)
        assertFalse(securityBody.contains("password", ignoreCase = true), securityBody)
        assertFalse(securityBody.contains("secret", ignoreCase = true), securityBody)
        assertFalse(securityBody.contains("backupDirectory"), securityBody)
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
    fun test_offline_sync_rejects_price_not_in_server_price_list() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val token = cashier.token
        val userId = cashier.userId
        val product = createProductFixture()
        val deviceId = "integration-device-${UUID.randomUUID()}"
        val openedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(10)
        val opened = client.post("/api/sales/sessions/sync/open") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """{"clientGeneratedId":"offline-open-${UUID.randomUUID()}","deviceId":"$deviceId","cashierUserId":"$userId","openedAt":"$openedAt","startingCash":"100.00","openingNote":"Offline price guard"}"""
            )
        }
        assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
        val sessionId = Json.parseToJsonElement(opened.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("serverCashSessionId").jsonPrimitive.content

        // Harga eceran fixture 100.00; kasir mencoba mencatat 1.00.
        val tampered = client.post("/api/sales/checkout/sync") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """{"clientGeneratedId":"offline-checkout-${UUID.randomUUID()}","deviceId":"$deviceId","localTransactionCode":"LOCAL-TAMPER","cashierUserId":"$userId","cashSessionId":"$sessionId","paymentMethod":"tunai","subtotal":"5.00","discount":"0.00","total":"5.00","paidAmount":"5.00","remainingAmount":"0.00","occurredAt":"${openedAt.plusHours(1)}","items":[{"productId":"${product.id}","productNameSnapshot":"Offline Product","quantity":"5.00","priceAtTransaction":"1.00","cogsAtTransaction":"0.00","discount":"0.00","subtotal":"5.00"}]}"""
            )
        }
        assertEquals(HttpStatusCode.Conflict, tampered.status, tampered.bodyAsText())
        assertTrue(tampered.bodyAsText().contains("OFFLINE_PRICE_CONFLICT"), tampered.bodyAsText())
        assertEquals(0, transactionCount())
        assertEquals(BigDecimal("10.00"), stockQuantity(product.id))

        // Pembulatan negatif yang memangkas hampir seluruh total juga ditolak.
        val zeroTotal = client.post("/api/sales/checkout/sync") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """{"clientGeneratedId":"offline-checkout-${UUID.randomUUID()}","deviceId":"$deviceId","localTransactionCode":"LOCAL-ROUND","cashierUserId":"$userId","cashSessionId":"$sessionId","paymentMethod":"tunai","subtotal":"500.00","discount":"0.00","total":"1.00","paidAmount":"1.00","remainingAmount":"0.00","occurredAt":"${openedAt.plusHours(1)}","roundingAmount":"-499.00","items":[{"productId":"${product.id}","productNameSnapshot":"Offline Product","quantity":"5.00","priceAtTransaction":"100.00","cogsAtTransaction":"50.00","discount":"0.00","subtotal":"500.00"}]}"""
            )
        }
        assertEquals(HttpStatusCode.Conflict, zeroTotal.status, zeroTotal.bodyAsText())
        assertEquals(0, transactionCount())
    }

    @Test
    fun test_offline_secondary_unit_from_new_client_deducts_base_stock() = testApplication {
        application { configureIntegrationApplication() }
        val fixture = offlineUnitFixture("30.00")
        val key = "offline-unit-${UUID.randomUUID()}"
        val body = fixture.saleBody(listOf(fixture.dusLine()), key)

        val synced = syncOfflineBody(fixture, body)
        assertEquals(HttpStatusCode.Created, synced.status, synced.bodyAsText())
        val transactionId = offlineTransactionId(synced)

        assertEquals(BigDecimal("6.00"), stockQuantity(fixture.product.id))
        assertEquals(BigDecimal("6.00"), latestLedgerBalance(fixture.product.id))
        // Nilai uang persis seperti yang dibayar pelanggan; HPP per satuan dasar.
        assertEquals(
            "24.00|100.00|50.00|2400.00|2400.00|${fixture.product.unitId}",
            storedOfflineItem(transactionId)
        )
        assertEquals("2400.00|2400.00", firstString("SELECT gross_subtotal || '|' || total FROM sales.transactions WHERE id = '$transactionId'"))

        // Retry tetap idempoten dan tidak memotong stok dua kali.
        val replay = syncOfflineBody(fixture, body)
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertTrue(replay.bodyAsText().contains("\"syncStatus\":\"DUPLICATE\""), replay.bodyAsText())
        assertEquals(BigDecimal("6.00"), stockQuantity(fixture.product.id))

        val audit = offlineItemAudit(transactionId)
        assertEquals("CLIENT_UNIT", audit.getValue("source").jsonPrimitive.content)
        assertEquals(fixture.dusId.toString(), audit.getValue("soldUnitId").jsonPrimitive.content)
        assertEquals("2.00", audit.getValue("soldQuantity").jsonPrimitive.content)
        assertEquals("1200.00", audit.getValue("soldUnitPrice").jsonPrimitive.content)
        assertEquals("12", audit.getValue("unitFactor").jsonPrimitive.content)
        assertEquals("24.00", audit.getValue("baseQuantity").jsonPrimitive.content)
    }

    @Test
    fun test_offline_secondary_unit_from_legacy_client_is_converted() = testApplication {
        application { configureIntegrationApplication() }
        val fixture = offlineUnitFixture("30.00")

        val synced = syncOfflineBody(fixture, fixture.saleBody(listOf(fixture.dusLine(unitId = null, baseQuantity = null))))
        assertEquals(HttpStatusCode.Created, synced.status, synced.bodyAsText())
        val transactionId = offlineTransactionId(synced)

        assertEquals(BigDecimal("6.00"), stockQuantity(fixture.product.id))
        assertEquals(
            "24.00|100.00|50.00|2400.00|2400.00|${fixture.product.unitId}",
            storedOfflineItem(transactionId)
        )
        val audit = offlineItemAudit(transactionId)
        assertEquals("LEGACY_AUTO", audit.getValue("source").jsonPrimitive.content)
        assertEquals(fixture.dusId.toString(), audit.getValue("soldUnitId").jsonPrimitive.content)
        // Jejak audit tidak bisa diubah atau dihapus.
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                assertEquals(0, statement.executeUpdate("DELETE FROM system.audit_logs WHERE record_id IN (SELECT id FROM sales.transaction_items WHERE transaction_id = '$transactionId')"))
            }
        }
        assertEquals(audit, offlineItemAudit(transactionId))
    }

    @Test
    fun test_offline_legacy_client_ambiguous_or_inexact_unit_is_rejected() = testApplication {
        application { configureIntegrationApplication() }
        val fixture = offlineUnitFixture("30.00")
        val legacyDus = fixture.dusLine(unitId = null, baseQuantity = null)

        // Ambigu: 1.200 = 12 x harga eceran, dan juga harga lama per pcs.
        insertPriceHistory(fixture.product, "1200.00", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1))
        assertOfflineConflict(fixture, legacyDus)
        // Klien baru dengan harga yang sama tidak ambigu.
        val explicit = syncOfflineBody(fixture, fixture.saleBody(listOf(fixture.dusLine())))
        assertEquals(HttpStatusCode.Created, explicit.status, explicit.bodyAsText())
        assertEquals(BigDecimal("6.00"), stockQuantity(fixture.product.id))

        // Tidak eksak: 100 x 0,3333 = 33,33 tetapi 2 x 0,3333 bukan kuantitas 2 desimal.
        val inexact = offlineUnitFixture("30.00", factor = "0.3333")
        assertOfflineConflict(inexact, inexact.dusLine(price = "33.33", unitId = null, baseQuantity = null))
        assertEquals(BigDecimal("30.00"), stockQuantity(inexact.product.id))
        assertEquals(1, transactionCount())
    }

    @Test
    fun test_offline_unit_payload_is_validated() = testApplication {
        application { configureIntegrationApplication() }
        val fixture = offlineUnitFixture("30.00")
        val otherUnit = unitOtherThan(fixture.product.unitId, fixture.dusId)

        listOf(
            fixture.dusLine(unitId = otherUnit.toString()),
            fixture.dusLine(baseQuantity = "2.00"),
            fixture.dusLine(baseQuantity = "23.99"),
            fixture.pcsLine("2.00", baseQuantity = "24.00")
        ).forEach { line -> assertOfflineConflict(fixture, line) }

        // Harga dus diakui sebagai harga pcs: tetap konflik harga.
        assertOfflineConflict(
            fixture,
            fixture.dusLine(unitId = fixture.product.unitId.toString(), baseQuantity = "2.00"),
            code = "OFFLINE_PRICE_CONFLICT"
        )

        listOf(
            fixture.dusLine(baseQuantity = null),
            fixture.dusLine(unitId = null),
            fixture.dusLine(unitId = "bukan-uuid")
        ).forEach { line ->
            val invalid = syncOfflineBody(fixture, fixture.saleBody(listOf(line)))
            assertEquals(HttpStatusCode.BadRequest, invalid.status, invalid.bodyAsText())
            assertTrue(invalid.bodyAsText().contains("VALIDATION_ERROR"), invalid.bodyAsText())
        }
        val notANumber = syncOfflineBody(fixture, fixture.saleBody(listOf(fixture.dusLine(baseQuantity = "dua puluh"))))
        assertEquals(HttpStatusCode.BadRequest, notANumber.status, notANumber.bodyAsText())
        assertTrue(notANumber.bodyAsText().contains("INVALID_REQUEST"), notANumber.bodyAsText())
        assertEquals(0, transactionCount())
        assertEquals(BigDecimal("30.00"), stockQuantity(fixture.product.id))

        // Konversi yang tidak bermuara ke satuan dasar tidak boleh menentukan stok.
        insertUnitConversion(fixture.product.id, otherUnit, fixture.dusId, "6.0000")
        assertOfflineConflict(
            fixture, fixture.dusLine(price = "600.00", unitId = otherUnit.toString(), baseQuantity = "12.00")
        )
    }

    @Test
    fun test_offline_item_audit_is_atomic_with_checkout() = testApplication {
        application { configureIntegrationApplication() }
        val fixture = offlineUnitFixture("30.00")
        val body = fixture.saleBody(listOf(fixture.dusLine()))
        fun execute(sql: String) = dataSource.connection.use { it.createStatement().use { s -> s.execute(sql) } }
        execute(
            """
            CREATE FUNCTION system.fn_test_fail_unit_audit() RETURNS TRIGGER AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'audit gagal'; END; ${'$'}${'$'} LANGUAGE plpgsql;
            CREATE TRIGGER trg_test_fail_unit_audit BEFORE INSERT ON system.audit_logs FOR EACH ROW
            WHEN (NEW.new_data ->> 'event' = 'OFFLINE_ITEM_UNIT') EXECUTE FUNCTION system.fn_test_fail_unit_audit()
            """.trimIndent()
        )
        try {
            val failed = syncOfflineBody(fixture, body)
            assertEquals(HttpStatusCode.InternalServerError, failed.status, failed.bodyAsText())
        } finally {
            execute("DROP TRIGGER trg_test_fail_unit_audit ON system.audit_logs; DROP FUNCTION system.fn_test_fail_unit_audit()")
        }
        assertEquals(0, transactionCount())
        assertEquals(BigDecimal("30.00"), stockQuantity(fixture.product.id))

        // Kegagalan tidak meninggalkan jejak, jadi retry dengan key yang sama diterima.
        val retried = syncOfflineBody(fixture, body)
        assertEquals(HttpStatusCode.Created, retried.status, retried.bodyAsText())
        assertEquals(BigDecimal("6.00"), stockQuantity(fixture.product.id))
    }

    @Test
    fun test_offline_mixed_units_and_stock_check_use_base_quantity() = testApplication {
        application { configureIntegrationApplication() }
        // 2 dus + 3 pcs = 27: ditolak pada stok 26, diterima pada 27.
        val short = offlineUnitFixture("26.00")
        val rejected = syncOfflineBody(short, short.saleBody(listOf(short.dusLine(), short.pcsLine("3.00"))))
        assertFalse(rejected.status == HttpStatusCode.Created, rejected.bodyAsText())
        assertTrue(rejected.bodyAsText().contains("diminta 27.00"), rejected.bodyAsText())
        assertEquals(BigDecimal("26.00"), stockQuantity(short.product.id))

        // Konflik satuan dan harga dilaporkan lebih dulu daripada stok kurang.
        assertOfflineConflict(short, short.dusLine("5.00", baseQuantity = "5.00"))
        assertOfflineConflict(short, short.pcsLine("50.00", price = "99.00"), code = "OFFLINE_PRICE_CONFLICT")
        // Satu item bermasalah membatalkan seluruh transaksi.
        assertOfflineConflict(short, short.pcsLine("3.00"), short.dusLine(baseQuantity = "2.00"))

        val exact = offlineUnitFixture("27.00")
        val accepted = syncOfflineBody(exact, exact.saleBody(listOf(exact.dusLine(), exact.pcsLine("3.00"))))
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
        assertEquals(BigDecimal("0.00"), stockQuantity(exact.product.id))
    }

    @Test
    fun test_offline_secondary_unit_uses_tier_price_of_base_quantity() = testApplication {
        application { configureIntegrationApplication() }
        val fixture = offlineUnitFixture("30.00")
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO inventory.product_price_tiers (product_id, min_qty, unit_price) VALUES (?, 24, 90)"
            ).use { it.setObject(1, fixture.product.id); it.executeUpdate() }
        }
        val synced = syncOfflineBody(fixture, fixture.saleBody(listOf(fixture.dusLine(price = "1080.00"))))
        assertEquals(HttpStatusCode.Created, synced.status, synced.bodyAsText())
        assertEquals(
            "24.00|90.00|50.00|2160.00|2160.00|${fixture.product.unitId}",
            storedOfflineItem(offlineTransactionId(synced))
        )
    }

    @Test
    fun test_void_and_refund_of_converted_offline_items_restore_base_stock() = testApplication {
        application { configureIntegrationApplication() }
        val owner = loginAsOwner()
        val fixture = offlineUnitFixture("60.00")
        suspend fun sell(line: String): String {
            val synced = syncOfflineBody(fixture, fixture.saleBody(listOf(line)))
            assertEquals(HttpStatusCode.Created, synced.status, synced.bodyAsText())
            assertEquals(BigDecimal("36.00"), stockQuantity(fixture.product.id))
            return offlineTransactionId(synced)
        }
        val legacyDus = fixture.dusLine(unitId = null, baseQuantity = null)

        listOf(fixture.dusLine(), legacyDus).forEach { line ->
            val voided = voidTransaction(owner, sell(line), "void-unit-${UUID.randomUUID()}", "Salah input satuan")
            assertTrue(voided.status.value in 200..201, voided.bodyAsText())
            assertEquals(BigDecimal("60.00"), stockQuantity(fixture.product.id))

            val refunded = refundTransaction(owner, sell(line), "refund-unit-${UUID.randomUUID()}", "Barang dikembalikan")
            assertTrue(refunded.status.value in 200..201, refunded.bodyAsText())
            assertTrue(refunded.bodyAsText().contains("\"refundedAmount\":\"2400.00\""), refunded.bodyAsText())
            assertEquals(BigDecimal("60.00"), stockQuantity(fixture.product.id))
        }
        assertEquals(BigDecimal("60.00"), latestLedgerBalance(fixture.product.id))

        val kept = refundTransaction(
            owner, sell(legacyDus), "refund-unit-${UUID.randomUUID()}", "Barang rusak total", "DAMAGED"
        )
        assertTrue(kept.status.value in 200..201, kept.bodyAsText())
        assertEquals(BigDecimal("36.00"), stockQuantity(fixture.product.id))
    }

    @Test
    fun test_online_checkout_ignores_secondary_units() = testApplication {
        application { configureIntegrationApplication() }
        val fixture = offlineUnitFixture("30.00", openOnline = true)
        val sale = checkout(fixture.cashier.token, fixture.product, "24.00", "2400.00", "online-unit-${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.Created, sale.status, sale.bodyAsText())
        val transactionId = checkoutTransactionId(sale)
        assertEquals(BigDecimal("6.00"), stockQuantity(fixture.product.id))
        assertEquals("24.00|100.00|50.00|2400.00|2400.00|${fixture.product.unitId}", storedOfflineItem(transactionId))
        assertEquals(
            "0",
            firstString(
                "SELECT COUNT(*)::text FROM system.audit_logs a JOIN sales.transaction_items ti ON ti.id = a.record_id " +
                    "WHERE ti.transaction_id = '$transactionId' AND a.new_data IS NOT NULL"
            )
        )
    }

    @Test
    fun test_stock_list_includes_base_and_secondary_units() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val withDus = createProductFixture()
        val plain = createProductFixture()
        val indirect = createProductFixture()
        val dusId = unitOtherThan(withDus.unitId)
        val otherId = unitOtherThan(withDus.unitId, dusId)
        insertUnitConversion(withDus.id, dusId, withDus.unitId, "0.3333")
        // Konversi yang tidak bermuara ke satuan dasar tidak boleh tampil, walau produknya punya yang lain.
        insertUnitConversion(withDus.id, otherId, dusId, "6.0000")
        insertUnitConversion(indirect.id, otherId, dusId, "6.0000")

        suspend fun stockOf(product: ProductFixture): JsonObject {
            val sku = firstString("SELECT sku FROM inventory.products WHERE id = '${product.id}'")
            val response = client.get("/api/inventory/stock?search=$sku") { bearer(token) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonObject
                .getValue("data").jsonArray.single().jsonObject
        }
        fun JsonObject.text(field: String) = get(field)?.jsonPrimitive?.contentOrNull
        val baseName = unitName(withDus.unitId)

        val dusStock = stockOf(withDus)
        assertEquals(withDus.unitId.toString(), dusStock.text("baseUnitId"))
        assertEquals(baseName, dusStock.text("baseUnitName"))
        assertEquals(baseName, dusStock.text("unitName"))
        assertEquals(dusId.toString(), dusStock.text("secondaryUnitId"))
        assertEquals(unitName(dusId), dusStock.text("secondaryUnitName"))
        assertEquals("0.3333", dusStock.text("secondaryUnitFactor"))

        listOf(plain, indirect).forEach { product ->
            val stock = stockOf(product)
            assertEquals(product.unitId.toString(), stock.text("baseUnitId"))
            assertEquals(baseName, stock.text("baseUnitName"))
            listOf("secondaryUnitId", "secondaryUnitName", "secondaryUnitFactor").forEach {
                assertEquals(null, stock.text(it), it)
            }
        }

        // Satu halaman berisi banyak produk: satuan tidak tertukar, faktor bulat tanpa nol di belakang.
        execute("UPDATE inventory.unit_conversions SET factor = 120 WHERE product_id = '${withDus.id}' AND to_unit_id = '${withDus.unitId}'")
        val page = client.get("/api/inventory/stock?search=IT-&limit=1000") { bearer(token) }
        val rows = Json.parseToJsonElement(page.bodyAsText()).jsonObject.getValue("data").jsonObject
            .getValue("data").jsonArray.map { it.jsonObject }.associateBy { it.text("productId") }
        assertEquals("120", rows.getValue(withDus.id.toString()).text("secondaryUnitFactor"))
        assertEquals(null, rows.getValue(plain.id.toString()).text("secondaryUnitId"))

        val empty = client.get("/api/inventory/stock?search=tidak-ada-${UUID.randomUUID()}") { bearer(token) }
        assertEquals(HttpStatusCode.OK, empty.status, empty.bodyAsText())
    }

    @Test
    fun test_transaction_items_include_recorded_unit_name() = testApplication {
        application { configureIntegrationApplication() }
        val fixture = offlineUnitFixture("60.00")
        val product = fixture.product
        val token = fixture.cashier.token
        val recordedName = unitName(product.unitId)
        suspend fun itemsOf(response: HttpResponse): List<JsonObject> {
            assertTrue(response.status.value in 200..201, response.bodyAsText())
            return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonObject
                .getValue("items").jsonArray.map { it.jsonObject }
        }
        fun JsonObject.recordedUnit() =
            getValue("unitId").jsonPrimitive.content to getValue("unitName").jsonPrimitive.content
        val expected = product.unitId.toString() to recordedName

        val key = "unit-name-${UUID.randomUUID()}"
        val sale = checkout(token, product, "2.00", "200.00", key)
        val saleItem = itemsOf(sale).single()
        assertEquals(expected, saleItem.recordedUnit())
        // Pemindahan mapper tidak mengubah isi item lain.
        assertEquals(
            firstString("SELECT name FROM inventory.products WHERE id = '${product.id}'"),
            saleItem.getValue("productName").jsonPrimitive.content
        )
        val synced = syncOfflineBody(fixture, fixture.saleBody(listOf(fixture.dusLine(), fixture.pcsLine("3.00"))))
        assertEquals(HttpStatusCode.Created, synced.status, synced.bodyAsText())

        // Satuan yang tercatat di item, bukan satuan produk saat ini.
        execute("UPDATE inventory.products SET base_unit_id = '${fixture.dusId}' WHERE id = '${product.id}'")
        assertEquals(saleItem, itemsOf(checkout(token, product, "2.00", "200.00", key)).single())
        listOf(checkoutTransactionId(sale) to listOf("2.00"), offlineTransactionId(synced) to listOf("24.00", "3.00"))
            .forEach { (transactionId, quantities) ->
                val items = itemsOf(client.get("/api/sales/transactions/$transactionId") { bearer(token) })
                assertEquals(quantities, items.map { it.getValue("quantity").jsonPrimitive.content })
                items.forEach { assertEquals(expected, it.recordedUnit()) }
            }
    }

    @Test
    fun test_offline_factor_changed_during_session_accepts_old_factor() = testApplication {
        application { configureIntegrationApplication() }
        val owner = loginAsOwner()
        val fixture = offlineUnitFixture("120.00")
        suspend fun accept(line: String, source: String, factor: String, baseQuantity: String) {
            val before = stockQuantity(fixture.product.id)
            val synced = syncOfflineBody(fixture, fixture.saleBody(listOf(line)))
            assertEquals(HttpStatusCode.Created, synced.status, synced.bodyAsText())
            assertEquals(before.subtract(BigDecimal(baseQuantity)), stockQuantity(fixture.product.id))
            val audit = offlineItemAudit(offlineTransactionId(synced))
            assertEquals(source, audit.getValue("source").jsonPrimitive.content)
            assertEquals(factor, audit.getValue("unitFactor").jsonPrimitive.content)
            assertEquals(fixture.dusId.toString(), audit.getValue("soldUnitId").jsonPrimitive.content)
        }
        val legacyOld = fixture.dusLine("1.00", unitId = null, baseQuantity = null)

        putProductUnit(owner, fixture.product, fixture.dusId, "10")
        assertEquals("12.0000|${fixture.dusId}|${fixture.product.unitId}", conversionHistory(fixture.product.id))

        accept(fixture.dusLine("1.00", baseQuantity = "12.00"), "CLIENT_UNIT", "12", "12.00")
        accept(legacyOld, "LEGACY_AUTO", "12", "12.00")
        accept(fixture.dusLine("1.00", price = "1000.00", baseQuantity = "10.00"), "CLIENT_UNIT", "10", "10.00")
        // Harga dan jumlah wajib memakai faktor yang sama.
        assertOfflineConflict(
            fixture, fixture.dusLine("1.00", price = "1000.00", baseQuantity = "12.00"), code = "OFFLINE_PRICE_CONFLICT"
        )
        val unknown = assertOfflineConflict(fixture, fixture.dusLine("1.00", baseQuantity = "11.00"))
        assertTrue(unknown.contains("faktor satuan di server (10)"), unknown)

        // Satuan sekunder dihapus: faktor terakhir dan faktor sebelumnya tetap dikenali.
        putProductUnit(owner, fixture.product, null, null)
        accept(fixture.dusLine("1.00", baseQuantity = "12.00"), "CLIENT_UNIT", "12", "12.00")
        accept(fixture.dusLine("1.00", price = "1000.00", baseQuantity = "10.00"), "CLIENT_UNIT", "10", "10.00")
        accept(legacyOld, "LEGACY_AUTO", "12", "12.00")
        accept(fixture.dusLine("1.00", "1000.00", unitId = null, baseQuantity = null), "LEGACY_AUTO", "10", "10.00")

        // Batas jendela: perubahan tepat saat sesi dibuka masih berlaku.
        execute(
            "UPDATE inventory.unit_conversion_history SET changed_at = " +
                "(SELECT opened_at FROM sales.cash_sessions WHERE id = '${fixture.sessionId}') WHERE product_id = '${fixture.product.id}'"
        )
        accept(fixture.dusLine("1.00", baseQuantity = "12.00"), "CLIENT_UNIT", "12", "12.00")

        // Di luar jendela sesi faktor lama tidak berlaku lagi.
        moveHistoryOutsideWindow(fixture.product)
        assertOfflineConflict(fixture, fixture.dusLine("1.00", baseQuantity = "12.00"))
        assertOfflineConflict(fixture, legacyOld, code = "OFFLINE_PRICE_CONFLICT")
    }

    @Test
    fun test_free_product_that_ever_had_a_secondary_unit_is_rejected_for_legacy_client() = testApplication {
        application { configureIntegrationApplication() }
        val owner = loginAsOwner()
        val fixture = offlineUnitFixture("50.00")
        val paid = fixture.copy(product = createProductFixture()).pcsLine("1.00")
        val free = fixture.dusLine(price = "0.00", unitId = null, baseQuantity = null)
        val freeExplicit = fixture.dusLine(price = "0.00")
        putProductUnit(owner, fixture.product, fixture.dusId, "12", priceRetail = "0.00")

        assertOfflineConflict(fixture, paid, free)
        putProductUnit(owner, fixture.product, null, null, priceRetail = "0.00")
        assertOfflineConflict(fixture, paid, free)
        // Klien baru menyatakan satuannya, jadi tidak ambigu selama faktornya masih di dalam jendela.
        val explicit = syncOfflineBody(fixture, fixture.saleBody(listOf(paid, freeExplicit)))
        assertEquals(HttpStatusCode.Created, explicit.status, explicit.bodyAsText())
        assertEquals(BigDecimal("26.00"), stockQuantity(fixture.product.id))

        moveHistoryOutsideWindow(fixture.product)
        assertOfflineConflict(fixture, paid, free)
        assertOfflineConflict(fixture, paid, freeExplicit)
        val freePcs = syncOfflineBody(fixture, fixture.saleBody(listOf(paid, fixture.pcsLine("2.00", price = "0.00"))))
        assertEquals(HttpStatusCode.Created, freePcs.status, freePcs.bodyAsText())
        assertEquals(BigDecimal("24.00"), stockQuantity(fixture.product.id))

        // Produk gratis yang tidak pernah punya satuan sekunder: satu tafsir, tetap diterima.
        val neverHad = fixture.copy(product = createProductFixture())
        putProductUnit(owner, neverHad.product, null, null, priceRetail = "0.00")
        val accepted = syncOfflineBody(
            fixture, fixture.saleBody(listOf(paid, neverHad.dusLine(price = "0.00", unitId = null, baseQuantity = null)))
        )
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
        assertEquals(BigDecimal("8.00"), stockQuantity(neverHad.product.id))
    }

    @Test
    fun test_base_price_equal_to_old_secondary_price_is_rejected_for_legacy_client() = testApplication {
        application { configureIntegrationApplication() }
        val owner = loginAsOwner()
        val fixture = offlineUnitFixture("30.00")
        val legacy = fixture.dusLine(unitId = null, baseQuantity = null)
        // Dus (12 x 100) dihapus dan harga satuan dasar menjadi 1.200.
        putProductUnit(owner, fixture.product, null, null, priceRetail = "1200.00")
        assertOfflineConflict(fixture, legacy)
        moveHistoryOutsideWindow(fixture.product)
        assertOfflineConflict(fixture, legacy)

        val explicit = syncOfflineBody(fixture, fixture.saleBody(listOf(fixture.pcsLine("2.00", price = "1200.00"))))
        assertEquals(HttpStatusCode.Created, explicit.status, explicit.bodyAsText())
        assertEquals(BigDecimal("28.00"), stockQuantity(fixture.product.id))

        // Riwayat produk lain dalam transaksi yang sama tidak ikut menolak: satu produk hanya punya
        // harga lama 100, satu lagi hanya punya faktor lama 12.
        val onlyPrice = fixture.copy(product = createProductFixture())
        putProductUnit(owner, onlyPrice.product, null, null, priceRetail = "1200.00")
        val onlyFactor = fixture.copy(product = createProductFixture())
        insertUnitConversion(onlyFactor.product.id, fixture.dusId, onlyFactor.product.unitId, "12")
        putProductUnit(owner, onlyFactor.product, null, null, priceRetail = "1200.00")
        execute("DELETE FROM inventory.price_history WHERE product_id = '${onlyFactor.product.id}'")
        listOf(onlyPrice, onlyFactor).forEach { moveHistoryOutsideWindow(it.product) }
        val accepted = syncOfflineBody(
            fixture,
            fixture.saleBody(
                listOf(
                    fixture.pcsLine("1.00", price = "1200.00"),
                    onlyPrice.dusLine(unitId = null, baseQuantity = null),
                    onlyFactor.dusLine(unitId = null, baseQuantity = null)
                )
            )
        )
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
        assertEquals(BigDecimal("8.00"), stockQuantity(onlyPrice.product.id))
        assertEquals(BigDecimal("8.00"), stockQuantity(onlyFactor.product.id))
    }

    @Test
    fun test_replaced_factor_with_same_secondary_price_is_rejected_for_legacy_client() = testApplication {
        application { configureIntegrationApplication() }
        val owner = loginAsOwner()
        val fixture = offlineUnitFixture("60.00")
        val legacy = fixture.dusLine("1.00", unitId = null, baseQuantity = null)
        // Dulu 12 x 100, sekarang 24 x 50: harga dus tetap 1.200.
        putProductUnit(owner, fixture.product, fixture.dusId, "24", priceRetail = "50.00")
        assertOfflineConflict(fixture, legacy)
        moveHistoryOutsideWindow(fixture.product)
        assertOfflineConflict(fixture, legacy)

        val explicit = syncOfflineBody(fixture, fixture.saleBody(listOf(fixture.dusLine("1.00"))))
        assertEquals(HttpStatusCode.Created, explicit.status, explicit.bodyAsText())
        assertEquals(BigDecimal("36.00"), stockQuantity(fixture.product.id))

        // Faktor menuju satuan dasar lain bukan penjelasan.
        val otherUnit = unitOtherThan(fixture.product.unitId, fixture.dusId)
        execute("UPDATE inventory.unit_conversion_history SET to_unit_id = '$otherUnit' WHERE product_id = '${fixture.product.id}'")
        val accepted = syncOfflineBody(fixture, fixture.saleBody(listOf(legacy)))
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
        assertEquals(BigDecimal("12.00"), stockQuantity(fixture.product.id))
    }

    @Test
    fun test_old_contractor_price_only_rejects_for_contractor_customer() = testApplication {
        application { configureIntegrationApplication() }
        val owner = loginAsOwner()
        val fixture = offlineUnitFixture("30.00")
        val customerId = createReceivableCustomerFixture(BigDecimal("1000000.00"))
        // Harga kontraktor lama 90 x 12 = 1.080 = harga kontraktor/eceran satuan dasar sekarang.
        putProductUnit(owner, fixture.product, null, null, priceRetail = "1080.00", priceContractor = "1080.00")
        moveHistoryOutsideWindow(fixture.product)
        val line = fixture.dusLine("1.00", price = "1080.00", unitId = null, baseQuantity = null)

        execute("UPDATE receivable.customers SET is_contractor = TRUE WHERE id = '$customerId'")
        assertOfflineConflict(fixture, line, customerId = customerId)
        execute("UPDATE receivable.customers SET is_contractor = FALSE WHERE id = '$customerId'")
        val accepted = syncOfflineBody(fixture, fixture.saleBody(listOf(line), customerId = customerId))
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
        assertEquals(BigDecimal("29.00"), stockQuantity(fixture.product.id))
    }

    @Test
    fun test_old_factor_respects_trusted_window_and_current_base_unit() = testApplication {
        application { configureIntegrationApplication() }
        val fixture = offlineUnitFixture("60.00")
        val line = fixture.dusLine("1.00", baseQuantity = "12.00")
        execute("UPDATE inventory.unit_conversions SET factor = 10 WHERE product_id = '${fixture.product.id}'")

        // openedAt mundur tidak memperlebar jendela: perubahan 4 hari lalu tetap di luar 72 jam.
        execute("UPDATE sales.cash_sessions SET opened_at = NOW() - INTERVAL '30 days' WHERE id = '${fixture.sessionId}'")
        execute("UPDATE inventory.unit_conversion_history SET changed_at = NOW() - INTERVAL '4 days' WHERE product_id = '${fixture.product.id}'")
        assertOfflineConflict(fixture, line)
        execute("UPDATE inventory.unit_conversion_history SET changed_at = NOW() - INTERVAL '2 days' WHERE product_id = '${fixture.product.id}'")
        // Faktor ke satuan dasar lama bukan faktor ke satuan dasar sekarang.
        val otherUnit = unitOtherThan(fixture.product.unitId, fixture.dusId)
        execute("UPDATE inventory.unit_conversion_history SET to_unit_id = '$otherUnit' WHERE product_id = '${fixture.product.id}'")
        assertOfflineConflict(fixture, line)
        execute("UPDATE inventory.unit_conversion_history SET to_unit_id = '${fixture.product.unitId}' WHERE product_id = '${fixture.product.id}'")
        val accepted = syncOfflineBody(fixture, fixture.saleBody(listOf(line)))
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
        assertEquals(BigDecimal("48.00"), stockQuantity(fixture.product.id))
    }

    @Test
    fun test_unit_conversion_history_records_only_real_changes() = testApplication {
        application { configureIntegrationApplication() }
        val owner = loginAsOwner()
        val product = createProductFixture()
        val dusId = unitOtherThan(product.unitId)
        val packId = unitOtherThan(product.unitId, dusId)
        fun history() = conversionHistory(product.id)

        putProductUnit(owner, product, dusId, "12")
        val conversionId = firstString("SELECT id::text FROM inventory.unit_conversions WHERE product_id = '${product.id}'")
        putProductUnit(owner, product, dusId, "12.0000")
        execute("UPDATE inventory.unit_conversions SET factor = 12 WHERE product_id = '${product.id}'")
        assertEquals("", history())
        assertEquals(conversionId, firstString("SELECT id::text FROM inventory.unit_conversions WHERE product_id = '${product.id}'"))

        // Ganti faktor, ganti satuan dengan faktor sama, lalu hapus.
        putProductUnit(owner, product, dusId, "12.5")
        putProductUnit(owner, product, packId, "12.5")
        assertEquals(
            "$packId|12.5000",
            firstString("SELECT from_unit_id || '|' || factor FROM inventory.unit_conversions WHERE product_id = '${product.id}'")
        )
        putProductUnit(owner, product, null, null)
        putProductUnit(owner, product, null, null)
        val base = product.unitId
        assertEquals("12.0000|$dusId|$base,12.5000|$dusId|$base,12.5000|$packId|$base", history())

        // Transaksi yang gagal tidak meninggalkan riwayat; hapus permanen produk tidak terhalang trigger.
        insertUnitConversion(product.id, dusId, base, "6")
        inRolledBackTransaction { connection ->
            connection.createStatement().use { it.execute("DELETE FROM inventory.unit_conversions WHERE product_id = '${product.id}'") }
        }
        assertEquals(3, history().split(',').size)
        execute("DELETE FROM inventory.stock_movements WHERE product_id = '${product.id}'")
        execute("DELETE FROM inventory.products WHERE id = '${product.id}'")
        assertEquals("", history())
    }

    @Test
    fun test_changing_base_unit_rewrites_conversion_and_records_old_factor() = testApplication {
        application { configureIntegrationApplication() }
        val owner = loginAsOwner()
        val product = createProductFixture()
        val dusId = unitOtherThan(product.unitId)
        val packId = unitOtherThan(product.unitId, dusId)
        putProductUnit(owner, product, dusId, "12")
        putProductUnit(owner, product, dusId, "12", baseUnitId = packId)
        assertEquals(
            "$dusId|$packId|12.0000",
            firstString("SELECT concat_ws('|', from_unit_id, to_unit_id, factor) FROM inventory.unit_conversions WHERE product_id = '${product.id}'")
        )
        assertEquals("12.0000|$dusId|${product.unitId}", conversionHistory(product.id))
    }

    @Test
    fun test_v47_keeps_existing_conversions_and_starts_with_empty_history() {
        val migration = requireNotNull(
            javaClass.classLoader.getResource("db/migration/V47__unit_conversion_history.sql")
        ).readText()
        val product = createProductFixture()
        insertUnitConversion(product.id, unitOtherThan(product.unitId), product.unitId, "12")
        inRolledBackTransaction { connection ->
            connection.createStatement().use { statement ->
                fun value(sql: String) = statement.executeQuery(sql).use { rows -> rows.next(); rows.getString(1) }
                val snapshot = "SELECT md5(string_agg(c::text, ',' ORDER BY id)) FROM inventory.unit_conversions c"
                // Kembalikan skema ke V46, lalu jalankan V47 di atas data yang ada.
                statement.execute("DROP TRIGGER trg_unit_conversions_history ON inventory.unit_conversions")
                statement.execute("DROP FUNCTION inventory.fn_log_unit_conversion_history()")
                statement.execute("DROP TABLE inventory.unit_conversion_history")
                val before = value(snapshot)
                statement.execute(migration)
                assertEquals(before, value(snapshot))
                assertEquals("0", value("SELECT COUNT(*) FROM inventory.unit_conversion_history"))
                statement.execute("DELETE FROM inventory.unit_conversions WHERE product_id = '${product.id}'")
                assertEquals("12.0000", value("SELECT old_factor FROM inventory.unit_conversion_history WHERE product_id = '${product.id}'"))
            }
        }
    }

    @Test
    fun test_e2e_supported_offline_sync_is_idempotent_and_audited() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val token = cashier.token
        val userId = cashier.userId
        val product = createProductFixture()
        val deviceId = "integration-device-${UUID.randomUUID()}"
        val openedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(10)
        val openKey = "offline-open-${UUID.randomUUID()}"
        val openBody =
            """{"clientGeneratedId":"$openKey","deviceId":"$deviceId","cashierUserId":"$userId","openedAt":"$openedAt","startingCash":"100.00","openingNote":"Offline integration"}"""

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
            """{"clientGeneratedId":"$checkoutKey","deviceId":"$deviceId","localTransactionCode":"LOCAL-001","cashierUserId":"$userId","cashSessionId":"$sessionId","paymentMethod":"tunai","subtotal":"100.00","discount":"0.00","total":"100.00","paidAmount":"100.00","remainingAmount":"0.00","occurredAt":"${openedAt.plusHours(1)}","items":[{"productId":"${product.id}","productNameSnapshot":"Offline Product","quantity":"1.00","priceAtTransaction":"100.00","cogsAtTransaction":"50.00","discount":"0.00","subtotal":"100.00"}]}"""
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
            """{"clientGeneratedId":"$expenseKey","deviceId":"$deviceId","cashierUserId":"$userId","serverCashSessionId":"$sessionId","amount":"10.00","category":"Operasional","note":"Biaya offline integration","occurredAt":"${openedAt.plusHours(2)}"}"""
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
            """{"deviceId":"$deviceId","clientGeneratedId":"$closeKey","serverCashSessionId":"$sessionId","cashierUserId":"$userId","closedAt":"${openedAt.plusHours(9)}","actualCash":"190.00","expectedCash":"190.00","difference":"0.00","closingNote":"Offline close integration"}"""
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
        val cashier = loginAsCashier(token)
        val product = createProductFixture()
        val customerId = createReceivableCustomerFixture(BigDecimal("1000.00"))
        assertEquals(HttpStatusCode.Created, openSession(cashier.token).status)

        val sale = checkout(cashier.token, product, "2.00", "200.00", "e2e-checkout-${UUID.randomUUID()}")
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

    @Test
    fun test_discount_snapshots_refund_and_analytics_use_net_amount() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val admin = createAndLoginApprovalUser(token, Role.ADMIN, "discount_snapshot_admin")
        val cashier = loginAsCashier(token)
        val product = createProductFixture()
        assertEquals(HttpStatusCode.Created, openSession(cashier.token).status)

        val preview = discountPreview(
            cashier.token, product, "PERCENTAGE", "10.00",
            transactionDiscountType = "FIXED_AMOUNT", transactionDiscountValue = "10.00"
        )
        assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
        val attemptId = UUID.fromString(
            Json.parseToJsonElement(preview.bodyAsText()).jsonObject.getValue("data").jsonObject
                .getValue("checkoutAttemptId").jsonPrimitive.content
        )
        val approvalId = managerApprovalId(
            requestManagerApproval(
                cashier.token, attemptId, admin.username, admin.pin,
                action = "DISCOUNT_OVERRIDE", resourceType = "TRANSACTION"
            )
        )

        val response = discountCheckout(
            token = cashier.token,
            product = product,
            itemDiscountType = "PERCENTAGE",
            itemDiscountValue = "10.00",
            transactionDiscountType = "FIXED_AMOUNT",
            transactionDiscountValue = "10.00",
            amountPaid = "80.00",
            idempotencyKey = "discount-${UUID.randomUUID()}",
            checkoutAttemptId = attemptId,
            managerApprovalId = approvalId
        )
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val transactionId = checkoutTransactionId(response)
        val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals("100.00", data.getValue("grossSubtotal").jsonPrimitive.content)
        assertEquals("20.00", data.getValue("totalDiscountAmount").jsonPrimitive.content)
        assertEquals("80.00", data.getValue("total").jsonPrimitive.content)

        val snapshot = discountSnapshot(UUID.fromString(transactionId))
        assertEquals(BigDecimal("100.00"), snapshot.first)
        assertEquals(BigDecimal("20.00"), snapshot.second)
        assertEquals(BigDecimal("80.00"), snapshot.third)

        val refund = refundTransaction(
            token, transactionId, "refund-${UUID.randomUUID()}", "Refund transaksi berdiskon"
        )
        assertEquals(HttpStatusCode.Created, refund.status, refund.bodyAsText())
        assertEquals(BigDecimal("80.00"), refundFinancialAmount(transactionId))

        val report = client.get("/api/analytics/sales/report") { bearer(token) }
        assertEquals(HttpStatusCode.OK, report.status, report.bodyAsText())
        val totals = Json.parseToJsonElement(report.bodyAsText()).jsonObject.getValue("data").jsonObject
            .getValue("totals").jsonObject
        assertEquals("100.00", totals.getValue("grossRevenue").jsonPrimitive.content)
        assertEquals("20.00", totals.getValue("discountAmount").jsonPrimitive.content)
        assertEquals("80.00", totals.getValue("refundAmount").jsonPrimitive.content)
        assertEquals("0.00", totals.getValue("netRevenue").jsonPrimitive.content)
    }

    @Test
    fun test_cashier_discount_override_is_bound_to_attempt_intent_and_idempotent() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "discount_admin")
        val cashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "discount_cashier")
        val otherCashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "discount_other_cashier")
        val product = createProductFixture()
        assertEquals(HttpStatusCode.Created, openSession(cashier.token).status)

        val underLimit = discountCheckout(
            cashier.token, product, "PERCENTAGE", "10.00", amountPaid = "90.00",
            idempotencyKey = "discount-${UUID.randomUUID()}"
        )
        assertEquals(HttpStatusCode.Created, underLimit.status, underLimit.bodyAsText())

        val preview = discountPreview(cashier.token, product, "PERCENTAGE", "15.00")
        assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
        val previewData = Json.parseToJsonElement(preview.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertTrue(previewData.getValue("approvalRequired").jsonPrimitive.content.toBoolean())
        val attemptId = UUID.fromString(previewData.getValue("checkoutAttemptId").jsonPrimitive.content)

        val missing = discountCheckout(
            cashier.token, product, "PERCENTAGE", "15.00", amountPaid = "85.00",
            idempotencyKey = "discount-${UUID.randomUUID()}", checkoutAttemptId = attemptId
        )
        assertTrue(missing.status.value >= 400, missing.bodyAsText())

        listOf("VOID_TRANSACTION").forEach { wrongAction ->
            val wrongApprovalId = managerApprovalId(
                requestManagerApproval(
                    cashier.token, attemptId, admin.username, admin.pin,
                    action = wrongAction, resourceType = "TRANSACTION"
                )
            )
            val rejected = discountCheckout(
                cashier.token, product, "PERCENTAGE", "15.00", amountPaid = "85.00",
                idempotencyKey = "discount-${UUID.randomUUID()}", checkoutAttemptId = attemptId,
                managerApprovalId = wrongApprovalId
            )
            assertTrue(rejected.status.value >= 400, rejected.bodyAsText())
        }

        val wrongScopeApprovalId = managerApprovalId(
            requestManagerApproval(
                cashier.token, UUID.randomUUID(), admin.username, admin.pin,
                action = "DISCOUNT_OVERRIDE", resourceType = "TRANSACTION"
            )
        )
        val wrongScope = discountCheckout(
            cashier.token, product, "PERCENTAGE", "15.00", amountPaid = "85.00",
            idempotencyKey = "discount-${UUID.randomUUID()}", checkoutAttemptId = attemptId,
            managerApprovalId = wrongScopeApprovalId
        )
        assertTrue(wrongScope.status.value >= 400, wrongScope.bodyAsText())

        val foreignRequesterApprovalId = managerApprovalId(
            requestManagerApproval(
                otherCashier.token, attemptId, admin.username, admin.pin,
                action = "DISCOUNT_OVERRIDE", resourceType = "TRANSACTION"
            )
        )
        val foreignRequester = discountCheckout(
            cashier.token, product, "PERCENTAGE", "15.00", amountPaid = "85.00",
            idempotencyKey = "discount-${UUID.randomUUID()}", checkoutAttemptId = attemptId,
            managerApprovalId = foreignRequesterApprovalId
        )
        assertTrue(foreignRequester.status.value >= 400, foreignRequester.bodyAsText())

        val expiredApprovalId = managerApprovalId(
            requestManagerApproval(
                cashier.token, attemptId, admin.username, admin.pin,
                action = "DISCOUNT_OVERRIDE", resourceType = "TRANSACTION"
            )
        )
        expireManagerApproval(expiredApprovalId)
        val expired = discountCheckout(
            cashier.token, product, "PERCENTAGE", "15.00", amountPaid = "85.00",
            idempotencyKey = "discount-${UUID.randomUUID()}", checkoutAttemptId = attemptId,
            managerApprovalId = expiredApprovalId
        )
        assertTrue(expired.status.value >= 400, expired.bodyAsText())

        val approvalId = managerApprovalId(
            requestManagerApproval(
                cashier.token, attemptId, admin.username, admin.pin,
                action = "DISCOUNT_OVERRIDE", resourceType = "TRANSACTION"
            )
        )
        val mutation = discountCheckout(
            cashier.token, product, "PERCENTAGE", "50.00", amountPaid = "50.00",
            idempotencyKey = "discount-${UUID.randomUUID()}", checkoutAttemptId = attemptId,
            managerApprovalId = approvalId
        )
        assertTrue(mutation.status.value >= 400, mutation.bodyAsText())

        val key = "discount-${UUID.randomUUID()}"
        val approved = discountCheckout(
            cashier.token, product, "PERCENTAGE", "15.00", amountPaid = "85.00",
            idempotencyKey = key, checkoutAttemptId = attemptId, managerApprovalId = approvalId
        )
        assertEquals(HttpStatusCode.Created, approved.status, approved.bodyAsText())
        val replay = discountCheckout(
            cashier.token, product, "PERCENTAGE", "15.00", amountPaid = "85.00",
            idempotencyKey = key, checkoutAttemptId = attemptId, managerApprovalId = approvalId
        )
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertEquals(checkoutTransactionId(approved), checkoutTransactionId(replay))
        assertTrue(checkoutReplay(replay))

        val reused = discountCheckout(
            cashier.token, product, "PERCENTAGE", "15.00", amountPaid = "85.00",
            idempotencyKey = "discount-${UUID.randomUUID()}", checkoutAttemptId = attemptId,
            managerApprovalId = approvalId
        )
        assertTrue(reused.status.value >= 400, reused.bodyAsText())
        assertEquals(2, transactionCount())
        assertEquals(BigDecimal("8.00"), stockQuantity(product.id))
    }

    @Test
    fun test_discount_override_rejects_refund_approval_action() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "discount_refund_action_admin")
        val cashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "discount_refund_action_cashier")
        val product = createProductFixture()
        assertEquals(HttpStatusCode.Created, openSession(cashier.token).status)
        val preview = discountPreview(cashier.token, product, "PERCENTAGE", "15.00")
        val previewData = Json.parseToJsonElement(preview.bodyAsText()).jsonObject.getValue("data").jsonObject
        val attemptId = UUID.fromString(previewData.getValue("checkoutAttemptId").jsonPrimitive.content)
        val refundApprovalId = managerApprovalId(
            requestManagerApproval(
                cashier.token, attemptId, admin.username, admin.pin,
                action = "REFUND_TRANSACTION", resourceType = "TRANSACTION"
            )
        )

        val rejected = discountCheckout(
            cashier.token, product, "PERCENTAGE", "15.00", amountPaid = "85.00",
            idempotencyKey = "discount-${UUID.randomUUID()}", checkoutAttemptId = attemptId,
            managerApprovalId = refundApprovalId
        )
        assertEquals(HttpStatusCode.Forbidden, rejected.status, rejected.bodyAsText())
        assertTrue(rejected.bodyAsText().contains("MANAGER_APPROVAL_ACTION_MISMATCH"), rejected.bodyAsText())
        assertEquals(0, transactionCount())
        assertEquals("APPROVED", managerApprovalStatus(refundApprovalId))
    }

    @Test
    fun test_concurrent_discount_checkout_with_one_approval_creates_one_transaction() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "discount_concurrent_admin")
        val cashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "discount_concurrent_cashier")
        val product = createProductFixture()
        assertEquals(HttpStatusCode.Created, openSession(cashier.token).status)
        val preview = discountPreview(cashier.token, product, "PERCENTAGE", "15.00")
        val previewData = Json.parseToJsonElement(preview.bodyAsText()).jsonObject.getValue("data").jsonObject
        val attemptId = UUID.fromString(previewData.getValue("checkoutAttemptId").jsonPrimitive.content)
        val approvalId = managerApprovalId(
            requestManagerApproval(
                cashier.token, attemptId, admin.username, admin.pin,
                action = "DISCOUNT_OVERRIDE", resourceType = "TRANSACTION"
            )
        )

        val release = CompletableDeferred<Unit>()
        val responses = coroutineScope {
            val first = async {
                release.await()
                discountCheckout(
                    cashier.token, product, "PERCENTAGE", "15.00", amountPaid = "85.00",
                    idempotencyKey = "discount-${UUID.randomUUID()}", checkoutAttemptId = attemptId,
                    managerApprovalId = approvalId
                )
            }
            val second = async {
                release.await()
                discountCheckout(
                    cashier.token, product, "PERCENTAGE", "15.00", amountPaid = "85.00",
                    idempotencyKey = "discount-${UUID.randomUUID()}", checkoutAttemptId = attemptId,
                    managerApprovalId = approvalId
                )
            }
            release.complete(Unit)
            listOf(first.await(), second.await())
        }
        val responseDetails = responses.map { "${it.status}: ${it.bodyAsText()}" }.joinToString(" | ")
        assertEquals(1, responses.count { it.status.value in 200..299 }, responseDetails)
        assertEquals(1, transactionCount())
        assertEquals(BigDecimal("9.00"), stockQuantity(product.id))
        assertEquals("USED", managerApprovalStatus(approvalId))
    }

    @Test
    fun test_discount_checkout_failure_rolls_back_transaction_stock_audit_and_approval() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "discount_rollback_admin")
        val cashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "discount_rollback_cashier")
        val product = createProductFixture()
        assertEquals(HttpStatusCode.Created, openSession(cashier.token).status)
        val preview = discountPreview(cashier.token, product, "PERCENTAGE", "15.00")
        val previewData = Json.parseToJsonElement(preview.bodyAsText()).jsonObject.getValue("data").jsonObject
        val attemptId = UUID.fromString(previewData.getValue("checkoutAttemptId").jsonPrimitive.content)
        val approvalId = managerApprovalId(
            requestManagerApproval(
                cashier.token, attemptId, admin.username, admin.pin,
                action = "DISCOUNT_OVERRIDE", resourceType = "TRANSACTION"
            )
        )
        installFailingDiscountCheckoutTrigger()
        try {
            val response = discountCheckout(
                cashier.token, product, "PERCENTAGE", "15.00", amountPaid = "85.00",
                idempotencyKey = "discount-${UUID.randomUUID()}", checkoutAttemptId = attemptId,
                managerApprovalId = approvalId
            )
            assertTrue(response.status.value >= 400, response.bodyAsText())
        } finally {
            removeFailingDiscountCheckoutTrigger()
        }

        assertEquals(0, transactionCount())
        assertEquals(BigDecimal("10.00"), stockQuantity(product.id))
        assertEquals("APPROVED", managerApprovalStatus(approvalId))
        assertFalse(discountAttemptConsumed(attemptId))
        assertEquals(0, auditActionCount("sales", "transactions", attemptId, "UPDATE"))
    }

    @Test
    fun test_discounted_dp_creates_receivable_from_net_total() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsCashier(loginAsOwner()).token
        val product = createProductFixture()
        val customerId = createReceivableCustomerFixture(BigDecimal("1000.00"))
        assertEquals(HttpStatusCode.Created, openSession(token).status)

        val response = client.post("/api/sales/checkout") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """{"idempotencyKey":"discount-${UUID.randomUUID()}","customerId":"$customerId","items":[{"productId":"${product.id}","qty":"1.00","discountRequest":{"type":"PERCENTAGE","value":"10.00"}}],"paymentMethod":"dp","amountPaid":"30.00","dueDays":30}"""
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val transactionId = UUID.fromString(checkoutTransactionId(response))
        assertEquals(BigDecimal("60.00"), transactionReceivableRemaining(transactionId))
    }

    @Test
    fun test_offline_open_rejects_opened_at_outside_server_window() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val maxHours = EnvironmentConfig.offlineMaxSessionAgeHours

        listOf(now.minusHours(maxHours + 1), now.minusDays(30), now.plusMinutes(10)).forEach { openedAt ->
            val rejected = syncOpenSession(cashier, openedAt)
            assertEquals(HttpStatusCode.Conflict, rejected.status, rejected.bodyAsText())
            assertTrue(rejected.bodyAsText().contains("OFFLINE_OPENED_AT_INVALID"), rejected.bodyAsText())
            assertTrue(rejected.bodyAsText().contains("business rule"), rejected.bodyAsText())
        }
        assertEquals(0, openSessionCount(cashier.userId))

        val key = "offline-open-${UUID.randomUUID()}"
        val accepted = syncOpenSession(cashier, now.minusHours(maxHours - 1), key)
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())

        // Retry sesi yang sudah tersimpan tetap idempoten walau umurnya sudah lewat batas.
        val replay = syncOpenSession(cashier, now.minusHours(maxHours + 6), key)
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertTrue(replay.bodyAsText().contains("\"syncStatus\":\"DUPLICATE\""), replay.bodyAsText())
    }

    @Test
    fun test_offline_checkout_outside_session_time_mentions_business_rule() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val product = createProductFixture()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val sessionId = offlineSessionId(syncOpenSession(cashier, now.minusHours(2)))

        val rejected = syncOfflineSale(cashier, sessionId, product, occurredAt = now.minusHours(5), price = "100.00")
        assertEquals(HttpStatusCode.Conflict, rejected.status, rejected.bodyAsText())
        assertTrue(rejected.bodyAsText().contains("OFFLINE_OCCURRED_AT_INVALID"), rejected.bodyAsText())
        assertTrue(rejected.bodyAsText().contains("business rule"), rejected.bodyAsText())
        assertEquals(0, transactionCount())
    }

    @Test
    fun test_offline_checkout_cannot_disable_tax_when_settings_did_not_change() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val product = createProductFixture()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        insertPriceHistory(product, oldRetail = "80.00", changedAt = now.minusHours(1))

        withTaxedStoreSettings(updatedAt = now.minusDays(2)) {
            val sessionId = offlineSessionId(syncOpenSession(cashier, now.minusHours(3)))
            val occurredAt = now.minusMinutes(30)

            val untaxed = syncOfflineSale(cashier, sessionId, product, occurredAt, price = "100.00")
            assertEquals(HttpStatusCode.Conflict, untaxed.status, untaxed.bodyAsText())
            assertTrue(untaxed.bodyAsText().contains("OFFLINE_TAX_CONFLICT"), untaxed.bodyAsText())
            assertTrue(untaxed.bodyAsText().contains("business rule"), untaxed.bodyAsText())
            assertEquals(0, transactionCount())
            assertEquals(BigDecimal("10.00"), stockQuantity(product.id))

            val taxed = syncOfflineSale(cashier, sessionId, product, occurredAt, price = "100.00", tax = "11.00")
            assertEquals(HttpStatusCode.Created, taxed.status, taxed.bodyAsText())
            // Harga lama yang berubah di tengah sesi tetap diterima.
            val oldPrice = syncOfflineSale(cashier, sessionId, product, occurredAt, price = "80.00", tax = "8.80")
            assertEquals(HttpStatusCode.Created, oldPrice.status, oldPrice.bodyAsText())
            assertEquals(2, transactionCount())
            assertEquals(BigDecimal("8.00"), stockQuantity(product.id))
        }
    }

    @Test
    fun test_backdated_session_stored_earlier_does_not_widen_offline_window() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val product = createProductFixture()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        // Sesi yang tersimpan sebelum aturan openedAt berlaku: openedAt mundur 30 hari, sync baru saja.
        val sessionId = insertOpenCashSession(cashier.userId, openedAt = now.minusDays(30), offlineSyncedAt = now)
        insertPriceHistory(product, oldRetail = "80.00", changedAt = now.minusDays(10))

        withTaxedStoreSettings(updatedAt = now.minusDays(10)) {
            val occurredAt = now.minusMinutes(30)
            val untaxed = syncOfflineSale(cashier, sessionId, product, occurredAt, price = "100.00")
            assertEquals(HttpStatusCode.Conflict, untaxed.status, untaxed.bodyAsText())
            assertTrue(untaxed.bodyAsText().contains("OFFLINE_TAX_CONFLICT"), untaxed.bodyAsText())

            val stalePrice = syncOfflineSale(cashier, sessionId, product, occurredAt, price = "80.00", tax = "8.80")
            assertEquals(HttpStatusCode.Conflict, stalePrice.status, stalePrice.bodyAsText())
            assertTrue(stalePrice.bodyAsText().contains("OFFLINE_PRICE_CONFLICT"), stalePrice.bodyAsText())
            assertEquals(0, transactionCount())

            val valid = syncOfflineSale(cashier, sessionId, product, occurredAt, price = "100.00", tax = "11.00")
            assertEquals(HttpStatusCode.Created, valid.status, valid.bodyAsText())
        }
    }

    @Test
    fun test_offline_max_session_age_bounds_open_and_price_window() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val product = createProductFixture()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val koin = GlobalContext.get()
        val twoHours = Duration.ofHours(2)
        val shortRepository = SalesRepositoryImpl(koin.get(), koin.get(), twoHours)

        suspend fun open(openedAt: OffsetDateTime) = SalesService(shortRepository, twoHours).syncOpenCashSession(
            cashier.userId,
            OfflineCashSessionOpenSyncRequest(
                clientGeneratedId = "offline-open-${UUID.randomUUID()}",
                deviceId = OFFLINE_DEVICE_ID,
                cashierUserId = cashier.userId.toString(),
                openedAt = openedAt.toString(),
                startingCash = BigDecimal("100.00")
            )
        )
        val tooOld = assertFailsWith<BusinessConflictException> { open(now.minusHours(3)) }
        assertEquals("OFFLINE_OPENED_AT_INVALID", tooOld.code)
        assertTrue(tooOld.message.orEmpty().contains("lebih dari 2 jam"), tooOld.message)
        open(now.minusHours(1))
        assertEquals(HttpStatusCode.OK, closeSession(cashier.token, "100.00").status)

        // Harga 80 berubah 3 jam lalu: di luar jendela 2 jam, di dalam jendela default.
        val sessionId = insertOpenCashSession(cashier.userId, openedAt = now.minusHours(10), offlineSyncedAt = now)
        insertPriceHistory(product, oldRetail = "80.00", changedAt = now.minusHours(3))
        val body = offlineSaleBody(cashier, sessionId, product, now.minusMinutes(30), price = "80.00")
        val request = Json.decodeFromString<OfflineCheckoutSyncRequest>(body)
        val stale = assertFailsWith<BusinessConflictException> {
            SalesService(shortRepository, twoHours).syncOfflineCheckout(cashier.userId, request)
        }
        assertEquals("OFFLINE_PRICE_CONFLICT", stale.code)
        assertEquals(0, transactionCount())
        val accepted = syncOfflineSale(cashier, sessionId, product, now.minusMinutes(30), price = "80.00")
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
    }

    @Test
    fun test_unrelated_store_setting_change_does_not_loosen_offline_tax_check() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val cashier = loginAsCashier(ownerToken)
        val product = createProductFixture()
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        withTaxedStoreSettings(updatedAt = now.minusDays(2)) {
            val sessionId = offlineSessionId(syncOpenSession(cashier, now.minusHours(3)))
            val updatedAtBefore = firstString("SELECT updated_at::text FROM system.store_settings")
            // Form pengaturan mengirim ulang nilai pajak dan pembulatan yang sama.
            val saved = client.put("/api/system/settings") {
                contentType(ContentType.Application.Json)
                bearer(ownerToken)
                setBody(
                    """{"storeName":"Toko Ganti Nama","phone":"0812","printerSize":"80mm","taxEnabled":true,"taxRate":"11.00","taxMode":"EXCLUDE","taxLabel":"Label Baru","storeNpwp":"01.234","cashRoundingUnit":0,"cashRoundingRule":"NEAREST"}"""
                )
            }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            assertFalse(saved.bodyAsText().contains("settingsUpdatedAt", ignoreCase = true), saved.bodyAsText())
            assertTrue(firstString("SELECT updated_at::text FROM system.store_settings") != updatedAtBefore)
            updateStoreSettings("cash_rounding_unit = 500")

            val untaxed = syncOfflineSale(cashier, sessionId, product, now.minusMinutes(30), price = "100.00")
            assertEquals(HttpStatusCode.Conflict, untaxed.status, untaxed.bodyAsText())
            assertTrue(untaxed.bodyAsText().contains("OFFLINE_TAX_CONFLICT"), untaxed.bodyAsText())
            assertEquals(0, transactionCount())
        }
    }

    @Test
    fun test_tax_and_rounding_changes_loosen_only_their_own_offline_check() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val product = createProductFixture()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        insertPriceHistory(product, oldRetail = "80.00", changedAt = now.minusHours(1))
        suspend fun ApplicationTestBuilder.sale(sessionId: String, tax: String?, rounding: String) =
            syncOfflineSale(cashier, sessionId, product, now.minusMinutes(30), "80.00", tax, rounding)

        withTaxedStoreSettings(updatedAt = now.minusDays(2)) {
            val sessionId = offlineSessionId(syncOpenSession(cashier, now.minusHours(3)))
            // 80 + pajak 8.80 + pembulatan 11.20 = 100; pembulatan toko tidak aktif.
            val rounded = sale(sessionId, "8.80", "11.20")
            assertEquals(HttpStatusCode.Conflict, rounded.status, rounded.bodyAsText())
            assertTrue(rounded.bodyAsText().contains("OFFLINE_ROUNDING_CONFLICT"), rounded.bodyAsText())

            updateStoreSettings("tax_rate = 12.00")
            val oldTax = sale(sessionId, "8.80", "0.00")
            assertEquals(HttpStatusCode.Created, oldTax.status, oldTax.bodyAsText())
            val stillRounded = sale(sessionId, "8.80", "11.20")
            assertEquals(HttpStatusCode.Conflict, stillRounded.status, stillRounded.bodyAsText())
            assertTrue(stillRounded.bodyAsText().contains("OFFLINE_ROUNDING_CONFLICT"), stillRounded.bodyAsText())
        }

        withTaxedStoreSettings(updatedAt = now.minusDays(2)) {
            assertEquals(HttpStatusCode.OK, closeSession(cashier.token, "188.80").status)
            val sessionId = offlineSessionId(syncOpenSession(cashier, now.minusHours(3)))
            updateStoreSettings("cash_rounding_unit = 1000")
            updateStoreSettings("cash_rounding_unit = 0")
            val oldRounding = sale(sessionId, "8.80", "11.20")
            assertEquals(HttpStatusCode.Created, oldRounding.status, oldRounding.bodyAsText())
            val untaxed = sale(sessionId, null, "0.00")
            assertEquals(HttpStatusCode.Conflict, untaxed.status, untaxed.bodyAsText())
            assertTrue(untaxed.bodyAsText().contains("OFFLINE_TAX_CONFLICT"), untaxed.bodyAsText())
        }
    }

    @Test
    fun test_store_settings_trigger_moves_only_the_related_timestamp() {
        val old = "2026-01-01T00:00:00Z"
        val changes = mapOf(
            "tax_enabled = NOT tax_enabled" to "tax",
            "tax_rate = 12.50" to "tax",
            "tax_mode = 'INCLUDE'" to "tax",
            "cash_rounding_unit = 500" to "rounding",
            "cash_rounding_rule = 'UP'" to "rounding",
            "tax_label = 'Lain'" to "",
            "store_npwp = '99'" to "",
            "store_name = 'Lain', phone = '1'" to "",
            "cashier_discount_limit_percent = 1" to "",
            "tax_rate = tax_rate, cash_rounding_unit = cash_rounding_unit" to "",
            // Kolom waktu tidak bisa ditulis langsung.
            "tax_settings_updated_at = NOW(), rounding_settings_updated_at = NOW()" to ""
        )
        inRolledBackTransaction { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "UPDATE system.store_settings SET tax_enabled = FALSE, tax_rate = 11, tax_mode = 'EXCLUDE', " +
                        "cash_rounding_unit = 0, cash_rounding_rule = 'NEAREST'"
                )
                changes.forEach { (assignment, expected) ->
                    statement.execute("SAVEPOINT before_change")
                    statement.execute("ALTER TABLE system.store_settings DISABLE TRIGGER USER")
                    statement.execute(
                        "UPDATE system.store_settings SET tax_settings_updated_at = '$old', rounding_settings_updated_at = '$old'"
                    )
                    statement.execute("ALTER TABLE system.store_settings ENABLE TRIGGER USER")
                    statement.execute("UPDATE system.store_settings SET $assignment")
                    statement.executeQuery(
                        "SELECT tax_settings_updated_at <> '$old', rounding_settings_updated_at <> '$old' FROM system.store_settings"
                    ).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(expected == "tax", rows.getBoolean(1), assignment)
                        assertEquals(expected == "rounding", rows.getBoolean(2), assignment)
                    }
                    statement.execute("ROLLBACK TO SAVEPOINT before_change")
                }
            }
        }
    }

    @Test
    fun test_v46_backfills_change_timestamps_without_touching_updated_at() {
        val migration = requireNotNull(
            javaClass.classLoader.getResource("db/migration/V46__store_settings_change_timestamps.sql")
        ).readText()
        inRolledBackTransaction { connection ->
            connection.createStatement().use { statement ->
                // Kembalikan skema ke V45 dengan data lama, lalu jalankan V46.
                statement.execute("DROP TRIGGER trg_store_settings_track_changes ON system.store_settings")
                statement.execute("DROP FUNCTION system.fn_track_store_settings_changes()")
                statement.execute(
                    "ALTER TABLE system.store_settings DROP COLUMN tax_settings_updated_at, DROP COLUMN rounding_settings_updated_at"
                )
                statement.execute("ALTER TABLE system.store_settings DISABLE TRIGGER trg_store_settings_timestamp")
                statement.execute("UPDATE system.store_settings SET updated_at = '2026-01-01T00:00:00Z'")
                statement.execute("ALTER TABLE system.store_settings ENABLE TRIGGER trg_store_settings_timestamp")
                statement.execute(migration)
                statement.executeQuery(
                    """
                    SELECT updated_at = '2026-01-01T00:00:00Z', tax_settings_updated_at = updated_at,
                           rounding_settings_updated_at = updated_at FROM system.store_settings
                    """.trimIndent()
                ).use { rows ->
                    assertTrue(rows.next())
                    (1..3).forEach { column -> assertTrue(rows.getBoolean(column), "kolom $column") }
                }
                // Trigger timestamp aktif lagi.
                statement.execute("UPDATE system.store_settings SET phone = '1'")
                statement.executeQuery("SELECT updated_at > '2026-01-01T00:00:00Z' FROM system.store_settings").use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getBoolean(1))
                }
            }
        }
    }

    @Test
    fun test_offline_cash_expense_outside_session_time_is_rejected() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val sessionId = offlineSessionId(syncOpenSession(cashier, now.minusHours(2)))
        val cashBefore = activeCashSystemCash(cashier.userId)

        listOf(now.minusHours(3) to "lebih awal", now.plusMinutes(10) to "masa depan").forEach { (occurredAt, detail) ->
            val rejected = syncOfflineExpense(cashier, sessionId, occurredAt)
            assertEquals(HttpStatusCode.Conflict, rejected.status, rejected.bodyAsText())
            assertTrue(rejected.bodyAsText().contains("OFFLINE_OCCURRED_AT_INVALID"), rejected.bodyAsText())
            assertTrue(rejected.bodyAsText().contains("business rule"), rejected.bodyAsText())
            assertTrue(rejected.bodyAsText().contains("Waktu pengeluaran kas offline"), rejected.bodyAsText())
            assertTrue(rejected.bodyAsText().contains(detail), rejected.bodyAsText())
        }
        assertEquals("0", firstString("SELECT COUNT(*) FROM sales.cash_expenses"))
        assertEquals(cashBefore, activeCashSystemCash(cashier.userId))

        val key = "offline-expense-${UUID.randomUUID()}"
        val accepted = syncOfflineExpense(cashier, sessionId, now.minusHours(1), key)
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
        // Pengeluaran yang sudah tersimpan tetap dijawab DUPLICATE, apa pun waktu pada retry.
        val replay = syncOfflineExpense(cashier, sessionId, now.minusHours(9), key)
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertTrue(replay.bodyAsText().contains("\"syncStatus\":\"DUPLICATE\""), replay.bodyAsText())
        assertEquals("1", firstString("SELECT COUNT(*) FROM sales.cash_expenses"))
    }

    @Test
    fun test_offline_close_outside_session_time_is_rejected() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val sessionId = offlineSessionId(syncOpenSession(cashier, now.minusHours(2)))

        listOf(now.minusHours(3) to "lebih awal", now.plusMinutes(10) to "masa depan").forEach { (closedAt, detail) ->
            val rejected = syncOfflineClose(cashier, sessionId, closedAt)
            assertEquals(HttpStatusCode.Conflict, rejected.status, rejected.bodyAsText())
            assertTrue(rejected.bodyAsText().contains("OFFLINE_CLOSED_AT_INVALID"), rejected.bodyAsText())
            assertTrue(rejected.bodyAsText().contains("business rule"), rejected.bodyAsText())
            assertTrue(rejected.bodyAsText().contains("Waktu tutup sesi kas offline"), rejected.bodyAsText())
            assertTrue(rejected.bodyAsText().contains(detail), rejected.bodyAsText())
        }
        assertEquals(1, openSessionCount(cashier.userId))
        assertEquals(0, auditActionCount("sales", "cash_sessions", UUID.fromString(sessionId), "UPDATE"))

        val key = "offline-close-${UUID.randomUUID()}"
        val accepted = syncOfflineClose(cashier, sessionId, now.minusHours(1), key)
        assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
        assertTrue(accepted.bodyAsText().contains("\"syncStatus\":\"UPDATED\""), accepted.bodyAsText())
        mapOf(key to "DUPLICATE", "offline-close-${UUID.randomUUID()}" to "ALREADY_CLOSED").forEach { (retryKey, status) ->
            val replay = syncOfflineClose(cashier, sessionId, now.minusDays(9), retryKey)
            assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
            assertTrue(replay.bodyAsText().contains("\"syncStatus\":\"$status\""), replay.bodyAsText())
        }
    }

    @Test
    fun test_transaction_debt_payment_rejects_key_used_by_a_reversal() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val cashier = loginAsCashier(ownerToken)
        val product = createProductFixture()
        val customerId = createReceivableCustomerFixture(BigDecimal("1000.00"))
        assertEquals(HttpStatusCode.Created, openSession(cashier.token).status)
        val transactionId = createDebtTransaction(cashier.token, product, customerId)
        val receivableId = transactionReceivableId(transactionId)
        val paid = payDebt(cashier.token, transactionId, "80.00", "transfer", "debt-pay-${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.OK, paid.status, paid.bodyAsText())
        val paymentId = firstString("SELECT id FROM receivable.receivable_payments WHERE receivable_id = '$receivableId'")
        val reversalKey = "debt-reversal-${UUID.randomUUID()}"
        val reversal = reverseReceivablePayment(ownerToken, paymentId, "Salah nominal", reversalKey)
        assertEquals(HttpStatusCode.Created, reversal.status, reversal.bodyAsText())

        // Nominal, metode, dan piutangnya sama; hanya jenis entrinya yang berbeda.
        val conflict = payDebt(cashier.token, transactionId, "80.00", "transfer", reversalKey)
        assertEquals(HttpStatusCode.BadRequest, conflict.status, conflict.bodyAsText())
        assertTrue(
            conflict.bodyAsText().contains("Idempotency key sudah dipakai untuk pembayaran lain"),
            conflict.bodyAsText()
        )
        assertEquals(2, receivablePaymentCount(receivableId))
        assertEquals(BigDecimal("200.00"), transactionReceivableRemaining(UUID.fromString(transactionId)))
    }

    @Test
    fun test_transaction_debt_payment_succeeds_and_replays_idempotently() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val product = createProductFixture()
        val customerId = createReceivableCustomerFixture(BigDecimal("1000.00"))
        assertEquals(HttpStatusCode.Created, openSession(cashier.token).status)
        val transactionId = createDebtTransaction(cashier.token, product, customerId)
        val receivableId = transactionReceivableId(transactionId)
        val cashBefore = activeCashSystemCash(cashier.userId)

        val key = "debt-pay-${UUID.randomUUID()}"
        val first = payDebt(cashier.token, transactionId, "80.00", "tunai", key)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(BigDecimal("120.00"), transactionReceivableRemaining(UUID.fromString(transactionId)))
        assertEquals("dp", transactionStatus(transactionId))
        assertEquals("PARTIAL", receivableStatus(receivableId))

        val replays = coroutineScope {
            (1..3).map { async { payDebt(cashier.token, transactionId, "80.00", "tunai", key) } }.awaitAll()
        }
        replays.forEach { replay ->
            assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
            assertEquals(checkoutDataId(first), checkoutDataId(replay))
        }
        assertEquals(1, receivablePaymentCount(receivableId))
        assertEquals(1, debtPaymentCount(transactionId))
        assertEquals(cashBefore.add(BigDecimal("80.00")), activeCashSystemCash(cashier.userId))

        val settled = payDebt(cashier.token, transactionId, "120.00", "transfer", "debt-pay-${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.OK, settled.status, settled.bodyAsText())
        assertEquals("lunas", transactionStatus(transactionId))
        assertEquals("PAID", receivableStatus(receivableId))
        // Transfer tidak masuk laci kas.
        assertEquals(cashBefore.add(BigDecimal("80.00")), activeCashSystemCash(cashier.userId))

        // Retry yang terlambat tetap menerima hasil sukses walau transaksi sudah lunas.
        val lateReplay = payDebt(cashier.token, transactionId, "80.00", "tunai", key)
        assertEquals(HttpStatusCode.OK, lateReplay.status, lateReplay.bodyAsText())
        assertEquals(2, receivablePaymentCount(receivableId))
    }

    @Test
    fun test_transaction_debt_payment_rejects_reused_key_for_different_payment() = testApplication {
        application { configureIntegrationApplication() }
        val cashier = loginAsCashier(loginAsOwner())
        val product = createProductFixture()
        val customerId = createReceivableCustomerFixture(BigDecimal("1000.00"))
        assertEquals(HttpStatusCode.Created, openSession(cashier.token).status)
        val transactionId = createDebtTransaction(cashier.token, product, customerId)
        val otherTransactionId = createDebtTransaction(cashier.token, product, customerId)
        val key = "debt-pay-${UUID.randomUUID()}"
        val paid = payDebt(cashier.token, transactionId, "80.00", "tunai", key)
        assertEquals(HttpStatusCode.OK, paid.status, paid.bodyAsText())
        val cashAfterPayment = activeCashSystemCash(cashier.userId)

        val conflicts = listOf(
            payDebt(cashier.token, transactionId, "70.00", "tunai", key),
            payDebt(cashier.token, transactionId, "80.00", "transfer", key),
            payDebt(cashier.token, otherTransactionId, "80.00", "tunai", key)
        )
        conflicts.forEach { conflict ->
            assertEquals(HttpStatusCode.BadRequest, conflict.status, conflict.bodyAsText())
            assertTrue(conflict.bodyAsText().contains("VALIDATION_ERROR"), conflict.bodyAsText())
            assertTrue(
                conflict.bodyAsText().contains("Idempotency key sudah dipakai untuk pembayaran lain"),
                conflict.bodyAsText()
            )
        }
        assertEquals(1, receivablePaymentCount(transactionReceivableId(transactionId)))
        assertEquals(0, receivablePaymentCount(transactionReceivableId(otherTransactionId)))
        assertEquals(1, debtPaymentCount(transactionId))
        assertEquals(0, debtPaymentCount(otherTransactionId))
        assertEquals(cashAfterPayment, activeCashSystemCash(cashier.userId))
        assertEquals(BigDecimal("120.00"), transactionReceivableRemaining(UUID.fromString(transactionId)))
    }

    @Test
    fun test_owner_discount_above_cashier_limit_needs_no_approval() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val cashier = loginAsCashier(ownerToken)
        val product = createProductFixture()
        // API hanya mengizinkan KASIR membuka sesi, jadi sesi OWNER dibuat langsung di database.
        insertOpenCashSession(ownerId(), openedAt = OffsetDateTime.now(ZoneOffset.UTC))
        assertEquals(HttpStatusCode.Created, openSession(cashier.token).status)

        val byCashier = discountCheckout(
            cashier.token, product, "PERCENTAGE", "50.00", amountPaid = "50.00",
            idempotencyKey = "discount-${UUID.randomUUID()}"
        )
        assertEquals(HttpStatusCode.Forbidden, byCashier.status, byCashier.bodyAsText())
        assertTrue(byCashier.bodyAsText().contains("MANAGER_APPROVAL_REQUIRED"), byCashier.bodyAsText())
        assertEquals(0, transactionCount())

        val preview = discountPreview(ownerToken, product, "PERCENTAGE", "50.00")
        assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
        val previewData = Json.parseToJsonElement(preview.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertFalse(previewData.getValue("approvalRequired").jsonPrimitive.content.toBoolean())

        val byOwner = discountCheckout(
            ownerToken, product, "PERCENTAGE", "50.00", amountPaid = "50.00",
            idempotencyKey = "discount-${UUID.randomUUID()}"
        )
        assertEquals(HttpStatusCode.Created, byOwner.status, byOwner.bodyAsText())
        val transactionId = UUID.fromString(checkoutTransactionId(byOwner))
        assertEquals(
            Triple(BigDecimal("100.00"), BigDecimal("50.00"), BigDecimal("50.00")),
            discountSnapshot(transactionId)
        )
        assertEquals(0, managerApprovalCount())
        assertEquals(
            "0",
            firstString("SELECT COUNT(*) FROM sales.transactions WHERE discount_manager_approval_id IS NOT NULL")
        )
        assertEquals(BigDecimal("9.00"), stockQuantity(product.id))
    }

    private suspend fun reserveInParallel(
        count: Int,
        userId: UUID,
        scope: CredentialScope,
        policy: PinRolePolicy,
        instantOf: (Int) -> Instant
    ): List<AttemptReservation> = coroutineScope {
        (1..count).map { index ->
            async(Dispatchers.IO) {
                CredentialAttemptGuard(SystemRepository(), Clock.fixed(instantOf(index), ZoneOffset.UTC))
                    .reserve(userId, scope, policy)
            }
        }.awaitAll()
    }

    private suspend fun ApplicationTestBuilder.resetPin(token: String, password: String, newPin: String): HttpResponse =
        client.post("/api/auth/reset-pin") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody("""{"currentPassword":"$password","newPin":"$newPin"}""")
        }

    private suspend fun ApplicationTestBuilder.syncOpenSession(
        cashier: ApprovalUserFixture,
        openedAt: OffsetDateTime,
        clientGeneratedId: String = "offline-open-${UUID.randomUUID()}"
    ): HttpResponse = client.post("/api/sales/sessions/sync/open") {
        contentType(ContentType.Application.Json)
        bearer(cashier.token)
        setBody(
            """{"clientGeneratedId":"$clientGeneratedId","deviceId":"$OFFLINE_DEVICE_ID","cashierUserId":"${cashier.userId}","openedAt":"$openedAt","startingCash":"100.00"}"""
        )
    }

    private suspend fun offlineSessionId(opened: HttpResponse): String {
        assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
        return Json.parseToJsonElement(opened.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("serverCashSessionId").jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.syncOfflineSale(
        cashier: ApprovalUserFixture,
        sessionId: String,
        product: ProductFixture,
        occurredAt: OffsetDateTime,
        price: String,
        tax: String? = null,
        rounding: String = "0.00"
    ): HttpResponse = client.post("/api/sales/checkout/sync") {
        contentType(ContentType.Application.Json)
        bearer(cashier.token)
        setBody(offlineSaleBody(cashier, sessionId, product, occurredAt, price, tax, rounding))
    }

    /** Satu item qty 1 dibayar tunai; [tax] diisi = snapshot pajak EXCLUDE 11%. */
    private fun offlineSaleBody(
        cashier: ApprovalUserFixture,
        sessionId: String,
        product: ProductFixture,
        occurredAt: OffsetDateTime,
        price: String,
        tax: String? = null,
        rounding: String = "0.00"
    ): String {
        val total = BigDecimal(price).add(BigDecimal(tax ?: "0.00")).add(BigDecimal(rounding))
        val taxFields = if (tax == null) "" else
            ""","taxEnabled":true,"taxMode":"EXCLUDE","taxRate":"11.00","taxableBase":"$price","taxAmount":"$tax""""
        return """{"clientGeneratedId":"offline-checkout-${UUID.randomUUID()}","deviceId":"$OFFLINE_DEVICE_ID","localTransactionCode":"LOCAL-GUARD","cashierUserId":"${cashier.userId}","cashSessionId":"$sessionId","paymentMethod":"tunai","subtotal":"$price","discount":"0.00","total":"$total","paidAmount":"$total","remainingAmount":"0.00","occurredAt":"$occurredAt","roundingAmount":"$rounding","items":[{"productId":"${product.id}","productNameSnapshot":"Offline Product","quantity":"1.00","priceAtTransaction":"$price","cogsAtTransaction":"50.00","discount":"0.00","subtotal":"$price"}]$taxFields}"""
    }

    /** Produk eceran 100 (HPP 50) dengan satuan sekunder berisi [factor] satuan dasar, dan sesi kas offline. */
    private suspend fun ApplicationTestBuilder.offlineUnitFixture(
        stock: String,
        factor: String = "12.0000",
        openOnline: Boolean = false
    ): OfflineUnitFixture {
        val cashier = loginAsCashier(loginAsOwner())
        val product = createProductFixture(BigDecimal(stock))
        val dusId = unitOtherThan(product.unitId)
        insertUnitConversion(product.id, dusId, product.unitId, factor)
        val openedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2)
        val sessionId = if (openOnline) {
            assertEquals(HttpStatusCode.Created, openSession(cashier.token).status)
            ""
        } else {
            offlineSessionId(syncOpenSession(cashier, openedAt))
        }
        return OfflineUnitFixture(cashier, sessionId, product, dusId, openedAt.plusMinutes(30))
    }

    private fun insertUnitConversion(productId: UUID, fromUnitId: UUID, toUnitId: UUID, factor: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO inventory.unit_conversions (product_id, from_unit_id, to_unit_id, factor) VALUES (?, ?, ?, ?)"
            ).use { statement ->
                statement.setObject(1, productId)
                statement.setObject(2, fromUnitId)
                statement.setObject(3, toUnitId)
                statement.setBigDecimal(4, BigDecimal(factor))
                statement.executeUpdate()
            }
        }
    }

    private fun unitOtherThan(vararg unitIds: UUID): UUID = UUID.fromString(
        firstString(
            "SELECT id::text FROM inventory.units WHERE id NOT IN (${unitIds.joinToString { "'$it'" }}) ORDER BY id LIMIT 1"
        )
    )

    private suspend fun ApplicationTestBuilder.putProductUnit(
        ownerToken: String,
        product: ProductFixture,
        secondaryUnitId: UUID?,
        factor: String?,
        baseUnitId: UUID = product.unitId,
        priceRetail: String = "100.00",
        priceContractor: String = "90.00"
    ) {
        val (categoryId, name) = firstString(
            "SELECT category_id || '|' || name FROM inventory.products WHERE id = '${product.id}'"
        ).split('|')
        val unitFields = if (secondaryUnitId == null) "" else
            ""","secondaryUnitId":"$secondaryUnitId","secondaryUnitFactor":"$factor""""
        val saved = client.put("/api/inventory/products/${product.id}") {
            contentType(ContentType.Application.Json)
            bearer(ownerToken)
            setBody(
                """{"categoryId":"$categoryId","baseUnitId":"$baseUnitId","name":"$name","priceBuy":"50.00","priceRetail":"$priceRetail","priceContractor":"$priceContractor","minStock":"0.00"$unitFields}"""
            )
        }
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
    }

    /** `faktor_lama|dari|ke` per baris, urut waktu. */
    private fun conversionHistory(productId: UUID): String = firstString(
        "SELECT COALESCE(string_agg(concat_ws('|', old_factor, from_unit_id, to_unit_id), ',' ORDER BY changed_at), '') " +
            "FROM inventory.unit_conversion_history WHERE product_id = '$productId'"
    )

    private fun unitName(unitId: UUID): String = firstString("SELECT name FROM inventory.units WHERE id = '$unitId'")

    private fun execute(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    private suspend fun ApplicationTestBuilder.syncOfflineBody(fixture: OfflineUnitFixture, body: String): HttpResponse =
        client.post("/api/sales/checkout/sync") {
            contentType(ContentType.Application.Json)
            bearer(fixture.cashier.token)
            setBody(body)
        }

    /** 409 dengan kode yang tepat, dan tidak ada stok, mutasi, transaksi, pembayaran, atau kas yang berubah. */
    private suspend fun ApplicationTestBuilder.assertOfflineConflict(
        fixture: OfflineUnitFixture,
        vararg lines: String,
        code: String = "OFFLINE_UNIT_CONFLICT",
        customerId: UUID? = null
    ): String {
        val before = salesStateSnapshot()
        val response = syncOfflineBody(fixture, fixture.saleBody(lines.toList(), customerId = customerId))
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Conflict, response.status, body)
        assertTrue(body.contains(code), body)
        assertTrue(body.contains("business rule"), body)
        assertEquals(before, salesStateSnapshot(), body)
        return body
    }

    private fun salesStateSnapshot(): String = firstString(
        """
        SELECT concat_ws('|',
            (SELECT md5(string_agg(s::text, ',' ORDER BY s.id)) FROM inventory.stock s),
            (SELECT COUNT(*) FROM inventory.stock_movements),
            (SELECT COUNT(*) FROM sales.transactions),
            (SELECT COUNT(*) FROM sales.transaction_items),
            (SELECT COUNT(*) FROM sales.payments),
            (SELECT md5(string_agg(c::text, ',' ORDER BY c.id)) FROM sales.cash_sessions c))
        """.trimIndent()
    )

    /** Sesi fixture dibuka 2 jam lalu. */
    private fun moveHistoryOutsideWindow(product: ProductFixture) {
        listOf("unit_conversion_history", "price_history").forEach { table ->
            execute("UPDATE inventory.$table SET changed_at = NOW() - INTERVAL '3 hours' WHERE product_id = '${product.id}'")
        }
    }

    private suspend fun offlineTransactionId(response: HttpResponse): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("serverTransactionId").jsonPrimitive.content

    private fun storedOfflineItem(transactionId: String): String = firstString(
        "SELECT concat_ws('|', quantity, price_at_transaction, cogs_at_transaction, gross_line_total, subtotal, unit_id) " +
            "FROM sales.transaction_items WHERE transaction_id = '$transactionId'"
    )

    private fun offlineItemAudit(transactionId: String) = Json.parseToJsonElement(
        firstString(
            "SELECT a.new_data::text FROM system.audit_logs a JOIN sales.transaction_items ti ON ti.id = a.record_id " +
                "WHERE ti.transaction_id = '$transactionId' AND a.table_name = 'transaction_items'"
        )
    ).jsonObject

    private suspend fun ApplicationTestBuilder.syncOfflineExpense(
        cashier: ApprovalUserFixture,
        sessionId: String,
        occurredAt: OffsetDateTime,
        clientGeneratedId: String = "offline-expense-${UUID.randomUUID()}"
    ): HttpResponse = client.post("/api/sales/cash-expenses/sync") {
        contentType(ContentType.Application.Json)
        bearer(cashier.token)
        setBody(
            """{"clientGeneratedId":"$clientGeneratedId","deviceId":"$OFFLINE_DEVICE_ID","cashierUserId":"${cashier.userId}","serverCashSessionId":"$sessionId","amount":"10.00","category":"Operasional","note":"Biaya offline","occurredAt":"$occurredAt"}"""
        )
    }

    private suspend fun ApplicationTestBuilder.syncOfflineClose(
        cashier: ApprovalUserFixture,
        sessionId: String,
        closedAt: OffsetDateTime,
        clientGeneratedId: String = "offline-close-${UUID.randomUUID()}"
    ): HttpResponse = client.post("/api/sales/sessions/sync/close") {
        contentType(ContentType.Application.Json)
        bearer(cashier.token)
        setBody(
            """{"deviceId":"$OFFLINE_DEVICE_ID","clientGeneratedId":"$clientGeneratedId","serverCashSessionId":"$sessionId","cashierUserId":"${cashier.userId}","closedAt":"$closedAt","actualCash":"100.00"}"""
        )
    }

    private fun insertOpenCashSession(
        userId: UUID,
        openedAt: OffsetDateTime,
        offlineSyncedAt: OffsetDateTime? = null
    ): String = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO sales.cash_sessions (user_id, opened_at, opening_cash, system_cash, offline_synced_at)
            VALUES (?, ?, 0, 0, ?) RETURNING id
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, openedAt)
            statement.setObject(3, offlineSyncedAt, Types.TIMESTAMP_WITH_TIMEZONE)
            statement.executeQuery().use { rows -> rows.next(); rows.getString(1) }
        }
    }

    private fun insertPriceHistory(product: ProductFixture, oldRetail: String, changedAt: OffsetDateTime) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO inventory.price_history (
                    product_id, changed_by, old_price_buy, new_price_buy, old_price_retail, new_price_retail,
                    old_price_contractor, new_price_contractor, changed_at
                ) VALUES (?, ?, 50, 50, ?, 100, 90, 90, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, product.id)
                statement.setObject(2, ownerId())
                statement.setBigDecimal(3, BigDecimal(oldRetail))
                statement.setObject(4, changedAt)
                statement.executeUpdate()
            }
        }
    }

    /** Pajak EXCLUDE 11% tanpa pembulatan sejak [updatedAt]; pengaturan semula selalu dipulihkan. */
    private suspend fun withTaxedStoreSettings(updatedAt: OffsetDateTime, block: suspend () -> Unit) {
        val original = firstString("SELECT to_jsonb(s)::text FROM system.store_settings s")
        try {
            writeStoreSettings(
                "tax_enabled = TRUE, tax_mode = 'EXCLUDE', tax_rate = 11.00, cash_rounding_unit = 0, " +
                    "cash_rounding_rule = 'NEAREST', updated_at = '$updatedAt', " +
                    "tax_settings_updated_at = '$updatedAt', rounding_settings_updated_at = '$updatedAt'"
            )
            block()
        } finally {
            val columns = "store_name, address, phone, receipt_header, receipt_footer, tax_enabled, tax_mode, tax_rate, " +
                "tax_label, store_npwp, cash_rounding_unit, cash_rounding_rule, updated_by, updated_at, " +
                "tax_settings_updated_at, rounding_settings_updated_at"
            writeStoreSettings(
                "($columns) = (SELECT $columns FROM jsonb_populate_record(" +
                    "NULL::system.store_settings, '${original.replace("'", "''")}'::jsonb))"
            )
        }
    }

    /** Trigger dimatikan sebentar (dalam transaksi) karena ia selalu menimpa kolom waktu. */
    private fun writeStoreSettings(assignments: String) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute("ALTER TABLE system.store_settings DISABLE TRIGGER USER")
                    assertEquals(1, statement.executeUpdate("UPDATE system.store_settings SET $assignments"))
                    statement.execute("ALTER TABLE system.store_settings ENABLE TRIGGER USER")
                }
                connection.commit()
            } catch (cause: Exception) {
                connection.rollback()
                throw cause
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /** UPDATE biasa dengan trigger aktif, meniru perubahan pengaturan di tengah sesi. */
    private fun updateStoreSettings(assignments: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                assertEquals(1, statement.executeUpdate("UPDATE system.store_settings SET $assignments"))
            }
        }
    }

    private fun inRolledBackTransaction(block: (Connection) -> Unit) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection)
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    /** Penjualan 2 x 100.00 yang seluruhnya hutang. */
    private suspend fun ApplicationTestBuilder.createDebtTransaction(
        token: String,
        product: ProductFixture,
        customerId: UUID
    ): String {
        val sale = checkoutWithCustomer(
            token, product, "2.00", "0.00", "debt-sale-${UUID.randomUUID()}", "hutang", customerId
        )
        assertEquals(HttpStatusCode.Created, sale.status, sale.bodyAsText())
        return checkoutTransactionId(sale)
    }

    private suspend fun ApplicationTestBuilder.payDebt(
        token: String,
        transactionId: String,
        amount: String,
        method: String,
        idempotencyKey: String
    ): HttpResponse = client.post("/api/sales/transactions/$transactionId/pay") {
        contentType(ContentType.Application.Json)
        bearer(token)
        setBody("""{"amount":"$amount","method":"$method","idempotencyKey":"$idempotencyKey"}""")
    }

    private fun transactionReceivableId(transactionId: String): String = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT id FROM receivable.receivables WHERE transaction_id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(transactionId))
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) }
        }
    }

    private fun debtPaymentCount(transactionId: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM sales.payments WHERE transaction_id = ? AND payment_source::text = 'RECEIVABLE'"
        ).use { statement ->
            statement.setObject(1, UUID.fromString(transactionId))
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private suspend fun ApplicationTestBuilder.loginTokens(username: String, password: String): TokenPair {
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonObject
        return TokenPair(
            access = data.getValue("token").jsonPrimitive.content,
            refresh = data.getValue("refreshToken").jsonPrimitive.content
        )
    }

    private suspend fun ApplicationTestBuilder.refreshTokens(refreshToken: String): HttpResponse =
        client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }

    private fun tokenVersion(userId: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT token_version FROM system.users WHERE id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
        }
    }

    private suspend fun ApplicationTestBuilder.discountPreview(
        token: String,
        product: ProductFixture,
        type: String,
        value: String,
        transactionDiscountType: String? = null,
        transactionDiscountValue: String? = null
    ): HttpResponse {
        val transactionDiscount = if (transactionDiscountType == null) "" else
            ",\"transactionDiscount\":{\"type\":\"$transactionDiscountType\",\"value\":\"$transactionDiscountValue\"}"
        return client.post("/api/sales/checkout/preview") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """{"items":[{"productId":"${product.id}","qty":"1.00","discountRequest":{"type":"$type","value":"$value"}}]$transactionDiscount}"""
            )
        }
    }

    private suspend fun ApplicationTestBuilder.discountCheckout(
        token: String,
        product: ProductFixture,
        itemDiscountType: String,
        itemDiscountValue: String,
        transactionDiscountType: String? = null,
        transactionDiscountValue: String? = null,
        amountPaid: String,
        idempotencyKey: String,
        checkoutAttemptId: UUID? = null,
        managerApprovalId: UUID? = null
    ): HttpResponse {
        val transactionDiscount = if (transactionDiscountType == null) "" else
            ",\"transactionDiscount\":{\"type\":\"$transactionDiscountType\",\"value\":\"$transactionDiscountValue\"}"
        val attempt = checkoutAttemptId?.let { ",\"checkoutAttemptId\":\"$it\"" }.orEmpty()
        val approval = managerApprovalId?.let { ",\"managerApprovalId\":\"$it\"" }.orEmpty()
        return client.post("/api/sales/checkout") {
            contentType(ContentType.Application.Json)
            bearer(token)
            setBody(
                """{"idempotencyKey":"$idempotencyKey","items":[{"productId":"${product.id}","qty":"1.00","discountRequest":{"type":"$itemDiscountType","value":"$itemDiscountValue"}}],"paymentMethod":"tunai","amountPaid":"$amountPaid"$transactionDiscount$attempt$approval}"""
            )
        }
    }

    private fun discountSnapshot(transactionId: UUID): Triple<BigDecimal, BigDecimal, BigDecimal> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT gross_subtotal,total_discount_amount,total FROM sales.transactions WHERE id=?"
            ).use { statement ->
                statement.setObject(1, transactionId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    Triple(rows.getBigDecimal(1), rows.getBigDecimal(2), rows.getBigDecimal(3))
                }
            }
        }

    private fun transactionReceivableRemaining(transactionId: UUID): BigDecimal = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT amount-paid_amount FROM receivable.receivables WHERE transaction_id=?"
        ).use { statement ->
            statement.setObject(1, transactionId)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getBigDecimal(1)
            }
        }
    }

    private fun installFailingDiscountCheckoutTrigger() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE OR REPLACE FUNCTION sales.fn_test_fail_discount_checkout()
                    RETURNS TRIGGER LANGUAGE plpgsql AS ${'$'}${'$'}
                    BEGIN
                        RAISE EXCEPTION 'forced discount checkout rollback';
                    END;
                    ${'$'}${'$'}
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TRIGGER trg_test_fail_discount_checkout
                    BEFORE INSERT ON sales.transaction_items
                    FOR EACH ROW EXECUTE FUNCTION sales.fn_test_fail_discount_checkout()
                    """.trimIndent()
                )
            }
        }
    }

    private fun removeFailingDiscountCheckoutTrigger() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TRIGGER IF EXISTS trg_test_fail_discount_checkout ON sales.transaction_items")
                statement.execute("DROP FUNCTION IF EXISTS sales.fn_test_fail_discount_checkout()")
            }
        }
    }

    private fun discountAttemptConsumed(id: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT consumed_at IS NOT NULL FROM sales.checkout_discount_attempts WHERE id=?"
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getBoolean(1)
            }
        }
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

    /** Sesi kas hanya boleh dibuka role KASIR, jadi alur POS di test memakai kasir khusus ini. */
    private suspend fun ApplicationTestBuilder.loginAsCashier(ownerToken: String): ApprovalUserFixture =
        createAndLoginApprovalUser(ownerToken, Role.KASIR, "pos_cashier")

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

    private suspend fun ApplicationTestBuilder.createAndLoginApprovalUser(
        ownerToken: String,
        role: String,
        usernamePrefix: String
    ): ApprovalUserFixture {
        val roleId = dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT id FROM system.roles WHERE name::text = ?").use { statement ->
                statement.setString(1, role)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    rows.getObject(1, UUID::class.java)
                }
            }
        }
        val username = "${usernamePrefix}_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val createResponse = client.post("/api/system/users") {
            contentType(ContentType.Application.Json)
            bearer(ownerToken)
            setBody(
                """
                {
                  "name":"Integration Approval User",
                  "username":"$username",
                  "password":"$APPROVAL_TEST_PASSWORD",
                  "pin":"$APPROVAL_TEST_PIN",
                  "roleId":"$roleId"
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, createResponse.status, createResponse.bodyAsText())
        val userId = UUID.fromString(
            Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject.getValue("data").jsonObject
                .getValue("id").jsonPrimitive.content
        )
        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$APPROVAL_TEST_PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status, loginResponse.bodyAsText())
        val token = Json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("token").jsonPrimitive.content
        return ApprovalUserFixture(userId, username, APPROVAL_TEST_PIN, token)
    }

    private suspend fun ApplicationTestBuilder.requestManagerApproval(
        token: String?,
        resourceId: UUID,
        approverUsername: String,
        approverPin: String,
        action: String = "VOID_TRANSACTION",
        resourceType: String = "TRANSACTION"
    ): HttpResponse = client.post("/api/system/manager-approvals") {
        contentType(ContentType.Application.Json)
        token?.let { bearer(it) }
        setBody(
            """
            {
              "action":"$action",
              "resourceType":"$resourceType",
              "resourceId":"$resourceId",
              "approverUsername":"$approverUsername",
              "approverPin":"$approverPin"
            }
            """.trimIndent()
        )
    }

    private suspend fun managerApprovalId(response: HttpResponse): UUID {
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return UUID.fromString(
            Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonObject
                .getValue("approvalId").jsonPrimitive.content
        )
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

    private suspend fun ApplicationTestBuilder.checkoutWithCustomer(
        token: String,
        product: ProductFixture,
        quantity: String,
        amountPaid: String,
        idempotencyKey: String,
        paymentMethod: String,
        customerId: UUID
    ): HttpResponse = client.post("/api/sales/checkout") {
        contentType(ContentType.Application.Json)
        bearer(token)
        setBody(
            """
            {
              "customerId":"$customerId",
              "items":[{"productId":"${product.id}","qty":"$quantity","discount":"0.00"}],
              "paymentMethod":"$paymentMethod",
              "amountPaid":"$amountPaid",
              "idempotencyKey":"$idempotencyKey"
            }
            """.trimIndent()
        )
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
        reason: String,
        managerApprovalId: UUID? = null
    ): HttpResponse = client.post("/api/sales/transactions/$transactionId/void") {
        contentType(ContentType.Application.Json)
        bearer(token)
        val approvalJson = managerApprovalId?.let { ",\"managerApprovalId\":\"$it\"" }.orEmpty()
        setBody("""{"idempotencyKey":"$idempotencyKey","reason":"$reason"$approvalJson}""")
    }

    private suspend fun voidReplay(response: HttpResponse): Boolean =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("idempotentReplay").jsonPrimitive.content.toBoolean()

    private suspend fun ApplicationTestBuilder.refundTransaction(
        token: String,
        transactionId: String,
        idempotencyKey: String,
        reason: String,
        disposition: String = "RETURN_TO_STOCK",
        managerApprovalId: UUID? = null
    ): HttpResponse = client.post("/api/sales/transactions/$transactionId/refund") {
        contentType(ContentType.Application.Json)
        bearer(token)
        val approvalJson = managerApprovalId?.let { ",\"managerApprovalId\":\"$it\"" }.orEmpty()
        setBody(
            """{"idempotencyKey":"$idempotencyKey","reason":"$reason","returnDisposition":"$disposition"$approvalJson}"""
        )
    }

    private suspend fun refundReplay(response: HttpResponse): Boolean =
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

    private fun openSessionCount(userId: UUID): Int {
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM sales.cash_sessions
                WHERE user_id = ? AND closed_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
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

    private fun transactionRefundCount(transactionId: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM sales.transaction_refunds WHERE transaction_id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(transactionId))
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    private fun refundFinancialAmount(transactionId: String): BigDecimal = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT refunded_amount FROM sales.transaction_refunds WHERE transaction_id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(transactionId))
            statement.executeQuery().use { result -> assertTrue(result.next()); result.getBigDecimal(1) }
        }
    }

    private fun refundCompensationTotal(transactionId: String): BigDecimal = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COALESCE(SUM(amount), 0) FROM sales.payments WHERE transaction_id = ? AND transaction_refund_id IS NOT NULL"
        ).use { statement ->
            statement.setObject(1, UUID.fromString(transactionId))
            statement.executeQuery().use { result -> result.next(); result.getBigDecimal(1) }
        }
    }

    private fun refundCompensationCount(transactionId: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM sales.payments WHERE transaction_id = ? AND transaction_refund_id IS NOT NULL"
        ).use { statement ->
            statement.setObject(1, UUID.fromString(transactionId))
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    private fun activeCashSystemCash(userId: UUID): BigDecimal = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT system_cash FROM sales.cash_sessions WHERE user_id = ? AND closed_at IS NULL"
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result -> assertTrue(result.next()); result.getBigDecimal(1) }
        }
    }

    private fun receivableActive(transactionId: String): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT is_active FROM receivable.receivables WHERE transaction_id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(transactionId))
            statement.executeQuery().use { result -> assertTrue(result.next()); result.getBoolean(1) }
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

    private fun voidAuditCount(transactionId: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM system.audit_logs
            WHERE schema_name = 'sales'
              AND table_name = 'transaction_voids'
              AND new_data ->> 'transactionId' = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, transactionId.toString())
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    private fun voidAuditMetadata(transactionId: UUID): String = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT new_data::text
            FROM system.audit_logs
            WHERE schema_name = 'sales'
              AND table_name = 'transaction_voids'
              AND new_data ->> 'transactionId' = ?
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, transactionId.toString())
            statement.executeQuery().use { result ->
                assertTrue(result.next(), "Audit Void harus tersedia")
                result.getString(1)
            }
        }
    }

    private fun managerApprovalCount(): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM system.manager_approvals").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun managerApprovalStatus(id: UUID): String = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT status FROM system.manager_approvals WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getString(1)
            }
        }
    }

    private fun expireManagerApproval(id: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE system.manager_approvals SET created_at = NOW() - INTERVAL '10 minutes', expires_at = NOW() - INTERVAL '5 minutes' WHERE id = ?"
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeUpdate()
            }
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

    private fun insertTransactionWithInvalidDiscount(product: ProductFixture, userId: UUID) {
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
                    statement.setObject(1, activeSessionId(userId))
                    statement.setObject(2, userId)
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

    private fun activeSessionId(userId: UUID): UUID {
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id
                FROM sales.cash_sessions
                WHERE user_id = ? AND closed_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
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

    private data class ApprovalUserFixture(
        val userId: UUID,
        val username: String,
        val pin: String,
        val token: String
    )

    private data class ProductFixture(
        val id: UUID,
        val unitId: UUID
    )

    private data class OfflineUnitFixture(
        val cashier: ApprovalUserFixture,
        val sessionId: String,
        val product: ProductFixture,
        val dusId: UUID,
        val occurredAt: OffsetDateTime
    ) {
        /** Bawaan: 2 dus @ 1.200, isi 12. `null` = field tidak dikirim (klien lama). */
        fun dusLine(
            quantity: String = "2.00",
            price: String = "1200.00",
            unitId: String? = dusId.toString(),
            baseQuantity: String? = "24.00"
        ) = line(quantity, price, unitId, baseQuantity)

        fun pcsLine(quantity: String, price: String = "100.00", baseQuantity: String = quantity) =
            line(quantity, price, product.unitId.toString(), baseQuantity)

        private fun line(quantity: String, price: String, unitId: String?, baseQuantity: String?): String {
            val subtotal = BigDecimal(price).multiply(BigDecimal(quantity)).setScale(2)
            val unitFields = listOfNotNull(
                unitId?.let { "\"unitId\":\"$it\"" },
                baseQuantity?.let { "\"baseQuantity\":\"$it\"" }
            ).joinToString("") { ",$it" }
            return """{"productId":"${product.id}","productNameSnapshot":"Offline Product","quantity":"$quantity","priceAtTransaction":"$price","cogsAtTransaction":"0.00","discount":"0.00","subtotal":"$subtotal"$unitFields}"""
        }

        fun saleBody(
            lines: List<String>,
            key: String = "offline-unit-${UUID.randomUUID()}",
            customerId: UUID? = null
        ): String {
            val customer = customerId?.let { ""","customerId":"$it"""" }.orEmpty()
            val total = lines.sumOf { BigDecimal(Json.parseToJsonElement(it).jsonObject.getValue("subtotal").jsonPrimitive.content) }
            return """{"clientGeneratedId":"$key","deviceId":"$OFFLINE_DEVICE_ID","localTransactionCode":"LOCAL-UNIT","cashierUserId":"${cashier.userId}","cashSessionId":"$sessionId","paymentMethod":"tunai","subtotal":"$total","discount":"0.00","total":"$total","paidAmount":"$total","remainingAmount":"0.00","occurredAt":"$occurredAt"$customer,"items":[${lines.joinToString(",")}]}"""
        }
    }

    private data class TokenPair(val access: String, val refresh: String)

    private companion object {
        const val OFFLINE_DEVICE_ID = "integration-offline-device"
        const val APPROVAL_TEST_PASSWORD = "Integration-Approval-789!"
        const val APPROVAL_TEST_PIN = "846291"
    }
}
