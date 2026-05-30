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
        topProductsLimitStr: String?
    ): SalesReportResponse {
        val zoneId = ZoneId.of("Asia/Jakarta")
        val today = LocalDate.now(zoneId)
        val endDate = parseDate(endDateStr, today)
        val startDate = parseDate(startDateStr, endDate.minusDays(30))

        if (startDate.isAfter(endDate)) {
            throw ValidationException("Tanggal mulai tidak boleh lebih dari tanggal akhir")
        }

        val cashierId = parseOptionalUuid(cashierIdStr, "cashierId")
        val sessionId = parseOptionalUuid(sessionIdStr, "sessionId")
        val topProductsLimit = parseTopProductsLimit(topProductsLimitStr)

        return repo.getSalesReport(
            startDate = startDate,
            endDate = endDate,
            cashierId = cashierId,
            sessionId = sessionId,
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
}
