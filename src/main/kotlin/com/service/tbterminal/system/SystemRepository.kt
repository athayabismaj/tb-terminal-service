package com.service.tbterminal.system

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

class SystemRepository {

    suspend fun findUserByUsername(username: String): UserRow? =
        newSuspendedTransaction(Dispatchers.IO) {
            (UsersTable innerJoin RolesTable)
                .select { UsersTable.username eq username }
                .singleOrNull()
                ?.let {
                    UserRow(
                        id = it[UsersTable.id],
                        username = it[UsersTable.username],
                        name = it[UsersTable.name],
                        pinHash = it[UsersTable.pinHash],
                        roleName = it[RolesTable.name],
                        isActive = it[UsersTable.isActive]
                    )
                }
        }

    suspend fun updateLastLogin(id: java.util.UUID) {
        newSuspendedTransaction(Dispatchers.IO) {
            UsersTable.update({ UsersTable.id eq id }) {
                it[lastLogin] = Instant.now()
            }
        }
    }
}
