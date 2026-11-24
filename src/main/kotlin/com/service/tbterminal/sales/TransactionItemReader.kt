package com.service.tbterminal.sales

import com.service.tbterminal.inventory.UnitsTable
import org.jetbrains.exposed.sql.select
import java.util.UUID

/** Wajib dipanggil di dalam transaksi database yang sedang berjalan. */
internal fun loadTransactionItems(transactionId: UUID): List<TransactionItemResponse> {
    val rows = TransactionItemsTable.select { TransactionItemsTable.transactionId eq transactionId }.toList()
    // Query terpisah, bukan join, supaya urutan item tidak berubah.
    val unitNames = UnitsTable
        .select { UnitsTable.id inList rows.map { it[TransactionItemsTable.unitId] }.distinct() }
        .associate { it[UnitsTable.id] to it[UnitsTable.name] }
    return rows.map { row ->
        TransactionItemResponse(
            productId = row[TransactionItemsTable.productId].toString(),
            productName = row[TransactionItemsTable.productName],
            unitId = row[TransactionItemsTable.unitId].toString(),
            unitName = unitNames.getValue(row[TransactionItemsTable.unitId]),
            quantity = row[TransactionItemsTable.quantity],
            priceAtTransaction = row[TransactionItemsTable.priceAtTransaction],
            cogsAtTransaction = row[TransactionItemsTable.cogsAtTransaction],
            grossLineTotal = row[TransactionItemsTable.grossLineTotal],
            discountType = row[TransactionItemsTable.discountType]?.name,
            discountValue = row[TransactionItemsTable.discountValue],
            discount = row[TransactionItemsTable.discount],
            subtotal = row[TransactionItemsTable.subtotal],
            note = row[TransactionItemsTable.note]
        )
    }
}
