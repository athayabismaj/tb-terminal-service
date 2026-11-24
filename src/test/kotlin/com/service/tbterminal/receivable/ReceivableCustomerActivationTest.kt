package com.service.tbterminal.receivable

import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.plugins.configureSerialization
import com.service.tbterminal.plugins.configureStatusPages
import com.service.tbterminal.shared.AuthenticatedUserPrincipal
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.system.SystemRepository
import com.service.tbterminal.system.SystemService
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

class ReceivableCustomerActivationTest {

    @BeforeTest
    fun resetKoinBefore() = stopKoin()

    @AfterTest
    fun resetKoinAfter() = stopKoin()

    @Test
    fun `activateCustomer reactivates inactive customer and is idempotent when active`() = runBlocking {
        val id = UUID.randomUUID()
        val repo = FakeCustomerActivationRepository(
            customers = mutableMapOf(
                id to sampleCustomerResponse(id = id, name = "Budi Santoso", isActive = false)
            )
        )
        val service = ReceivableService(repo)

        val reactivated = service.activateCustomer(id.toString())
        assertTrue(reactivated.isActive)
        assertEquals(1, repo.activateCalls)

        // Already active -> idempotent without extra update
        val idempotent = service.activateCustomer(id.toString())
        assertTrue(idempotent.isActive)
        assertEquals(1, repo.activateCalls)
    }

    @Test
    fun `activateCustomer rejects invalid uuid missing customer and active name conflict`(): Unit = runBlocking {
        val inactiveId = UUID.randomUUID()
        val activeDuplicateId = UUID.randomUUID()
        val repo = FakeCustomerActivationRepository(
            customers = mutableMapOf(
                inactiveId to sampleCustomerResponse(id = inactiveId, name = "Toko Maju", isActive = false),
                activeDuplicateId to sampleCustomerResponse(id = activeDuplicateId, name = "Toko Maju", isActive = true)
            )
        )
        val service = ReceivableService(repo)

        assertFailsWith<ValidationException> {
            service.activateCustomer("not-a-uuid")
        }
        assertFailsWith<NotFoundException> {
            service.activateCustomer(UUID.randomUUID().toString())
        }
        assertFailsWith<ValidationException> {
            service.activateCustomer(inactiveId.toString())
        }
        Unit
    }

    @Test
    fun `getCustomers and getCustomerById support status all active inactive and includeInactive`() = runBlocking {
        val activeId = UUID.randomUUID()
        val inactiveId = UUID.randomUUID()
        val repo = FakeCustomerActivationRepository(
            customers = mutableMapOf(
                activeId to sampleCustomerResponse(id = activeId, name = "Andi Aktif", isActive = true),
                inactiveId to sampleCustomerResponse(id = inactiveId, name = "Budi Nonaktif", isActive = false)
            )
        )
        val service = ReceivableService(repo)

        val defaultActive = service.getCustomers(1, 20, null)
        assertEquals(listOf("Andi Aktif"), defaultActive.data.map { it.name })

        val onlyActive = service.getCustomers(1, 20, null, status = "active")
        assertEquals(listOf("Andi Aktif"), onlyActive.data.map { it.name })

        val onlyInactive = service.getCustomers(1, 20, null, status = "inactive")
        assertEquals(listOf("Budi Nonaktif"), onlyInactive.data.map { it.name })

        val allCustomers = service.getCustomers(1, 20, null, status = "all")
        assertEquals(2, allCustomers.data.size)

        assertFailsWith<ValidationException> {
            service.getCustomers(1, 20, null, status = "invalid")
        }

        assertFailsWith<NotFoundException> {
            service.getCustomerById(inactiveId.toString(), includeInactive = false)
        }
        val inactiveDetail = service.getCustomerById(inactiveId.toString(), includeInactive = true)
        assertFalse(inactiveDetail.isActive)
        assertEquals("Budi Nonaktif", inactiveDetail.name)
    }

    @Test
    fun `patch activate route allows owner and admin rejects kasir and returns 404 when missing`() = testApplication {
        Database.connect(
            url = "jdbc:h2:mem:customer_activation_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        val customerId = UUID.randomUUID()
        val repo = FakeCustomerActivationRepository(
            customers = mutableMapOf(
                customerId to sampleCustomerResponse(id = customerId, name = "Kontraktor Jaya", isActive = false)
            )
        )
        val service = ReceivableService(repo)
        val systemService = SystemService(SystemRepository())

        application {
            install(Koin) {
                modules(
                    module {
                        single { service }
                        single { systemService }
                    }
                )
            }
            configureSerialization()
            configureStatusPages()
            install(Authentication) {
                bearer("jwt-auth") {
                    authenticate { credential ->
                        when (credential.token) {
                            "owner-token" -> AuthenticatedUserPrincipal(UUID.randomUUID(), "owner", "owner", 1)
                            "admin-token" -> AuthenticatedUserPrincipal(UUID.randomUUID(), "admin", "admin", 1)
                            "kasir-token" -> AuthenticatedUserPrincipal(UUID.randomUUID(), "kasir", "kasir", 1)
                            else -> null
                        }
                    }
                }
            }
            receivableRoutes()
        }

        // Kasir must be rejected with 403 Forbidden
        val kasirRes = client.patch("/api/receivable/customers/$customerId/activate") {
            header(HttpHeaders.Authorization, "Bearer kasir-token")
        }
        assertEquals(HttpStatusCode.Forbidden, kasirRes.status)

        // Owner activates successfully with 200 OK
        val ownerRes = client.patch("/api/receivable/customers/$customerId/activate") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
        }
        assertEquals(HttpStatusCode.OK, ownerRes.status)
        val ownerBody = ownerRes.bodyAsText()
        assertTrue(ownerBody.contains("\"success\":true"))
        assertTrue(ownerBody.contains("\"isActive\":true"))

        // Admin idempotent call succeeds with 200 OK
        val adminRes = client.patch("/api/receivable/customers/$customerId/activate") {
            header(HttpHeaders.Authorization, "Bearer admin-token")
        }
        assertEquals(HttpStatusCode.OK, adminRes.status)

        // Missing ID returns 404 Not Found
        val missingRes = client.patch("/api/receivable/customers/${UUID.randomUUID()}/activate") {
            header(HttpHeaders.Authorization, "Bearer owner-token")
        }
        assertEquals(HttpStatusCode.NotFound, missingRes.status)
    }

