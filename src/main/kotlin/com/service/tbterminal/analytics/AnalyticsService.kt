package com.service.tbterminal.analytics

import com.service.tbterminal.shared.ValidationException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.ZoneId
import java.util.UUID

class AnalyticsService(private val repo: AnalyticsRepository) {

    suspend fun getDailySales(startDateStr: String?, endDateStr: String?): List<DailySalesSummary> {
        val zoneId = ZoneId.of("Asia/Jakarta")
        val today = LocalDate.now(zoneId)
        
        // Default to last 30 days if not provided
        val endDate = parseDate(endDateStr, today)
        val startDate = parseDate(startDateStr, endDate.minusDays(30))

        if (startDate.isAfter(endDate)) {
            throw ValidationException("Tanggal mulai tidak boleh lebih dari tanggal akhir")
        }
        validatePeriod(startDate, endDate)

        return repo.getDailySales(startDate, endDate)
    }

    suspend fun getDashboardMetrics(): DashboardMetrics {
        val zoneId = ZoneId.of("Asia/Jakarta")
        val today = LocalDate.now(zoneId)
        val firstDayOfMonth = today.withDayOfMonth(1)

        return repo.getDashboardMetrics(today, firstDayOfMonth)
    }

    suspend fun getSalesReport(
        startDateStr: String?,
        endDateStr: String?,
        cashierIdStr: String?,
        sessionIdStr: String?,
        topProductsLimitStr: String?,
        customerIdStr: String? = null,
        productIdStr: String? = null,
        categoryIdStr: String? = null,
        paymentMethodStr: String? = null,
        statusStr: String? = null
    ): SalesReportResponse {
        val zoneId = ZoneId.of("Asia/Jakarta")
        val today = LocalDate.now(zoneId)
        val endDate = parseDate(endDateStr, today)
        val startDate = parseDate(startDateStr, endDate.minusDays(30))

        if (startDate.isAfter(endDate)) {
            throw ValidationException("Tanggal mulai tidak boleh lebih dari tanggal akhir")
        }
        validatePeriod(startDate, endDate)

        val cashierId = parseOptionalUuid(cashierIdStr, "cashierId")
        val sessionId = parseOptionalUuid(sessionIdStr, "sessionId")
        val topProductsLimit = parseTopProductsLimit(topProductsLimitStr)

        val filter = SalesReportFilter(
            startDate = startDate,
            endDate = endDate,
            cashierId = cashierId,
            sessionId = sessionId,
            customerId = parseOptionalUuid(customerIdStr, "customerId"),
            productId = parseOptionalUuid(productIdStr, "productId"),
            categoryId = parseOptionalUuid(categoryIdStr, "categoryId"),
            paymentMethod = parseEnum(paymentMethodStr, "metode pembayaran", PAYMENT_METHODS),
            status = parseEnum(statusStr, "status", TRANSACTION_STATUSES)
        )

        return repo.getSalesReport(
            filter = filter,
            topProductsLimit = topProductsLimit
        )
    }

    private fun parseDate(dateStr: String?, default: LocalDate): LocalDate {
        if (dateStr.isNullOrBlank()) return default
        return try {
            LocalDate.parse(dateStr)
        } catch (e: DateTimeParseException) {
            throw ValidationException("Format tanggal salah. Gunakan format YYYY-MM-DD")
        }
    }

    private fun parseOptionalUuid(value: String?, fieldName: String): UUID? {
        if (value.isNullOrBlank()) return null
        return try {
            UUID.fromString(value)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Format $fieldName tidak valid")
        }
    }

    private fun parseTopProductsLimit(value: String?): Int {
        if (value.isNullOrBlank()) return 10
        val parsed = value.toIntOrNull()
            ?: throw ValidationException("topProductsLimit harus berupa angka")

        if (parsed < 1) {
            throw ValidationException("topProductsLimit minimal 1")
        }

        return parsed.coerceAtMost(100)
    }

    fun parseExportFilter(parameters: Map<String, String>): SalesReportFilter {
        val today = LocalDate.now(ZoneId.of("Asia/Jakarta"))
        val endDate = parseDate(parameters["endDate"], today)
        val startDate = parseDate(parameters["startDate"], endDate.minusDays(30))
        if (startDate.isAfter(endDate)) throw ValidationException("Tanggal mulai tidak boleh lebih dari tanggal akhir")
        validatePeriod(startDate, endDate)
        return SalesReportFilter(
            startDate = startDate,
            endDate = endDate,
            cashierId = parseOptionalUuid(parameters["cashierId"], "cashierId"),
            customerId = parseOptionalUuid(parameters["customerId"], "customerId"),
            productId = parseOptionalUuid(parameters["productId"], "productId"),
            categoryId = parseOptionalUuid(parameters["categoryId"], "categoryId"),
            paymentMethod = parseEnum(parameters["paymentMethod"], "metode pembayaran", PAYMENT_METHODS),
            status = parseEnum(parameters["status"], "status", TRANSACTION_STATUSES)
        )
    }

    suspend fun getCsvPage(type: CsvExportType, filter: SalesReportFilter, limit: Int, offset: Long): CsvPage =
        repo.getCsvPage(type, filter, limit.coerceIn(1, 1_000), offset.coerceAtLeast(0))

    private fun validatePeriod(startDate: LocalDate, endDate: LocalDate) {
        if (startDate.plusDays(MAX_REPORT_DAYS - 1).isBefore(endDate)) {
            throw ValidationException("Periode laporan maksimal $MAX_REPORT_DAYS hari")
        }
    }

    private fun parseEnum(value: String?, field: String, allowed: Set<String>): String? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim().lowercase()
        if (normalized !in allowed) throw ValidationException("$field tidak valid")
        return normalized
    }

    private companion object {
        const val MAX_REPORT_DAYS = 366L
        val PAYMENT_METHODS = setOf("tunai", "transfer", "qris", "dp", "hutang")
        val TRANSACTION_STATUSES = setOf("lunas", "dp", "hutang", "voided", "refunded")
    }
}
