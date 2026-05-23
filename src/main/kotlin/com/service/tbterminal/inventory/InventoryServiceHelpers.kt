package com.service.tbterminal.inventory

import com.service.tbterminal.shared.ValidationException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.util.UUID

internal fun parseInventoryUUID(id: String): UUID {
    return try {
        UUID.fromString(id)
    } catch (e: IllegalArgumentException) {
        throw ValidationException("Format ID tidak valid")
    }
}

internal fun throwIfDeleteConstraintViolation(e: Exception) {
    val message = e.message.orEmpty()
    val isForeignKeyViolation = e is ExposedSQLException && e.sqlState == "23503"
    val containsConstraintMessage = message.contains("foreign key constraint") ||
        message.contains("violates foreign key constraint")

    if (isForeignKeyViolation || containsConstraintMessage) {
        throw ValidationException("Data tidak dapat dihapus karena sedang digunakan")
    }
}
