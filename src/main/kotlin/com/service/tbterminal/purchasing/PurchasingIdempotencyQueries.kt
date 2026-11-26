package com.service.tbterminal.purchasing

import com.service.tbterminal.inventory.ProductsTable
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.shared.queryList
import com.service.tbterminal.shared.sqlParam
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.select

/**
 * Mengunci key sampai transaksi database selesai. Request kedua dengan key sama menunggu di sini,
 * lalu menemukan baris milik request pertama, sehingga tidak ada cek-lalu-tulis yang bisa balapan.
 * Wajib dipanggil PALING AWAL di transaksi, sebelum lock baris apa pun (urutan: advisory → hutang).
 */
internal fun Transaction.lockPurchasingIdempotencyKey(scope: String, key: String) {
    queryList(sqlParam("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", scope + key)) { }
}

/** Respons pembelian yang sudah tercatat untuk key ini, atau null. Payload beda → [ValidationException]. */
internal fun Transaction.replayPurchase(idempotency: PurchasingIdempotency): PurchaseResponse? {
    val row = PurchasesTable.innerJoin(SuppliersTable)
        .select { PurchasesTable.idempotencyKey eq idempotency.key }
        .singleOrNull() ?: return null
    if (row[PurchasesTable.requestFingerprint] != idempotency.fingerprint) {
        throw ValidationException(PURCHASE_IDEMPOTENCY_CONFLICT_MESSAGE)
    }
    val purchaseId = row[PurchasesTable.id]
    val items = PurchaseItemsTable
        .join(ProductsTable, JoinType.LEFT, PurchaseItemsTable.productId, ProductsTable.id)
        .select { PurchaseItemsTable.purchaseId eq purchaseId }
        .map { item ->
            PurchaseItemResponse(
                productId = item[PurchaseItemsTable.productId].toString(),
                productName = item.getOrNull(ProductsTable.name).orEmpty(),
                unitId = item[PurchaseItemsTable.unitId].toString(),
                quantity = item[PurchaseItemsTable.quantity],
                priceAtTransaction = item[PurchaseItemsTable.priceAtTransaction],
                cogsAtTransaction = item[PurchaseItemsTable.cogsAtTransaction],
                subtotal = item[PurchaseItemsTable.subtotal]
            )
        }
    return PurchaseResponse(
        id = purchaseId.toString(),
        supplierId = row[PurchasesTable.supplierId].toString(),
        supplierName = row[SuppliersTable.name],
        userId = row[PurchasesTable.userId].toString(),
        invoiceNo = row[PurchasesTable.invoiceNo],
        total = row[PurchasesTable.total],
        notes = row[PurchasesTable.notes],
        receivedAt = row[PurchasesTable.receivedAt].toString(),
        createdAt = row[PurchasesTable.createdAt].toString(),
        items = items,
        idempotentReplay = true
    )
}

/** Respons pembayaran hutang yang sudah tercatat untuk key ini, atau null. Payload beda → [ValidationException]. */
internal fun Transaction.replaySupplierPayment(idempotency: PurchasingIdempotency): SupplierPaymentResponse? {
    val row = SupplierPaymentsTable
        .select { SupplierPaymentsTable.idempotencyKey eq idempotency.key }
        .singleOrNull() ?: return null
    if (row[SupplierPaymentsTable.requestFingerprint] != idempotency.fingerprint) {
        throw ValidationException(SUPPLIER_PAYMENT_IDEMPOTENCY_CONFLICT_MESSAGE)
    }
    return row.toSupplierPaymentResponse(idempotentReplay = true)
}

/** Status dan sisa hutang diambil dari snapshot saat pembayaran (V48), bukan kondisi hutang sekarang. */
internal fun ResultRow.toSupplierPaymentResponse(idempotentReplay: Boolean): SupplierPaymentResponse {
    val status = this[SupplierPaymentsTable.payableStatusAfter]
        ?: error("Snapshot hutang tidak tersedia untuk pembayaran ${this[SupplierPaymentsTable.id]}")
    val remaining = this[SupplierPaymentsTable.payableRemainingAfter]
        ?: error("Snapshot hutang tidak tersedia untuk pembayaran ${this[SupplierPaymentsTable.id]}")
    return SupplierPaymentResponse(
        id = this[SupplierPaymentsTable.id].toString(),
        payableId = this[SupplierPaymentsTable.supplierPayableId].toString(),
        amount = this[SupplierPaymentsTable.amount],
        method = this[SupplierPaymentsTable.method].dbValue,
        reference = this[SupplierPaymentsTable.reference],
        notes = this[SupplierPaymentsTable.notes],
        paidAt = this[SupplierPaymentsTable.paidAt].toString(),
        payableStatus = status.dbValue,
        payableRemainingAmount = remaining,
        idempotentReplay = idempotentReplay
    )
}
