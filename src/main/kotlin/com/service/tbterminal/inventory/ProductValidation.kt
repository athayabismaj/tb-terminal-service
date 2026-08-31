package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private val SKU_PATTERN = Regex("^[A-Z0-9][A-Z0-9._-]{0,49}$")
private val MAX_PRICE = BigDecimal("9999999999999.99")
private val MAX_STOCK = BigDecimal("99999999.99")
private val MAX_UNIT_CONVERSION_FACTOR = BigDecimal("999999.9999")

internal fun normalizeSku(value: String): String = value.trim().uppercase()
internal fun inventoryToday(): LocalDate = LocalDate.now(ZoneId.of("Asia/Jakarta"))

internal fun validateSku(sku: String): String? = when {
    sku.isBlank() -> "SKU wajib diisi"
    sku.length > 50 -> "SKU maksimal 50 karakter"
    !SKU_PATTERN.matches(sku) -> "SKU hanya boleh berisi huruf, angka, titik, garis bawah, atau tanda hubung"
    else -> null
}

internal fun validateProductValues(
    name: String,
    priceBuy: BigDecimal,
    priceRetail: BigDecimal,
    priceContractor: BigDecimal,
    discount: BigDecimal,
    minStock: BigDecimal
): List<String> = buildList {
    if (name.isBlank()) add("Nama produk wajib diisi")
    if (name.length > 200) add("Nama produk maksimal 200 karakter")
    validateMoney("Harga beli", priceBuy)?.let(::add)
    validateMoney("Harga jual retail", priceRetail)?.let(::add)
    validateMoney("Harga jual kontraktor", priceContractor)?.let(::add)
    validateMoney("Diskon", discount)?.let(::add)
    when {
        minStock < BigDecimal.ZERO -> add("Stok minimum tidak boleh negatif")
        minStock > MAX_STOCK -> add("Stok minimum melebihi batas")
        minStock.scale() > 2 -> add("Stok minimum maksimal 2 angka desimal")
    }
}

internal fun requireValidProductValues(
    name: String,
    priceBuy: BigDecimal,
    priceRetail: BigDecimal,
    priceContractor: BigDecimal,
    discount: BigDecimal,
    minStock: BigDecimal
) {
    val error = validateProductValues(name, priceBuy, priceRetail, priceContractor, discount, minStock).firstOrNull()
    if (error != null) throw ValidationException(error)
}

internal fun parseUnitConversionFactor(value: String?): BigDecimal? {
    val raw = value?.trim()?.replace(',', '.')?.takeIf(String::isNotBlank) ?: return null
    return raw.toBigDecimalOrNull()
}

internal fun validateUnitConversion(
    baseUnitId: UUID,
    secondaryUnitId: UUID?,
    factor: BigDecimal?
): String? = when {
    secondaryUnitId == null && factor == null -> null
    secondaryUnitId == null -> "Satuan kedua wajib dipilih"
    factor == null -> "Faktor konversi wajib berupa angka valid"
    secondaryUnitId == baseUnitId -> "Satuan kedua harus berbeda dari satuan utama"
    factor <= BigDecimal.ZERO -> "Faktor konversi harus lebih dari nol"
    factor > MAX_UNIT_CONVERSION_FACTOR -> "Faktor konversi melebihi batas"
    factor.scale() > 4 -> "Faktor konversi maksimal 4 angka desimal"
    else -> null
}

private fun validateMoney(label: String, value: BigDecimal): String? = when {
    value < BigDecimal.ZERO -> "$label tidak boleh negatif"
    value > MAX_PRICE -> "$label melebihi batas"
    value.scale() > 2 -> "$label maksimal 2 angka desimal"
    else -> null
}
