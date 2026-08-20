package com.service.tbterminal.receivable

import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

class ReceivableService(private val repository: ReceivableRepository) {

    // ==========================================
    // CUSTOMERS
    // ==========================================

    suspend fun getCustomers(page: Int, limit: Int, search: String?): PaginatedResponse<CustomerResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit
        return repository.getPaginatedCustomers(safePage, safeLimit, search)
    }

    suspend fun getCustomerById(id: String): CustomerResponse {
        val uuid = parseUUID(id)
        return repository.getCustomerById(uuid)
            ?: throw NotFoundException("Pelanggan tidak ditemukan atau sudah dinonaktifkan")
    }

    suspend fun createCustomer(request: CustomerRequest): CustomerResponse {
        val name = request.name.trim()
        validateCustomerInput(name, request.creditLimit, request.paymentTermDays)

        // Cek duplikasi nama
        val existing = repository.getCustomerByName(name)
        if (existing != null) {
            throw ValidationException("Pelanggan dengan nama '$name' sudah ada")
        }

        val newId = repository.createCustomer(
            name = name,
            phone = request.phone?.trim(),
            address = request.address?.trim(),
            isContractor = request.isContractor,
            creditLimit = request.creditLimit,
            paymentTermDays = request.paymentTermDays
        )
        return repository.getCustomerById(newId)!!
    }

    suspend fun updateCustomer(id: String, request: CustomerRequest): CustomerResponse {
        val uuid = parseUUID(id)
        val name = request.name.trim()
        validateCustomerInput(name, request.creditLimit, request.paymentTermDays)

        val current = repository.getCustomerById(uuid)
            ?: throw NotFoundException("Pelanggan tidak ditemukan atau sudah dinonaktifkan")

        // Cek duplikasi nama hanya jika nama berubah
        if (current.name.lowercase() != name.lowercase()) {
            val existing = repository.getCustomerByName(name)
            if (existing != null) {
                throw ValidationException("Pelanggan dengan nama '$name' sudah ada")
            }
        }

        repository.updateCustomer(
            id = uuid,
            name = name,
            phone = request.phone?.trim(),
            address = request.address?.trim(),
            isContractor = request.isContractor,
            creditLimit = request.creditLimit,
            paymentTermDays = request.paymentTermDays
        )
        return repository.getCustomerById(uuid)!!
    }

    suspend fun deleteCustomer(id: String) {
        val uuid = parseUUID(id)
        repository.getCustomerById(uuid)
            ?: throw NotFoundException("Pelanggan tidak ditemukan atau sudah dinonaktifkan")

        try {
            repository.softDeleteCustomer(uuid)
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23503" || e.message?.contains("violates foreign key constraint") == true) {
                throw ValidationException("Pelanggan tidak dapat dihapus karena masih memiliki data terkait (piutang aktif)")
            }
            throw e
        }
    }

    // ==========================================
    // RECEIVABLES
    // ==========================================

    suspend fun getReceivables(
        page: Int, limit: Int, customerId: String?, status: String?,
        dueFilter: String?, dueFrom: String?, dueTo: String?
    ): PaginatedResponse<ReceivableResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = limit.coerceIn(1, 100)

        val customerUuid = customerId?.let { parseUUID(it) }
        val receivableStatus = status?.let {
            ReceivableStatus.entries.firstOrNull { e ->
                e.name.equals(it, ignoreCase = true) || e.dbValue.equals(it, ignoreCase = true)
            }
                ?: throw ValidationException(
                    "Status '$it' tidak valid. Gunakan: UNPAID, PARTIAL, atau PAID"
                )
        }
        val parsedDueFilter = dueFilter?.let {
            ReceivableDueFilter.entries.firstOrNull { value -> value.name.equals(it, ignoreCase = true) }
                ?: throw ValidationException("Filter jatuh tempo tidak valid")
        } ?: ReceivableDueFilter.ALL
        val fromDate = dueFrom?.let { parseDate(it, "dueFrom") }
        val toDate = dueTo?.let { parseDate(it, "dueTo") }
        if (fromDate != null && toDate != null && fromDate > toDate) {
            throw ValidationException("dueFrom tidak boleh setelah dueTo")
        }

        return repository.getPaginatedReceivables(
            safePage, safeLimit, customerUuid, receivableStatus, parsedDueFilter, fromDate, toDate
        )
    }

    suspend fun getReceivableById(id: String): ReceivableResponse {
        val uuid = parseUUID(id)
        return repository.getReceivableById(uuid)
            ?: throw NotFoundException("Piutang tidak ditemukan")
    }

    suspend fun createStandaloneReceivable(
        userId: UUID,
        request: CreateStandaloneReceivableRequest
    ): ReceivableResponse {
        val validated = validateStandaloneReceivableRequest(request)

        return repository.createStandaloneReceivable(
            userId = userId,
            customerId = parseUUID(request.customerId),
            amount = validated.amount,
            debtDate = validated.debtDate,
            dueDate = validated.dueDate,
            legacyInvoiceNumber = validated.legacyInvoiceNumber,
            source = validated.source,
            notes = validated.notes
        )
    }

    suspend fun getCustomerSummaries(
        page: Int,
        limit: Int,
        dueFilter: String?
    ): PaginatedResponse<CustomerReceivableSummaryResponse> {
        val parsedDueFilter = dueFilter?.let {
            ReceivableDueFilter.entries.firstOrNull { value -> value.name.equals(it, ignoreCase = true) }
                ?: throw ValidationException("Filter jatuh tempo tidak valid")
        } ?: ReceivableDueFilter.ALL
        return repository.getCustomerSummaries(page.coerceAtLeast(1), limit.coerceIn(1, 100), parsedDueFilter)
    }

    // ==========================================
    // PAYMENT ENGINE
    // ==========================================

    suspend fun getPayments(
        page: Int,
        limit: Int,
        receivableId: String? = null,
        customerId: String? = null,
        method: String? = null,
        userId: String? = null,
        customerSearch: String? = null,
        receiverSearch: String? = null,
        status: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null
    ): PaginatedResponse<PaymentHistoryResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = limit.coerceIn(1, 100)
        val parsedMethod = method?.let { raw ->
            RecPaymentMethod.entries.firstOrNull { it.dbValue.equals(raw, ignoreCase = true) }
                ?.takeIf { it in setOf(RecPaymentMethod.TUNAI, RecPaymentMethod.TRANSFER, RecPaymentMethod.QRIS) }
                ?: throw ValidationException("Metode filter harus tunai, transfer, atau qris")
        }
        val parsedStatus = status?.let(::parseReceivableStatus)
        val parsedFrom = dateFrom?.let { parseDate(it, "dateFrom") }
        val parsedTo = dateTo?.let { parseDate(it, "dateTo") }
        if (parsedFrom != null && parsedTo != null && parsedFrom > parsedTo) {
            throw ValidationException("dateFrom tidak boleh setelah dateTo")
        }
        return repository.getPaginatedPayments(
            page = safePage,
            limit = safeLimit,
            receivableId = receivableId?.let(::parseUUID),
            customerId = customerId?.let(::parseUUID),
            method = parsedMethod,
            userId = userId?.let(::parseUUID),
            customerSearch = customerSearch?.trim()?.takeIf(String::isNotBlank),
            receiverSearch = receiverSearch?.trim()?.takeIf(String::isNotBlank),
            status = parsedStatus,
            dateFrom = parsedFrom,
            dateTo = parsedTo
        )
    }

    suspend fun getPaymentReceipt(paymentId: String): PaymentHistoryResponse =
        repository.getPaymentReceipt(parseUUID(paymentId))
            ?: throw NotFoundException("Bukti pembayaran tidak ditemukan")

    suspend fun pay(userId: UUID, request: PaymentRequest): PaymentResponse {
        val validated = validateReceivablePaymentRequest(request)
        return repository.insertPaymentAndUpdateReceivable(
            receivableId = validated.receivableId,
            userId = userId,
            paymentAmount = validated.amount,
            method = validated.method,
            reference = validated.reference,
            notes = validated.notes,
            idempotencyKey = validated.idempotencyKey
        )
    }

    suspend fun reversePayment(
        userId: UUID,
        paymentId: String,
        request: ReversePaymentRequest
    ): PaymentResponse {
        val validated = validatePaymentReversalRequest(request)
        return repository.reversePayment(
            paymentId = parseUUID(paymentId),
            userId = userId,
            idempotencyKey = validated.idempotencyKey,
            reason = validated.reason
        )
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private fun validateCustomerInput(name: String, creditLimit: java.math.BigDecimal, paymentTermDays: Int) {
        if (name.isEmpty()) {
            throw ValidationException("Nama pelanggan tidak boleh kosong")
        }
        if (creditLimit < java.math.BigDecimal.ZERO) {
            throw ValidationException("Limit kredit tidak boleh kurang dari nol")
        }
        if (paymentTermDays < 0) {
            throw ValidationException("Termin bayar tidak boleh kurang dari nol hari")
        }
    }

    private fun parseUUID(id: String): UUID {
        return try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Format ID tidak valid")
        }
    }

    private fun parseDate(value: String, field: String): LocalDate {
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw ValidationException("Format $field harus yyyy-MM-dd")
        }
    }

    private fun parseReceivableStatus(value: String): ReceivableStatus =
        ReceivableStatus.entries.firstOrNull {
            it.name.equals(value, ignoreCase = true) || it.dbValue.equals(value, ignoreCase = true)
        } ?: throw ValidationException("Status harus UNPAID, PARTIAL, atau PAID")
}
