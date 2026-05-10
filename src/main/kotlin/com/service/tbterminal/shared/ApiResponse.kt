package com.service.tbterminal.shared

import kotlinx.serialization.Serializable

@Serializable
data class PaginationMeta(
    val total: Int,
    val page: Int,
    val perPage: Int,
    val totalPages: Int
)

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
    val meta: PaginationMeta? = null
) {
    companion object {
        fun <T> success(data: T, message: String? = "Berhasil"): ApiResponse<T> {
            return ApiResponse(success = true, data = data, message = message)
        }

        fun <T> successWithMeta(data: T, meta: PaginationMeta, message: String? = "Berhasil"): ApiResponse<T> {
            return ApiResponse(success = true, data = data, message = message, meta = meta)
        }

        fun <T> error(error: String, code: String? = null): ApiResponse<T> {
            return ApiResponse(success = false, error = error, code = code)
        }
    }
}
