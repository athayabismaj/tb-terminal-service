package com.service.tbterminal.plugins

import com.service.tbterminal.shared.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.jetbrains.exposed.exceptions.ExposedSQLException

fun Application.configureStatusPages() {
    install(StatusPages) {
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse("RATE_LIMITED", "Terlalu banyak percobaan. Silakan tunggu sebelum mencoba lagi")
            )
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_REQUEST", "Format request atau response tidak valid")
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_REQUEST", "Format request tidak valid")
            )
        }
        exception<NotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("NOT_FOUND", cause.message ?: "Data tidak ditemukan")
            )
        }
        exception<UnauthorizedException> { call, cause ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse("UNAUTHORIZED", cause.message ?: "Sesi tidak valid")
            )
        }
        exception<ForbiddenException> { call, cause ->
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("FORBIDDEN", cause.message ?: "Akses ditolak")
            )
        }
        exception<ValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("VALIDATION_ERROR", cause.message ?: "Validasi gagal")
            )
        }
        exception<StockInsufficientException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse("STOCK_INSUFFICIENT", cause.message ?: "Stok tidak mencukupi")
            )
        }
        exception<CreditLimitExceededException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse("CREDIT_LIMIT_EXCEEDED", cause.message ?: "Limit kredit terlampaui")
            )
        }
        exception<UsernameTakenException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("USERNAME_TAKEN", cause.message ?: "Username sudah digunakan")
            )
        }
        exception<SkuDuplicateException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("SKU_DUPLICATE", cause.message ?: "SKU sudah digunakan")
            )
        }
        exception<SessionNotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("SESSION_NOT_FOUND", cause.message ?: "Sesi kasir tidak aktif")
            )
        }
        exception<PayloadTooLargeException> { call, cause ->
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse("PAYLOAD_TOO_LARGE", cause.message ?: "Ukuran file melebihi batas maksimum")
            )
        }
        exception<ServiceUnavailableException> { call, cause ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("SERVICE_UNAVAILABLE", cause.message ?: "Layanan sementara tidak tersedia")
            )
        }
        exception<BusinessConflictException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse(cause.code, cause.message ?: "Data bertentangan dengan aturan bisnis server")
            )
        }
        exception<TooManyAttemptsException> { call, cause ->
            cause.retryAfterSeconds?.takeIf { it > 0 }?.let {
                call.response.headers.append(HttpHeaders.RetryAfter, it.toString())
            }
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse("TOO_MANY_ATTEMPTS", cause.message ?: "Terlalu banyak percobaan gagal")
            )
        }
        exception<ManagerApprovalException> { call, cause ->
            val status = when (cause.reason) {
                ManagerApprovalError.EXPIRED,
                ManagerApprovalError.ALREADY_USED -> HttpStatusCode.Conflict

                ManagerApprovalError.APPROVER_LOCKED -> HttpStatusCode.TooManyRequests

                else -> HttpStatusCode.Forbidden
            }
            call.respond(
                status,
                ErrorResponse(cause.reason.code, cause.message ?: "Manager approval tidak valid")
            )
        }
        exception<ExposedSQLException> { call, cause ->
            when (cause.sqlState) {
                "23505" -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("DUPLICATE_DATA", "Data sudah ada")
                )

                "23514" -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "FINANCIAL_CONSTRAINT_VIOLATION",
                        "Data melanggar batasan finansial"
                    )
                )

                else -> {
                    call.application.log.error("Unhandled database exception", cause)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(
                            code = "INTERNAL_SERVER_ERROR",
                            message = "Terjadi kesalahan pada server",
                            details = call.application.developmentErrorDetails(cause)
                        )
                    )
                }
            }
        }
        exception<java.io.IOException> { call, cause ->
            if (cause.message?.contains("exceeds limit", ignoreCase = true) == true) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorResponse("PAYLOAD_TOO_LARGE", "Ukuran file melebihi batas maksimum")
                )
            } else {
                call.application.log.error("Unhandled exception", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(
                        code = "INTERNAL_SERVER_ERROR",
                        message = "Terjadi kesalahan pada server",
                        details = call.application.developmentErrorDetails(cause)
                    )
                )
            }
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    code = "INTERNAL_SERVER_ERROR",
                    message = "Terjadi kesalahan pada server",
                    details = call.application.developmentErrorDetails(cause)
                )
            )
        }
    }
}

private fun Application.developmentErrorDetails(cause: Throwable): String? {
    if (!developmentMode) return null
    return buildString {
        append(cause::class.qualifiedName ?: cause::class.simpleName ?: "Throwable")
        cause.message?.let { message ->
            append(": ")
            append(message)
        }
    }
}
