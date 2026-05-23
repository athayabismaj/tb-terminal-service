package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ValidationException
import java.util.UUID

internal data class ProductDraft(
    val sku: String,
    val name: String,
    val categoryId: UUID,
    val unitId: UUID
)

internal suspend fun validateProductDraft(
    repository: InventoryRepository,
    request: ProductCreateRequest
): ProductDraft {
    val draft = ProductDraft(
        sku = request.sku.trim(),
        name = request.name.trim(),
        categoryId = parseInventoryUUID(request.categoryId),
        unitId = parseInventoryUUID(request.baseUnitId)
    )

    if (draft.sku.isEmpty()) throw ValidationException("SKU tidak boleh kosong")
    if (draft.name.isEmpty()) throw ValidationException("Nama produk tidak boleh kosong")

    repository.getCategoryById(draft.categoryId) ?: throw ValidationException("Kategori tidak valid atau tidak ditemukan")
    repository.getUnitById(draft.unitId) ?: throw ValidationException("Satuan tidak valid atau tidak ditemukan")
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
        minStock = request.minStock,
        photoFilename = request.photoFilename
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
        minStock = request.minStock,
        photoFilename = request.photoFilename
    )
    return repository.getProductById(newId)!!
}
