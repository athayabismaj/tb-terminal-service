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
