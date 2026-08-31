package com.service.tbterminal.sales

import kotlin.test.Test
import kotlin.test.assertTrue

class TransactionRefundMigrationTest {
    @Test
    fun `refund migration keeps event payment and stock history traceable`() {
        val sql = requireNotNull(
            javaClass.classLoader.getResource("db/migration/V36__transaction_refunds.sql")
        ).readText()

        assertTrue(sql.contains("CREATE TABLE sales.transaction_refunds"))
        assertTrue(sql.contains("UNIQUE (transaction_id)"))
        assertTrue(sql.contains("uq_transaction_refund_idempotency"))
        assertTrue(sql.contains("transaction_refund_id"))
        assertTrue(sql.contains("transaction_refund"))
        assertTrue(sql.contains("'REFUND'::system.stock_movement_type"))
        assertTrue(sql.contains("trg_prevent_transaction_refund_mutation"))
    }
}
