package com.service.tbterminal.system

import com.service.tbterminal.shared.jsonb
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.postgresql.util.PGobject

object RolesTable : Table("system.roles") {
    val id = uuid("id").databaseGenerated()
    val name = varchar("name", 50)
    override val primaryKey = PrimaryKey(id)
}

object UsersTable : Table("system.users") {
    val id = uuid("id").databaseGenerated()
    val roleId = uuid("role_id").references(RolesTable.id)
    val name = varchar("name", 100)
    val username = varchar("username", 50)
    val passwordHash = varchar("password_hash", 255)
    val pinHash = varchar("pin_hash", 255)
    val email = varchar("email", 150).nullable()
    val isActive = bool("is_active")
    val tokenVersion = integer("token_version").default(0)
    val lastLogin = timestampWithTimeZone("last_login").nullable()
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()
    val updatedAt = timestampWithTimeZone("updated_at").databaseGenerated()
    
    override val primaryKey = PrimaryKey(id)
}

enum class AuditAction {
    INSERT,
    UPDATE,
    DELETE
}

object AuditLogsTable : Table("system.audit_logs") {
    val id = uuid("id").databaseGenerated()
    val userId = uuid("user_id").references(UsersTable.id).nullable()
    val action = customEnumeration(
        name = "action",
        sql = "system.audit_action",
        fromDb = { value -> AuditAction.valueOf(value.toString().uppercase()) },
        toDb = { value ->
            PGobject().apply {
                type = "system.audit_action"
                this.value = value.name
            }
        }
    )
    val targetSchemaName = varchar("schema_name", 50)
    val targetTableName = varchar("table_name", 100)
    val recordId = uuid("record_id").nullable()
    val oldData = jsonb("old_data").nullable()
    val newData = jsonb("new_data").nullable()
    val ipAddress = varchar("ip_address", 45).nullable()
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

enum class PrinterSize(val dbValue: String) {
    SIZE_58("58mm"),
    SIZE_80("80mm")
}

object StoreSettingsTable : Table("system.store_settings") {
    val id = uuid("id").databaseGenerated()
    val storeName = varchar("store_name", 150).default("Toko Bangunan")
    val address = text("address").nullable()
    val phone = varchar("phone", 20).nullable()
    val receiptHeader = text("receipt_header").nullable()
    val receiptFooter = text("receipt_footer").nullable()
    val printerSize = customEnumeration(
        name = "printer_size",
        sql = "system.printer_size",
        fromDb = { PrinterSize.entries.first { e -> e.dbValue == it.toString() } },
        toDb = { value -> postgresEnum("system.printer_size", value.dbValue) }
    )
    val cashierDiscountLimitPercent = decimal("cashier_discount_limit_percent", 5, 2)
        .default(java.math.BigDecimal("10.00"))
        .default(java.math.BigDecimal("10.00"))
    val updatedBy = uuid("updated_by").references(UsersTable.id).nullable()
    val updatedAt = timestampWithTimeZone("updated_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

private fun postgresEnum(type: String, value: String): PGobject {
    return PGobject().apply {
        this.type = type
        this.value = value
    }
}
