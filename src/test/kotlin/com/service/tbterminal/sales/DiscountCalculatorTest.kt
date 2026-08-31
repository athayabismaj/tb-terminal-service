package com.service.tbterminal.sales

import com.service.tbterminal.shared.ValidationException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiscountCalculatorTest {
    @Test
    fun `item percentage is calculated from gross line`() {
        val result = calculate("50000", "2", DiscountRequest(DiscountType.PERCENTAGE, bd("10")))

        assertEquals(bd("100000.00"), result.grossSubtotal)
        assertEquals(bd("10000.00"), result.itemDiscountTotal)
        assertEquals(bd("90000.00"), result.netTotal)
    }

    @Test
    fun `fixed item discount applies once to line total`() {
        val result = calculate("20000", "3", DiscountRequest(DiscountType.FIXED_AMOUNT, bd("10000")))

        assertEquals(bd("60000.00"), result.grossSubtotal)
        assertEquals(bd("10000.00"), result.itemDiscountTotal)
        assertEquals(bd("50000.00"), result.netTotal)
    }

    @Test
    fun `transaction percentage applies after item discount`() {
        val result = DiscountCalculator.calculate(
            listOf(input("200000", itemDiscount = DiscountRequest(DiscountType.FIXED_AMOUNT, bd("20000")))),
            DiscountRequest(DiscountType.PERCENTAGE, bd("10"))
        )

        assertEquals(bd("200000.00"), result.grossSubtotal)
        assertEquals(bd("20000.00"), result.itemDiscountTotal)
        assertEquals(bd("18000.00"), result.transactionDiscountAmount)
        assertEquals(bd("162000.00"), result.netTotal)
        assertEquals(bd("19.0000"), result.effectiveDiscountPercent)
    }

    @Test
    fun `zero and one hundred percent are valid`() {
        assertEquals(bd("100.00"), calculate("100", discount = percentage("0")).netTotal)
        assertEquals(bd("0.00"), calculate("100", discount = percentage("100")).netTotal)
    }

    @Test
    fun `invalid percentage and fixed discount above base are rejected`() {
        listOf("-0.01", "100.01").forEach { value ->
            assertFailsWith<ValidationException> { calculate("100", discount = percentage(value)) }
        }
        assertFailsWith<ValidationException> {
            calculate("100", discount = DiscountRequest(DiscountType.FIXED_AMOUNT, bd("100.01")))
        }
    }

    @Test
    fun `legacy fixed discount remains per unit compatible but snapshot is line amount`() {
        val result = DiscountCalculator.calculate(
            listOf(input("50", quantity = "2", legacyDiscount = "5")),
            null
        )

        assertEquals(bd("10.00"), result.items.single().discountAmount)
        assertEquals(bd("90.00"), result.netTotal)
    }

    @Test
    fun `rounding is deterministic half up and fingerprint changes with intent`() {
        val first = calculate("0.05", "1", percentage("10"))
        val same = calculate("0.05", "1", percentage("10"))
        val changed = calculate("0.05", "1", percentage("20"))

        assertEquals(bd("0.01"), first.itemDiscountTotal)
        assertEquals(first.fingerprint, same.fingerprint)
        kotlin.test.assertNotEquals(first.fingerprint, changed.fingerprint)
    }

    private fun calculate(
        unitPrice: String,
        quantity: String = "1",
        discount: DiscountRequest? = null
    ) = DiscountCalculator.calculate(listOf(input(unitPrice, quantity, discount)), null)

    private fun input(
        unitPrice: String,
        quantity: String = "1",
        itemDiscount: DiscountRequest? = null,
        legacyDiscount: String = "0"
    ) = DiscountItemInput("product-1", bd(quantity), bd(unitPrice), itemDiscount, bd(legacyDiscount))

    private fun percentage(value: String) = DiscountRequest(DiscountType.PERCENTAGE, bd(value))
    private fun bd(value: String) = BigDecimal(value)
}
