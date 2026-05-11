package com.service.tbterminal.plugins

import com.service.tbterminal.shared.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ApiResponse.error<Unit>(cause.message ?: "Data tidak ditemukan", "NOT_FOUND"))
        }
        exception<ForbiddenException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, ApiResponse.error<Unit>(cause.message ?: "Akses ditolak", "FORBIDDEN"))
        }
        exception<ValidationException> { call, cause ->
            call.respond(HttpStatusCode.UnprocessableEntity, ApiResponse.error<Unit>(cause.message ?: "Validasi gagal", "VALIDATION_ERROR"))
        }
        exception<StockInsufficientException> { call, cause ->
            call.respond(HttpStatusCode.UnprocessableEntity, ApiResponse.error<Unit>(cause.message ?: "Stok tidak mencukupi", "STOCK_INSUFFICIENT"))
        }
        exception<CreditLimitExceededException> { call, cause ->
            call.respond(HttpStatusCode.UnprocessableEntity, ApiResponse.error<Unit>(cause.message ?: "Limit kredit terlampaui", "CREDIT_LIMIT_EXCEEDED"))
        }
        exception<UsernameTakenException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ApiResponse.error<Unit>(cause.message ?: "Username sudah digunakan", "USERNAME_TAKEN"))
        }
        exception<SkuDuplicateException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ApiResponse.error<Unit>(cause.message ?: "SKU sudah digunakan", "SKU_DUPLICATE"))
        }
        exception<SessionNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ApiResponse.error<Unit>(cause.message ?: "Sesi kasir tidak aktif", "SESSION_NOT_FOUND"))
        }
        // Catch-all: jangan bocorkan stack trace ke client
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiResponse.error<Unit>("Terjadi kesalahan pada server", "INTERNAL_ERROR"))
        }
    }
}
