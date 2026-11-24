package com.service.tbterminal.purchasing

import com.service.tbterminal.inventory.InventoryRepositoryImpl
import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PurchasingSupplierActivationTest {

    @Test
    fun `activateSupplier reactivates inactive supplier and is idempotent when active`() = runBlocking {
        val id = UUID.randomUUID()
        val repo = FakeSupplierActivationRepository(
            suppliers = mutableMapOf(
                id to sampleSupplierResponse(id = id, name = "PT Semen Nusantara", isActive = false)
            )
        )
        val service = PurchasingService(repo, InventoryRepositoryImpl())

        val reactivated = service.activateSupplier(id.toString())
        assertTrue(reactivated.isActive)
        assertEquals(1, repo.activateCalls)

        val idempotent = service.activateSupplier(id.toString())
        assertTrue(idempotent.isActive)
        assertEquals(1, repo.activateCalls)
    }

    @Test
    fun `activateSupplier rejects invalid uuid missing supplier and active name conflict`(): Unit = runBlocking {
        val inactiveId = UUID.randomUUID()
        val activeDuplicateId = UUID.randomUUID()
        val repo = FakeSupplierActivationRepository(
            suppliers = mutableMapOf(
                inactiveId to sampleSupplierResponse(id = inactiveId, name = "CV Besi Jaya", isActive = false),
                activeDuplicateId to sampleSupplierResponse(id = activeDuplicateId, name = "CV Besi Jaya", isActive = true)
            )
        )
        val service = PurchasingService(repo, InventoryRepositoryImpl())

        assertFailsWith<ValidationException> {
            service.activateSupplier("not-a-uuid")
        }
        assertFailsWith<NotFoundException> {
            service.activateSupplier(UUID.randomUUID().toString())
        }
        assertFailsWith<ValidationException> {
            service.activateSupplier(inactiveId.toString())
        }
        Unit
    }

    @Test
    fun `getSuppliers and getSupplierById support status all active inactive and includeInactive`() = runBlocking {
        val activeId = UUID.randomUUID()
        val inactiveId = UUID.randomUUID()
        val repo = FakeSupplierActivationRepository(
            suppliers = mutableMapOf(
                activeId to sampleSupplierResponse(id = activeId, name = "Aktif Supplier", isActive = true),
                inactiveId to sampleSupplierResponse(id = inactiveId, name = "Nonaktif Supplier", isActive = false)
            )
        )
        val service = PurchasingService(repo, InventoryRepositoryImpl())

        assertEquals(listOf("Aktif Supplier"), service.getSuppliers(1, 10, null, null).data.map { it.name })
        assertEquals(listOf("Aktif Supplier"), service.getSuppliers(1, 10, null, "active").data.map { it.name })
        assertEquals(listOf("Nonaktif Supplier"), service.getSuppliers(1, 10, null, "inactive").data.map { it.name })
        assertEquals(
            listOf("Aktif Supplier", "Nonaktif Supplier"),
            service.getSuppliers(1, 10, null, "all").data.map { it.name }
        )

        assertFailsWith<NotFoundException> {
            service.getSupplierById(inactiveId.toString(), includeInactive = false)
        }
        assertEquals(
            "Nonaktif Supplier",
            service.getSupplierById(inactiveId.toString(), includeInactive = true).name
        )
    }
}

private fun sampleSupplierResponse(
    id: UUID,
    name: String,
    isActive: Boolean
) = SupplierResponse(
    id = id.toString(),
    name = name,
    phone = "081234567890",
    address = "Jl. Industri No. 1",
    paymentTermDays = 30,
    isActive = isActive,
    createdAt = "2026-01-01T00:00:00Z",
    updatedAt = "2026-01-01T00:00:00Z"
)

private class FakeSupplierActivationRepository(
    val suppliers: MutableMap<UUID, SupplierResponse> = mutableMapOf()
) : PurchasingRepository {
    var activateCalls = 0

    override suspend fun getPaginatedSuppliers(
        page: Int,
        limit: Int,
        search: String?,
        status: String?
    ): PaginatedResponse<SupplierResponse> {
        val normalizedStatus = status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val filtered = suppliers.values
            .filter { supplier ->
                when (normalizedStatus) {
                    null, "active" -> supplier.isActive
                    "inactive" -> !supplier.isActive
                    "all" -> true
                    else -> throw ValidationException("Status tidak valid")
                }
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

    override suspend fun getSupplierById(id: UUID): SupplierResponse? =
        suppliers[id]?.takeIf { it.isActive }

    override suspend fun getSupplierByIdIncludingInactive(id: UUID): SupplierResponse? =
        suppliers[id]

    override suspend fun getSupplierByName(name: String): SupplierResponse? =
        suppliers.values.firstOrNull { it.isActive && it.name.equals(name, ignoreCase = true) }

    override suspend fun createSupplier(name: String, phone: String?, address: String?, paymentTermDays: Int): UUID =
        error("unused")

    override suspend fun updateSupplier(id: UUID, name: String, phone: String?, address: String?, paymentTermDays: Int): Boolean =
        error("unused")

    override suspend fun softDeleteSupplier(id: UUID): Boolean = error("unused")

    override suspend fun activateSupplier(id: UUID): Boolean {
        activateCalls++
        val current = suppliers[id] ?: return false
        suppliers[id] = current.copy(isActive = true)
        return true
    }

    override suspend fun executePurchase(
        userId: UUID,
        supplierId: UUID,
        invoiceNo: String?,
        resolvedItems: List<ResolvedPurchaseItem>,
        calculatedTotal: BigDecimal,
        paymentMethod: PurchasePaymentMethod,
        amountPaid: BigDecimal,
        notes: String?,
        dueDays: Int
    ): PurchaseResponse = error("unused")

    override suspend fun getPaginatedPurchases(
        page: Int,
        limit: Int,
        supplierId: UUID?,
        startDate: String?,
        endDate: String?
    ): PaginatedResponse<PurchaseSummary> = error("unused")

    override suspend fun getPurchaseById(id: UUID): PurchaseResponse? = error("unused")

    override suspend fun getPaginatedPayables(
        page: Int,
        limit: Int,
        supplierId: UUID?,
        status: PayableStatus?
    ): PaginatedResponse<PayableResponse> = error("unused")

    override suspend fun getPayableById(id: UUID): PayableResponse? = error("unused")
    override suspend fun getPayableByPurchaseId(purchaseId: UUID): PayableResponse? = error("unused")
    override suspend fun getPayableForUpdate(id: UUID): PayableForUpdateRow? = error("unused")

    override suspend fun insertPaymentAndUpdatePayable(
        payableId: UUID,
        userId: UUID,
        paymentAmount: BigDecimal,
        method: PurchasePaymentMethod,
        reference: String?,
        notes: String?
    ): SupplierPaymentResponse = error("unused")
}
