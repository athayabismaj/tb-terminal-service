package com.service.tbterminal.shared

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import java.util.UUID

/**
 * Extension untuk mengambil ID User dari JWT Principal.
 * Melempar UnauthorizedException jika token tidak valid atau tidak ada.
 */
fun ApplicationCall.getUserId(): UUID {
    val principal = principal<JWTPrincipal>()
    val idString = principal?.payload?.getClaim("id")?.asString() 
        ?: throw UnauthorizedException("Token tidak valid atau tidak ditemukan")
    
    return try {
        UUID.fromString(idString)
    } catch (e: IllegalArgumentException) {
        throw UnauthorizedException("ID User dalam token tidak valid")
    }
}

/**
 * Extension untuk memvalidasi Role User dari JWT Principal.
 * Melempar ForbiddenException jika user tidak memiliki role yang diizinkan.
 */
fun ApplicationCall.requireRole(vararg allowedRoles: String) {
    val principal = principal<JWTPrincipal>()
    val role = principal?.payload?.getClaim("role")?.asString() 
        ?: throw UnauthorizedException("Role tidak ditemukan dalam token")

    if (role !in allowedRoles) {
        throw ForbiddenException("Akses ditolak: Membutuhkan role ${allowedRoles.joinToString(" atau ")}")
    }
}

/**
 * Helper extension untuk merespon error secara eksplisit tanpa melempar Exception.
 * Biasanya digunakan ketika tidak ingin menggunakan StatusPages untuk case spesifik.
 * Secara default, disarankan untuk throw Exception dan biarkan StatusPages menangani.
 */
suspend fun ApplicationCall.respondError(exception: Throwable) {
    val (status, code, message) = when (exception) {
        is UnauthorizedException -> Triple(HttpStatusCode.Unauthorized, "UNAUTHORIZED", exception.message ?: "Tidak ada akses")
        is ForbiddenException -> Triple(HttpStatusCode.Forbidden, "FORBIDDEN", exception.message ?: "Akses ditolak")
        is NotFoundException -> Triple(HttpStatusCode.NotFound, "NOT_FOUND", exception.message ?: "Data tidak ditemukan")
        is ValidationException -> Triple(HttpStatusCode.BadRequest, "VALIDATION_ERROR", exception.message ?: "Validasi gagal")
        else -> Triple(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "Terjadi kesalahan sistem")
    }
    
    respond(status, ApiResponse.error<Unit>(message, code))
}
