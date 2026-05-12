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

    // ==========================================
    // STORE SETTINGS (Singleton)
    // ==========================================

    suspend fun getStoreSettings(): StoreSettingsResponse = newSuspendedTransaction(Dispatchers.IO) {
        val row = StoreSettingsTable.selectAll().firstOrNull()

        if (row != null) {
            return@newSuspendedTransaction StoreSettingsResponse(
                id = row[StoreSettingsTable.id].toString(),
                storeName = row[StoreSettingsTable.storeName],
                address = row[StoreSettingsTable.address],
                phone = row[StoreSettingsTable.phone],
                receiptHeader = row[StoreSettingsTable.receiptHeader],
                receiptFooter = row[StoreSettingsTable.receiptFooter],
                printerSize = row[StoreSettingsTable.printerSize].dbValue,
                updatedAt = row[StoreSettingsTable.updatedAt].toString()
            )
        }

        // Jika kosong, insert default row
        val newId = StoreSettingsTable.insert {
            it[storeName] = "Toko Bangunan Default"
            it[printerSize] = PrinterSize.SIZE_80
        } get StoreSettingsTable.id

        val newRow = StoreSettingsTable.select { StoreSettingsTable.id eq newId }.single()
        
        StoreSettingsResponse(
            id = newRow[StoreSettingsTable.id].toString(),
            storeName = newRow[StoreSettingsTable.storeName],
            address = newRow[StoreSettingsTable.address],
            phone = newRow[StoreSettingsTable.phone],
            receiptHeader = newRow[StoreSettingsTable.receiptHeader],
            receiptFooter = newRow[StoreSettingsTable.receiptFooter],
            printerSize = newRow[StoreSettingsTable.printerSize].dbValue,
            updatedAt = newRow[StoreSettingsTable.updatedAt].toString()
        )
    }

    suspend fun updateStoreSettings(
        userId: UUID,
        storeName: String,
        address: String?,
        phone: String?,
        receiptHeader: String?,
        receiptFooter: String?,
        printerSize: PrinterSize
    ): StoreSettingsResponse = newSuspendedTransaction(Dispatchers.IO) {
        
        // Memastikan row pertama ada, agar bisa di-update
        val existing = StoreSettingsTable.selectAll().firstOrNull()
        if (existing == null) {
            getStoreSettings() // Akan membuat row default
        }

        StoreSettingsTable.update({ Op.TRUE }) {
            it[this.storeName] = storeName
            it[this.address] = address
            it[this.phone] = phone
            it[this.receiptHeader] = receiptHeader
            it[this.receiptFooter] = receiptFooter
            it[this.printerSize] = printerSize
            it[this.updatedBy] = userId
            it[this.updatedAt] = Instant.now()
        }

        val updatedRow = StoreSettingsTable.selectAll().first()
        
        StoreSettingsResponse(
            id = updatedRow[StoreSettingsTable.id].toString(),
            storeName = updatedRow[StoreSettingsTable.storeName],
            address = updatedRow[StoreSettingsTable.address],
            phone = updatedRow[StoreSettingsTable.phone],
            receiptHeader = updatedRow[StoreSettingsTable.receiptHeader],
            receiptFooter = updatedRow[StoreSettingsTable.receiptFooter],
            printerSize = updatedRow[StoreSettingsTable.printerSize].dbValue,
            updatedAt = updatedRow[StoreSettingsTable.updatedAt].toString()
        )
    }
}

