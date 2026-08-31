package com.service.tbterminal.shared

/**
 * Konstanta role pengguna.
 * WAJIB gunakan konstanta ini — JANGAN hardcode string role di tempat lain.
 */
object Role {
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val KASIR = "kasir"

    /** Semua role yang valid dalam sistem */
    val ALL = listOf(OWNER, ADMIN, KASIR)

    /** Role yang memiliki akses manajemen (bukan kasir) */
    val MANAGEMENT = listOf(OWNER, ADMIN)
}

/**
 * Izin bisnis terpusat. Route dan service wajib merujuk izin ini agar matriks
 * akses tidak tersebar sebagai kombinasi string role di setiap modul.
 */
enum class Permission(internal val allowedRoles: Set<String>) {
    MANAGE_USERS(setOf(Role.OWNER)),
    MANAGE_ROLES(setOf(Role.OWNER)),
    MANAGE_DATABASE_BACKUPS(setOf(Role.OWNER)),
    MANAGE_SECURITY_SETTINGS(setOf(Role.OWNER)),
    REQUEST_MANAGER_APPROVAL(Role.ALL.toSet()),
    APPROVE_SENSITIVE_ACTION(Role.MANAGEMENT.toSet()),
    READ_STORE_PROFILE(Role.ALL.toSet()),
    UPDATE_STORE_PROFILE(Role.MANAGEMENT.toSet()),
    VIEW_AUDIT_LOG(Role.MANAGEMENT.toSet()),
    MANAGE_INVENTORY(Role.MANAGEMENT.toSet()),
    MANAGE_PURCHASING(Role.MANAGEMENT.toSet()),
    USE_RECEIVABLES(Role.ALL.toSet()),
    MANAGE_RECEIVABLES(Role.MANAGEMENT.toSet()),
    OPERATE_POS(Role.ALL.toSet()),
    MANAGE_CASH_SESSIONS(Role.MANAGEMENT.toSet()),
    VOID_TRANSACTION(Role.MANAGEMENT.toSet()),
    REFUND_TRANSACTION(Role.MANAGEMENT.toSet()),
    OVERRIDE_DISCOUNT_LIMIT(Role.MANAGEMENT.toSet()),
    VIEW_ANALYTICS(Role.MANAGEMENT.toSet())
}

object AccessPolicy {
    fun isAllowed(role: String, permission: Permission): Boolean = role in permission.allowedRoles

    fun require(role: String, permission: Permission) {
        if (!isAllowed(role, permission)) {
            throw ForbiddenException("Akses ditolak untuk operasi ${permission.name.lowercase()}")
        }
    }
}
