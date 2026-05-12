package com.service.tbterminal.receivable

import com.service.tbterminal.inventory.PaginatedResponse
import com.service.tbterminal.shared.NotFoundException
import com.service.tbterminal.shared.ValidationException
import org.jetbrains.exposed.exceptions.ExposedSQLException
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
        page: Int, limit: Int, customerId: String?, status: String?
    ): PaginatedResponse<ReceivableResponse> {
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit

        val customerUuid = customerId?.let { parseUUID(it) }
        val receivableStatus = status?.let {
            ReceivableStatus.entries.firstOrNull { e -> e.dbValue == it.lowercase() }
                ?: throw ValidationException(
                    "Status '$it' tidak valid. Gunakan: belum_lunas, sebagian, atau lunas"
                )
        }

        return repository.getPaginatedReceivables(safePage, safeLimit, customerUuid, receivableStatus)
    }

    suspend fun getReceivableById(id: String): ReceivableResponse {
        val uuid = parseUUID(id)
        return repository.getReceivableById(uuid)
            ?: throw NotFoundException("Piutang tidak ditemukan")
    }

    // ==========================================
    // PAYMENT ENGINE
    // ==========================================

    suspend fun pay(userId: UUID, request: PaymentRequest): PaymentResponse {
        // 1. Validasi amount > 0
        if (request.amount <= java.math.BigDecimal.ZERO) {
            throw ValidationException("Jumlah pembayaran harus lebih dari nol")
        }

        // 2. Parse receivableId
        val receivableId = parseUUID(request.receivableId)

        // 3. Parse method
        val method = RecPaymentMethod.entries.firstOrNull { it.dbValue == request.method.lowercase() }
            ?: throw ValidationException(
                "Metode pembayaran '${request.method}' tidak valid. " +
                "Gunakan: tunai, transfer, atau qris"
            )

        // 4-10. Eksekusi dalam satu transaksi atomik dengan FOR UPDATE lock
        return org.jetbrains.exposed.sql.transactions.transaction {
            // 4. Lock row piutang (FOR UPDATE) — mencegah race condition
            val receivable = kotlinx.coroutines.runBlocking { repository.getReceivableForUpdate(receivableId) }
                ?: throw NotFoundException("Piutang dengan ID ${request.receivableId} tidak ditemukan")

            // 5. Cek apakah sudah lunas
            if (receivable.status == ReceivableStatus.LUNAS) {
                throw ValidationException("Piutang ini sudah lunas, tidak dapat menerima pembayaran lagi")
            }

            // 6. Hitung sisa hutang
            val remainingAmount = receivable.amount.subtract(receivable.paidAmount)

            // 7. Overpayment Guard
            if (request.amount > remainingAmount) {
                throw ValidationException(
                    "Pembayaran melebihi sisa hutang. " +
                    "Sisa hutang: ${remainingAmount.toPlainString()}, " +
                    "jumlah bayar: ${request.amount.toPlainString()}"
                )
            }

            // 8. Hitung newPaidAmount
            val newPaidAmount = receivable.paidAmount.add(request.amount)

            // 9. Tentukan status baru
            val newStatus = if (newPaidAmount >= receivable.amount) {
                ReceivableStatus.LUNAS
            } else {
                ReceivableStatus.SEBAGIAN
            }

            // 10. Insert payment + update receivable (atomik)
            kotlinx.coroutines.runBlocking {
                repository.insertPaymentAndUpdateReceivable(
                    receivableId = receivableId,
                    userId = userId,
                    paymentAmount = request.amount,
                    method = method,
                    reference = request.reference,
                    notes = request.notes,
                    newPaidAmount = newPaidAmount,
                    newStatus = newStatus
                )
            }
        }
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
}
