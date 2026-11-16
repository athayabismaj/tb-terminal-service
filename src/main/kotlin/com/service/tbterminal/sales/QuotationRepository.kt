package com.service.tbterminal.sales

import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.inventory.ProductsTable
import com.service.tbterminal.inventory.UnitsTable
import com.service.tbterminal.receivable.CustomersTable
import com.service.tbterminal.shared.ForbiddenException
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.Role
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.system.CashRoundingRule
import com.service.tbterminal.system.StoreSettingsTable
import com.service.tbterminal.system.TaxMode
import com.service.tbterminal.system.UsersTable
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

interface QuotationRepository {
    suspend fun createQuotation(actorUserId: UUID, request: CreateQuotationRequest): QuotationDetailResponse
    suspend fun getQuotations(
        page: Int,
        limit: Int,
        status: String? = null,
        search: String? = null,
        actorUserId: UUID,
        actorRole: String,
    ): PaginatedResponse<QuotationSummaryResponse>
    suspend fun getQuotationById(
        quotationId: UUID,
        actorUserId: UUID,
        actorRole: String,
    ): QuotationDetailResponse
    suspend fun updateQuotationStatus(
        quotationId: UUID,
        actorUserId: UUID,
        actorRole: String,
        request: UpdateQuotationStatusRequest,
    ): QuotationDetailResponse
}

