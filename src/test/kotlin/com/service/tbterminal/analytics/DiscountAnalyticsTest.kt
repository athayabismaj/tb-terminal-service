package com.service.tbterminal.analytics

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscountAnalyticsTest {
    @Test
    fun `net revenue subtracts discount and financial refund once`() {
        val totals = SalesReportTotals(
            transactionCount = 1,
            grossRevenue = BigDecimal("100.00"),
            discountAmount = BigDecimal("20.00"),
            paidAmount = BigDecimal("80.00"),
            outstandingAmount = BigDecimal.ZERO,
            grossProfit = BigDecimal.ZERO,
            refundAmount = BigDecimal("80.00")
        )

        assertEquals(BigDecimal("0.00"), totals.netRevenue)
    }
}
