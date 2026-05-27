package com.service.tbterminal.plugins

import com.service.tbterminal.shared.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.jetbrains.exposed.exceptions.ExposedSQLException

fun Application.configureStatusPages() {
    install(StatusPages) {
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