class QuotationRepositoryImpl : QuotationRepository {
    override suspend fun createQuotation(
        actorUserId: UUID,
        request: CreateQuotationRequest,
    ): QuotationDetailResponse = newSuspendedTransaction(Dispatchers.IO) {
        val requestedProductIds = request.items.map { UUID.fromString(it.productId.trim()) }.distinct()
        val products = ProductsTable.select { ProductsTable.id inList requestedProductIds }
            .associateBy { it[ProductsTable.id] }
        if (products.size != requestedProductIds.size) {
            throw NotFoundException("Satu atau lebih produk tidak ditemukan")
        }

        val unitIds = products.values.map { it[ProductsTable.baseUnitId] }.distinct()
        val unitsById = UnitsTable.select { UnitsTable.id inList unitIds }
            .associateBy { it[UnitsTable.id] }

        val customerUuid = request.customerId?.trim()?.takeIf { it.isNotEmpty() }?.let(UUID::fromString)
        val customerRow = customerUuid?.let { cid ->
            CustomersTable.select { CustomersTable.id eq cid }.singleOrNull()
                ?: throw NotFoundException("Pelanggan tidak ditemukan")
        }
        if (customerRow != null && !customerRow[CustomersTable.isActive]) {
            throw ValidationException("Pelanggan tidak aktif")
        }
        val resolvedCustomerName = customerRow?.get(CustomersTable.name)
            ?: request.customerName?.trim()?.takeIf { it.isNotEmpty() }
        val pricingContext = loadSalesTierPricingContext(customerUuid, requestedProductIds)

        val discountInputs = request.items.mapIndexed { idx, item ->
            val pid = UUID.fromString(item.productId.trim())
            val productRow = requireNotNull(products[pid])
            if (!productRow[ProductsTable.isActive]) {
                throw ValidationException("Produk ${productRow[ProductsTable.name]} tidak aktif")
            }
            DiscountItemInput(
                reference = "${pid}#$idx",
                quantity = item.qty,
                unitPrice = pricingContext.resolveUnitPrice(productRow, item.qty),
                discount = item.discountRequest,
            )
        }

        val calculation = DiscountCalculator.calculate(discountInputs, request.transactionDiscount)
        val settingsRow = StoreSettingsTable.selectAll().limit(1).singleOrNull()
        val taxConfig = StoreTaxRoundingConfig(
            taxEnabled = settingsRow?.get(StoreSettingsTable.taxEnabled) ?: false,
            taxRate = settingsRow?.get(StoreSettingsTable.taxRate) ?: TaxAndRoundingCalculator.DEFAULT_TAX_RATE,
            taxMode = settingsRow?.get(StoreSettingsTable.taxMode) ?: TaxMode.EXCLUDE,
            taxLabel = settingsRow?.get(StoreSettingsTable.taxLabel) ?: TaxAndRoundingCalculator.DEFAULT_TAX_LABEL,
            cashRoundingUnit = settingsRow?.get(StoreSettingsTable.cashRoundingUnit) ?: 0,
            cashRoundingRule = settingsRow?.get(StoreSettingsTable.cashRoundingRule) ?: CashRoundingRule.NEAREST,
        )
        val taxCalc = TaxAndRoundingCalculator.calculateTax(calculation.netTotal, taxConfig)

        val now = OffsetDateTime.now(ZoneId.of("Asia/Jakarta"))
        val validUntilVal = now.plusDays(request.validDays.toLong())
        val quotationId = UUID.randomUUID()
        val quotationNumber = generateQuotationNumber(now)

        QuotationsTable.insert {
            it[id] = quotationId
            it[this.quotationNumber] = quotationNumber
            it[userId] = actorUserId
            it[customerId] = customerUuid
            it[customerNameSnapshot] = resolvedCustomerName
            it[status] = QuotationStatus.DRAFT.name
            it[validUntil] = validUntilVal
            it[notes] = request.notes?.trim()?.takeIf { n -> n.isNotEmpty() }
            it[grossSubtotal] = calculation.grossSubtotal
            it[itemDiscountTotal] = calculation.itemDiscountTotal
            it[transactionDiscountType] = calculation.transactionDiscountType
            it[transactionDiscountValue] = calculation.transactionDiscountValue
            it[transactionDiscountAmount] = calculation.transactionDiscountAmount
            it[totalDiscountAmount] = calculation.totalDiscountAmount
            it[taxEnabled] = taxCalc.taxEnabled
            it[taxMode] = taxCalc.taxMode
            it[taxRate] = taxCalc.taxRate
            it[taxLabel] = taxCalc.taxLabel
            it[taxableBase] = taxCalc.taxableBase
            it[taxAmount] = taxCalc.taxAmount
            it[total] = taxCalc.totalWithTax
            it[createdAt] = now
            it[updatedAt] = now
        }

        request.items.forEachIndexed { idx, reqItem ->
            val pid = UUID.fromString(reqItem.productId.trim())
            val prod = requireNotNull(products[pid])
            val baseUnitId = prod[ProductsTable.baseUnitId]
            val unitSymbol = unitsById[baseUnitId]?.let { u ->
                u[UnitsTable.symbol].ifBlank { u[UnitsTable.name] }
            }
            val lineCalc = calculation.items[idx]
            QuotationItemsTable.insert {
                it[id] = UUID.randomUUID()
                it[this.quotationId] = quotationId
                it[productId] = pid
                it[unitId] = baseUnitId
                it[productNameSnapshot] = prod[ProductsTable.name]
                it[unitNameSnapshot] = unitSymbol
                it[quantity] = lineCalc.quantity
                it[unitPrice] = lineCalc.unitPrice
                it[discountType] = lineCalc.discountType
                it[discountValue] = lineCalc.discountValue
                it[discountAmount] = lineCalc.discountAmount
                it[subtotal] = lineCalc.netLineTotal
                it[note] = reqItem.note?.trim()?.takeIf { n -> n.isNotEmpty() }
            }
        }

        loadQuotationDetail(quotationId)
    }

