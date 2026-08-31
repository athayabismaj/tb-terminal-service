package com.service.tbterminal.sales

import java.util.UUID

class DiscountService(
    private val repository: DiscountRepository
) {
    suspend fun preview(
        actorUserId: UUID,
        actorRole: String,
        request: CheckoutPreviewRequest
    ): CheckoutPreviewResponse {
        validateCheckoutItems(request.items)
        // Calculator di repository memakai harga jual terbaru dari database.
        return repository.createPreview(actorUserId, actorRole, request)
    }
}
