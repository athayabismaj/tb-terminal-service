package com.service.tbterminal.system

import com.service.tbterminal.inventory.PaginatedResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
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
                    email = it[UsersTable.email],
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
                    email = it[UsersTable.email],
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
                        passwordHash = it[UsersTable.passwordHash],
                        pinHash = it[UsersTable.pinHash],
                        email = it[UsersTable.email],
                        roleName = it[RolesTable.name],
                        isActive = it[UsersTable.isActive],
                        tokenVersion = it[UsersTable.tokenVersion],
                        createdAt = it[UsersTable.createdAt],
                        lastLoginAt = it[UsersTable.lastLogin]
                    )
                }
        }

    suspend fun findAuthenticationUserById(id: UUID): AuthenticationUserState? =
        newSuspendedTransaction(Dispatchers.IO) {
            (UsersTable innerJoin RolesTable)
                .select { UsersTable.id eq id }
                .singleOrNull()
                ?.let {
                    AuthenticationUserState(
                        id = it[UsersTable.id],
                        username = it[UsersTable.username],
                        roleName = it[RolesTable.name],
                        isActive = it[UsersTable.isActive],
                        tokenVersion = it[UsersTable.tokenVersion]
                    )
                }
        }

    suspend fun createUser(name: String, username: String, passwordHash: String, pinHash: String, email: String?, roleId: UUID): UUID = newSuspendedTransaction(Dispatchers.IO) {
        val newId = UUID.randomUUID()
        UsersTable.insert {
            it[this.id] = newId
            it[this.name] = name
            it[this.username] = username
            it[this.passwordHash] = passwordHash
            it[this.pinHash] = pinHash
            it[this.email] = email
            it[this.roleId] = roleId
            it[this.isActive] = true
        }
        newId
    }

    suspend fun updateUser(id: UUID, name: String, username: String, roleId: UUID, isActive: Boolean, email: String?, passwordHash: String?, pinHash: String?): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val updated = UsersTable.update({ UsersTable.id eq id }) {
            it[this.name] = name
            it[this.username] = username
            it[this.roleId] = roleId
            it[this.isActive] = isActive
            it[this.email] = email
            it[this.updatedAt] = OffsetDateTime.now()
            if (passwordHash != null) {
                it[this.passwordHash] = passwordHash
            }
            if (pinHash != null) {
                it[this.pinHash] = pinHash
            }
        }
        updated > 0
    }

    suspend fun updatePin(id: UUID, pinHash: String): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val updated = UsersTable.update({ UsersTable.id eq id }) {
            it[this.pinHash] = pinHash
            it[this.updatedAt] = OffsetDateTime.now()
        }
        updated > 0
    }

    suspend fun updatePassword(id: UUID, passwordHash: String): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val updated = UsersTable.update({ UsersTable.id eq id }) {
            it[this.passwordHash] = passwordHash
            it[this.updatedAt] = OffsetDateTime.now()
        }
        updated > 0
    }

    suspend fun softDeleteUser(id: UUID): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val updated = UsersTable.update({ UsersTable.id eq id }) {
            it[this.isActive] = false
            it[this.updatedAt] = OffsetDateTime.now()
        }
        updated > 0
    }

    suspend fun updateLastLogin(id: UUID) {
        newSuspendedTransaction(Dispatchers.IO) {
            UsersTable.update({ UsersTable.id eq id }) {
                it[lastLogin] = OffsetDateTime.now()
            }
        }
    }

    suspend fun incrementTokenVersion(id: UUID): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        UsersTable.update({ UsersTable.id eq id }) {
            it[UsersTable.tokenVersion] = UsersTable.tokenVersion + 1
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    suspend fun getPaginatedAuditLogs(
        page: Int,
        limit: Int,
        action: AuditAction?,
        since: OffsetDateTime?
    ): PaginatedResponse<AuditLogResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val offset = ((page - 1) * limit).toLong()
        var query = AuditLogsTable.selectAll()

        if (action != null) {
            query = query.andWhere { AuditLogsTable.action eq action }
        }

        if (since != null) {
            query = query.andWhere { AuditLogsTable.createdAt greaterEq since }
        }

        val totalCount = query.count()
        val totalPages = kotlin.math.ceil(totalCount.toDouble() / limit).toInt()

        val rows = query
            .orderBy(AuditLogsTable.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .toList()

        val actorIds = rows
            .mapNotNull { row -> row[AuditLogsTable.userId] }
            .distinct()

        val actorsById = if (actorIds.isEmpty()) {
            emptyMap()
        } else {
            (UsersTable innerJoin RolesTable)
                .select { UsersTable.id inList actorIds }
                .associate { row ->
                    row[UsersTable.id] to AuditActorRow(
                        name = row[UsersTable.name],
                        roleName = row[RolesTable.name]
                    )
                }
        }

        val data = rows.map { row ->
            val actorId = row[AuditLogsTable.userId]
            val actor = actorId?.let(actorsById::get)
            val action = row[AuditLogsTable.action]
            val schemaName = row[AuditLogsTable.targetSchemaName]
            val tableName = row[AuditLogsTable.targetTableName]

            AuditLogResponse(
                id = row[AuditLogsTable.id].toString(),
                actorUserId = actorId?.toString(),
                actorName = actor?.name,
                actorRole = actor?.roleName,
                action = action.name,
                schemaName = schemaName,
                tableName = tableName,
                recordId = row[AuditLogsTable.recordId]?.toString(),
                ipAddress = row[AuditLogsTable.ipAddress],
                activityLabel = action.toActivityLabel(tableName),
                createdAt = row[AuditLogsTable.createdAt].toString()
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

    suspend fun insertAuditLog(
        actorUserId: UUID?,
        action: AuditAction,
        schemaName: String,
        tableName: String,
        recordId: UUID?,
        ipAddress: String?
    ) {
        newSuspendedTransaction(Dispatchers.IO) {
            AuditLogsTable.insert {
                it[userId] = actorUserId
                it[this.action] = action
                it[targetSchemaName] = schemaName
                it[targetTableName] = tableName
                it[this.recordId] = recordId
                it[this.ipAddress] = ipAddress?.take(45)
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
        val newId = UUID.randomUUID()
        StoreSettingsTable.insert {
            it[this.id] = newId
            it[storeName] = "Toko Bangunan Default"
            it[printerSize] = PrinterSize.SIZE_80
        }

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
            it[this.updatedAt] = OffsetDateTime.now()
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

private data class AuditActorRow(
    val name: String,
    val roleName: String
)

private fun AuditAction.toActivityLabel(tableName: String): String {
    when (tableName.lowercase()) {
        "user_pin" -> return "Ubah PIN"
        "user_password" -> return "Ubah Password"
        "user_credentials" -> return "Ubah Kredensial"
    }

    return when (this) {
        AuditAction.INSERT -> "Tambah ${tableName.toDomainLabel()}"
        AuditAction.UPDATE -> "Ubah ${tableName.toDomainLabel()}"
        AuditAction.DELETE -> "Nonaktifkan ${tableName.toDomainLabel()}"
    }
}

private fun String.toDomainLabel(): String {
    return when (lowercase()) {
        "users" -> "User"
        "user_pin" -> "PIN"
        "user_password" -> "Password"
        "user_credentials" -> "Kredensial"
        "store_settings" -> "Pengaturan Toko"
        "products" -> "Produk"
        "categories" -> "Kategori"
        "customers" -> "Pelanggan"
        "suppliers" -> "Supplier"
        else -> replace('_', ' ').replaceFirstChar { first -> first.titlecase() }
    }
}
