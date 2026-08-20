package com.service.tbterminal.inventory

import java.math.BigDecimal
import java.time.LocalDate

internal val PRODUCT_CSV_HEADERS = listOf(
    "sku", "name", "category", "unit", "price_buy", "price_retail",
    "price_contractor", "min_stock", "opening_stock", "opening_date", "opening_note"
)

internal fun previewProductCsv(
    csv: String,
    categoryIds: Map<String, String>,
    unitIds: Map<String, String>,
    existingSkus: Set<String>,
    today: LocalDate = inventoryToday()
): ProductCsvPreviewResponse {
    val records = parseCsv(csv)
    if (records.isEmpty()) return ProductCsvPreviewResponse(0, 0, 0, emptyList())
    val headers = records.first().map { it.trim().lowercase() }
    if (headers != PRODUCT_CSV_HEADERS) {
        val row = ProductCsvRowPreview(
            rowNumber = 1, sku = "", name = "", category = "", unit = "", priceBuy = "",
            priceRetail = "", priceContractor = "", minStock = "", openingStock = "",
            openingDate = "", openingNote = "",
            errors = listOf("Header CSV harus: ${PRODUCT_CSV_HEADERS.joinToString(",")}")
        )
        return ProductCsvPreviewResponse(0, 0, 1, listOf(row))
    }

    val dataRecords = records.drop(1).filterNot { row -> row.all(String::isBlank) }
    if (dataRecords.size > 500) {
        val row = emptyPreview(2, listOf("Maksimal 500 baris produk per impor"))
        return ProductCsvPreviewResponse(dataRecords.size, 0, dataRecords.size, listOf(row))
    }

    val seenSkus = mutableSetOf<String>()
    val rows = dataRecords.mapIndexed { index, values ->
        if (values.size != PRODUCT_CSV_HEADERS.size) {
            return@mapIndexed emptyPreview(index + 2, listOf("Jumlah kolom harus ${PRODUCT_CSV_HEADERS.size}"))
        }
        val sku = normalizeSku(values[0])
        val name = values[1].trim()
        val category = values[2].trim()
        val unit = values[3].trim()
        val priceBuy = values[4].decimalOrNull()
        val priceRetail = values[5].decimalOrNull()
        val priceContractor = values[6].decimalOrNull()
        val minStock = values[7].decimalOrNull()
        val openingStock = values[8].ifBlank { "0" }.decimalOrNull()
        val openingDate = values[9].trim()
        val openingNote = values[10].trim()
        val errors = buildList {
            validateSku(sku)?.let(::add)
            if (!seenSkus.add(sku)) add("SKU duplikat di dalam file")
            if (sku in existingSkus) add("SKU sudah digunakan")
            if (category.lowercase() !in categoryIds) add("Kategori tidak ditemukan")
            if (unit.lowercase() !in unitIds) add("Satuan tidak ditemukan")
            if (priceBuy == null) add("Harga beli tidak valid")
            if (priceRetail == null) add("Harga retail tidak valid")
            if (priceContractor == null) add("Harga kontraktor tidak valid")
            if (minStock == null) add("Stok minimum tidak valid")
            if (openingStock == null) add("Saldo awal tidak valid")
            if (priceBuy != null && priceRetail != null && priceContractor != null && minStock != null) {
                addAll(validateProductValues(name, priceBuy, priceRetail, priceContractor, BigDecimal.ZERO, minStock))
            }
            if (openingStock != null) {
                if (openingStock < BigDecimal.ZERO) add("Saldo awal tidak boleh negatif")
                if (openingStock.scale() > 2) add("Saldo awal maksimal 2 angka desimal")
                if (openingStock > BigDecimal("99999999.99")) add("Saldo awal melebihi batas")
                if (openingStock > BigDecimal.ZERO) {
                    val parsedDate = runCatching { LocalDate.parse(openingDate) }.getOrNull()
                    if (parsedDate == null) add("Tanggal saldo awal wajib berformat YYYY-MM-DD")
                    else if (parsedDate > today) add("Tanggal saldo awal tidak boleh di masa depan")
                    if (openingNote.isBlank()) add("Catatan saldo awal wajib diisi")
                    if (openingNote.length > 500) add("Catatan saldo awal maksimal 500 karakter")
                }
            }
        }
        ProductCsvRowPreview(
            index + 2, sku, name, category, unit, values[4].trim(), values[5].trim(),
            values[6].trim(), values[7].trim(), values[8].trim(), openingDate, openingNote, errors
        )
    }
    return ProductCsvPreviewResponse(rows.size, rows.count { it.valid }, rows.count { !it.valid }, rows)
}

private fun parseCsv(content: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0
    val normalized = content.removePrefix("\uFEFF")
    while (index < normalized.length) {
        val char = normalized[index]
        when {
            char == '"' && quoted && index + 1 < normalized.length && normalized[index + 1] == '"' -> {
                field.append('"'); index++
            }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> { row.add(field.toString()); field.clear() }
            (char == '\n' || char == '\r') && !quoted -> {
                if (char == '\r' && index + 1 < normalized.length && normalized[index + 1] == '\n') index++
                row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf()
            }
            else -> field.append(char)
        }
        index++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
    return rows
}

private fun String.decimalOrNull(): BigDecimal? = trim().replace(',', '.').toBigDecimalOrNull()

private fun emptyPreview(rowNumber: Int, errors: List<String>) = ProductCsvRowPreview(
    rowNumber, "", "", "", "", "", "", "", "", "", "", "", errors
)
