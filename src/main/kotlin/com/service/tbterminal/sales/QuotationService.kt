package com.service.tbterminal.sales

import com.service.tbterminal.inventory.PaginatedResponse
import java.util.UUID

class QuotationService(
    private val repository: QuotationRepository
) {
    suspend fun createQuotation(actorUserId: UUID, request: CreateQuotationRequest): QuotationDetailResponse {
        QuotationValidation.validateCreateRequest(request)
        return repository.createQuotation(actorUserId, request)
    }

    suspend fun getQuotations(
        page: Int,
        limit: Int,
        status: String?,
        search: String?,
        actorUserId: UUID,
        actorRole: String,
    ): PaginatedResponse<QuotationSummaryResponse> =
        repository.getQuotations(page, limit, status, search, actorUserId, actorRole)

    suspend fun getQuotationById(
        quotationIdRaw: String,
        actorUserId: UUID,
        actorRole: String,
    ): QuotationDetailResponse {
        val qid = QuotationValidation.parseUuid(quotationIdRaw, "quotationId")
        return repository.getQuotationById(qid, actorUserId, actorRole)
    }

    suspend fun updateQuotationStatus(
        quotationIdRaw: String,
        actorUserId: UUID,
        actorRole: String,
        request: UpdateQuotationStatusRequest,
    ): QuotationDetailResponse {
        val qid = QuotationValidation.parseUuid(quotationIdRaw, "quotationId")
        return repository.updateQuotationStatus(qid, actorUserId, actorRole, request)
    }
}