    override suspend fun getQuotations(
        page: Int,
        limit: Int,
        status: String?,
        search: String?,
        actorUserId: UUID,
        actorRole: String,
    ): PaginatedResponse<QuotationSummaryResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 100)
        val cleanStatus = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val cleanSearch = search?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        val query = QuotationsTable.selectAll()
            .orderBy(QuotationsTable.createdAt to SortOrder.DESC)

        val rows = query.toList().filter { row ->
            if (actorRole == Role.KASIR && row[QuotationsTable.userId] != actorUserId) {
                return@filter false
            }
            if (cleanStatus != null && row[QuotationsTable.status].uppercase() != cleanStatus) {
                return@filter false
            }
            if (cleanSearch != null) {
                val matchNumber = row[QuotationsTable.quotationNumber].lowercase().contains(cleanSearch)
                val matchCustomer = row[QuotationsTable.customerNameSnapshot]?.lowercase()?.contains(cleanSearch) == true
                val matchNotes = row[QuotationsTable.notes]?.lowercase()?.contains(cleanSearch) == true
                if (!matchNumber && !matchCustomer && !matchNotes) return@filter false
            }
            true
        }

        val total = rows.size.toLong()
        val totalPages = if (total == 0L) 1 else ((total + safeLimit - 1) / safeLimit).toInt()
        val offset = (safePage - 1) * safeLimit
        val pagedRows = if (offset >= rows.size) emptyList() else rows.subList(offset, minOf(offset + safeLimit, rows.size))

        val quotationIds = pagedRows.map { it[QuotationsTable.id] }
        val itemCountByQuotation = if (quotationIds.isEmpty()) {
            emptyMap()
        } else {
            QuotationItemsTable.select { QuotationItemsTable.quotationId inList quotationIds }
                .groupingBy { it[QuotationItemsTable.quotationId] }
                .eachCount()
        }

        val userIds = pagedRows.map { it[QuotationsTable.userId] }.distinct()
        val userNamesById = if (userIds.isEmpty()) {
            emptyMap()
        } else {
            UsersTable.select { UsersTable.id inList userIds }
                .associate { it[UsersTable.id] to it[UsersTable.name] }
        }

        val data = pagedRows.map { row ->
            val qid = row[QuotationsTable.id]
            QuotationSummaryResponse(
                id = qid.toString(),
                quotationNumber = row[QuotationsTable.quotationNumber],
                userId = row[QuotationsTable.userId].toString(),
                cashierName = userNamesById[row[QuotationsTable.userId]],
                customerId = row[QuotationsTable.customerId]?.toString(),
                customerName = row[QuotationsTable.customerNameSnapshot],
                status = row[QuotationsTable.status],
                validUntil = row[QuotationsTable.validUntil]?.toString(),
                total = row[QuotationsTable.total],
                itemCount = itemCountByQuotation[qid] ?: 0,
                notes = row[QuotationsTable.notes],
                convertedTransactionId = row[QuotationsTable.convertedTransactionId]?.toString(),
                createdAt = row[QuotationsTable.createdAt].toString(),
            )
        }

        PaginatedResponse(
            data = data,
            total = total,
            page = safePage,
            limit = safeLimit,
            totalPages = totalPages,
        )
    }

    override suspend fun getQuotationById(
        quotationId: UUID,
        actorUserId: UUID,
        actorRole: String,
    ): QuotationDetailResponse = newSuspendedTransaction(Dispatchers.IO) {
        val detail = loadQuotationDetail(quotationId)
        if (actorRole == Role.KASIR && detail.userId != actorUserId.toString()) {
            throw ForbiddenException("Kasir hanya dapat melihat penawaran miliknya")
        }
        detail
    }

    override suspend fun updateQuotationStatus(
        quotationId: UUID,
        actorUserId: UUID,
        actorRole: String,
        request: UpdateQuotationStatusRequest,
    ): QuotationDetailResponse = newSuspendedTransaction(Dispatchers.IO) {
        val existing = QuotationsTable.select { QuotationsTable.id eq quotationId }.singleOrNull()
            ?: throw NotFoundException("Penawaran harga tidak ditemukan")
        if (actorRole == Role.KASIR && existing[QuotationsTable.userId] != actorUserId) {
            throw ForbiddenException("Kasir hanya dapat memperbarui penawaran miliknya")
        }

        val currentStatus = QuotationStatus.valueOf(existing[QuotationsTable.status])
        val (targetStatus, convertedTxId) = QuotationValidation.validateStatusTransition(currentStatus, request)
        val now = OffsetDateTime.now(ZoneId.of("Asia/Jakarta"))

        QuotationsTable.update({ QuotationsTable.id eq quotationId }) {
            it[status] = targetStatus.name
            if (targetStatus == QuotationStatus.CONVERTED) {
                if (convertedTxId != null) it[convertedTransactionId] = convertedTxId
                if (existing[QuotationsTable.convertedAt] == null) it[convertedAt] = now
            }
            it[updatedAt] = now
        }

        loadQuotationDetail(quotationId)
    }

    private fun loadQuotationDetail(quotationId: UUID): QuotationDetailResponse {
        val row = QuotationsTable.select { QuotationsTable.id eq quotationId }.singleOrNull()
            ?: throw NotFoundException("Penawaran harga tidak ditemukan")
        val cashierName = UsersTable.select { UsersTable.id eq row[QuotationsTable.userId] }
            .singleOrNull()
            ?.get(UsersTable.name)
        val items = QuotationItemsTable.select { QuotationItemsTable.quotationId eq quotationId }
            .map { item ->
                QuotationItemResponse(
                    id = item[QuotationItemsTable.id].toString(),
                    productId = item[QuotationItemsTable.productId].toString(),
                    productName = item[QuotationItemsTable.productNameSnapshot],
                    unitId = item[QuotationItemsTable.unitId].toString(),
                    unitName = item[QuotationItemsTable.unitNameSnapshot],
                    quantity = item[QuotationItemsTable.quantity],
                    unitPrice = item[QuotationItemsTable.unitPrice],
                    discountType = item[QuotationItemsTable.discountType],
                    discountValue = item[QuotationItemsTable.discountValue],
                    discountAmount = item[QuotationItemsTable.discountAmount],
                    subtotal = item[QuotationItemsTable.subtotal],
                    note = item[QuotationItemsTable.note],
                )
            }
        return QuotationDetailResponse(
            id = row[QuotationsTable.id].toString(),
            quotationNumber = row[QuotationsTable.quotationNumber],
            userId = row[QuotationsTable.userId].toString(),
            cashierName = cashierName,
            customerId = row[QuotationsTable.customerId]?.toString(),
            customerName = row[QuotationsTable.customerNameSnapshot],
            status = row[QuotationsTable.status],
            validUntil = row[QuotationsTable.validUntil]?.toString(),
            notes = row[QuotationsTable.notes],
            grossSubtotal = row[QuotationsTable.grossSubtotal],
            itemDiscountTotal = row[QuotationsTable.itemDiscountTotal],
            transactionDiscountType = row[QuotationsTable.transactionDiscountType],
            transactionDiscountValue = row[QuotationsTable.transactionDiscountValue],
            transactionDiscountAmount = row[QuotationsTable.transactionDiscountAmount],
            totalDiscountAmount = row[QuotationsTable.totalDiscountAmount],
            taxEnabled = row[QuotationsTable.taxEnabled],
            taxMode = row[QuotationsTable.taxMode]?.name,
            taxRate = row[QuotationsTable.taxRate],
            taxLabel = row[QuotationsTable.taxLabel],
            taxableBase = row[QuotationsTable.taxableBase],
            taxAmount = row[QuotationsTable.taxAmount],
            total = row[QuotationsTable.total],
            convertedTransactionId = row[QuotationsTable.convertedTransactionId]?.toString(),
            convertedAt = row[QuotationsTable.convertedAt]?.toString(),
            createdAt = row[QuotationsTable.createdAt].toString(),
            updatedAt = row[QuotationsTable.updatedAt].toString(),
            items = items,
        )
    }

    private fun generateQuotationNumber(now: OffsetDateTime): String {
        val datePart = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val randomSuffix = UUID.randomUUID().toString().take(4).uppercase()
        return "RAB-$datePart-$randomSuffix"
    }
}
