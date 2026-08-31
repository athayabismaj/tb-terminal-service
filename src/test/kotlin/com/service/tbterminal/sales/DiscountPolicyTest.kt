package com.service.tbterminal.sales

import com.service.tbterminal.shared.Role
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscountPolicyTest {
    @Test
    fun `owner and admin never need cashier override`() {
        assertFalse(requiresDiscountOverride(Role.OWNER, bd("100"), bd("10")))
        assertFalse(requiresDiscountOverride(Role.ADMIN, bd("100"), bd("10")))
    }

    @Test
    fun `cashier boundary is inclusive`() {
        assertFalse(requiresDiscountOverride(Role.KASIR, bd("9"), bd("10")))
        assertFalse(requiresDiscountOverride(Role.KASIR, bd("10"), bd("10")))
        assertTrue(requiresDiscountOverride(Role.KASIR, bd("10.01"), bd("10")))
    }

    @Test
    fun `combined discount cannot bypass cashier limit`() {
        val calculation = DiscountCalculator.calculate(
            listOf(
                DiscountItemInput(
                    reference = "product-1",
                    quantity = bd("1"),
                    unitPrice = bd("100"),
                    discount = DiscountRequest(DiscountType.PERCENTAGE, bd("8"))
                )
            ),
            DiscountRequest(DiscountType.PERCENTAGE, bd("8"))
        )

        assertTrue(calculation.effectiveDiscountPercent > bd("10"))
        assertTrue(requiresDiscountOverride(Role.KASIR, calculation.effectiveDiscountPercent, bd("10")))
    }

    private fun bd(value: String) = BigDecimal(value)
}
