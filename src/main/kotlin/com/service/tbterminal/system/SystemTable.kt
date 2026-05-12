package com.service.tbterminal.system

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object RolesTable : Table("system.roles") {
    val id = uuid("id")
    val name = varchar("name", 50)
    override val primaryKey = PrimaryKey(id)
}

object UsersTable : Table("system.users") {
    val id = uuid("id")
    val roleId = uuid("role_id").references(RolesTable.id)
    val name = varchar("name", 100)
    val username = varchar("username", 50)
    val pinHash = varchar("pin_hash", 255)
    val isActive = bool("is_active")
    val lastLogin = timestamp("last_login").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    
    override val primaryKey = PrimaryKey(id)
}
