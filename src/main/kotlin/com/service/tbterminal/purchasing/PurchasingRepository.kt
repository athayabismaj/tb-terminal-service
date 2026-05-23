package com.service.tbterminal.purchasing

import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.inventory.ProductsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

interface PurchasingRepository {
    // Suppliers
    suspend fun getPaginatedSuppliers(page: Int, limit: Int, search: String?): PaginatedResponse<SupplierResponse>
    suspend fun getSupplierById(id: UUID): SupplierResponse?
    suspend fun getSupplierByName(name: String): SupplierResponse?
    suspend fun createSupplier(name: String, phone: String?, address: String?, paymentTermDays: Int): UUID
    suspend fun updateSupplier(id: UUID, name: String, phone: String?, address: String?, paymentTermDays: Int): Boolean
    suspend fun softDeleteSupplier(id: UUID): Boolean

    // Purchases
    suspend fun executePurchase(
        userId: UUID,
        supplierId: UUID,
        invoiceNo: String?,
        resolvedItems: List<ResolvedPurchaseItem>,
        calculatedTotal: java.math.BigDecimal,
        paymentMethod: PurchasePaymentMethod,
        amountPaid: java.math.BigDecimal,
        notes: String?,
        dueDays: Int
    ): PurchaseResponse

    suspend fun getPaginatedPurchases(
        page: Int, limit: Int, supplierId: UUID?
    ): PaginatedResponse<PurchaseSummary>

    suspend fun getPurchaseById(id: UUID): PurchaseResponse?

    // Payables
    suspend fun getPaginatedPayables(
        page: Int, limit: Int, supplierId: UUID?, status: PayableStatus?
    ): PaginatedResponse<PayableResponse>
    suspend fun getPayableById(id: UUID): PayableResponse?
    suspend fun getPayableForUpdate(id: UUID): PayableForUpdateRow?

    // Supplier Payments
    suspend fun insertPaymentAndUpdatePayable(
        payableId: UUID, userId: UUID,
        paymentAmount: java.math.BigDecimal, method: PurchasePaymentMethod,
        reference: String?, notes: String?,
        newPaidAmount: java.math.BigDecimal, newStatus: PayableStatus
    ): SupplierPaymentResponse
}

// Data class internal untuk menyimpan data hutang yang di-lock (FOR UPDATE)
data class PayableForUpdateRow(
    val id: UUID,
    val supplierId: UUID,
    val purchaseId: UUID,
    val amount: java.math.BigDecimal,
    val paidAmount: java.math.BigDecimal,
    val status: PayableStatus
)

// Data class internal untuk membawa data produk yang sudah di-resolve
data class ResolvedPurchaseItem(
    val productId: UUID,
    val unitId: UUID,
    val productName: String,
    val qty: java.math.BigDecimal,
    val priceAtTransaction: java.math.BigDecimal,  // Harga beli baru (dari nota)
    val cogsAtTransaction: java.math.BigDecimal,    // Harga beli lama (snapshot HPP sebelum update)
    val subtotal: java.math.BigDecimal              // qty * priceAtTransaction
)

class PurchasingRepositoryImpl : PurchasingRepository {

    // ==========================================
    // SUPPLIERS
    // ==========================================

    override suspend fun getPaginatedSuppliers(page: Int, limit: Int, search: String?): PaginatedResponse<SupplierResponse> = transaction {
        val offset = ((page - 1) * limit).toLong()

        var query = SuppliersTable.select { SuppliersTable.isActive eq true }

        if (!search.isNullOrBlank()) {
            val searchTerm = "%${search.lowercase()}%"
            query = query.andWhere {
                (SuppliersTable.name.lowerCase() like searchTerm) or
                (SuppliersTable.phone.lowerCase() like searchTerm)
            }
        }

        val totalCount = query.count()
        val totalPages = kotlin.math.ceil(totalCount.toDouble() / limit).toInt()

        val data = query
            .orderBy(SuppliersTable.name, SortOrder.ASC)
            .limit(limit, offset)
            .map { rowToSupplierResponse(it) }

        PaginatedResponse(
            data = data,
            total = totalCount,
            page = page,
            limit = limit,
            totalPages = totalPages
        )
    }

    override suspend fun getSupplierById(id: UUID): SupplierResponse? = transaction {
        SuppliersTable.select { (SuppliersTable.id eq id) and (SuppliersTable.isActive eq true) }
            .singleOrNull()?.let { rowToSupplierResponse(it) }
    }

    override suspend fun getSupplierByName(name: String): SupplierResponse? = transaction {
        SuppliersTable.select {
            (SuppliersTable.name.lowerCase() eq name.lowercase()) and (SuppliersTable.isActive eq true)
        }.singleOrNull()?.let { rowToSupplierResponse(it) }
    }