    private fun sampleCustomerResponse(id: UUID, name: String, isActive: Boolean) = CustomerResponse(
        id = id.toString(),
        name = name,
        phone = "081234567890",
        address = "Jl. Sudirman No. 45",
        isContractor = true,
        creditLimit = BigDecimal("5000000"),
        paymentTermDays = 14,
        isActive = isActive,
        createdAt = "2026-01-10T08:00:00Z",
        updatedAt = "2026-02-15T10:30:00Z"
    )
}

private class FakeCustomerActivationRepository(
    private val customers: MutableMap<UUID, CustomerResponse>
) : ReceivableRepository {
    var activateCalls = 0

    override suspend fun getCustomerById(id: UUID): CustomerResponse? =
        customers[id]?.takeIf { it.isActive }

    override suspend fun getCustomerByIdIncludingInactive(id: UUID): CustomerResponse? =
        customers[id]

    override suspend fun getCustomerByName(name: String): CustomerResponse? =
        customers.values.firstOrNull { it.isActive && it.name.equals(name, ignoreCase = true) }

    override suspend fun activateCustomer(id: UUID): Boolean {
        activateCalls++
        val existing = customers[id] ?: return false
        customers[id] = existing.copy(isActive = true)
        return true
    }

    override suspend fun softDeleteCustomer(id: UUID): Boolean {
        val existing = customers[id] ?: return false
        customers[id] = existing.copy(isActive = false)
        return true
    }

    override suspend fun getPaginatedCustomers(
        page: Int,
        limit: Int,
        search: String?,
        status: String?
    ): PaginatedResponse<CustomerResponse> {
        val filtered = customers.values
            .filter { customer ->
                when (status?.lowercase()) {
                    "all" -> true
                    "inactive" -> !customer.isActive
                    else -> customer.isActive
                }
            }
            .filter { customer ->
                search.isNullOrBlank() ||
                    customer.name.contains(search, ignoreCase = true) ||
                    (customer.phone?.contains(search, ignoreCase = true) == true)
            }
            .sortedBy { it.name }
        return PaginatedResponse(
            data = filtered,
            total = filtered.size.toLong(),
            page = page,
            limit = limit,
            totalPages = 1
        )
    }

    override suspend fun createCustomer(
        name: String,
        phone: String?,
        address: String?,
        isContractor: Boolean,
        creditLimit: BigDecimal,
        paymentTermDays: Int
    ): UUID = unsupported()

    override suspend fun updateCustomer(
        id: UUID,
        name: String,
        phone: String?,
        address: String?,
        isContractor: Boolean,
        creditLimit: BigDecimal,
        paymentTermDays: Int
    ): Boolean = unsupported()

    override suspend fun getPaginatedReceivables(
        page: Int,
        limit: Int,
        customerId: UUID?,
        status: ReceivableStatus?,
        dueFilter: ReceivableDueFilter,
        dueFrom: LocalDate?,
        dueTo: LocalDate?
    ): PaginatedResponse<ReceivableResponse> = unsupported()

    override suspend fun getReceivableById(id: UUID): ReceivableResponse? = unsupported()
    override suspend fun getReceivableForUpdate(id: UUID): ReceivableForUpdateRow? = unsupported()

    override suspend fun createStandaloneReceivable(
        userId: UUID,
        customerId: UUID,
        amount: BigDecimal,
        debtDate: LocalDate,
        dueDate: LocalDate,
        legacyInvoiceNumber: String?,
        source: ReceivableSource,
        notes: String?
    ): ReceivableResponse = unsupported()

    override suspend fun getCustomerSummaries(
        page: Int,
        limit: Int,
        dueFilter: ReceivableDueFilter
    ): PaginatedResponse<CustomerReceivableSummaryResponse> = unsupported()

    override suspend fun getPaginatedPayments(
        page: Int,
        limit: Int,
        receivableId: UUID?,
        customerId: UUID?,
        method: RecPaymentMethod?,
        userId: UUID?,
        customerSearch: String?,
        receiverSearch: String?,
        status: ReceivableStatus?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?
    ): PaginatedResponse<PaymentHistoryResponse> = unsupported()

    override suspend fun getPaymentReceipt(paymentId: UUID): PaymentHistoryResponse? = unsupported()

    override suspend fun insertPaymentAndUpdateReceivable(
        receivableId: UUID,
        userId: UUID,
        paymentAmount: BigDecimal,
        method: RecPaymentMethod,
        reference: String?,
        notes: String?,
        idempotencyKey: String
    ): PaymentResponse = unsupported()

    override suspend fun reversePayment(
        paymentId: UUID,
        userId: UUID,
        idempotencyKey: String,
        reason: String
    ): PaymentResponse = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Not used in test")
}
