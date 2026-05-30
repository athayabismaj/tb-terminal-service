package com.service.tbterminal.analytics

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

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

    suspend fun getSalesReport(
        startDate: LocalDate,
        endDate: LocalDate,
        cashierId: UUID?,
        sessionId: UUID?,
        topProductsLimit: Int
    ): SalesReportResponse = newSuspendedTransaction(Dispatchers.IO) {
        val zoneId = ZoneId.of("Asia/Jakarta")
        val startAt = startDate.atStartOfDay(zoneId).toOffsetDateTime()
        val endExclusive = endDate.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime()

        val transactionWhere = buildTransactionWhere(
            transactionAlias = "t",
            startAt = startAt,
            endExclusive = endExclusive,
            cashierId = cashierId,
            sessionId = sessionId
        )
        val paymentWhere = buildPaymentWhere(
            paymentAlias = "p",
            transactionAlias = "t",
            startAt = startAt,
            endExclusive = endExclusive,
            cashierId = cashierId,
            sessionId = sessionId
        )

        val baseTotals = queryOne(
            """
            SELECT
                COUNT(t.id) AS transaction_count,
                COALESCE(SUM(t.total), 0)::numeric AS gross_revenue,
                COALESCE(SUM(t.paid_amount), 0)::numeric AS paid_amount
            FROM sales.transactions t
            WHERE $transactionWhere
            """
        ) { row ->
            SalesReportTotals(
                transactionCount = row.getLong("transaction_count"),
                grossRevenue = row.getBigDecimalOrZero("gross_revenue"),
                paidAmount = row.getBigDecimalOrZero("paid_amount"),
                outstandingAmount = BigDecimal.ZERO,
                grossProfit = BigDecimal.ZERO
            )
        } ?: SalesReportTotals(
            transactionCount = 0,
            grossRevenue = BigDecimal.ZERO,
            paidAmount = BigDecimal.ZERO,
            outstandingAmount = BigDecimal.ZERO,
            grossProfit = BigDecimal.ZERO
        )

        val grossProfit = queryOne(
            """
            SELECT COALESCE(SUM(ti.subtotal - (ti.cogs_at_transaction * ti.quantity)), 0)::numeric AS gross_profit
            FROM sales.transaction_items ti
            JOIN sales.transactions t ON t.id = ti.transaction_id
            WHERE $transactionWhere
            """
        ) { row ->
            row.getBigDecimalOrZero("gross_profit")
        } ?: BigDecimal.ZERO

        val receivables = queryOne(
            """
            SELECT
                COUNT(r.id) AS receivable_count,
                COALESCE(SUM(r.amount), 0)::numeric AS created_receivable_amount,
                COALESCE(SUM(r.paid_amount), 0)::numeric AS paid_amount,
                COALESCE(SUM(r.amount - r.paid_amount), 0)::numeric AS remaining_amount
            FROM receivable.receivables r
            JOIN sales.transactions t ON t.id = r.transaction_id
            WHERE $transactionWhere
            """
        ) { row ->
            SalesReceivableSummary(
                createdReceivableAmount = row.getBigDecimalOrZero("created_receivable_amount"),
                paidAmount = row.getBigDecimalOrZero("paid_amount"),
                remainingAmount = row.getBigDecimalOrZero("remaining_amount"),
                receivableCount = row.getLong("receivable_count")
            )
        } ?: SalesReceivableSummary(
            createdReceivableAmount = BigDecimal.ZERO,
            paidAmount = BigDecimal.ZERO,
            remainingAmount = BigDecimal.ZERO,
            receivableCount = 0
        )

        val paymentRows = queryList(
            """
            SELECT
                p.method::text AS payment_method,
                COUNT(p.id) AS payment_count,
                COALESCE(SUM(p.amount), 0)::numeric AS amount
            FROM sales.payments p
            JOIN sales.transactions t ON t.id = p.transaction_id
            WHERE $paymentWhere
            GROUP BY p.method
            """
        ) { row ->
            PaymentMethodSummary(
                method = row.getString("payment_method"),
                paymentCount = row.getLong("payment_count"),
                amount = row.getBigDecimalOrZero("amount")
            )
        }.associateBy { it.method }

        val paymentMethods = PAYMENT_METHODS.map { method ->
            paymentRows[method] ?: PaymentMethodSummary(
                method = method,
                paymentCount = 0,
                amount = BigDecimal.ZERO
            )
        }

        val statusRows = queryList(
            """
            SELECT
                t.status::text AS transaction_status,
                COUNT(t.id) AS transaction_count,
                COALESCE(SUM(t.total), 0)::numeric AS revenue,
                COALESCE(SUM(t.paid_amount), 0)::numeric AS paid_amount
            FROM sales.transactions t
            WHERE $transactionWhere
            GROUP BY t.status
            """
        ) { row ->
            TransactionStatusSummary(
                status = row.getString("transaction_status"),
                transactionCount = row.getLong("transaction_count"),
                revenue = row.getBigDecimalOrZero("revenue"),
                paidAmount = row.getBigDecimalOrZero("paid_amount")
            )
        }.associateBy { it.status }

        val transactionStatuses = TRANSACTION_STATUSES.map { status ->
            statusRows[status] ?: TransactionStatusSummary(
                status = status,
                transactionCount = 0,
                revenue = BigDecimal.ZERO,
                paidAmount = BigDecimal.ZERO
            )
        }

        val topProducts = queryList(
            """
            SELECT
                ti.product_id::text AS product_id,
                ti.product_name AS product_name,
                COALESCE(SUM(ti.quantity), 0)::numeric AS qty_sold,
                COALESCE(SUM(ti.subtotal), 0)::numeric AS revenue,
                COALESCE(SUM(ti.subtotal - (ti.cogs_at_transaction * ti.quantity)), 0)::numeric AS gross_profit
            FROM sales.transaction_items ti
            JOIN sales.transactions t ON t.id = ti.transaction_id
            WHERE $transactionWhere
            GROUP BY ti.product_id, ti.product_name
            ORDER BY qty_sold DESC, revenue DESC, product_name ASC
            LIMIT $topProductsLimit
            """
        ) { row ->
            TopProductSalesSummary(
                productId = row.getString("product_id"),
                productName = row.getString("product_name"),
                qtySold = row.getBigDecimalOrZero("qty_sold"),
                revenue = row.getBigDecimalOrZero("revenue"),
                grossProfit = row.getBigDecimalOrZero("gross_profit")
            )
        }

        val profitSubqueryWhere = buildTransactionWhere(
            transactionAlias = "t2",
            startAt = startAt,
            endExclusive = endExclusive,
            cashierId = cashierId,
            sessionId = sessionId
        )
        val cashiers = queryList(
            """
            WITH item_profit AS (
                SELECT
                    ti.transaction_id,
                    COALESCE(SUM(ti.subtotal - (ti.cogs_at_transaction * ti.quantity)), 0)::numeric AS gross_profit
                FROM sales.transaction_items ti
                JOIN sales.transactions t2 ON t2.id = ti.transaction_id
                WHERE $profitSubqueryWhere
                GROUP BY ti.transaction_id
            )
            SELECT
                t.user_id::text AS user_id,
                u.name AS cashier_name,
                COUNT(t.id) AS transaction_count,
                COALESCE(SUM(t.total), 0)::numeric AS revenue,
                COALESCE(SUM(ip.gross_profit), 0)::numeric AS gross_profit
            FROM sales.transactions t
            JOIN system.users u ON u.id = t.user_id
            LEFT JOIN item_profit ip ON ip.transaction_id = t.id
            WHERE $transactionWhere
            GROUP BY t.user_id, u.name
            ORDER BY revenue DESC, transaction_count DESC, cashier_name ASC
            """
        ) { row ->
            CashierSalesSummary(
                userId = row.getString("user_id"),
                cashierName = row.getString("cashier_name"),
                transactionCount = row.getLong("transaction_count"),
                revenue = row.getBigDecimalOrZero("revenue"),
                grossProfit = row.getBigDecimalOrZero("gross_profit")
            )
        }

        SalesReportResponse(
            range = SalesReportRange(
                startDate = startDate.toString(),
                endDate = endDate.toString()
            ),
            totals = baseTotals.copy(
                outstandingAmount = receivables.remainingAmount,
                grossProfit = grossProfit
            ),
            paymentMethods = paymentMethods,
            transactionStatuses = transactionStatuses,
            topProducts = topProducts,
            cashiers = cashiers,
            receivables = receivables
        )
    }

    private fun buildTransactionWhere(
        transactionAlias: String,
        startAt: OffsetDateTime,
        endExclusive: OffsetDateTime,
        cashierId: UUID?,
        sessionId: UUID?
    ): String {
        val conditions = mutableListOf(
            "$transactionAlias.type = 'penjualan'::system.trx_type",
            "$transactionAlias.created_at >= ${startAt.toSqlTimestamptz()}",
            "$transactionAlias.created_at < ${endExclusive.toSqlTimestamptz()}"
        )
        cashierId?.let { conditions += "$transactionAlias.user_id = ${it.toSqlUuid()}" }
        sessionId?.let { conditions += "$transactionAlias.session_id = ${it.toSqlUuid()}" }
        return conditions.joinToString(" AND ")
    }

    private fun buildPaymentWhere(
        paymentAlias: String,
        transactionAlias: String,
        startAt: OffsetDateTime,
        endExclusive: OffsetDateTime,
        cashierId: UUID?,
        sessionId: UUID?
    ): String {
        val conditions = mutableListOf(
            "$transactionAlias.type = 'penjualan'::system.trx_type",
            "$paymentAlias.paid_at >= ${startAt.toSqlTimestamptz()}",
            "$paymentAlias.paid_at < ${endExclusive.toSqlTimestamptz()}"
        )
        cashierId?.let { conditions += "$transactionAlias.user_id = ${it.toSqlUuid()}" }
        sessionId?.let { conditions += "$transactionAlias.session_id = ${it.toSqlUuid()}" }
        return conditions.joinToString(" AND ")
    }

    private fun <T> Transaction.queryList(sql: String, mapper: (ResultSet) -> T): List<T> {
        return exec(sql.trimIndent()) { resultSet ->
            val rows = mutableListOf<T>()
            while (resultSet.next()) {
                rows += mapper(resultSet)
            }
            rows
        } ?: emptyList()
    }

    private fun <T> Transaction.queryOne(sql: String, mapper: (ResultSet) -> T): T? {
        return queryList(sql, mapper).firstOrNull()
    }

    private fun ResultSet.getBigDecimalOrZero(columnLabel: String): BigDecimal {
        return getBigDecimal(columnLabel) ?: BigDecimal.ZERO
    }

    private fun OffsetDateTime.toSqlTimestamptz(): String {
        return "'${format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}'::timestamptz"
    }

    private fun UUID.toSqlUuid(): String {
        return "'$this'::uuid"
    }

    private companion object {
        val PAYMENT_METHODS = listOf("tunai", "transfer", "qris", "dp", "hutang")
        val TRANSACTION_STATUSES = listOf("lunas", "dp", "hutang")
    }
}
