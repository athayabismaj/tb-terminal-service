package com.service.tbterminal.system

import com.service.tbterminal.inventory.PaginatedResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

class SystemRepository {

    suspend fun getRoles(): List<RoleResponse> = newSuspendedTransaction(Dispatchers.IO) {
        RolesTable.selectAll().orderBy(RolesTable.name, SortOrder.ASC).map {
            RoleResponse(
                id = it[RolesTable.id].toString(),
                name = it[RolesTable.name]
            )
        }
    }

    suspend fun getPaginatedUsers(page: Int, limit: Int, search: String?): PaginatedResponse<UserResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val offset = ((page - 1) * limit).toLong()

        var query = (UsersTable innerJoin RolesTable).selectAll()

        if (!search.isNullOrBlank()) {
            val searchTerm = "%${search.lowercase()}%"
            query = query.andWhere {
                (UsersTable.name.lowerCase() like searchTerm) or
                (UsersTable.username.lowerCase() like searchTerm)
            }
        }

        val totalCount = query.count()
        val totalPages = kotlin.math.ceil(totalCount.toDouble() / limit).toInt()

        val data = query
            .orderBy(UsersTable.name, SortOrder.ASC)
            .limit(limit, offset)
            .map {
                UserResponse(
                    id = it[UsersTable.id].toString(),
                    roleId = it[UsersTable.roleId].toString(),
                    roleName = it[RolesTable.name],
                    name = it[UsersTable.name],
                    username = it[UsersTable.username],
                    isActive = it[UsersTable.isActive],
                    lastLogin = it[UsersTable.lastLogin]?.toString(),
                    createdAt = it[UsersTable.createdAt].toString()
                )
            }

        PaginatedResponse(
            data = data,
            total = totalCount,
            page = page,
            limit = limit,
            totalPages = totalPages
        )
    }

    suspend fun getUserById(id: UUID): UserResponse? = newSuspendedTransaction(Dispatchers.IO) {
        (UsersTable innerJoin RolesTable)
            .select { UsersTable.id eq id }
            .singleOrNull()?.let {
                UserResponse(
                    id = it[UsersTable.id].toString(),
                    roleId = it[UsersTable.roleId].toString(),
                    roleName = it[RolesTable.name],
                    name = it[UsersTable.name],
                    username = it[UsersTable.username],
                    isActive = it[UsersTable.isActive],
                    lastLogin = it[UsersTable.lastLogin]?.toString(),
                    createdAt = it[UsersTable.createdAt].toString()
                )
            }
    }

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

    suspend fun createUser(name: String, username: String, pinHash: String, roleId: UUID): UUID = newSuspendedTransaction(Dispatchers.IO) {
        UsersTable.insert {
            it[this.name] = name
            it[this.username] = username
            it[this.pinHash] = pinHash
            it[this.roleId] = roleId
            it[this.isActive] = true
        } get UsersTable.id
    }

    suspend fun updateUser(id: UUID, name: String, username: String, roleId: UUID, isActive: Boolean, pinHash: String?): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val updated = UsersTable.update({ UsersTable.id eq id }) {
            it[this.name] = name
            it[this.username] = username
            it[this.roleId] = roleId
            it[this.isActive] = isActive
            it[this.updatedAt] = Instant.now()
            if (pinHash != null) {
                it[this.pinHash] = pinHash
            }
        }
        updated > 0
    }

    suspend fun updatePin(id: UUID, pinHash: String): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val updated = UsersTable.update({ UsersTable.id eq id }) {
            it[this.pinHash] = pinHash
            it[this.updatedAt] = Instant.now()
        }
        updated > 0
    }

    suspend fun softDeleteUser(id: UUID): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val updated = UsersTable.update({ UsersTable.id eq id }) {
            it[this.isActive] = false
            it[this.updatedAt] = Instant.now()
        }
        updated > 0
    }

    suspend fun updateLastLogin(id: UUID) {
        newSuspendedTransaction(Dispatchers.IO) {
            UsersTable.update({ UsersTable.id eq id }) {
                it[lastLogin] = Instant.now()
            }
        }
    }
}

