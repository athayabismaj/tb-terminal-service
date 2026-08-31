package com.service.tbterminal.sales

import com.service.tbterminal.inventory.ProductsTable
import com.service.tbterminal.shared.ManagerApprovalError
import com.service.tbterminal.shared.ManagerApprovalException
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.system.StoreSettingsTable
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
import java.util.UUID

interface DiscountRepository {
    suspend fun createPreview(
        actorUserId: UUID,
        actorRole: String,
        request: CheckoutPreviewRequest
    ): CheckoutPreviewResponse

    fun cashierLimitInCurrentTransaction(): java.math.BigDecimal

    fun validateAttemptInCurrentTransaction(
        attemptId: UUID,
        actorUserId: UUID,
        discountFingerprint: String
    ): DiscountAttemptValidation

    fun consumeAttemptInCurrentTransaction(attemptId: UUID, transactionId: UUID)
}

class DiscountRepositoryImpl : DiscountRepository {
    override suspend fun createPreview(
        actorUserId: UUID,
        actorRole: String,
        request: CheckoutPreviewRequest
    ): CheckoutPreviewResponse = newSuspendedTransaction(Dispatchers.IO) {
        val requestedIds = request.items.map { UUID.fromString(it.productId) }
        val products = ProductsTable.select { ProductsTable.id inList requestedIds.sortedBy(UUID::toString) }
            .orderBy(ProductsTable.id to SortOrder.ASC)
            .associateBy { it[ProductsTable.id] }
        if (products.size != requestedIds.size) throw NotFoundException("Satu atau lebih produk tidak ditemukan")

        val inputs = request.items.map { item ->
            val productId = UUID.fromString(item.productId)
            val product = requireNotNull(products[productId])
            if (!product[ProductsTable.isActive]) {
                throw ValidationException("Produk ${product[ProductsTable.name]} tidak aktif")
            }
            DiscountItemInput(
                reference = productId.toString(),
                quantity = item.qty,
                unitPrice = product[ProductsTable.priceRetail],
                discount = item.discountRequest,
                legacyPerUnitDiscount = item.discount
            )
        }
        val calculation = DiscountCalculator.calculate(inputs, request.transactionDiscount)
        val limit = cashierLimitInCurrentTransaction()
        val approvalRequired = requiresDiscountOverride(actorRole, calculation.effectiveDiscountPercent, limit)
        val attemptId = UUID.randomUUID()
        val expiresAt = OffsetDateTime.now().plusMinutes(PREVIEW_TTL_MINUTES)
        CheckoutDiscountAttemptsTable.insert {
            it[id] = attemptId
            it[requestedByUserId] = actorUserId
            it[discountFingerprint] = calculation.fingerprint
            it[grossSubtotal] = calculation.grossSubtotal
            it[totalDiscountAmount] = calculation.totalDiscountAmount
            it[effectiveDiscountPercent] = calculation.effectiveDiscountPercent
            it[cashierLimitPercent] = limit
            it[this.approvalRequired] = approvalRequired
            it[this.expiresAt] = expiresAt
        }
        CheckoutPreviewResponse(
            checkoutAttemptId = attemptId.toString(),
            discountFingerprint = calculation.fingerprint,
            items = calculation.items.map { line ->
                CheckoutPreviewItemResponse(
                    productId = line.reference,
                    quantity = line.quantity,
                    unitPrice = line.unitPrice,
                    grossLineTotal = line.grossLineTotal,
                    discountType = line.discountType?.name,
                    discountValue = line.discountValue,
                    discountAmount = line.discountAmount,
                    netLineTotal = line.netLineTotal
                )
            },
            grossSubtotal = calculation.grossSubtotal,
            itemDiscountTotal = calculation.itemDiscountTotal,
            transactionDiscountAmount = calculation.transactionDiscountAmount,
            totalDiscountAmount = calculation.totalDiscountAmount,
            effectiveDiscountPercent = calculation.effectiveDiscountPercent,
            netTotal = calculation.netTotal,
            cashierDiscountLimitPercent = limit,
            approvalRequired = approvalRequired,
            expiresAt = expiresAt.toString()
        )
    }

    override fun cashierLimitInCurrentTransaction(): java.math.BigDecimal =
        StoreSettingsTable.selectAll().limit(1).singleOrNull()
            ?.get(StoreSettingsTable.cashierDiscountLimitPercent)
            ?: DEFAULT_CASHIER_DISCOUNT_LIMIT_PERCENT

    override fun validateAttemptInCurrentTransaction(
        attemptId: UUID,
        actorUserId: UUID,
        discountFingerprint: String
    ): DiscountAttemptValidation {
        val row = CheckoutDiscountAttemptsTable.select { CheckoutDiscountAttemptsTable.id eq attemptId }
            .forUpdate().singleOrNull()
            ?: throw scopeMismatch("Checkout attempt untuk Discount Override tidak ditemukan")
        val attempt = row.toValidation()
        if (attempt.actorUserId != actorUserId) {
            throw scopeMismatch("Checkout attempt tidak berlaku untuk pengguna ini")
        }
        if (!attempt.approvalRequired) {
            throw scopeMismatch("Checkout attempt tidak memerlukan Discount Override")
        }
        if (attempt.consumedAt != null || attempt.transactionId != null) {
            throw ManagerApprovalException(
                ManagerApprovalError.ALREADY_USED,
                "Checkout attempt sudah digunakan"
            )
        }
        if (!OffsetDateTime.now().isBefore(attempt.expiresAt)) {
            throw ManagerApprovalException(
                ManagerApprovalError.EXPIRED,
                "Checkout attempt sudah kedaluwarsa; lakukan preview ulang"
            )
        }
        if (attempt.discountFingerprint != discountFingerprint) {
            throw scopeMismatch("Harga, quantity, atau intent diskon berubah; lakukan preview dan approval ulang")
        }
        return attempt
    }

    override fun consumeAttemptInCurrentTransaction(attemptId: UUID, transactionId: UUID) {
        val updated = CheckoutDiscountAttemptsTable.update({
            (CheckoutDiscountAttemptsTable.id eq attemptId) and CheckoutDiscountAttemptsTable.consumedAt.isNull()
        }) {
            it[consumedAt] = OffsetDateTime.now()
            it[this.transactionId] = transactionId
        }
        if (updated != 1) {
            throw ManagerApprovalException(ManagerApprovalError.ALREADY_USED, "Checkout attempt sudah digunakan")
        }
    }

    private fun ResultRow.toValidation() = DiscountAttemptValidation(
        attemptId = this[CheckoutDiscountAttemptsTable.id],
        actorUserId = this[CheckoutDiscountAttemptsTable.requestedByUserId],
        discountFingerprint = this[CheckoutDiscountAttemptsTable.discountFingerprint],
        approvalRequired = this[CheckoutDiscountAttemptsTable.approvalRequired],
        expiresAt = this[CheckoutDiscountAttemptsTable.expiresAt],
        consumedAt = this[CheckoutDiscountAttemptsTable.consumedAt],
        transactionId = this[CheckoutDiscountAttemptsTable.transactionId]
    )

    private fun scopeMismatch(message: String) = ManagerApprovalException(
        ManagerApprovalError.SCOPE_MISMATCH,
        message
    )

    private companion object {
        const val PREVIEW_TTL_MINUTES = 10L
    }
}
