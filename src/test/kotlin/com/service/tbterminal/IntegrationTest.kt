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
import com.service.tbterminal.system.ManagerApprovalAction
import com.service.tbterminal.system.ManagerApprovalResourceType
import com.service.tbterminal.system.ManagerApprovalScope
import com.service.tbterminal.system.ManagerApprovalService
import com.service.tbterminal.shared.ManagerApprovalException
import com.service.tbterminal.shared.Role
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
import kotlin.test.assertFalse
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
                statement.execute("TRUNCATE TABLE sales.checkout_discount_attempts CASCADE")
                statement.execute("TRUNCATE TABLE system.manager_approvals CASCADE")
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
    fun test_full_refund_should_compensate_cash_restore_stock_and_be_idempotent() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture()
        assertTrue(openSession(token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(token, product, "2.00", "200.00", "checkout-${UUID.randomUUID()}")
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
        assertEquals(BigDecimal("100000.00"), activeCashSystemCash(ownerId()))

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
        val product = createProductFixture()
        assertTrue(openSession(token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
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
        val product = createProductFixture()
        val customerId = createReceivableCustomerFixture(BigDecimal("1000.00"))
        assertTrue(openSession(token).status.value in 200..299)

        val debtId = checkoutTransactionId(
            checkoutWithCustomer(
                token, product, "1.00", "0.00", "checkout-${UUID.randomUUID()}", "hutang", customerId
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
                token, product, "1.00", "30.00", "checkout-${UUID.randomUUID()}", "dp", customerId
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
        val product = createProductFixture()
        assertTrue(openSession(token).status.value in 200..299)
        val transactionId = checkoutTransactionId(
            checkout(token, product, "2.00", "200.00", "checkout-${UUID.randomUUID()}")
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
    fun test_admin_voids_directly_while_cashier_requires_and_consumes_scoped_approval() = testApplication {
        application { configureIntegrationApplication() }
        val ownerToken = loginAsOwner()
        val admin = createAndLoginApprovalUser(ownerToken, Role.ADMIN, "void_direct_admin")
        val cashier = createAndLoginApprovalUser(ownerToken, Role.KASIR, "void_cashier")
        val product = createProductFixture()

        assertTrue(openSession(admin.token).status.value in 200..299)
        val adminTransactionId = checkoutTransactionId(
            checkout(admin.token, product, "1.00", "100.00", "checkout-${UUID.randomUUID()}")
        )
        val adminVoid = voidTransaction(
            admin.token,
            adminTransactionId,
            "void-${UUID.randomUUID()}",
            "Admin membatalkan transaksi"
        )
        assertEquals(HttpStatusCode.OK, adminVoid.status, adminVoid.bodyAsText())
        assertFalse(adminVoid.bodyAsText().contains("managerApprovalId\":\""), adminVoid.bodyAsText())

        assertTrue(openSession(cashier.token).status.value in 200..299)
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

        responses.take(5).forEach { response ->
            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        }
        assertEquals(HttpStatusCode.TooManyRequests, responses.last().status, responses.last().bodyAsText())
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

    @Test
    fun test_discount_snapshots_refund_and_analytics_use_net_amount() = testApplication {
        application { configureIntegrationApplication() }
        val token = loginAsOwner()
        val product = createProductFixture()
        assertEquals(HttpStatusCode.Created, openSession(token).status)

        val response = discountCheckout(
            token = token,
            product = product,
            itemDiscountType = "PERCENTAGE",
            itemDiscountValue = "10.00",
            transactionDiscountType = "FIXED_AMOUNT",
            transactionDiscountValue = "10.00",
            amountPaid = "80.00",
            idempotencyKey = "discount-${UUID.randomUUID()}"
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
        val token = loginAsOwner()
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

    private suspend fun ApplicationTestBuilder.discountPreview(
        token: String,
        product: ProductFixture,
        type: String,
        value: String
    ): HttpResponse = client.post("/api/sales/checkout/preview") {
        contentType(ContentType.Application.Json)
        bearer(token)
        setBody(
            """{"items":[{"productId":"${product.id}","qty":"1.00","discountRequest":{"type":"$type","value":"$value"}}]}"""
        )
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

    private companion object {
        const val APPROVAL_TEST_PASSWORD = "Integration-Approval-789!"
        const val APPROVAL_TEST_PIN = "846291"
    }
}
