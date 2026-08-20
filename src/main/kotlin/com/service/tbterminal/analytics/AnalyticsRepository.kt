package com.service.tbterminal.analytics

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.statements.StatementType
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
            val zoneId = ZoneId.of("Asia/Jakarta")
            val startAt = startDate.atStartOfDay(zoneId).toOffsetDateTime()
            val endExclusive = endDate.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime()

            queryList(
                """
                SELECT
                    (t.created_at AT TIME ZONE 'Asia/Jakarta')::date AS sale_date,
                    COUNT(t.id) FILTER (WHERE t.status::text <> 'voided') AS total_transactions,
                    COALESCE(SUM(t.total) FILTER (WHERE t.status::text <> 'voided'), 0)::numeric AS total_revenue,
                    COALESCE(SUM(t.dp_amount) FILTER (WHERE t.status::text <> 'voided'), 0)::numeric AS total_dp,
                    COUNT(t.id) FILTER (WHERE t.status::text = 'voided') AS voided_transaction_count,
                    COALESCE(SUM(t.total) FILTER (WHERE t.status::text = 'voided'), 0)::numeric AS voided_amount
                FROM sales.transactions t
                WHERE t.type = 'penjualan'::system.trx_type
                  AND t.created_at >= ${startAt.toSqlTimestamptz()}
                  AND t.created_at < ${endExclusive.toSqlTimestamptz()}
                GROUP BY sale_date
                ORDER BY sale_date DESC
                """
            ) { row ->
                DailySalesSummary(
                    date = row.getDate("sale_date").toString(),
                    transactionCount = row.getLong("total_transactions"),
                    totalRevenue = row.getBigDecimalOrZero("total_revenue"),
                    totalDp = row.getBigDecimalOrZero("total_dp"),
                    voidedTransactionCount = row.getLong("voided_transaction_count"),
                    voidedAmount = row.getBigDecimalOrZero("voided_amount")
                )
            }
        }

    suspend fun getDashboardMetrics(today: LocalDate, firstDayOfMonth: LocalDate): DashboardMetrics =
        newSuspendedTransaction(Dispatchers.IO) {
            
            val zoneId = ZoneId.of("Asia/Jakarta")
            val monthStart = firstDayOfMonth.atStartOfDay(zoneId).toOffsetDateTime()
            val todayStart = today.atStartOfDay(zoneId).toOffsetDateTime()
            val tomorrowStart = today.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime()
            val revenue = queryOne(
                """
                SELECT
                    COALESCE(SUM(total) FILTER (WHERE created_at >= ${todayStart.toSqlTimestamptz()}), 0)::numeric AS today,
                    COALESCE(SUM(total), 0)::numeric AS month
                FROM sales.transactions
                WHERE type = 'penjualan'::system.trx_type
                  AND status::text <> 'voided'
                  AND created_at >= ${monthStart.toSqlTimestamptz()}
                  AND created_at < ${tomorrowStart.toSqlTimestamptz()}
                """
            ) { it.getBigDecimalOrZero("today") to it.getBigDecimalOrZero("month") }
                ?: (BigDecimal.ZERO to BigDecimal.ZERO)

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
                totalRevenueToday = revenue.first,
                totalRevenueThisMonth = revenue.second,
                totalActiveReceivables = totalActiveReceivables,
                activeReceivableCount = receivablesCount,
                lowStockCount = lowStockCount,
                lowStockItems = lowStockItems
            )
        }

    suspend fun getSalesReport(
        filter: SalesReportFilter,
        topProductsLimit: Int
    ): SalesReportResponse = newSuspendedTransaction(Dispatchers.IO) {
        val zoneId = ZoneId.of("Asia/Jakarta")
        val startAt = filter.startDate.atStartOfDay(zoneId).toOffsetDateTime()
        val endExclusive = filter.endDate.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime()

        val transactionWhere = buildTransactionWhere(
            transactionAlias = "t",
            startAt = startAt,
            endExclusive = endExclusive,
            filter = filter,
            includeVoided = false
        )
        val baseTotals = queryOne(
            """
            WITH transaction_report AS (
                SELECT
                    t.id,
                    t.total,
                    CASE
                        WHEN r.id IS NULL THEN GREATEST(t.total - t.paid_amount, 0)
                        ELSE GREATEST(r.amount - r.paid_amount, 0)
                    END AS remaining_amount
                FROM sales.transactions t
                LEFT JOIN receivable.receivables r ON r.transaction_id = t.id
                WHERE $transactionWhere
            )
            SELECT
                COUNT(id) AS transaction_count,
                COALESCE(SUM(total), 0)::numeric AS gross_revenue,
                COALESCE(SUM(LEAST(total, GREATEST(total - remaining_amount, 0))), 0)::numeric AS paid_amount
            FROM transaction_report
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
                COUNT(p.id) FILTER (WHERE p.amount > 0) AS payment_count,
                COALESCE(SUM(p.amount), 0)::numeric AS amount
            FROM sales.payments p
            JOIN sales.transactions t ON t.id = p.transaction_id
            WHERE $transactionWhere
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
            WITH transaction_report AS (
                SELECT
                    t.id,
                    t.total,
                    CASE
                        WHEN r.id IS NULL THEN GREATEST(t.total - t.paid_amount, 0)
                        ELSE GREATEST(r.amount - r.paid_amount, 0)
                    END AS remaining_amount
                FROM sales.transactions t
                LEFT JOIN receivable.receivables r ON r.transaction_id = t.id
                WHERE $transactionWhere
            ), status_report AS (
                SELECT
                    id,
                    total,
                    LEAST(total, GREATEST(total - remaining_amount, 0)) AS effective_paid_amount,
                    CASE
                        WHEN remaining_amount <= 0 THEN 'lunas'
                        WHEN LEAST(total, GREATEST(total - remaining_amount, 0)) > 0 THEN 'dp'
                        ELSE 'hutang'
                    END AS effective_status
                FROM transaction_report
            )
            SELECT
                effective_status AS transaction_status,
                COUNT(id) AS transaction_count,
                COALESCE(SUM(total), 0)::numeric AS revenue,
                COALESCE(SUM(effective_paid_amount), 0)::numeric AS paid_amount
            FROM status_report
            GROUP BY effective_status
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
                COALESCE(p.name, ti.product_id::text) AS product_name,
                COALESCE(SUM(ti.quantity), 0)::numeric AS qty_sold,
                COALESCE(SUM(ti.subtotal), 0)::numeric AS revenue,
                COALESCE(SUM(ti.subtotal - (ti.cogs_at_transaction * ti.quantity)), 0)::numeric AS gross_profit
            FROM sales.transaction_items ti
            JOIN sales.transactions t ON t.id = ti.transaction_id
            LEFT JOIN inventory.products p ON p.id = ti.product_id
            WHERE $transactionWhere
            GROUP BY ti.product_id, p.name
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
            filter = filter,
            includeVoided = false
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

        val voidedWhere = buildTransactionWhere("t", startAt, endExclusive, filter, includeVoided = true)
        val voided = queryOne(
            """
            SELECT COUNT(t.id) AS transaction_count,
                   COALESCE(SUM(t.total), 0)::numeric AS amount,
                   COALESCE(SUM(t.paid_amount), 0)::numeric AS paid_amount
            FROM sales.transactions t
            WHERE $voidedWhere AND t.status::text = 'voided'
            """
        ) { row ->
            VoidedSalesSummary(
                transactionCount = row.getLong("transaction_count"),
                amount = row.getBigDecimalOrZero("amount"),
                paidAmount = row.getBigDecimalOrZero("paid_amount")
            )
        } ?: VoidedSalesSummary()

        SalesReportResponse(
            range = SalesReportRange(
                startDate = filter.startDate.toString(),
                endDate = filter.endDate.toString()
            ),
            totals = baseTotals.copy(
                outstandingAmount = receivables.remainingAmount,
                grossProfit = grossProfit
            ),
            paymentMethods = paymentMethods,
            transactionStatuses = transactionStatuses,
            topProducts = topProducts,
            cashiers = cashiers,
            receivables = receivables,
            voided = voided
        )
    }

    suspend fun getCsvPage(
        type: CsvExportType,
        filter: SalesReportFilter,
        limit: Int,
        offset: Long
    ): CsvPage = newSuspendedTransaction(Dispatchers.IO) {
        val zoneId = ZoneId.of("Asia/Jakarta")
        val startAt = filter.startDate.atStartOfDay(zoneId).toOffsetDateTime()
        val endExclusive = filter.endDate.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime()
        val transactionWhere = buildTransactionWhere("t", startAt, endExclusive, filter, includeVoided = true)
        val paging = "LIMIT $limit OFFSET $offset"

        when (type) {
            CsvExportType.TRANSACTIONS -> CsvPage(
                headers = listOf("nomor_transaksi", "tanggal", "status", "kasir", "pelanggan", "total", "dibayar", "metode_pembayaran", "catatan"),
                rows = queryList(
                    """
                    SELECT t.receipt_number, t.created_at::text, t.status::text, u.name AS cashier,
                           COALESCE(c.name, 'Umum') AS customer, t.total::text, t.paid_amount::text,
                           COALESCE(STRING_AGG(DISTINCT p.method::text, '|' ORDER BY p.method::text), '') AS methods,
                           COALESCE(t.notes, '') AS notes
                    FROM sales.transactions t
                    JOIN system.users u ON u.id = t.user_id
                    LEFT JOIN receivable.customers c ON c.id = t.customer_id
                    LEFT JOIN sales.payments p ON p.transaction_id = t.id AND p.amount > 0
                    WHERE $transactionWhere
                    GROUP BY t.id, u.name, c.name
                    ORDER BY t.created_at, t.id
                    $paging
                    """
                ) { r -> listOf(r.str("receipt_number"), r.str("created_at"), r.str("status"), r.str("cashier"), r.str("customer"), r.str("total"), r.str("paid_amount"), r.str("methods"), r.str("notes")) }
            )

            CsvExportType.SALES_DETAILS -> CsvPage(
                headers = listOf("nomor_transaksi", "tanggal", "status", "sku", "produk", "kategori", "satuan", "jumlah", "harga_snapshot", "hpp_snapshot", "diskon", "subtotal"),
                rows = queryList(
                    """
                    SELECT t.receipt_number, t.created_at::text, t.status::text, p.sku, p.name AS product,
                           c.name AS category, u.symbol AS unit, ti.quantity::text,
                           ti.price_at_transaction::text, ti.cogs_at_transaction::text,
                           ti.discount::text, ti.subtotal::text
                    FROM sales.transaction_items ti
                    JOIN sales.transactions t ON t.id = ti.transaction_id
                    JOIN inventory.products p ON p.id = ti.product_id
                    JOIN inventory.categories c ON c.id = p.category_id
                    JOIN inventory.units u ON u.id = ti.unit_id
                    WHERE $transactionWhere
                    ORDER BY t.created_at, t.id, ti.id
                    $paging
                    """
                ) { r -> listOf(r.str("receipt_number"), r.str("created_at"), r.str("status"), r.str("sku"), r.str("product"), r.str("category"), r.str("unit"), r.str("quantity"), r.str("price_at_transaction"), r.str("cogs_at_transaction"), r.str("discount"), r.str("subtotal")) }
            )

            CsvExportType.STOCK -> {
                val productWhere = buildProductWhere(filter, "p")
                CsvPage(
                    headers = listOf("sku", "produk", "kategori", "satuan", "stok", "stok_minimum", "harga_beli", "harga_jual", "aktif"),
                    rows = queryList(
                        """
                        SELECT p.sku, p.name AS product, c.name AS category, u.symbol AS unit,
                               COALESCE(s.quantity, 0)::text AS stock, p.min_stock::text,
                               p.price_buy::text, p.price_retail::text, p.is_active::text
                        FROM inventory.products p
                        JOIN inventory.categories c ON c.id = p.category_id
                        JOIN inventory.units u ON u.id = p.base_unit_id
                        LEFT JOIN inventory.stock s ON s.product_id = p.id
                        WHERE $productWhere
                        ORDER BY p.sku, p.id
                        $paging
                        """
                    ) { r -> listOf(r.str("sku"), r.str("product"), r.str("category"), r.str("unit"), r.str("stock"), r.str("min_stock"), r.str("price_buy"), r.str("price_retail"), r.str("is_active")) }
                )
            }

            CsvExportType.STOCK_CARD -> {
                val conditions = mutableListOf(
                    "m.occurred_at >= ${startAt.toSqlTimestamptz()}",
                    "m.occurred_at < ${endExclusive.toSqlTimestamptz()}"
                )
                filter.productId?.let { conditions += "m.product_id = ${it.toSqlUuid()}" }
                filter.categoryId?.let { conditions += "p.category_id = ${it.toSqlUuid()}" }
                CsvPage(
                    headers = listOf("tanggal", "sku", "produk", "jenis", "saldo_sebelum", "masuk", "keluar", "saldo_sesudah", "referensi", "pengguna"),
                    rows = queryList(
                        """
                        SELECT m.occurred_at::text, p.sku, p.name AS product, m.movement_type::text,
                               m.balance_before::text, m.qty_in::text, m.qty_out::text, m.balance_after::text,
                               COALESCE(m.reference_number, m.reference_id::text) AS reference,
                               COALESCE(u.name, 'SYSTEM') AS actor
                        FROM inventory.stock_movements m
                        JOIN inventory.products p ON p.id = m.product_id
                        LEFT JOIN system.users u ON u.id = m.user_id
                        WHERE ${conditions.joinToString(" AND ")}
                        ORDER BY m.occurred_at, m.sequence_no
                        $paging
                        """
                    ) { r -> listOf(r.str("occurred_at"), r.str("sku"), r.str("product"), r.str("movement_type"), r.str("balance_before"), r.str("qty_in"), r.str("qty_out"), r.str("balance_after"), r.str("reference"), r.str("actor")) }
                )
            }

            CsvExportType.RECEIVABLES -> {
                val conditions = mutableListOf(
                    "r.debt_date >= '${filter.startDate}'::date",
                    "r.debt_date <= '${filter.endDate}'::date",
                    "r.is_active = TRUE"
                )
                filter.customerId?.let { conditions += "r.customer_id = ${it.toSqlUuid()}" }
                filter.status?.let { status ->
                    val receivableStatus = mapOf("lunas" to "lunas", "dp" to "sebagian", "hutang" to "belum_lunas")[status]
                    if (receivableStatus != null) conditions += "r.status::text = '$receivableStatus'"
                }
                CsvPage(
                    headers = listOf("pelanggan", "tanggal_piutang", "jatuh_tempo", "sumber", "nomor_nota", "nominal", "dibayar", "sisa", "status", "catatan"),
                    rows = queryList(
                        """
                        SELECT c.name AS customer, r.debt_date::text, r.due_date::text, r.source::text,
                               COALESCE(t.receipt_number, r.legacy_invoice_number, '') AS invoice,
                               r.amount::text, r.paid_amount::text, (r.amount-r.paid_amount)::text AS remaining,
                               r.status::text, COALESCE(r.notes, '') AS notes
                        FROM receivable.receivables r
                        JOIN receivable.customers c ON c.id = r.customer_id
                        LEFT JOIN sales.transactions t ON t.id = r.transaction_id
                        WHERE ${conditions.joinToString(" AND ")}
                        ORDER BY r.debt_date, r.id
                        $paging
                        """
                    ) { r -> listOf(r.str("customer"), r.str("debt_date"), r.str("due_date"), r.str("source"), r.str("invoice"), r.str("amount"), r.str("paid_amount"), r.str("remaining"), r.str("status"), r.str("notes")) }
                )
            }

            CsvExportType.PAYMENTS -> {
                val conditions = mutableListOf(
                    "rp.payment_date >= '${filter.startDate}'::date",
                    "rp.payment_date <= '${filter.endDate}'::date"
                )
                filter.cashierId?.let { conditions += "rp.user_id = ${it.toSqlUuid()}" }
                filter.customerId?.let { conditions += "r.customer_id = ${it.toSqlUuid()}" }
                filter.paymentMethod?.let { conditions += "rp.method::text = '$it'" }
                CsvPage(
                    headers = listOf("nomor_pembayaran", "tanggal", "jenis", "pelanggan", "metode", "nominal", "referensi", "catatan", "penerima", "saldo_sebelum", "saldo_sesudah", "status_sesudah"),
                    rows = queryList(
                        """
                        SELECT rp.payment_number, rp.payment_date::text, rp.entry_type::text,
                               c.name AS customer, rp.method::text, rp.amount::text,
                               COALESCE(rp.reference, '') AS reference, COALESCE(rp.notes, '') AS notes,
                               u.name AS receiver, rp.balance_before::text, rp.balance_after::text,
                               rp.status_after::text
                        FROM receivable.receivable_payments rp
                        JOIN receivable.receivables r ON r.id = rp.receivable_id
                        JOIN receivable.customers c ON c.id = r.customer_id
                        JOIN system.users u ON u.id = rp.user_id
                        WHERE ${conditions.joinToString(" AND ")}
                        ORDER BY rp.payment_date, rp.paid_at, rp.id
                        $paging
                        """
                    ) { r -> listOf(r.str("payment_number"), r.str("payment_date"), r.str("entry_type"), r.str("customer"), r.str("method"), r.str("amount"), r.str("reference"), r.str("notes"), r.str("receiver"), r.str("balance_before"), r.str("balance_after"), r.str("status_after")) }
                )
            }
        }
    }

    private fun buildProductWhere(filter: SalesReportFilter, alias: String): String {
        val conditions = mutableListOf("TRUE")
        filter.productId?.let { conditions += "$alias.id = ${it.toSqlUuid()}" }
        filter.categoryId?.let { conditions += "$alias.category_id = ${it.toSqlUuid()}" }
        return conditions.joinToString(" AND ")
    }

    private fun buildTransactionWhere(
        transactionAlias: String,
        startAt: OffsetDateTime,
        endExclusive: OffsetDateTime,
        filter: SalesReportFilter,
        includeVoided: Boolean
    ): String {
        val conditions = mutableListOf(
            "$transactionAlias.type = 'penjualan'::system.trx_type",
            "$transactionAlias.created_at >= ${startAt.toSqlTimestamptz()}",
            "$transactionAlias.created_at < ${endExclusive.toSqlTimestamptz()}"
        )
        if (!includeVoided) conditions += "$transactionAlias.status::text <> 'voided'"
        filter.cashierId?.let { conditions += "$transactionAlias.user_id = ${it.toSqlUuid()}" }
        filter.sessionId?.let { conditions += "$transactionAlias.session_id = ${it.toSqlUuid()}" }
        filter.customerId?.let { conditions += "$transactionAlias.customer_id = ${it.toSqlUuid()}" }
        filter.status?.let { conditions += "$transactionAlias.status::text = '${it}'" }
        filter.paymentMethod?.let {
            conditions += "EXISTS (SELECT 1 FROM sales.payments fp WHERE fp.transaction_id = $transactionAlias.id AND fp.method::text = '${it}' AND fp.amount > 0)"
        }
        filter.productId?.let {
            conditions += "EXISTS (SELECT 1 FROM sales.transaction_items fi WHERE fi.transaction_id = $transactionAlias.id AND fi.product_id = ${it.toSqlUuid()})"
        }
        filter.categoryId?.let {
            conditions += "EXISTS (SELECT 1 FROM sales.transaction_items fi JOIN inventory.products fp ON fp.id = fi.product_id WHERE fi.transaction_id = $transactionAlias.id AND fp.category_id = ${it.toSqlUuid()})"
        }
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
        return exec(sql.trimIndent(), explicitStatementType = StatementType.SELECT) { resultSet ->
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

    private fun ResultSet.str(columnLabel: String): String? = getString(columnLabel)

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
