package com.service.tbterminal.analytics

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date

// ==========================================
// EXPOSED TABLES (Mapping Database Views)
// ==========================================

object VDailySalesView : Table("sales.v_daily_sales") {
    val saleDate = date("sale_date")
    val totalTransactions = long("total_transactions")
    val totalRevenue = decimal("total_revenue", 15, 2)
    val totalDp = decimal("total_dp", 15, 2)
}

object VReceivablesActiveView : Table("receivable.v_receivables_active") {
    val receivableId = uuid("receivable_id")
    val customerId = uuid("customer_id")
    val customerName = varchar("customer_name", 100)
    val totalDebt = decimal("total_debt", 15, 2)
    val paidAmount = decimal("paid_amount", 15, 2)
    val remainingDebt = decimal("remaining_debt", 15, 2)
    val dueDate = date("due_date")
    val status = varchar("status", 50)
}

object AnalyticsStockDetailView : Table("inventory.v_stock_detail") {
    val productId = uuid("product_id")
    val sku = varchar("sku", 50)
    val productName = varchar("product_name", 200)
    val categoryName = varchar("category_name", 100)
    val unitName = varchar("unit_name", 50)
    val quantity = decimal("quantity", 10, 2)
    val minStock = decimal("min_stock", 10, 2)
    val priceBuy = decimal("price_buy", 15, 2)
    val priceRetail = decimal("price_retail", 15, 2)
    val priceContractor = decimal("price_contractor", 15, 2)
    val isActive = bool("is_active")
}

// ==========================================
// DATA CLASSES (DTOs)
// ==========================================

@Serializable
data class DailySalesSummary(
    val date: String,
    val transactionCount: Long,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val totalRevenue: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val totalDp: java.math.BigDecimal
)

@Serializable
data class ReceivableActiveSummary(
    val receivableId: String,
    val customerName: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val remainingDebt: java.math.BigDecimal,
    val dueDate: String,
    val status: String
)

@Serializable
data class StockLowSummary(
    val productId: String,
    val sku: String,
    val productName: String,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val quantity: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val minStock: java.math.BigDecimal
)

@Serializable
data class DashboardMetrics(
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val totalRevenueToday: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val totalRevenueThisMonth: java.math.BigDecimal,
    @Serializable(with = com.service.tbterminal.shared.BigDecimalSerializer::class) val totalActiveReceivables: java.math.BigDecimal,
    val activeReceivableCount: Long,
    val lowStockCount: Long,
    val lowStockItems: List<StockLowSummary>
)