    override suspend fun createSupplier(
        name: String, phone: String?, address: String?, paymentTermDays: Int
    ): UUID = transaction {
        val supplierId = UUID.randomUUID()
        SuppliersTable.insert {
            it[this.id] = supplierId
            it[this.name] = name
            it[this.phone] = phone
            it[this.address] = address
            it[this.paymentTermDays] = paymentTermDays
        }
        supplierId
    }

    override suspend fun updateSupplier(
        id: UUID, name: String, phone: String?, address: String?, paymentTermDays: Int
    ): Boolean = transaction {
        val updatedRows = SuppliersTable.update({ SuppliersTable.id eq id }) {
            it[this.name] = name
            it[this.phone] = phone
            it[this.address] = address
            it[this.paymentTermDays] = paymentTermDays
            it[this.updatedAt] = Instant.now()
        }
        updatedRows > 0
    }

    override suspend fun softDeleteSupplier(id: UUID): Boolean = transaction {
        val updatedRows = SuppliersTable.update({ SuppliersTable.id eq id }) {
            it[isActive] = false
            it[updatedAt] = Instant.now()
        }
        updatedRows > 0
    }

    // ==========================================
    // PURCHASE ENGINE
    // ==========================================

    override suspend fun executePurchase(
        userId: UUID,
        supplierId: UUID,
        invoiceNo: String?,
        resolvedItems: List<ResolvedPurchaseItem>,
        calculatedTotal: java.math.BigDecimal,
        paymentMethod: PurchasePaymentMethod,
        amountPaid: java.math.BigDecimal,
        notes: String?,
        dueDays: Int
    ): PurchaseResponse = transaction {

        // 1. Insert ke purchases
        val purchaseId = UUID.randomUUID()
        PurchasesTable.insert {
            it[this.id] = purchaseId
            it[this.supplierId] = supplierId
            it[this.userId] = userId
            it[this.invoiceNo] = invoiceNo
            it[this.total] = calculatedTotal
            it[this.notes] = notes
        }

        // 2. Loop insert purchase_items + update HPP di products
        resolvedItems.forEach { item ->
            // Insert item (trigger fn_sync_stock akan menambah stok otomatis)
            PurchaseItemsTable.insert {
                it[this.purchaseId] = purchaseId
                it[this.productId] = item.productId
                it[this.unitId] = item.unitId
                it[this.quantity] = item.qty
                it[this.priceAtTransaction] = item.priceAtTransaction
                it[this.cogsAtTransaction] = item.cogsAtTransaction
                it[this.subtotal] = item.subtotal
            }

            // Update HPP (price_buy) di inventory.products
            // Trigger fn_log_price_history akan mencatat riwayat secara otomatis
            ProductsTable.update({ ProductsTable.id eq item.productId }) {
                it[this.priceBuy] = item.priceAtTransaction
                it[this.updatedAt] = Instant.now()
            }
        }

        // 3. Auto-Payables: jika HUTANG atau amountPaid < total → buat hutang
        val isHutang = paymentMethod == PurchasePaymentMethod.HUTANG
        val isDP = paymentMethod == PurchasePaymentMethod.DP
        val hasShortfall = amountPaid < calculatedTotal

        if (isHutang || isDP || hasShortfall) {
            val hutangAmount = calculatedTotal.subtract(amountPaid)
            if (hutangAmount > java.math.BigDecimal.ZERO) {
                SupplierPayablesTable.insert {
                    it[this.supplierId] = supplierId
                    it[this.purchaseId] = purchaseId
                    it[this.amount] = hutangAmount
                    it[this.paidAmount] = java.math.BigDecimal.ZERO
                    it[this.dueDate] = java.time.LocalDate.now().plusDays(dueDays.toLong())
                    it[this.status] = PayableStatus.BELUM_LUNAS
                }
            }
        }

        // 4. Baca data yang baru dibuat untuk response
        val purchaseRow = PurchasesTable.innerJoin(SuppliersTable)
            .select { PurchasesTable.id eq purchaseId }.single()

        val items = PurchaseItemsTable.select { PurchaseItemsTable.purchaseId eq purchaseId }
            .map { row ->
                PurchaseItemResponse(
                    productId = row[PurchaseItemsTable.productId].toString(),
                    productName = resolvedItems.firstOrNull {
                        it.productId.toString() == row[PurchaseItemsTable.productId].toString()
                    }?.productName ?: "",
                    unitId = row[PurchaseItemsTable.unitId].toString(),
                    quantity = row[PurchaseItemsTable.quantity],
                    priceAtTransaction = row[PurchaseItemsTable.priceAtTransaction],
                    cogsAtTransaction = row[PurchaseItemsTable.cogsAtTransaction],
                    subtotal = row[PurchaseItemsTable.subtotal]
                )
            }

        PurchaseResponse(
            id = purchaseRow[PurchasesTable.id].toString(),
            supplierId = purchaseRow[PurchasesTable.supplierId].toString(),
            supplierName = purchaseRow[SuppliersTable.name],
            userId = purchaseRow[PurchasesTable.userId].toString(),
            invoiceNo = purchaseRow[PurchasesTable.invoiceNo],
            total = purchaseRow[PurchasesTable.total],
            notes = purchaseRow[PurchasesTable.notes],
            receivedAt = purchaseRow[PurchasesTable.receivedAt].toString(),
            createdAt = purchaseRow[PurchasesTable.createdAt].toString(),
            items = items
        )
    }

