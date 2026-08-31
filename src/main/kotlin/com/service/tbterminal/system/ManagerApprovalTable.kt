package com.service.tbterminal.system

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object ManagerApprovalsTable : Table("system.manager_approvals") {
    val id = uuid("id").databaseGenerated()
    val requestedByUserId = uuid("requested_by_user_id").references(UsersTable.id)
    val approvedByUserId = uuid("approved_by_user_id").references(UsersTable.id)
    val action = varchar("action", 40)
    val resourceType = varchar("resource_type", 40).nullable()
    val resourceId = uuid("resource_id").nullable()
    val status = varchar("status", 16)
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()
    val expiresAt = timestampWithTimeZone("expires_at")
    val usedAt = timestampWithTimeZone("used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
