package com.service.tbterminal.sales

import com.service.tbterminal.system.CashRoundingRule
import com.service.tbterminal.system.TaxMode
import java.io.File
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaxAndRoundingCalculatorTest {

    @Test
    fun `tax disabled keeps totals unchanged and zeroes tax fields`() {
        val calc = TaxAndRoundingCalculator.calculateTax(
            netGoodsTotal = BigDecimal("100000.00"),
            config = StoreTaxRoundingConfig(taxEnabled = false, taxRate = BigDecimal("11.00"))
        )

        assertEquals(false, calc.taxEnabled)
        assertNull(calc.taxMode)
        assertEquals(BigDecimal("0.00"), calc.taxRate)
        assertEquals(BigDecimal("0.00"), calc.taxableBase)
        assertEquals(BigDecimal("0.00"), calc.taxAmount)
        assertEquals(BigDecimal("100000.00"), calc.totalWithTax)
    }

    @Test
    fun `exclude tax adds PPN on top of net goods total`() {
        val calc = TaxAndRoundingCalculator.calculateTax(
            netGoodsTotal = BigDecimal("250000.00"),
            config = StoreTaxRoundingConfig(
                taxEnabled = true,
                taxRate = BigDecimal("11.00"),
                taxMode = TaxMode.EXCLUDE,
                taxLabel = "PPN"
            )
        )

        assertEquals(true, calc.taxEnabled)
        assertEquals(TaxMode.EXCLUDE, calc.taxMode)
        assertEquals(BigDecimal("250000.00"), calc.taxableBase)
        assertEquals(BigDecimal("27500.00"), calc.taxAmount)
        assertEquals(BigDecimal("277500.00"), calc.totalWithTax)
    }

    @Test
    fun `include tax splits DPP and taxAmount so sum equals net total exactly`() {
        val net = BigDecimal("111000.00")
        val calc = TaxAndRoundingCalculator.calculateTax(
            netGoodsTotal = net,
            config = StoreTaxRoundingConfig(
                taxEnabled = true,
                taxRate = BigDecimal("11.00"),
                taxMode = TaxMode.INCLUDE,
                taxLabel = "PPN"
            )
        )

        assertEquals(TaxMode.INCLUDE, calc.taxMode)
        assertEquals(BigDecimal("100000.00"), calc.taxableBase)
        assertEquals(BigDecimal("11000.00"), calc.taxAmount)
        assertEquals(net, calc.taxableBase.add(calc.taxAmount))
        assertEquals(net, calc.totalWithTax)

        // Odd amount with rounding residual: invariant taxableBase + taxAmount == net must still hold
        val oddNet = BigDecimal("100000.00")
        val oddCalc = TaxAndRoundingCalculator.calculateTax(
            netGoodsTotal = oddNet,
            config = StoreTaxRoundingConfig(
                taxEnabled = true,
                taxRate = BigDecimal("11.00"),
                taxMode = TaxMode.INCLUDE
            )
        )
        assertEquals(oddNet, oddCalc.taxableBase.add(oddCalc.taxAmount))
        assertEquals(oddNet, oddCalc.totalWithTax)
    }

    @Test
    fun `cash rounding applies only when cash is used and follows NEAREST DOWN UP rules`() {
        val base = BigDecimal("142370.00")

        val noCash = TaxAndRoundingCalculator.calculateCashRounding(
            totalBeforeRounding = base,
            hasCashPayment = false,
            unit = 500,
            rule = CashRoundingRule.NEAREST
        )
        assertEquals(BigDecimal("0.00"), noCash.roundingAmount)
        assertEquals(base, noCash.finalTotal)

        val nearest500 = TaxAndRoundingCalculator.calculateCashRounding(
            totalBeforeRounding = base,
            hasCashPayment = true,
            unit = 500,
            rule = CashRoundingRule.NEAREST
        )
        assertEquals(BigDecimal("130.00"), nearest500.roundingAmount)
        assertEquals(BigDecimal("142500.00"), nearest500.finalTotal)

        val down500 = TaxAndRoundingCalculator.calculateCashRounding(
            totalBeforeRounding = base,
            hasCashPayment = true,
            unit = 500,
            rule = CashRoundingRule.DOWN
        )
        assertEquals(BigDecimal("-370.00"), down500.roundingAmount)
        assertEquals(BigDecimal("142000.00"), down500.finalTotal)

        val up1000 = TaxAndRoundingCalculator.calculateCashRounding(
            totalBeforeRounding = base,
            hasCashPayment = true,
            unit = 1000,
            rule = CashRoundingRule.UP
        )
        assertEquals(BigDecimal("630.00"), up1000.roundingAmount)
        assertEquals(BigDecimal("143000.00"), up1000.finalTotal)
    }

    @Test
    fun `V40 migration contains tax rounding split payment and item note schema updates`() {
        val migration = File("src/main/resources/db/migration/V40__tax_rounding_split_payments_and_item_notes.sql").readText()
        listOf(
            "tax_enabled",
            "tax_rate",
            "tax_mode",
            "tax_label",
            "store_npwp",
            "cash_rounding_unit",
            "cash_rounding_rule",
            "taxable_base",
            "tax_amount",
            "rounding_amount",
            "payment_source",
            "ck_sales_transaction_discount_snapshot",
            "trg_prevent_sales_payment_mutation"
        ).forEach { token ->
            assertTrue(migration.contains(token), "V40 migration harus memuat $token")
        }
    }
}