    // ==========================================
    // PURCHASE LIST
    // ==========================================

    override suspend fun getPaginatedPurchases(
        page: Int, limit: Int, supplierId: UUID?
    ): PaginatedResponse<PurchaseSummary> = transaction {
        val offset = ((page - 1) * limit).toLong()

        var query = PurchasesTable.innerJoin(SuppliersTable).selectAll()

        if (supplierId != null) {
            query = query.andWhere { PurchasesTable.supplierId eq supplierId }
        }

        val totalCount = query.count()
        val totalPages = kotlin.math.ceil(totalCount.toDouble() / limit).toInt()

        val data = query
            .orderBy(PurchasesTable.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .map { row ->
                PurchaseSummary(
                    id = row[PurchasesTable.id].toString(),
                    supplierId = row[PurchasesTable.supplierId].toString(),
                    supplierName = row[SuppliersTable.name],
                    invoiceNo = row[PurchasesTable.invoiceNo],
                    total = row[PurchasesTable.total],
                    receivedAt = row[PurchasesTable.receivedAt].toString(),
                    createdAt = row[PurchasesTable.createdAt].toString()
                )
            }

        PaginatedResponse(
            data = data,
            total = totalCount,
            page = page,
            limit = limit,
            totalPages = totalPages
        )
    }

    override suspend fun getPurchaseById(id: UUID): PurchaseResponse? = transaction {
        val purchaseRow = PurchasesTable.innerJoin(SuppliersTable)
            .select { PurchasesTable.id eq id }
            .singleOrNull() ?: return@transaction null

        val items = PurchaseItemsTable.select { PurchaseItemsTable.purchaseId eq id }
            .map { row ->
                PurchaseItemResponse(
                    productId = row[PurchaseItemsTable.productId].toString(),
                    productName = "",
                    unitId = row[PurchaseItemsTable.unitId].toString(),
                    quantity = row[PurchaseItemsTable.quantity],
                    priceAtTransaction = row[PurchaseItemsTable.priceAtTransaction],
                    cogsAtTransaction = row[PurchaseItemsTable.cogsAtTransaction],
                    subtotal = row[PurchaseItemsTable.subtotal]
                )
            }

        PurchaseResponse(
            id = purchaseRow[PurchasesTable.id].toString(),
            supplierId = purchaseRow[PurchasesTable.supplierId].toString(),
            supplierName = purchaseRow[SuppliersTable.name],
            userId = purchaseRow[PurchasesTable.userId].toString(),
            invoiceNo = purchaseRow[PurchasesTable.invoiceNo],
            total = purchaseRow[PurchasesTable.total],
            notes = purchaseRow[PurchasesTable.notes],
            receivedAt = purchaseRow[PurchasesTable.receivedAt].toString(),
            createdAt = purchaseRow[PurchasesTable.createdAt].toString(),
            items = items
        )
    }

    // ==========================================
    // PAYABLES
    // ==========================================

    override suspend fun getPaginatedPayables(
        page: Int, limit: Int, supplierId: UUID?, status: PayableStatus?
    ): PaginatedResponse<PayableResponse> = transaction {
        val offset = ((page - 1) * limit).toLong()

        var query = SupplierPayablesTable.innerJoin(SuppliersTable).selectAll()

        if (supplierId != null) {
            query = query.andWhere { SupplierPayablesTable.supplierId eq supplierId }
        }
        if (status != null) {
            query = query.andWhere { SupplierPayablesTable.status eq status }
        }

        val totalCount = query.count()
        val totalPages = kotlin.math.ceil(totalCount.toDouble() / limit).toInt()

        val data = query
            .orderBy(SupplierPayablesTable.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .map { row ->
                val amount = row[SupplierPayablesTable.amount]
                val paidAmount = row[SupplierPayablesTable.paidAmount]
                PayableResponse(
                    id = row[SupplierPayablesTable.id].toString(),
                    supplierId = row[SupplierPayablesTable.supplierId].toString(),
                    supplierName = row[SuppliersTable.name],
                    purchaseId = row[SupplierPayablesTable.purchaseId].toString(),
                    amount = amount,
                    paidAmount = paidAmount,
                    remainingAmount = amount.subtract(paidAmount),
                    dueDate = row[SupplierPayablesTable.dueDate].toString(),
                    status = row[SupplierPayablesTable.status].dbValue,
                    createdAt = row[SupplierPayablesTable.createdAt].toString()
                )
            }

        PaginatedResponse(
            data = data,
            total = totalCount,
            page = page,
            limit = limit,
            totalPages = totalPages
        )
    }

    override suspend fun getPayableById(id: UUID): PayableResponse? = transaction {
        SupplierPayablesTable.innerJoin(SuppliersTable)
            .select { SupplierPayablesTable.id eq id }
            .singleOrNull()?.let { row ->
                val amount = row[SupplierPayablesTable.amount]
                val paidAmount = row[SupplierPayablesTable.paidAmount]
                PayableResponse(
                    id = row[SupplierPayablesTable.id].toString(),
                    supplierId = row[SupplierPayablesTable.supplierId].toString(),
                    supplierName = row[SuppliersTable.name],
                    purchaseId = row[SupplierPayablesTable.purchaseId].toString(),
                    amount = amount,
                    paidAmount = paidAmount,
                    remainingAmount = amount.subtract(paidAmount),
                    dueDate = row[SupplierPayablesTable.dueDate].toString(),
                    status = row[SupplierPayablesTable.status].dbValue,
                    createdAt = row[SupplierPayablesTable.createdAt].toString()
                )
            }
    }

    override suspend fun getPayableForUpdate(id: UUID): PayableForUpdateRow? = transaction {
        SupplierPayablesTable.select { SupplierPayablesTable.id eq id }
            .forUpdate()
            .singleOrNull()?.let { row ->
                PayableForUpdateRow(
                    id = row[SupplierPayablesTable.id],
                    supplierId = row[SupplierPayablesTable.supplierId],
                    purchaseId = row[SupplierPayablesTable.purchaseId],
                    amount = row[SupplierPayablesTable.amount],
                    paidAmount = row[SupplierPayablesTable.paidAmount],
                    status = row[SupplierPayablesTable.status]
                )
            }
    }

    // ==========================================
    // SUPPLIER PAYMENTS
    // ==========================================

    override suspend fun insertPaymentAndUpdatePayable(
        payableId: UUID, userId: UUID,
        paymentAmount: java.math.BigDecimal, method: PurchasePaymentMethod,
        reference: String?, notes: String?,
        newPaidAmount: java.math.BigDecimal, newStatus: PayableStatus
    ): SupplierPaymentResponse = transaction {
        // 1. INSERT payment
        val paymentId = UUID.randomUUID()
        SupplierPaymentsTable.insert {
            it[this.id] = paymentId
            it[this.supplierPayableId] = payableId
            it[this.userId] = userId
            it[this.amount] = paymentAmount
            it[this.method] = method
            it[this.reference] = reference
            it[this.notes] = notes
        }

        // 2. UPDATE payable paid_amount dan status
        SupplierPayablesTable.update({ SupplierPayablesTable.id eq payableId }) {
            it[this.paidAmount] = newPaidAmount
            it[this.status] = newStatus
            it[this.updatedAt] = Instant.now()
        }

        // 3. Baca payment yang baru dibuat untuk response
        val paymentRow = SupplierPaymentsTable.select { SupplierPaymentsTable.id eq paymentId }.single()
        val payableAmount = SupplierPayablesTable.select { SupplierPayablesTable.id eq payableId }.single()
            .let { it[SupplierPayablesTable.amount] }

        SupplierPaymentResponse(
            id = paymentRow[SupplierPaymentsTable.id].toString(),
            payableId = payableId.toString(),
            amount = paymentRow[SupplierPaymentsTable.amount],
            method = paymentRow[SupplierPaymentsTable.method].dbValue,
            reference = paymentRow[SupplierPaymentsTable.reference],
            notes = paymentRow[SupplierPaymentsTable.notes],
            paidAt = paymentRow[SupplierPaymentsTable.paidAt].toString(),
            payableStatus = newStatus.dbValue,
            payableRemainingAmount = payableAmount.subtract(newPaidAmount)
        )
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private fun rowToSupplierResponse(row: ResultRow): SupplierResponse {
        return SupplierResponse(
            id = row[SuppliersTable.id].toString(),
            name = row[SuppliersTable.name],
            phone = row[SuppliersTable.phone],
            address = row[SuppliersTable.address],
            paymentTermDays = row[SuppliersTable.paymentTermDays],
            isActive = row[SuppliersTable.isActive],
            createdAt = row[SuppliersTable.createdAt].toString(),
            updatedAt = row[SuppliersTable.updatedAt].toString()
        )
    }
}
