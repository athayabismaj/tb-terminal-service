package com.service.tbterminal.sales

import com.service.tbterminal.inventory.AdjType
import com.service.tbterminal.inventory.StockAdjustmentsTable
import com.service.tbterminal.inventory.StockTable
import com.service.tbterminal.receivable.RecPaymentMethod
import com.service.tbterminal.receivable.ReceivablePaymentEntryType
import com.service.tbterminal.receivable.ReceivablePaymentsTable
import com.service.tbterminal.receivable.ReceivablesTable
import com.service.tbterminal.receivable.receivableToday
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import com.service.tbterminal.system.AuditAction
import com.service.tbterminal.system.AuditLogsTable
import com.service.tbterminal.system.ManagerApprovalRecord
import com.service.tbterminal.system.ManagerApprovalScope
import com.service.tbterminal.system.ManagerApprovalService
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID

interface RefundRepository {
    suspend fun findByIdempotencyKey(idempotencyKey: String): RefundTransactionResponse?

    suspend fun refundTransaction(
        actorUserId: UUID,
        transactionId: UUID,
        request: ValidatedRefundRequest,
        approvalScope: ManagerApprovalScope?,
        ipAddress: String?
    ): RefundTransactionResponse
}

class RefundRepositoryImpl(
    private val managerApprovalService: ManagerApprovalService
) : RefundRepository {
    override suspend fun findByIdempotencyKey(
        idempotencyKey: String
    ): RefundTransactionResponse? = newSuspendedTransaction(Dispatchers.IO) {
        TransactionRefundsTable.select {
            TransactionRefundsTable.idempotencyKey eq idempotencyKey
        }.singleOrNull()?.let { refundResponse(it, replay = false) }
    }

    override suspend fun refundTransaction(
        actorUserId: UUID,
        transactionId: UUID,
        request: ValidatedRefundRequest,
        approvalScope: ManagerApprovalScope?,
        ipAddress: String?
    ): RefundTransactionResponse = newSuspendedTransaction(Dispatchers.IO) {
        val existingByKey = TransactionRefundsTable.select {
            TransactionRefundsTable.idempotencyKey eq request.idempotencyKey
        }.forUpdate().singleOrNull()
        if (existingByKey != null) {
            ensureSameRefund(existingByKey, actorUserId, transactionId, request, approvalScope?.approvalId)
            return@newSuspendedTransaction refundResponse(existingByKey, replay = true)
        }

        // Konsisten dengan pembayaran piutang dan Void: lock piutang sebelum transaksi.
        val receivableRow = ReceivablesTable.select {
            ReceivablesTable.transactionId eq transactionId
        }.forUpdate().singleOrNull()
        val transactionRow = TransactionsTable.select { TransactionsTable.id eq transactionId }
            .forUpdate().singleOrNull()
            ?: throw NotFoundException("Transaksi tidak ditemukan")

        when (transactionRow[TransactionsTable.status]) {
            TrxStatus.VOIDED -> throw ValidationException("Transaksi VOIDED tidak dapat direfund")
            TrxStatus.REFUNDED -> {
                TransactionRefundsTable.select {
                    TransactionRefundsTable.transactionId eq transactionId
                }.singleOrNull()?.let { existing ->
                    if (existing[TransactionRefundsTable.idempotencyKey] == request.idempotencyKey) {
                        ensureSameRefund(existing, actorUserId, transactionId, request, approvalScope?.approvalId)
                        return@newSuspendedTransaction refundResponse(existing, replay = true)
                    }
                }
                throw ValidationException("Transaksi sudah pernah direfund")
            }
            TrxStatus.LUNAS, TrxStatus.DP, TrxStatus.HUTANG -> Unit
        }
        if (transactionRow[TransactionsTable.type] != TrxType.PENJUALAN) {
            throw ValidationException("Hanya transaksi penjualan yang dapat direfund")
        }

        val validatedApproval = approvalScope?.let {
            managerApprovalService.validateApprovalInCurrentTransaction(it)
        }

        val transactionItems = TransactionItemsTable.select {
            TransactionItemsTable.transactionId eq transactionId
        }.toList()
        if (transactionItems.isEmpty()) throw ValidationException("Transaksi tidak memiliki detail item")

        val quantitiesByProduct = transactionItems
            .groupBy { it[TransactionItemsTable.productId] }
            .mapValues { (_, rows) ->
                rows.fold(BigDecimal.ZERO) { total, row -> total + row[TransactionItemsTable.quantity] }
            }
        val stockRows = if (request.disposition == RefundDisposition.RETURN_TO_STOCK) {
            StockTable.select {
                StockTable.productId inList quantitiesByProduct.keys.sortedBy(UUID::toString)
            }.orderBy(StockTable.productId to SortOrder.ASC).forUpdate()
                .associateBy { it[StockTable.productId] }
                .also {
                    if (it.size != quantitiesByProduct.size) {
                        throw ValidationException("Data stok transaksi tidak lengkap")
                    }
                }
        } else emptyMap()

        val receivableId = receivableRow?.get(ReceivablesTable.id)
        val activeLedgerPayments = receivableId?.let { id ->
            val ledgerRows = ReceivablePaymentsTable.select {
                ReceivablePaymentsTable.receivableId eq id
            }.orderBy(ReceivablePaymentsTable.paidAt to SortOrder.ASC).toList()
            val reversedIds = ledgerRows.mapNotNull { it[ReceivablePaymentsTable.reversedPaymentId] }.toSet()
            ledgerRows.filter {
                it[ReceivablePaymentsTable.entryType] == ReceivablePaymentEntryType.PAYMENT &&
                    it[ReceivablePaymentsTable.id] !in reversedIds
            }
        }.orEmpty()

        val initialPaid = if (receivableRow != null) {
            transactionRow[TransactionsTable.total]
                .subtract(receivableRow[ReceivablesTable.amount])
                .max(BigDecimal.ZERO)
        } else {
            transactionRow[TransactionsTable.paidAmount]
        }.money()
        val refundedAmount = calculateRefundedAmount(
            transactionAmount = transactionRow[TransactionsTable.total],
            initialPaidAmount = initialPaid,
            receivablePayments = activeLedgerPayments.map { it[ReceivablePaymentsTable.amount] }
        )

        val refundId = UUID.randomUUID()
        TransactionRefundsTable.insert {
            it[id] = refundId
            it[this.transactionId] = transactionId
            it[reason] = request.reason
            it[transactionAmount] = transactionRow[TransactionsTable.total]
            it[this.refundedAmount] = refundedAmount
            it[status] = RefundStatus.COMPLETED
            it[disposition] = request.disposition
            it[requestedByUserId] = actorUserId
            it[approvedByUserId] = validatedApproval?.approvedByUserId
            it[managerApprovalId] = approvalScope?.approvalId
            it[idempotencyKey] = request.idempotencyKey
        }

        if (request.disposition == RefundDisposition.RETURN_TO_STOCK) {
            quantitiesByProduct.toSortedMap(compareBy(UUID::toString)).forEach { (productId, quantity) ->
                val before = requireNotNull(stockRows[productId])[StockTable.quantity]
                val adjustmentId = UUID.randomUUID()
                StockAdjustmentsTable.insert {
                    it[id] = adjustmentId
                    it[this.productId] = productId
                    it[userId] = actorUserId
                    it[adjType] = AdjType.CORRECTION
                    it[qtyBefore] = before
                    it[qtyAfter] = before.add(quantity)
                    it[reason] = "REFUND ${transactionRow[TransactionsTable.receiptNumber]}: ${request.reason}"
                    it[adjustmentSource] = "transaction_refund"
                    it[referenceType] = "TRANSACTION_REFUND"
                    it[referenceId] = refundId
                    it[occurredOn] = receivableToday()
                }
                insertAudit(actorUserId, "inventory", "stock_adjustments", adjustmentId, AuditAction.INSERT)
                insertAudit(
                    actorUserId,
                    "inventory",
                    "stock",
                    requireNotNull(stockRows[productId])[StockTable.id],
                    AuditAction.UPDATE
                )
            }
        }

        if (receivableId != null) {
            activeLedgerPayments.forEach { payment ->
                val reversalId = UUID.randomUUID()
                ReceivablePaymentsTable.insert {
                    it[id] = reversalId
                    it[this.receivableId] = receivableId
                    it[userId] = actorUserId
                    it[amount] = payment[ReceivablePaymentsTable.amount]
                    it[entryType] = ReceivablePaymentEntryType.REVERSAL
                    it[idempotencyKey] = "refund-$refundId-${payment[ReceivablePaymentsTable.id]}"
                    it[reversedPaymentId] = payment[ReceivablePaymentsTable.id]
                    it[method] = payment[ReceivablePaymentsTable.method]
                    it[reference] = payment[ReceivablePaymentsTable.paymentNumber]
                    it[notes] = "Reversal otomatis karena refund ${transactionRow[TransactionsTable.receiptNumber]}"
                }
                val compensationId = insertCompensationPayment(
                    transactionId,
                    PaymentMethod.entries.first { it.dbValue == payment[ReceivablePaymentsTable.method].dbValue },
                    payment[ReceivablePaymentsTable.amount],
                    refundId
                )
                if (payment[ReceivablePaymentsTable.method] == RecPaymentMethod.TUNAI) {
                    reverseCashForPayment(
                        payment[ReceivablePaymentsTable.userId],
                        payment[ReceivablePaymentsTable.paidAt],
                        payment[ReceivablePaymentsTable.amount],
                        actorUserId
                    )
                }
                insertAudit(actorUserId, "receivable", "receivable_payments", reversalId, AuditAction.INSERT)
                insertAudit(actorUserId, "sales", "payments", compensationId, AuditAction.INSERT)
            }
            ReceivablesTable.update({ ReceivablesTable.id eq receivableId }) { it[isActive] = false }
            insertAudit(actorUserId, "receivable", "receivables", receivableId, AuditAction.UPDATE)
        }

        if (initialPaid > BigDecimal.ZERO) {
            val initialPayment = PaymentsTable.select {
                (PaymentsTable.transactionId eq transactionId) and
                    (PaymentsTable.amount greater BigDecimal.ZERO)
            }.orderBy(PaymentsTable.paidAt to SortOrder.ASC).limit(1).singleOrNull()
                ?: throw ValidationException("Pembayaran awal transaksi tidak ditemukan")
            val compensationId = insertCompensationPayment(
                transactionId,
                initialPayment[PaymentsTable.method],
                initialPaid,
                refundId
            )
            if (initialPayment[PaymentsTable.method] == PaymentMethod.TUNAI) {
                reverseCashSession(transactionRow[TransactionsTable.sessionId], initialPaid, actorUserId)
            }
            insertAudit(actorUserId, "sales", "payments", compensationId, AuditAction.INSERT)
        }

        TransactionsTable.update({ TransactionsTable.id eq transactionId }) {
            it[status] = TrxStatus.REFUNDED
        }
        val consumedApproval = approvalScope?.let {
            managerApprovalService.consumeApprovalInCurrentTransaction(it, ipAddress)
        }
        insertRefundAudit(
            actorUserId = actorUserId,
            transactionId = transactionId,
            refundId = refundId,
            request = request,
            refundedAmount = refundedAmount,
            approval = consumedApproval ?: validatedApproval,
            ipAddress = ipAddress
        )
        insertAudit(actorUserId, "sales", "transactions", transactionId, AuditAction.UPDATE)

        refundResponse(
            TransactionRefundsTable.select { TransactionRefundsTable.id eq refundId }.single(),
            replay = false
        )
    }

    private fun ensureSameRefund(
        row: ResultRow,
        actorUserId: UUID,
        transactionId: UUID,
        request: ValidatedRefundRequest,
        managerApprovalId: UUID?
    ) {
        if (row[TransactionRefundsTable.requestedByUserId] != actorUserId ||
            row[TransactionRefundsTable.transactionId] != transactionId ||
            row[TransactionRefundsTable.reason] != request.reason ||
            row[TransactionRefundsTable.disposition] != request.disposition ||
            row[TransactionRefundsTable.managerApprovalId] != managerApprovalId
        ) {
            throw ValidationException("idempotencyKey sudah digunakan untuk permintaan refund yang berbeda")
        }
    }

    private fun insertCompensationPayment(
        transactionId: UUID,
        method: PaymentMethod,
        amount: BigDecimal,
        refundId: UUID
    ): UUID {
        val paymentId = UUID.randomUUID()
        PaymentsTable.insert {
            it[id] = paymentId
            it[this.transactionId] = transactionId
            it[this.method] = method
            it[this.amount] = amount.negate()
            it[reference] = "REFUND:$refundId"
            it[transactionRefundId] = refundId
        }
        return paymentId
    }

    private fun reverseCashForPayment(
        receiverId: UUID,
        paidAt: OffsetDateTime,
        amount: BigDecimal,
        actorUserId: UUID
    ) {
        val session = CashSessionsTable.select {
            (CashSessionsTable.userId eq receiverId) and (CashSessionsTable.openedAt lessEq paidAt)
        }.orderBy(CashSessionsTable.openedAt to SortOrder.DESC).forUpdate().firstOrNull()
            ?: throw ValidationException("Sesi kas penerima pembayaran tidak ditemukan untuk refund")
        val closedAt = session[CashSessionsTable.closedAt]
        if (closedAt != null && paidAt > closedAt) {
            throw ValidationException("Pembayaran tunai tidak cocok dengan sesi kas")
        }
        reverseCashSession(session[CashSessionsTable.id], amount, actorUserId)
    }

    private fun reverseCashSession(sessionId: UUID, amount: BigDecimal, actorUserId: UUID) {
        val session = CashSessionsTable.select { CashSessionsTable.id eq sessionId }
            .forUpdate().singleOrNull()
            ?: throw ValidationException("Sesi kas transaksi tidak ditemukan untuk refund")
        val current = session[CashSessionsTable.systemCash] ?: session[CashSessionsTable.openingCash]
        val updated = current.subtract(amount).money()
        CashSessionsTable.update({ CashSessionsTable.id eq sessionId }) {
            it[systemCash] = updated
            session[CashSessionsTable.closingCash]?.let { closing ->
                it[difference] = closing.subtract(updated).money()
            }
        }
        insertAudit(actorUserId, "sales", "cash_sessions", sessionId, AuditAction.UPDATE)
    }

    private fun insertRefundAudit(
        actorUserId: UUID,
        transactionId: UUID,
        refundId: UUID,
        request: ValidatedRefundRequest,
        refundedAmount: BigDecimal,
        approval: ManagerApprovalRecord?,
        ipAddress: String?
    ) {
        val metadata = buildJsonObject {
            put("event", "TRANSACTION_REFUNDED")
            put("refundId", refundId.toString())
            put("transactionId", transactionId.toString())
            put("actorUserId", actorUserId.toString())
            put("reason", request.reason)
            put("refundedAmount", refundedAmount.toPlainString())
            put("returnDisposition", request.disposition.name)
            put("timestamp", OffsetDateTime.now().toString())
            approval?.let {
                put("managerApprovalId", it.id.toString())
                put("approvedByUserId", it.approvedByUserId.toString())
            }
        }.toString()
        AuditLogsTable.insert {
            it[id] = UUID.randomUUID()
            it[userId] = actorUserId
            it[action] = AuditAction.INSERT
            it[targetSchemaName] = "sales"
            it[targetTableName] = "transaction_refunds"
            it[recordId] = refundId
            it[newData] = metadata
            it[this.ipAddress] = ipAddress?.take(45)
        }
    }

    private fun insertAudit(
        actorUserId: UUID,
        schemaName: String,
        tableName: String,
        recordId: UUID,
        action: AuditAction
    ) {
        AuditLogsTable.insert {
            it[id] = UUID.randomUUID()
            it[userId] = actorUserId
            it[this.action] = action
            it[targetSchemaName] = schemaName
            it[targetTableName] = tableName
            it[this.recordId] = recordId
        }
    }

    private fun refundResponse(row: ResultRow, replay: Boolean) = RefundTransactionResponse(
        refundId = row[TransactionRefundsTable.id].toString(),
        refundNumber = row[TransactionRefundsTable.refundNumber],
        transactionId = row[TransactionRefundsTable.transactionId].toString(),
        status = TrxStatus.REFUNDED.name,
        transactionAmount = row[TransactionRefundsTable.transactionAmount],
        refundedAmount = row[TransactionRefundsTable.refundedAmount],
        returnDisposition = row[TransactionRefundsTable.disposition].name,
        reason = row[TransactionRefundsTable.reason],
        requestedByUserId = row[TransactionRefundsTable.requestedByUserId].toString(),
        createdAt = row[TransactionRefundsTable.createdAt].toString(),
        managerApprovalId = row[TransactionRefundsTable.managerApprovalId]?.toString(),
        idempotentReplay = replay
    )
}

private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
