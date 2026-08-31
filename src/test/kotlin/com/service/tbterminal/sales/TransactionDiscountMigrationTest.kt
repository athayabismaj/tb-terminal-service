package com.service.tbterminal.sales

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TransactionDiscountMigrationTest {
    @Test
    fun `V37 adds immutable discount snapshots setting and scoped checkout attempt`() {
        val migration = File("src/main/resources/db/migration/V37__transaction_discounts.sql").readText()

        listOf(
            "cashier_discount_limit_percent",
            "gross_line_total",
            "total_discount_amount",
            "discount_manager_approval_id",
            "checkout_discount_attempts",
            "discount_fingerprint",
            "requested_by_user_id"
        ).forEach { token -> assertTrue(migration.contains(token), "Migration harus memiliki $token") }
        assertTrue(migration.contains("REFERENCES system.manager_approvals"))
        assertTrue(migration.contains("prevent_sales_history_delete"))
    }
}
