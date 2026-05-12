package com.service.tbterminal.analytics

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate

class AnalyticsRepository {

    suspend fun getDailySales(startDate: LocalDate, endDate: LocalDate): List<DailySalesSummary> =
        newSuspendedTransaction(Dispatchers.IO) {
            VDailySalesView
                .select { (VDailySalesView.saleDate greaterEq startDate) and (VDailySalesView.saleDate lessEq endDate) }
                .orderBy(VDailySalesView.saleDate, SortOrder.DESC)
                .map {
                    DailySalesSummary(
                        date = it[VDailySalesView.saleDate].toString(),
                        transactionCount = it[VDailySalesView.totalTransactions],
                        totalRevenue = it[VDailySalesView.totalRevenue],
                        totalDp = it[VDailySalesView.totalDp]
                    )
                }
        }

    suspend fun getDashboardMetrics(today: LocalDate, firstDayOfMonth: LocalDate): DashboardMetrics =
        newSuspendedTransaction(Dispatchers.IO) {
            
            // 1. Total Revenue Today
            val revenueTodayRow = VDailySalesView
                .select { VDailySalesView.saleDate eq today }
                .singleOrNull()
            val totalRevenueToday = revenueTodayRow?.get(VDailySalesView.totalRevenue) ?: java.math.BigDecimal.ZERO

            // 2. Total Revenue This Month
            val totalRevenueThisMonth = VDailySalesView
                .slice(VDailySalesView.totalRevenue.sum())
                .select { (VDailySalesView.saleDate greaterEq firstDayOfMonth) and (VDailySalesView.saleDate lessEq today) }
                .singleOrNull()
                ?.getOrNull(VDailySalesView.totalRevenue.sum()) ?: java.math.BigDecimal.ZERO

            // 3. Receivables
            val receivablesCount = VReceivablesActiveView.selectAll().count()
            val totalActiveReceivables = VReceivablesActiveView
                .slice(VReceivablesActiveView.remainingDebt.sum())
                .selectAll()
                .singleOrNull()
                ?.getOrNull(VReceivablesActiveView.remainingDebt.sum()) ?: java.math.BigDecimal.ZERO

            // 4. Low Stock Items (Quantity <= MinStock and is_active = true)
            // Limit 10 for dashboard
            val lowStockQuery = AnalyticsStockDetailView
                .select { (AnalyticsStockDetailView.quantity lessEq AnalyticsStockDetailView.minStock) and (AnalyticsStockDetailView.isActive eq true) }
            
            val lowStockCount = lowStockQuery.count()
            
            val lowStockItems = lowStockQuery
                .orderBy(AnalyticsStockDetailView.quantity, SortOrder.ASC)
                .limit(10)
                .map {
                    StockLowSummary(
                        productId = it[AnalyticsStockDetailView.productId].toString(),
                        sku = it[AnalyticsStockDetailView.sku],
                        productName = it[AnalyticsStockDetailView.productName],
                        quantity = it[AnalyticsStockDetailView.quantity],
                        minStock = it[AnalyticsStockDetailView.minStock]
                    )
                }

            DashboardMetrics(
                totalRevenueToday = totalRevenueToday,
                totalRevenueThisMonth = totalRevenueThisMonth,
                totalActiveReceivables = totalActiveReceivables,
                activeReceivableCount = receivablesCount,
                lowStockCount = lowStockCount,
                lowStockItems = lowStockItems
            )
        }
}
