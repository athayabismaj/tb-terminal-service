package com.service.tbterminal.analytics

import com.service.tbterminal.shared.ValidationException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.ZoneId

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

    private fun parseDate(dateStr: String?, default: LocalDate): LocalDate {
        if (dateStr.isNullOrBlank()) return default
        return try {
            LocalDate.parse(dateStr)
        } catch (e: DateTimeParseException) {
            throw ValidationException("Format tanggal salah. Gunakan format YYYY-MM-DD")
        }
    }
}
