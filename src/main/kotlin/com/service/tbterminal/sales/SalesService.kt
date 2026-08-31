package com.service.tbterminal.sales

import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.shared.ForbiddenException
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.SessionNotFoundException
import com.service.tbterminal.shared.ValidationException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

class SalesService(private val repository: SalesRepository) {

    // ==========================================
    // CASH SESSIONS (SHIFT KASIR)
    // ==========================================

    suspend fun getActiveSession(userId: UUID): CashSessionResponse? {
        return repository.getActiveSession(userId)
    }

    suspend fun getSessions(
        page: Int,
        limit: Int,
        status: String?,
        startDate: String?,
        endDate: String?
    ): PaginatedResponse<CashSessionResponse> {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 100)
        return repository.getPaginatedSessions(safePage, safeLimit, status, startDate, endDate)
    }

    suspend fun getSessionById(id: String): CashSessionResponse {
        val sessionId = try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Format Session ID tidak valid")
        }
        return repository.getSessionById(sessionId)
            ?: throw NotFoundException("Sesi kas dengan ID $id tidak ditemukan")
    }

    suspend fun openSession(userId: UUID, request: OpenSessionRequest): CashSessionResponse {
        if (request.startingCash < BigDecimal.ZERO) {
            throw ValidationException("Modal awal tidak boleh kurang dari nol")
        }
        if (request.startingCash.scale() > MONEY_SCALE) {
            throw ValidationException("Modal awal maksimal memiliki 2 angka desimal")
        }

        return repository.openSession(userId, request.startingCash.normalizeMoney())
    }

    suspend fun syncOpenCashSession(
        authenticatedUserId: UUID,
        request: OfflineCashSessionOpenSyncRequest
    ): OfflineCashSessionOpenSyncResponse {
        val clientGeneratedId = validateSyncKey(request.clientGeneratedId, "clientGeneratedId")
        val deviceId = validateSyncKey(request.deviceId, "deviceId")
        val cashierUserId = parseUuid(request.cashierUserId, "Cashier User ID")
        if (cashierUserId != authenticatedUserId) {
            throw ForbiddenException("Tidak boleh sync sesi kas untuk cashierUserId lain")
        }
        if (request.startingCash < BigDecimal.ZERO) {
            throw ValidationException("Modal awal tidak boleh kurang dari nol")
        }

        repository.findOfflineSyncedCashSession(deviceId, clientGeneratedId, cashierUserId)?.let { return it }

        val command = OfflineCashSessionOpenSyncCommand(
            clientGeneratedId = clientGeneratedId,
            deviceId = deviceId,
            cashierUserId = cashierUserId,
            openedAt = parseSyncOccurredAt(request.openedAt),
            startingCash = request.startingCash.normalizeMoney(),
            openingNote = request.openingNote?.trim()?.takeIf(String::isNotBlank)
        )

        return try {
            repository.syncOpenCashSession(command)
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23505") {
                repository.findOfflineSyncedCashSession(deviceId, clientGeneratedId, cashierUserId)?.let { return it }
            }
            throw e
        }
    }

    suspend fun syncCloseCashSession(
        authenticatedUserId: UUID,
        request: OfflineCashSessionCloseSyncRequest
    ): OfflineCashSessionCloseSyncResponse {
        val deviceId = validateSyncKey(request.deviceId, "deviceId")
        val clientGeneratedId = validateSyncKey(request.clientGeneratedId, "clientGeneratedId")
        val serverCashSessionId = parseUuid(request.serverCashSessionId, "Cash Session ID")
        val cashierUserId = parseUuid(request.cashierUserId, "Cashier User ID")
        if (cashierUserId != authenticatedUserId) {
            throw ForbiddenException("Tidak boleh sync tutup sesi kas untuk cashierUserId lain")
        }
        if (request.actualCash < BigDecimal.ZERO) {
            throw ValidationException("Kas fisik akhir tidak boleh kurang dari nol")
        }
        if (request.expectedCash != null && request.expectedCash < BigDecimal.ZERO) {
            throw ValidationException("Kas sistem akhir tidak boleh kurang dari nol")
        }

        return repository.syncCloseCashSession(
            OfflineCashSessionCloseSyncCommand(
                deviceId = deviceId,
                clientGeneratedId = clientGeneratedId,
                serverCashSessionId = serverCashSessionId,
                cashierUserId = cashierUserId,
                closedAt = parseSyncOccurredAt(request.closedAt),
                actualCash = request.actualCash.normalizeMoney(),
                expectedCash = request.expectedCash?.normalizeMoney(),
                difference = request.difference?.normalizeMoney(),
                closingNote = request.closingNote?.trim()?.takeIf(String::isNotBlank)
            )
        )
    }

    suspend fun closeSession(userId: UUID, request: CloseSessionRequest): CashSessionResponse {
        val activeSession = repository.getActiveSession(userId)
            ?: throw SessionNotFoundException("Tidak ada sesi kasir aktif yang bisa ditutup")

        if (request.endingCashPhysical < java.math.BigDecimal.ZERO) {
            throw ValidationException("Uang fisik akhir tidak boleh kurang dari nol")
        }
        if (request.endingCashPhysical.scale() > MONEY_SCALE) {
            throw ValidationException("Uang fisik akhir maksimal memiliki 2 angka desimal")
        }
        val notes = request.notes?.trim()?.takeIf(String::isNotBlank)
        if (notes != null && notes.length > MAX_NOTES_LENGTH) {
            throw ValidationException("Catatan maksimal $MAX_NOTES_LENGTH karakter")
        }

        val sessionId = UUID.fromString(activeSession.id)
        val systemCash = activeSession.systemCash ?: activeSession.openingCash
        val closingCash = request.endingCashPhysical.normalizeMoney()
        val difference = closingCash.subtract(systemCash).normalizeMoney()

        val success = repository.closeSession(
            sessionId = sessionId,
            closingCash = closingCash,
            systemCash = systemCash,
            difference = difference,
            notes = notes
        )

        if (!success) throw NotFoundException("Gagal menutup sesi kasir")
        return repository.getSessionById(sessionId)!!
    }

    // ==========================================
    // POS — CHECKOUT ENGINE
    // ==========================================

    suspend fun checkout(userId: UUID, actorRole: String, request: CheckoutRequest): TransactionResponse {
        val idempotencyKey = validateCheckoutRequest(request)

        val paymentMethod = PaymentMethod.entries.firstOrNull { it.dbValue == request.paymentMethod.lowercase() }
            ?: throw ValidationException(
                "Metode pembayaran '${request.paymentMethod}' tidak valid. " +
                "Gunakan: tunai, transfer, qris, hutang, atau dp"
            )

        val customerId: UUID? = request.customerId?.let {
            try { UUID.fromString(it) } catch (e: IllegalArgumentException) {
                throw ValidationException("Format Customer ID tidak valid")
            }
        }

        val fingerprint = checkoutRequestFingerprint(request, idempotencyKey)

        repository.findCheckoutByIdempotencyKey(idempotencyKey)?.let { existing ->
            if (existing.transaction.userId != userId.toString()) {
                throw ValidationException("idempotencyKey sudah digunakan oleh transaksi lain")
            }
            if (existing.requestFingerprint != fingerprint) {
                throw ValidationException("idempotencyKey sudah digunakan untuk payload checkout yang berbeda")
            }
            return existing.transaction.copy(idempotentReplay = true)
        }

        return try {
            repository.executeCheckout(
                CheckoutCommand(
                    userId = userId,
                    actorRole = actorRole,
                    customerId = customerId,
                    requestItems = request.items,
                    transactionDiscount = request.transactionDiscount,
                    paymentMethod = paymentMethod,
                    amountPaid = request.amountPaid,
                    notes = request.notes,
                    dueDays = request.dueDays,
                    idempotencyKey = idempotencyKey,
                    requestFingerprint = fingerprint,
                    checkoutAttemptId = request.checkoutAttemptId?.let { parseUuid(it, "Checkout Attempt ID") },
                    managerApprovalId = request.managerApprovalId?.let { parseUuid(it, "Manager Approval ID") }
                )
            )
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23505") {
                repository.findCheckoutByIdempotencyKey(idempotencyKey)?.let { existing ->
                    if (existing.transaction.userId != userId.toString()) {
                        throw ValidationException("idempotencyKey sudah digunakan oleh transaksi lain")
                    }
                    if (existing.requestFingerprint != fingerprint) {
                        throw ValidationException("idempotencyKey sudah digunakan untuk payload checkout yang berbeda")
                    }
                    return existing.transaction.copy(idempotentReplay = true)
                }
            }
            // Tangkap trigger fn_sync_stock yang melempar error stok tidak cukup
            val msg = e.message ?: ""
            if (msg.contains("Insufficient stock") || msg.contains("Stock record not found")) {
                throw ValidationException("Stok tidak mencukupi untuk satu atau lebih item dalam keranjang")
            }
            throw e // Re-throw jika bukan error stok
        }
    }

    suspend fun syncOfflineCheckout(
        authenticatedUserId: UUID,
        request: OfflineCheckoutSyncRequest
    ): OfflineCheckoutSyncResponse {
        val clientGeneratedId = validateSyncKey(request.clientGeneratedId, "clientGeneratedId")
        val deviceId = validateSyncKey(request.deviceId, "deviceId")
        val localTransactionCode = request.localTransactionCode.trim()
        if (localTransactionCode.isBlank()) {
            throw ValidationException("localTransactionCode wajib diisi")
        }

        val cashierUserId = parseUuid(request.cashierUserId, "Cashier User ID")
        if (cashierUserId != authenticatedUserId) {
            throw ForbiddenException("Tidak boleh sync transaksi untuk cashierUserId lain")
        }

        repository.findOfflineSyncedCheckout(deviceId, clientGeneratedId, cashierUserId)?.let { return it }

        val cashSessionId = parseUuid(request.cashSessionId, "Cash Session ID")
        val customerId = request.customerId?.takeIf { it.isNotBlank() }?.let {
            parseUuid(it, "Customer ID")
        }
        val paymentMethod = PaymentMethod.entries.firstOrNull { it.dbValue == request.paymentMethod.lowercase() }
            ?: throw ValidationException(
                "Metode pembayaran '${request.paymentMethod}' tidak valid. " +
                    "Gunakan: tunai, transfer, qris, hutang, atau dp"
            )
        val occurredAt = parseSyncOccurredAt(request.occurredAt)

        if (request.items.isEmpty()) {
            throw ValidationException("Item checkout offline tidak boleh kosong")
        }
        if (request.dueDays < 0) {
            throw ValidationException("Termin piutang tidak boleh kurang dari nol hari")
        }

        val subtotal = request.subtotal.normalizeMoney()
        val discount = request.discount.normalizeMoney()
        val total = request.total.normalizeMoney()
        val paidAmount = request.paidAmount.normalizeMoney()
        val remainingAmount = request.remainingAmount.normalizeMoney()

        if (discount.compareTo(BigDecimal.ZERO) != 0 ||
            request.items.any { it.discount.compareTo(BigDecimal.ZERO) != 0 }
        ) {
            throw ValidationException(
                "Diskon offline tidak didukung karena harga dan approval wajib dihitung server saat checkout online"
            )
        }

        if (subtotal < BigDecimal.ZERO) {
            throw ValidationException("Subtotal checkout offline tidak boleh negatif")
        }
        if (discount < BigDecimal.ZERO) {
            throw ValidationException("Diskon checkout offline tidak boleh negatif")
        }
        if (total < BigDecimal.ZERO) {
            throw ValidationException("Total checkout offline tidak boleh negatif")
        }
        if (paidAmount < BigDecimal.ZERO) {
            throw ValidationException("Jumlah bayar tidak boleh negatif")
        }
        if (remainingAmount < BigDecimal.ZERO) {
            throw ValidationException("Sisa tagihan tidak boleh negatif")
        }
        if (paidAmount > total) {
            throw ValidationException("Jumlah bayar tidak boleh melebihi total transaksi")
        }
        if (total.subtract(paidAmount).normalizeMoney().compareTo(remainingAmount) != 0) {
            throw ValidationException("remainingAmount tidak sesuai dengan total dikurangi paidAmount")
        }

        val itemCommands = request.items.map { item ->
            val productId = parseUuid(item.productId, "Product ID")
            val productNameSnapshot = item.productNameSnapshot.trim()
            if (productNameSnapshot.isBlank()) {
                throw ValidationException("productNameSnapshot wajib diisi")
            }
            if (productNameSnapshot.length > 255) {
                throw ValidationException("productNameSnapshot tidak boleh lebih dari 255 karakter")
            }
            OfflineCheckoutSyncItemCommand(
                productId = productId,
                productNameSnapshot = productNameSnapshot,
                quantity = item.quantity,
                priceAtTransaction = item.priceAtTransaction,
                cogsAtTransaction = item.cogsAtTransaction,
                grossLineTotal = item.priceAtTransaction.multiply(item.quantity).normalizeMoney(),
                discountType = null,
                discountValue = BigDecimal.ZERO.setScale(MONEY_SCALE),
                discount = item.discount,
                subtotal = item.subtotal
            )
        }

        val grossSubtotal = itemCommands.fold(BigDecimal.ZERO) { acc, item ->
            acc.add(item.priceAtTransaction.multiply(item.quantity))
        }.normalizeMoney()
        val lineDiscountTotal = itemCommands.fold(BigDecimal.ZERO) { acc, item ->
            acc.add(item.discount.multiply(item.quantity))
        }.normalizeMoney()
        val netItemTotal = itemCommands.fold(BigDecimal.ZERO) { acc, item ->
            acc.add(item.subtotal)
        }.normalizeMoney()

        if (grossSubtotal.compareTo(subtotal) != 0) {
            throw ValidationException("Subtotal checkout offline tidak sesuai dengan total harga item")
        }
        if (lineDiscountTotal.compareTo(discount) != 0) {
            throw ValidationException("Diskon checkout offline harus berasal dari diskon item")
        }
        if (subtotal.subtract(discount).normalizeMoney().compareTo(total) != 0) {
            throw ValidationException("Total checkout offline tidak sesuai dengan subtotal dikurangi diskon")
        }
        if (netItemTotal.compareTo(total) != 0) {
            throw ValidationException("Total checkout offline tidak sesuai dengan subtotal item")
        }

        val requiresCustomer = paymentMethod == PaymentMethod.HUTANG ||
            paymentMethod == PaymentMethod.DP ||
            remainingAmount > BigDecimal.ZERO
        if (requiresCustomer && customerId == null) {
            throw ValidationException("Transaksi hutang/DP memerlukan Customer ID server yang valid")
        }

        val command = OfflineCheckoutSyncCommand(
            clientGeneratedId = clientGeneratedId,
            deviceId = deviceId,
            cashierUserId = cashierUserId,
            cashSessionId = cashSessionId,
            customerId = customerId,
            paymentMethod = paymentMethod,
            total = total,
            paidAmount = paidAmount,
            remainingAmount = remainingAmount,
            occurredAt = occurredAt,
            note = request.note,
            dueDays = request.dueDays,
            items = itemCommands
        )

        return try {
            repository.executeOfflineCheckoutSync(command)
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23505") {
                repository.findOfflineSyncedCheckout(deviceId, clientGeneratedId, cashierUserId)?.let { return it }
            }

            val msg = e.message ?: ""
            if (msg.contains("Insufficient stock") || msg.contains("Stock record not found")) {
                throw ValidationException("Stok tidak mencukupi untuk satu atau lebih item checkout offline")
            }
            throw e
        }
    }

    suspend fun getTransactions(
        page: Int,
        limit: Int,
        sessionId: String?,
        search: String? = null,
        receiptNumber: String? = null,
        cashierId: String? = null,
        customerId: String? = null,
        paymentMethod: String? = null,
        status: String? = null,
        startDate: String? = null,
        endDate: String? = null
    ): PaginatedResponse<TransactionSummary> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit
        val filter = validateTransactionHistoryFilter(
            sessionId, search, receiptNumber, cashierId, customerId, paymentMethod, status, startDate, endDate
        )
        return repository.getPaginatedTransactions(
            safePage, safeLimit.coerceAtMost(200), filter.sessionId, filter.search, filter.receiptNumber,
            filter.cashierId, filter.customerId, filter.paymentMethod, filter.status, filter.startAt, filter.endExclusive
        )
    }

    suspend fun voidTransaction(
        userId: UUID,
        actorRole: String,
        id: String,
        request: VoidTransactionRequest,
        ipAddress: String?
    ): VoidTransactionResponse {
        val transactionId = parseUuid(id, "Transaction ID")
        val (key, reason) = validateVoidRequest(request)
        val approvalScope = resolveVoidApprovalScope(
            actorUserId = userId,
            actorRole = actorRole,
            transactionId = transactionId,
            managerApprovalId = request.managerApprovalId
        )
        return repository.voidTransaction(
            actorUserId = userId,
            transactionId = transactionId,
            reason = reason,
            idempotencyKey = key,
            approvalScope = approvalScope,
            ipAddress = ipAddress
        )
    }

    suspend fun getTransactionById(id: String): TransactionResponse {
        val trxId = try { UUID.fromString(id) } catch (e: IllegalArgumentException) {
            throw ValidationException("Format Transaction ID tidak valid")
        }
        return repository.getTransactionById(trxId)
            ?: throw NotFoundException("Transaksi dengan ID $id tidak ditemukan")
    }

    suspend fun getReceivableIdByTransactionId(transactionId: String): String? {
        val trxId = try { UUID.fromString(transactionId) } catch (e: IllegalArgumentException) {
            throw ValidationException("Format Transaction ID tidak valid")
        }
        return repository.getReceivableIdByTransactionId(trxId)?.toString()
    }

    suspend fun addExpense(userId: UUID, request: CashExpenseRequest): CashExpenseResponse {
        if (request.amount <= BigDecimal.ZERO) {
            throw ValidationException("Nominal pengeluaran harus lebih dari 0")
        }
        if (request.amount.scale() > MONEY_SCALE) {
            throw ValidationException("Nominal pengeluaran maksimal memiliki 2 angka desimal")
        }
        val description = request.description.trim()
        if (description.isBlank()) {
            throw ValidationException("Deskripsi pengeluaran wajib diisi")
        }
        if (description.length > MAX_NOTES_LENGTH) {
            throw ValidationException("Deskripsi pengeluaran maksimal $MAX_NOTES_LENGTH karakter")
        }
        return repository.addExpense(
            userId,
            request.copy(amount = request.amount.normalizeMoney(), description = description)
        )
    }

    suspend fun syncCashExpense(
        authenticatedUserId: UUID,
        request: OfflineCashExpenseSyncRequest
    ): OfflineCashExpenseSyncResponse {
        val clientGeneratedId = validateSyncKey(request.clientGeneratedId, "clientGeneratedId")
        val deviceId = validateSyncKey(request.deviceId, "deviceId")
        val cashierUserId = parseUuid(request.cashierUserId, "Cashier User ID")
        if (cashierUserId != authenticatedUserId) {
            throw ForbiddenException("Tidak boleh sync pengeluaran kas untuk cashierUserId lain")
        }

        val serverCashSessionId = parseUuid(request.serverCashSessionId, "Cash Session ID")
        val amount = request.amount.normalizeMoney()
        if (amount <= BigDecimal.ZERO) {
            throw ValidationException("Nominal pengeluaran harus lebih dari 0")
        }

        val category = request.category.trim().takeIf(String::isNotBlank)
            ?: throw ValidationException("Kategori pengeluaran wajib diisi")
        if (category.length > 100) {
            throw ValidationException("Kategori pengeluaran tidak boleh lebih dari 100 karakter")
        }

        val note = request.note.trim().takeIf(String::isNotBlank)
            ?: throw ValidationException("Catatan pengeluaran wajib diisi")

        repository.findOfflineSyncedCashExpense(deviceId, clientGeneratedId, cashierUserId)?.let { return it }

        return try {
            repository.syncCashExpense(
                OfflineCashExpenseSyncCommand(
                    clientGeneratedId = clientGeneratedId,
                    deviceId = deviceId,
                    cashierUserId = cashierUserId,
                    serverCashSessionId = serverCashSessionId,
                    amount = amount,
                    category = category,
                    note = note,
                    occurredAt = parseSyncOccurredAt(request.occurredAt)
                )
            )
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23505") {
                repository.findOfflineSyncedCashExpense(deviceId, clientGeneratedId, cashierUserId)?.let { return it }
            }
            throw e
        }
    }

    suspend fun getExpenses(sessionId: String): List<CashExpenseResponse> {
        return repository.getExpenses(parseUuid(sessionId, "Session ID"))
    }

    suspend fun getExpenseHistory(
        page: Int,
        limit: Int,
        sessionId: String?,
        startDate: String?,
        endDate: String?
    ): PaginatedResponse<CashExpenseResponse> {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 100)
        val sessionUuid = sessionId?.let {
            try {
                UUID.fromString(it)
            } catch (e: IllegalArgumentException) {
                throw ValidationException("Format Session ID tidak valid")
            }
        }
        return repository.getPaginatedExpenses(safePage, safeLimit, sessionUuid, startDate, endDate)
    }

    suspend fun payTransactionDebt(userId: UUID, transactionId: String, request: PayDebtRequest): TransactionResponse {
        return repository.payTransactionDebt(userId, UUID.fromString(transactionId), request)
    }

    private fun validateSyncKey(value: String, fieldName: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            throw ValidationException("$fieldName wajib diisi")
        }
        if (trimmed.length > 100) {
            throw ValidationException("$fieldName tidak boleh lebih dari 100 karakter")
        }
        return trimmed
    }

    private fun parseUuid(value: String, label: String): UUID {
        return try {
            UUID.fromString(value)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Format $label tidak valid")
        }
    }

    private fun parseSyncOccurredAt(value: String): OffsetDateTime {
        val raw = value.trim()
        if (raw.isBlank()) {
            throw ValidationException("occurredAt wajib diisi")
        }
        return runCatching { OffsetDateTime.parse(raw) }
            .getOrElse {
                runCatching {
                    LocalDateTime.parse(raw)
                        .atZone(ZoneId.of("Asia/Jakarta"))
                        .toOffsetDateTime()
                }.getOrElse {
                    throw ValidationException("Format occurredAt tidak valid")
                }
            }
    }

    private companion object {
        const val MONEY_SCALE = 2
        const val MAX_NOTES_LENGTH = 500
    }
}

private fun BigDecimal.normalizeMoney(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
