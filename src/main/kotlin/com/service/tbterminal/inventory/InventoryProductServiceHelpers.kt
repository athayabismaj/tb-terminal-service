package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ValidationException
import java.util.UUID

internal data class ProductDraft(
    val sku: String,
    val name: String,
    val categoryId: UUID,
    val unitId: UUID,
    val secondaryUnitId: UUID?,
    val secondaryUnitFactor: java.math.BigDecimal?
)

internal suspend fun validateProductDraft(
    repository: InventoryRepository,
    request: ProductCreateRequest
): ProductDraft {
    val baseUnitId = parseInventoryUUID(request.baseUnitId)
    val secondaryUnitId = request.secondaryUnitId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(::parseInventoryUUID)
    val secondaryUnitFactor = parseUnitConversionFactor(request.secondaryUnitFactor)
    if (!request.secondaryUnitFactor.isNullOrBlank() && secondaryUnitFactor == null) {
        throw ValidationException("Faktor konversi wajib berupa angka valid")
    }
    val draft = ProductDraft(
        sku = normalizeSku(request.sku),
        name = request.name.trim(),
        categoryId = parseInventoryUUID(request.categoryId),
        unitId = baseUnitId,
        secondaryUnitId = secondaryUnitId,
        secondaryUnitFactor = secondaryUnitFactor
    )

    validateSku(draft.sku)?.let { throw ValidationException(it) }
    requireValidProductValues(
        draft.name, request.priceBuy, request.priceRetail, request.priceContractor,
        request.discount, request.minStock
    )

    repository.getCategoryById(draft.categoryId) ?: throw ValidationException("Kategori tidak valid atau tidak ditemukan")
    repository.getUnitById(draft.unitId) ?: throw ValidationException("Satuan tidak valid atau tidak ditemukan")
    validateUnitConversion(draft.unitId, draft.secondaryUnitId, draft.secondaryUnitFactor)
        ?.let { throw ValidationException(it) }
    draft.secondaryUnitId?.let { id ->
        repository.getUnitById(id) ?: throw ValidationException("Satuan kedua tidak valid atau tidak ditemukan")
    }
    return draft
}

internal suspend fun restoreInactiveProduct(
    repository: InventoryRepository,
    productId: String,
    draft: ProductDraft,
    request: ProductCreateRequest
): ProductResponse {
    val existingUuid = UUID.fromString(productId)
    repository.restoreProductAndOverwrite(
        id = existingUuid,
        categoryId = draft.categoryId,
        baseUnitId = draft.unitId,
        name = draft.name,
        priceBuy = request.priceBuy,
        priceRetail = request.priceRetail,
        priceContractor = request.priceContractor,
        discount = request.discount,
        minStock = request.minStock,
        photoFilename = request.photoFilename,
        secondaryUnitId = draft.secondaryUnitId,
        secondaryUnitFactor = draft.secondaryUnitFactor
    )
    return repository.getProductById(existingUuid)!!
}

internal suspend fun createNewProduct(
    repository: InventoryRepository,
    draft: ProductDraft,
    request: ProductCreateRequest
): ProductResponse {
    val newId = repository.createProductAndInitStock(
        categoryId = draft.categoryId,
        baseUnitId = draft.unitId,
        sku = draft.sku,
        name = draft.name,
        priceBuy = request.priceBuy,
        priceRetail = request.priceRetail,
        priceContractor = request.priceContractor,
        discount = request.discount,
        minStock = request.minStock,
        photoFilename = request.photoFilename,
        secondaryUnitId = draft.secondaryUnitId,
        secondaryUnitFactor = draft.secondaryUnitFactor
    )
    return repository.getProductById(newId)!!
}

