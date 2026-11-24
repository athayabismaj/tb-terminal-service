package com.service.tbterminal.system

import com.service.tbterminal.inventory.PaginatedResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
import java.util.UUID

class SystemRepository : PinVerificationGateway {

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

    suspend fun findUserById(id: UUID): UserRow? =
        newSuspendedTransaction(Dispatchers.IO) {
            (UsersTable innerJoin RolesTable)
                .select { UsersTable.id eq id }
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

    suspend fun updateOwnProfile(id: UUID, name: String, email: String?): Boolean =
        newSuspendedTransaction(Dispatchers.IO) {
            val updated = UsersTable.update({ UsersTable.id eq id }) {
                it[this.name] = name
                it[this.email] = email
                it[this.updatedAt] = OffsetDateTime.now()
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

    suspend fun insertRefreshToken(
        tokenId: UUID,
        userId: UUID,
        familyId: UUID,
        tokenVersion: Int,
        expiresAt: OffsetDateTime
    ) {
        newSuspendedTransaction(Dispatchers.IO) {
            // Bersihkan token kedaluwarsa milik user ini agar tabel tidak tumbuh tanpa batas.
            RefreshTokensTable.deleteWhere {
                (RefreshTokensTable.userId eq userId) and (RefreshTokensTable.expiresAt less OffsetDateTime.now())
            }
            RefreshTokensTable.insert {
                it[id] = tokenId
                it[this.userId] = userId
                it[this.familyId] = familyId
                it[this.tokenVersion] = tokenVersion
                it[this.expiresAt] = expiresAt
            }
        }
    }

    /**
     * Mengonsumsi refresh token secara atomik (row lock). Bila sah, token lama
     * ditandai terpakai dan token pengganti dicatat pada keluarga yang sama.
     */
    suspend fun consumeRefreshToken(
        tokenId: UUID,
        expectedUserId: UUID,
        expectedTokenVersion: Int,
        newTokenId: UUID,
        newExpiresAt: OffsetDateTime,
        now: OffsetDateTime
    ): RefreshConsumeResult = newSuspendedTransaction(Dispatchers.IO) {
        val row = RefreshTokensTable.select { RefreshTokensTable.id eq tokenId }
            .forUpdate()
            .singleOrNull()
            ?: return@newSuspendedTransaction RefreshConsumeResult.Rejected("unknown_token")
        val record = RefreshTokenRecord(
            id = row[RefreshTokensTable.id],
            userId = row[RefreshTokensTable.userId],
            familyId = row[RefreshTokensTable.familyId],
            tokenVersion = row[RefreshTokensTable.tokenVersion],
            expiresAt = row[RefreshTokensTable.expiresAt],
            usedAt = row[RefreshTokensTable.usedAt],
            revokedAt = row[RefreshTokensTable.revokedAt]
        )
        when (val decision = RefreshTokenRotationPolicy.evaluate(record, expectedUserId, expectedTokenVersion, now)) {
            RefreshDecision.Rotate -> {
                RefreshTokensTable.update({ RefreshTokensTable.id eq tokenId }) {
                    it[usedAt] = now
                    it[replacedBy] = newTokenId
                }
                RefreshTokensTable.insert {
                    it[id] = newTokenId
                    it[userId] = record.userId
                    it[familyId] = record.familyId
                    it[tokenVersion] = record.tokenVersion
                    it[expiresAt] = newExpiresAt
                }
                RefreshConsumeResult.Rotated(record.familyId)
            }

            RefreshDecision.ReuseDetected -> {
                RefreshTokensTable.update({
                    (RefreshTokensTable.familyId eq record.familyId) and RefreshTokensTable.revokedAt.isNull()
                }) {
                    it[revokedAt] = now
                }
                RefreshConsumeResult.ReuseDetected(record.familyId)
            }

            is RefreshDecision.Reject -> RefreshConsumeResult.Rejected(decision.reason)
        }
    }

    override suspend fun getPinAttemptState(userId: UUID, scopeKey: String): PinAttemptState? =
        newSuspendedTransaction(Dispatchers.IO) {
            PinVerificationAttemptsTable
                .select {
                    (PinVerificationAttemptsTable.userId eq userId) and
                        (PinVerificationAttemptsTable.deviceId eq scopeKey)
                }
                .firstOrNull()
                ?.let { row ->
                    PinAttemptState(
                        failedAttempts = row[PinVerificationAttemptsTable.failedAttempts],
                        lockedUntil = row[PinVerificationAttemptsTable.lockedUntil]
                    )
                }
        }

    override suspend fun incrementPinFailure(userId: UUID, scopeKey: String): Int =
        newSuspendedTransaction(Dispatchers.IO) {
            // Upsert atomik: request paralel tidak bisa "melewati" penghitung.
            exec(
                """
                INSERT INTO system.pin_verification_attempts (user_id, device_id, failed_attempts, locked_until, updated_at)
                VALUES (?, ?, 1, NULL, NOW())
                ON CONFLICT (user_id, device_id) DO UPDATE
                    SET failed_attempts = system.pin_verification_attempts.failed_attempts + 1,
                        updated_at = NOW()
                RETURNING failed_attempts
                """.trimIndent(),
                args = listOf(
                    UUIDColumnType() to userId,
                    VarCharColumnType(128) to scopeKey
                ),
                explicitStatementType = StatementType.SELECT
            ) { rs ->
                check(rs.next()) { "Gagal mencatat percobaan kredensial" }
                rs.getInt("failed_attempts")
            } ?: error("Gagal mencatat percobaan kredensial")
        }

    override suspend fun savePinAttemptState(
        userId: UUID,
        scopeKey: String,
        failedAttempts: Int,
        lockedUntil: OffsetDateTime?
    ) {
        newSuspendedTransaction(Dispatchers.IO) {
            val now = OffsetDateTime.now()
            val updated = PinVerificationAttemptsTable.update({
                (PinVerificationAttemptsTable.userId eq userId) and
                    (PinVerificationAttemptsTable.deviceId eq scopeKey)
            }) {
                it[this.failedAttempts] = failedAttempts
                it[this.lockedUntil] = lockedUntil
                it[this.updatedAt] = now
            }
            if (updated == 0) {
                PinVerificationAttemptsTable.insertIgnore {
                    it[this.userId] = userId
                    it[this.deviceId] = scopeKey
                    it[this.failedAttempts] = failedAttempts
                    it[this.lockedUntil] = lockedUntil
                    it[this.updatedAt] = now
                }
            }
        }
    }

    override suspend fun resetPinAttemptState(userId: UUID, scopeKey: String) {
        newSuspendedTransaction(Dispatchers.IO) {
            PinVerificationAttemptsTable.deleteWhere {
                (PinVerificationAttemptsTable.userId eq userId) and
                    (PinVerificationAttemptsTable.deviceId eq scopeKey)
            }
        }
    }

    override suspend fun findPinUserById(userId: UUID): UserRow? = findUserById(userId)

    override suspend fun revokeSessionsForUser(userId: UUID): Boolean = incrementTokenVersion(userId)

    override suspend fun recordPinAuditLog(
        actorUserId: UUID,
        action: AuditAction,
        tableName: String,
        recordId: UUID,
        ipAddress: String?,
        metadataJson: String
    ) {
        insertAuditLog(
            actorUserId = actorUserId,
            action = action,
            schemaName = "system",
            tableName = tableName,
            recordId = recordId,
            ipAddress = ipAddress,
            newData = metadataJson
        )
    }

    override suspend fun resetAllPinAttemptStatesForUser(userId: UUID) {
        newSuspendedTransaction(Dispatchers.IO) {
            PinVerificationAttemptsTable.deleteWhere {
                PinVerificationAttemptsTable.userId eq userId
            }
        }
    }

    suspend fun getPaginatedAuditLogs(
        page: Int,
        limit: Int,
        action: AuditAction?,
        since: OffsetDateTime?,
        until: OffsetDateTime? = null
    ): PaginatedResponse<AuditLogResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val offset = ((page - 1) * limit).toLong()
        var query = AuditLogsTable.selectAll()

        if (action != null) {
            query = query.andWhere { AuditLogsTable.action eq action }
        }

        if (since != null) {
            query = query.andWhere { AuditLogsTable.createdAt greaterEq since }
        }

        if (until != null) {
            query = query.andWhere { AuditLogsTable.createdAt less until }
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
                oldData = row[AuditLogsTable.oldData],
                newData = row[AuditLogsTable.newData],
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
        ipAddress: String?,
        newData: String? = null
    ) {
        newSuspendedTransaction(Dispatchers.IO) {
            AuditLogsTable.insert {
                it[userId] = actorUserId
                it[this.action] = action
                it[targetSchemaName] = schemaName
                it[targetTableName] = tableName
                it[this.recordId] = recordId
                it[this.ipAddress] = ipAddress?.take(45)
                it[this.newData] = newData
            }
        }
    }

    // ==========================================
    // STORE SETTINGS (Singleton)
    // ==========================================

    suspend fun getStoreSettings(): StoreSettingsResponse = newSuspendedTransaction(Dispatchers.IO) {
        val row = StoreSettingsTable.selectAll().firstOrNull()

        if (row != null) {
            return@newSuspendedTransaction row.toStoreSettingsResponse()
        }

        // Jika kosong, insert default row
        val newId = UUID.randomUUID()
        StoreSettingsTable.insert {
            it[this.id] = newId
            it[storeName] = "Toko Bangunan Default"
            it[printerSize] = PrinterSize.SIZE_80
        }

        val newRow = StoreSettingsTable.select { StoreSettingsTable.id eq newId }.single()
        newRow.toStoreSettingsResponse()
    }

    suspend fun updateStoreProfile(
        userId: UUID,
        storeName: String,
        address: String?,
        phone: String?,
        receiptHeader: String?,
        receiptFooter: String?,
        cashierDiscountLimitPercent: java.math.BigDecimal? = null,
        taxEnabled: Boolean? = null,
        taxRate: java.math.BigDecimal? = null,
        taxMode: TaxMode? = null,
        taxLabel: String? = null,
        updateStoreNpwp: Boolean = false,
        storeNpwp: String? = null,
        cashRoundingUnit: Int? = null,
        cashRoundingRule: CashRoundingRule? = null
    ): StoreSettingsResponse {
        getStoreSettings()
        return newSuspendedTransaction(Dispatchers.IO) {
            StoreSettingsTable.update({ Op.TRUE }) {
                it[this.storeName] = storeName
                it[this.address] = address
                it[this.phone] = phone
                it[this.receiptHeader] = receiptHeader
                it[this.receiptFooter] = receiptFooter
                cashierDiscountLimitPercent?.let { limit ->
                    it[this.cashierDiscountLimitPercent] = limit
                }
                taxEnabled?.let { enabled ->
                    it[this.taxEnabled] = enabled
                }
                taxRate?.let { rate ->
                    it[this.taxRate] = rate
                }
                taxMode?.let { mode ->
                    it[this.taxMode] = mode
                }
                taxLabel?.let { label ->
                    it[this.taxLabel] = label
                }
                if (updateStoreNpwp) {
                    it[this.storeNpwp] = storeNpwp
                }
                cashRoundingUnit?.let { unit ->
                    it[this.cashRoundingUnit] = unit
                }
                cashRoundingRule?.let { rule ->
                    it[this.cashRoundingRule] = rule
                }
                it[this.updatedBy] = userId
                it[this.updatedAt] = OffsetDateTime.now()
            }

            StoreSettingsTable.selectAll().first().toStoreSettingsResponse()
        }
    }
}

private fun ResultRow.toStoreSettingsResponse() = StoreSettingsResponse(
    id = this[StoreSettingsTable.id].toString(),
    storeName = this[StoreSettingsTable.storeName],
    address = this[StoreSettingsTable.address],
    phone = this[StoreSettingsTable.phone],
    receiptHeader = this[StoreSettingsTable.receiptHeader],
    receiptFooter = this[StoreSettingsTable.receiptFooter],
    printerSize = this[StoreSettingsTable.printerSize].dbValue,
    cashierDiscountLimitPercent = this[StoreSettingsTable.cashierDiscountLimitPercent],
    taxEnabled = this[StoreSettingsTable.taxEnabled],
    taxRate = this[StoreSettingsTable.taxRate],
    taxMode = this[StoreSettingsTable.taxMode].name,
    taxLabel = this[StoreSettingsTable.taxLabel],
    storeNpwp = this[StoreSettingsTable.storeNpwp],
    cashRoundingUnit = this[StoreSettingsTable.cashRoundingUnit],
    cashRoundingRule = this[StoreSettingsTable.cashRoundingRule].name,
    updatedAt = this[StoreSettingsTable.updatedAt].toString()
)

private data class AuditActorRow(
    val name: String,
    val roleName: String
)

private fun AuditAction.toActivityLabel(tableName: String): String {
    when (tableName.lowercase()) {
        "auth_login" -> return "Login"
        "auth_logout" -> return "Logout"
        "pin_unlock_failed" -> return "Gagal Verifikasi PIN"
        "pin_unlock_locked" -> return "Lockout Verifikasi PIN"
        "pin_unlock_revoked" -> return "Sesi Dicabut (Batas PIN)"
        "pin_reset" -> return "Reset PIN"
        "user_pin" -> return "Ubah PIN"
        "user_password" -> return "Ubah Password"
        "user_credentials" -> return "Ubah Kredensial"
        "products_price" -> return "Ubah Harga Produk"
        "stock_opname" -> return "Stok Opname"
        "stock_correction" -> return "Koreksi Stok"
        "stock_damage" -> return "Retur/Rusak Stok"
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
        "auth_login" -> "Login"
        "auth_logout" -> "Logout"
        "store_settings" -> "Pengaturan Toko"
        "products" -> "Produk"
        "products_price" -> "Harga Produk"
        "categories" -> "Kategori"
        "units" -> "Satuan"
        "stock_opname" -> "Stok Opname"
        "stock_correction" -> "Koreksi Stok"
        "stock_damage" -> "Retur/Rusak Stok"
        "purchases" -> "Barang Masuk"
        "supplier_payables" -> "Utang Supplier"
        "supplier_payments" -> "Pembayaran Utang Supplier"
        "customers" -> "Pelanggan"
        "receivables" -> "Piutang"
        "receivable_payments" -> "Pembayaran Piutang"
        "suppliers" -> "Supplier"
        "cash_sessions" -> "Kas Harian"
        "cash_expenses" -> "Pengeluaran Kas"
        "transactions" -> "Transaksi"
        else -> replace('_', ' ').replaceFirstChar { first -> first.titlecase() }
    }
}
